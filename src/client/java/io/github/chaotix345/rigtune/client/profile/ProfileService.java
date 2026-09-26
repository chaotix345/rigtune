package io.github.chaotix345.rigtune.client.profile;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ConfigTargets;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.probe.HardwareProbe;
import io.github.chaotix345.rigtune.client.probe.ModScanner;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.ui.Texts;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.core.apply.InstanceDirs;
import io.github.chaotix345.rigtune.core.apply.LogSafe;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.history.ChangeRecorder;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeAction;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import io.github.chaotix345.rigtune.core.profile.ActiveProfile;
import io.github.chaotix345.rigtune.core.profile.BatteryPrompt;
import io.github.chaotix345.rigtune.core.profile.EffectiveSettings;
import io.github.chaotix345.rigtune.core.profile.ProfileImport;
import io.github.chaotix345.rigtune.core.profile.ProfileNames;
import io.github.chaotix345.rigtune.core.profile.ProfileNotes;
import io.github.chaotix345.rigtune.core.profile.ProfileStore;
import io.github.chaotix345.rigtune.core.profile.ProfileStore.Profile;
import io.github.chaotix345.rigtune.core.profile.ProfileSwitch;
import io.github.chaotix345.rigtune.core.profile.ProfileTemplates;
import io.github.chaotix345.rigtune.core.profile.ProfileTemplates.TemplateId;
import io.github.chaotix345.rigtune.core.profile.ProfileView;
import io.github.chaotix345.rigtune.core.profile.ShareCode;
import io.github.chaotix345.rigtune.core.profile.ShareCodeException;
import io.github.chaotix345.rigtune.core.profile.ShareKeys;
import io.github.chaotix345.rigtune.core.recommend.Recommender.Clamp;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

// Performance Profiles and share codes (docs/v0.4/SPEC.md 4): profiles.json, switching (an ordinary Apply through
// RealController.apply(selected, entryId), labelled in profiles.json by the journal entry id), templates, share codes and
// the battery offer. RealController delegates every profile method here in one line. No work in the constructor (C4).
public final class ProfileService {
	public static final String NOTICE_BATTERY = "battery-offer:";
	public static final String NOTICE_BACK = "battery-back:";
	public static final String ACTION_SWITCH = "switch";
	public static final String ACTION_SNOOZE = "snooze";
	private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId(6000L);
	// Whether a benchmark runs (BenchmarkController.running); ProfilesGameTest stands one in without starting a world.
	private static volatile BooleanSupplier benchmarkRunning = BenchmarkController::running;

	private final RealController controller;
	private final Path configDir;
	private volatile @Nullable Offer offer;

	// A pending battery offer: its notice key and what it offers.
	private record Offer(String key, BatteryPrompt.Decision decision) {
	}

	// What a profile id resolves to now: the values to switch to (clamped), the clamps, and its names. own: the player's
	// own or an imported profile (Preview says which of its keys this game lacks), not a template.
	private record Target(Text name, String english, @Nullable String profileId, @Nullable String templateId, Map<String, String> values,
			List<Clamp> clamps, boolean own) {
	}

	public ProfileService(RealController controller, Path configDir) {
		this.controller = controller;
		this.configDir = configDir;
	}

	private ProfileStore store() {
		return ProfileStore.shared(configDir);
	}

	// Templates first, then the baseline ("My settings", auto-saved the first time this is asked), then saved and imported
	// profiles.
	public List<ProfileView> profiles() {
		ensureBaseline();
		String active = active();
		List<ProfileView> out = new ArrayList<>();
		for (TemplateId id : TemplateId.values()) {
			String viewId = ProfileStore.TEMPLATE_PREFIX + id.id();
			out.add(new ProfileView(viewId, id.displayName(), ProfileView.TEMPLATE, viewId.equals(active)));
		}
		List<Profile> saved = new ArrayList<>(store().profiles());
		saved.sort((a, b) -> Boolean.compare(!ProfileStore.SOURCE_BASELINE.equals(a.source()), !ProfileStore.SOURCE_BASELINE.equals(b.source())));
		for (Profile profile : saved) {
			out.add(new ProfileView(profile.id(), name(profile), profile.source(), profile.id().equals(active)));
		}
		return out;
	}

	public Component switchProfile(String id) {
		Component refused = refusal();
		if (refused != null) {
			return refused;
		}
		Target target = resolve(id);
		return target == null ? Component.translatable("rigtune.profile.status.unavailable") : switchTo(target);
	}

	// Off the render thread (Preview loads in the background).
	public ApplyPreview previewProfile(String id) {
		Target target = resolve(id);
		if (target == null) {
			return ApplyPreview.EMPTY;
		}
		SettingsSnapshot snapshot = snapshot();
		List<Recommendation> recs = ProfileSwitch.build(target.values(), snapshot, ModScanner.loadedIds(), labels(), target.english());
		return effective(controller.preview(recs), recs).withNotes(notes(target, snapshot, 0));
	}

	// Saves the current values of the managed keys. A name one of the player's profiles already has overwrites that profile,
	// so "My settings" can be re-saved.
	public Component saveCurrentProfile(String name) {
		String clean = ProfileNames.sanitise(name);
		Map<String, String> values = current();
		if (values.isEmpty()) {
			return Component.translatable("rigtune.profile.status.unavailable");
		}
		Profile same = clean == null ? null : store().profiles().stream()
				.filter(p -> clean.equals(p.name()) && !ProfileStore.SOURCE_IMPORTED.equals(p.source())).findFirst().orElse(null);
		Profile profile = same != null
				? new Profile(same.id(), same.name(), null, same.source(), same.createdAt(), controller.modVersion(), HardwareProbe.minecraftVersion(), values)
				: new Profile(ProfileStore.newProfileId(), clean, null, ProfileStore.SOURCE_SAVED, Instant.now().toString(), controller.modVersion(),
						HardwareProbe.minecraftVersion(), values);
		return save(profile);
	}

	// Decodes a code into what applying it would do. Writes nothing (the screen reads the clipboard, on Paste only).
	public ProfileImport importProfileCode(String code) {
		ShareCode.Decoded decoded;
		try {
			decoded = ShareCode.decode(code);
		} catch (ShareCodeException e) {
			RigTune.LOGGER.info("RigTune: not importing a profile code ({})", e.reason());
			return ProfileImport.failed(e.text());
		}
		RulesDocument rules = controller.rules();
		HardwareProfile hardware = controller.hardwareProfile();
		if (rules == null || hardware == null) {
			return ProfileImport.failed(Text.of("rigtune.profile.code.error.not_ready", "RigTune is still scanning this PC. Try again in a moment."));
		}
		String english = decoded.name() != null ? decoded.name() : Text.of("rigtune.profile.imported", "Imported profile").english();
		SettingsSnapshot snapshot = snapshot();
		ProfileTemplates.Result clamped = ProfileTemplates.clamp(decoded.values(refreshRate(hardware)), rules, hardware, mods(), snapshot, controller.goal());
		Target target = new Target(Text.literal(english), english, null, null, clamped.values(), clamped.clamps(), true);
		List<Recommendation> recs = ProfileSwitch.build(clamped.values(), snapshot, ModScanner.loadedIds(), labels(), english);
		ApplyPreview preview = effective(controller.preview(recs), recs).withNotes(notes(target, snapshot, decoded.unknownKeys()));
		return new ProfileImport(decoded.name(), preview, decoded.unknownKeys(), null, decoded.values(refreshRate(hardware)));
	}

	// Preview's Apply for a code: saved as an imported profile (when there's room), then switched to.
	public Component applyImportedProfile(ProfileImport imported) {
		Component refused = refusal();
		if (refused != null) {
			return refused;
		}
		if (!imported.ok() || imported.values().isEmpty()) {
			return Component.translatable("rigtune.profile.status.unavailable");
		}
		Profile profile = importedProfile(imported);
		boolean saved = store().saveProfile(profile);
		ProfileTemplates.Result clamped = ProfileTemplates.clamp(imported.values(), controller.rules(), controller.hardwareProfile(), mods(), snapshot(),
				controller.goal());
		return switchTo(new Target(name(profile), english(profile), saved ? profile.id() : null, null, clamped.values(), clamped.clamps(), true));
	}

	// Preview's Save only for a code.
	public Component saveImportedProfile(ProfileImport imported) {
		if (!imported.ok() || imported.values().isEmpty()) {
			return Component.translatable("rigtune.profile.status.unavailable");
		}
		return save(importedProfile(imported));
	}

	// A template is computed for this PC; a saved or imported profile is shared as saved (the recipient's own limits apply on
	// import, so this PC's clamps aren't passed on).
	public @Nullable String exportProfileCode(String id) {
		HardwareProfile hardware = controller.hardwareProfile();
		int refresh = hardware == null ? -1 : refreshRate(hardware);
		Profile own = id.startsWith(ProfileStore.TEMPLATE_PREFIX) ? null : store().profile(id);
		if (own != null) {
			return ShareCode.encode(english(own), own.settings(), refresh);
		}
		Target target = resolve(id);
		return target == null ? null : ShareCode.encode(target.english(), target.values(), refresh);
	}

	public void renameProfile(String id, String name) {
		store().rename(id, name);
	}

	// "My settings" (the baseline) can be re-saved but never deleted, so the way back can't be lost by a misclick.
	public void deleteProfile(String id) {
		Profile profile = store().profile(id);
		if (profile != null && !ProfileStore.SOURCE_BASELINE.equals(profile.source())) {
			store().delete(id);
		}
	}

	// History's labels ("Profile: Battery"), from profiles.json by journal entry id.
	public HistoryModel.@Nullable View labelled(HistoryModel.@Nullable View view) {
		if (view == null) {
			return null;
		}
		try {
			return HistoryModel.withProfiles(view, store().labels());
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Could not read the profile labels", e);
			return view;
		}
	}

	// Battery (docs/v0.4/SPEC.md 4), on the "RigTune power" thread: a debounced power change. Offers only, never switches.
	public void powerChanged(boolean onBattery) {
		HardwareProbe.setOnBattery(onBattery);
		Instant now = Instant.now();
		BatteryPrompt.Decision decision = BatteryPrompt.onEdge(onBattery, store().battery(), active(), benchmarkRunning.getAsBoolean(), now);
		Offer next = switch (decision.offer()) {
			case BATTERY -> new Offer(NOTICE_BATTERY + now.getEpochSecond(), decision);
			case PREVIOUS -> new Offer(NOTICE_BACK + now.getEpochSecond(), decision);
			case NONE -> null;
		};
		if (next != null && decision.offer() == BatteryPrompt.Offer.BATTERY) {
			store().batteryOffered(now.toString());
		}
		offer = next;
		Minecraft minecraft = controller.minecraft();
		if (minecraft == null) {
			return;
		}
		minecraft.execute(() -> {
			if (next != null) {
				SystemToast.addOrUpdate(minecraft.gui.toastManager(), TOAST_ID, Component.translatable("rigtune.battery.toast.title"),
						Texts.component(message(next)));
			}
			controller.rescan();
		});
	}

	// BatteryNoticeSource's notice: the pending offer, while it still applies.
	public @Nullable Notice batteryNotice() {
		Offer current = offer;
		if (current == null) {
			return null;
		}
		String target = current.decision().target();
		if (target == null || target.equals(active())) {
			offer = null;
			return null;
		}
		Text switchLabel = current.decision().offer() == BatteryPrompt.Offer.BATTERY
				? Text.of("rigtune.battery.action.switch", "Switch to Battery")
				: Text.of("rigtune.battery.action.back", "Switch back");
		return new Notice(current.key(), NoticePriority.BATTERY_OFFER, message(current), null,
				List.of(new NoticeAction(ACTION_SWITCH, switchLabel), new NoticeAction(ACTION_SNOOZE, Text.of("rigtune.battery.action.snooze", "Don't offer again"))),
				true);
	}

	public void batteryAction(String actionId) {
		Offer current = offer;
		if (current == null) {
			return;
		}
		offer = null;
		if (ACTION_SNOOZE.equals(actionId)) {
			store().snoozeBattery(true);
			return;
		}
		if (ACTION_SWITCH.equals(actionId) && current.decision().target() != null) {
			Component result = switchProfile(current.decision().target());
			Minecraft minecraft = controller.minecraft();
			if (minecraft != null) {
				SystemToast.addOrUpdate(minecraft.gui.toastManager(), TOAST_ID, Component.translatable("rigtune.profile.title"), result);
			}
		}
	}

	private Text message(Offer offer) {
		if (offer.decision().offer() == BatteryPrompt.Offer.BATTERY) {
			return Text.of("rigtune.battery.offer", "You're on battery power. Switch to the Battery profile to make it last longer?");
		}
		return Text.of("rigtune.battery.back", "You're plugged in again. Switch back to %s?", displayName(offer.decision().target()));
	}

	// The switch itself: one journal entry of kind apply, labelled in profiles.json.
	private Component switchTo(Target target) {
		// The way back: "My settings" exists before the first switch, even one made from the battery offer.
		ensureBaseline();
		SettingsSnapshot snapshot = snapshot();
		List<Recommendation> recs = ProfileSwitch.build(target.values(), snapshot, ModScanner.loadedIds(), labels(), target.english());
		String previous = active();
		Component name = Texts.component(target.name());
		if (recs.isEmpty()) {
			markActive(target, previous, null);
			return Component.translatable("rigtune.profile.status.already", name);
		}
		String entryId = ChangeRecorder.newEntryId();
		Component result = controller.apply(recs, entryId);
		JournalEntry entry = entry(entryId);
		if (entry == null || entry.changes().isEmpty()) {
			return result;
		}
		store().recordSwitch(new ProfileStore.Switch(entryId, target.profileId(), target.templateId(), target.english()), journalIds());
		markActive(target, previous, entryId);
		offer = null;
		long staged = entry.changes().stream().filter(c -> JournalChange.STAGED.equals(c.status())).count();
		MutableComponent message = staged > 1 ? Component.translatable("rigtune.profile.status.switched_restart", name, staged)
				: staged == 1 ? Component.translatable("rigtune.profile.status.switched_restart_one", name)
				: Component.translatable("rigtune.profile.status.switched", name);
		int failed = recs.size() - entry.changes().size();
		if (failed > 0) {
			message.append(" ").append(Component.translatable("rigtune.profile.status.some_failed", failed));
		}
		if (!target.clamps().isEmpty()) {
			message.append(" ").append(Component.translatable("rigtune.profile.status.clamped", target.clamps().size()));
		}
		return message;
	}

	private void markActive(Target target, @Nullable String previous, @Nullable String entryId) {
		String id = target.profileId() != null ? target.profileId()
				: target.templateId() != null ? ProfileStore.TEMPLATE_PREFIX + target.templateId() : null;
		store().setActive(id, entryId);
		if (BatteryPrompt.BATTERY.equals(id) && !BatteryPrompt.BATTERY.equals(previous)) {
			store().rememberPrevious(previous);
		}
	}

	// For ProfilesGameTest only: null puts the real check back.
	public static void overrideBenchmarkCheck(@Nullable BooleanSupplier running) {
		benchmarkRunning = running == null ? BenchmarkController::running : running;
	}

	private @Nullable Component refusal() {
		if (benchmarkRunning.getAsBoolean()) {
			return Component.translatable("rigtune.profile.status.benchmark");
		}
		if (controller.downloading()) {
			return Component.translatable("rigtune.status.busy");
		}
		if (controller.rules() == null || controller.hardwareProfile() == null) {
			return Component.translatable("rigtune.profile.code.error.not_ready");
		}
		return null;
	}

	private @Nullable Target resolve(@Nullable String id) {
		RulesDocument rules = controller.rules();
		HardwareProfile hardware = controller.hardwareProfile();
		if (id == null || rules == null || hardware == null) {
			return null;
		}
		if (id.startsWith(ProfileStore.TEMPLATE_PREFIX)) {
			TemplateId template = TemplateId.of(id.substring(ProfileStore.TEMPLATE_PREFIX.length()));
			if (template == null) {
				return null;
			}
			SettingsSnapshot snapshot = snapshot();
			Profile baseline = store().baseline();
			Map<String, String> base = baseline != null ? baseline.settings() : managed(snapshot);
			// The bundled rules only for a document without the template (an old cache); not kept (idle footprint).
			RulesDocument fallback = ProfileTemplates.defines(template, rules) ? null : RulesLoader.loadBundled();
			ProfileTemplates.Result result = ProfileTemplates.compute(template, rules, fallback, hardware, mods(), snapshot, base);
			return new Target(template.displayName(), template.displayName().english(), null, template.id(), result.values(), List.of(), false);
		}
		Profile profile = store().profile(id);
		if (profile == null) {
			return null;
		}
		if (ProfileStore.SOURCE_BASELINE.equals(profile.source())) {
			return new Target(name(profile), english(profile), profile.id(), null, profile.settings(), List.of(), true);
		}
		ProfileTemplates.Result clamped = ProfileTemplates.clamp(profile.settings(), rules, hardware, mods(), snapshot(), controller.goal());
		return new Target(name(profile), english(profile), profile.id(), null, clamped.values(), clamped.clamps(), true);
	}

	private List<Text> notes(Target target, SettingsSnapshot snapshot, int newerKeys) {
		int notHere = 0;
		if (target.own()) {
			Set<String> loaded = ModScanner.loadedIds();
			for (String key : target.values().keySet()) {
				if (!ProfileSwitch.takesPart(key, snapshot, loaded)) {
					notHere++;
				}
			}
		}
		return ProfileNotes.of(target.clamps(), newerKeys, notHere, labels());
	}

	private void ensureBaseline() {
		if (store().baseline() != null || !store().writable()) {
			return;
		}
		Map<String, String> values = current();
		if (!values.isEmpty()) {
			store().saveProfile(new Profile(ProfileStore.newProfileId(), Text.of("rigtune.profile.baseline", "My settings").english(), null,
					ProfileStore.SOURCE_BASELINE, Instant.now().toString(), controller.modVersion(), HardwareProbe.minecraftVersion(), values));
		}
	}

	private Component save(Profile profile) {
		if (!store().saveProfile(profile)) {
			return store().writable() ? Component.translatable("rigtune.profile.status.full", ProfileStore.MAX_PROFILES)
					: Component.translatable("rigtune.profile.status.read_only");
		}
		return Component.translatable("rigtune.profile.status.saved", Texts.component(name(profile)));
	}

	// The same code imported again reuses its profile (same name and values), so repeats don't fill the cap.
	private Profile importedProfile(ProfileImport imported) {
		String name = ProfileNames.sanitise(imported.name());
		Profile same = store().profiles().stream().filter(p -> ProfileStore.SOURCE_IMPORTED.equals(p.source())
				&& Objects.equals(p.name(), name) && p.settings().equals(imported.values())).findFirst().orElse(null);
		return new Profile(same != null ? same.id() : ProfileStore.newProfileId(), name, null, ProfileStore.SOURCE_IMPORTED,
				same != null ? same.createdAt() : Instant.now().toString(), controller.modVersion(), HardwareProbe.minecraftVersion(), imported.values());
	}

	// The active profile, while the switch that made it active still stands (not undone: ActiveProfile).
	private @Nullable String active() {
		String active = store().active();
		if (active == null) {
			return null;
		}
		try {
			Journal journal = ClientJournal.get();
			return ActiveProfile.inEffect(store().activeEntry(), journal.state(), journal.entries()) ? active : null;
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Could not read RigTune's history", e);
			return active;
		}
	}

	// A profile id's name without computing it (the battery offer's text).
	private Text displayName(@Nullable String id) {
		if (id == null) {
			return Text.literal("?");
		}
		if (id.startsWith(ProfileStore.TEMPLATE_PREFIX)) {
			TemplateId template = TemplateId.of(id.substring(ProfileStore.TEMPLATE_PREFIX.length()));
			return template == null ? Text.literal("?") : template.displayName();
		}
		Profile profile = store().profile(id);
		return profile == null ? Text.literal("?") : name(profile);
	}

	private static Text name(Profile profile) {
		if (profile.name() != null) {
			return Text.literal(profile.name());
		}
		return ProfileStore.SOURCE_IMPORTED.equals(profile.source()) ? Text.of("rigtune.profile.imported", "Imported profile")
				: Text.of("rigtune.profile.unnamed", "Unnamed profile");
	}

	private static String english(Profile profile) {
		return name(profile).english();
	}

	// The managed keys' current values (inside the share table's bounds).
	private Map<String, String> current() {
		return controller.minecraft() == null ? Map.of() : managed(snapshot());
	}

	private static Map<String, String> managed(SettingsSnapshot snapshot) {
		Map<String, String> out = new LinkedHashMap<>();
		for (ShareKeys.Key key : ShareKeys.V1) {
			String value = snapshot.get(key.key());
			if (value != null && key.encode(value) != null) {
				out.put(key.key(), value);
			}
		}
		return out;
	}

	// THE current values every switch, Preview, template and save uses: the files, with each staged key at the value its last
	// still-pending op sets (docs/research/v0.4/audit-apply-pipeline.md M1; EffectiveSettings).
	private SettingsSnapshot snapshot() {
		Minecraft minecraft = controller.minecraft();
		if (minecraft == null) {
			return new SettingsSnapshot(Map.of());
		}
		SettingsSnapshot files = minecraft.isSameThread() ? SettingsBridge.read(minecraft) : minecraft.submit(() -> SettingsBridge.read(minecraft)).join();
		Map<String, Path> fileByPrefix = new LinkedHashMap<>();
		ConfigTargets.all(configDir).forEach(t -> fileByPrefix.put(t.prefix(), t.file()));
		return EffectiveSettings.of(files, pendingOps(), fileByPrefix);
	}

	private List<PendingActions.Op> pendingOps() {
		Path file = PendingActions.defaultPath(configDir);
		if (!Files.isRegularFile(file)) {
			return List.of();
		}
		try {
			return PendingActions.load(file).relocated(InstanceDirs.modsDirOf(file), InstanceDirs.configDirOf(file)).ops();
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("Could not read {} ({})", LogSafe.name(file), LogSafe.error(e, file));
			return List.of();
		}
	}

	// PreviewPlanner compares a staged key with its file. When an earlier Apply in this start already staged that key, the
	// file isn't what the restart starts from, so a change the switch does stage would show as unchanged: those rows (and
	// the "from" values of the staged ones) come from the switch's own effective values.
	private ApplyPreview effective(ApplyPreview preview, List<Recommendation> recs) {
		Map<String, Action.SetSetting> byId = new LinkedHashMap<>();
		recs.forEach(r -> {
			if (r.action() instanceof Action.SetSetting set) {
				byId.put(r.id(), set);
			}
		});
		List<ConfigTargets.Target> targets = ConfigTargets.all(configDir);
		List<ApplyPreview.Setting> atRestart = new ArrayList<>();
		for (ApplyPreview.Setting setting : preview.atRestart()) {
			Action.SetSetting set = byId.get(setting.recommendationId());
			atRestart.add(set == null ? setting : new ApplyPreview.Setting(setting.recommendationId(), setting.file(), setting.key(), set.currentValue(),
					setting.newValue()));
		}
		List<ApplyPreview.Skipped> skipped = new ArrayList<>();
		for (ApplyPreview.Skipped skip : preview.skipped()) {
			Action.SetSetting set = byId.get(skip.recommendationId());
			ConfigTargets.Target target = set == null ? null : ConfigTargets.forKey(targets, set.key());
			if (skip.reason() == ApplyPreview.Reason.UNCHANGED && target != null && !SettingValues.same(set.currentValue(), set.newValue())) {
				atRestart.add(new ApplyPreview.Setting(skip.recommendationId(), target.file(), set.key().substring(target.prefix().length()),
						set.currentValue(), set.newValue()));
			} else {
				skipped.add(skip);
			}
		}
		return new ApplyPreview(preview.now(), atRestart, preview.downloads(), preview.disables(), skipped, preview.resolved(), preview.notes());
	}

	private List<InstalledMod> mods() {
		List<InstalledMod> mods = controller.mods();
		return mods == null ? List.of() : mods;
	}

	private Map<String, RulesDocument.SettingLabel> labels() {
		RulesDocument rules = controller.rules();
		return rules == null ? Map.of() : rules.settingLabels;
	}

	private static int refreshRate(HardwareProfile hardware) {
		return hardware.display() == null ? -1 : hardware.display().refreshRate();
	}

	private static @Nullable JournalEntry entry(String entryId) {
		try {
			return ClientJournal.get().entries().stream().filter(e -> entryId.equals(e.id())).findFirst().orElse(null);
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Could not read RigTune's history", e);
			return null;
		}
	}

	private static @Nullable Set<String> journalIds() {
		Journal journal = ClientJournal.get();
		if (journal.state() != Journal.State.OK) {
			return null;
		}
		Set<String> ids = new HashSet<>();
		journal.entries().forEach(e -> ids.add(e.id()));
		return ids;
	}
}
