package io.github.chaotix345.rigtune.client.stutter;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.Busy;
import io.github.chaotix345.rigtune.client.ConfigTargets;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkConditions;
import io.github.chaotix345.rigtune.client.compat.OptionalMods;
import io.github.chaotix345.rigtune.client.probe.HardwareProbe;
import io.github.chaotix345.rigtune.client.probe.ModScanner;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.ui.Texts;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.core.apply.InstanceDirs;
import io.github.chaotix345.rigtune.core.apply.LogSafe;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.benchmark.DhGeneration;
import io.github.chaotix345.rigtune.core.history.ChangeRecorder;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import io.github.chaotix345.rigtune.core.profile.ActiveProfile;
import io.github.chaotix345.rigtune.core.profile.EffectiveSettings;
import io.github.chaotix345.rigtune.core.profile.ProfileStore;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.stutter.FixConditions;
import io.github.chaotix345.rigtune.core.stutter.FixGate;
import io.github.chaotix345.rigtune.core.stutter.FixHold;
import io.github.chaotix345.rigtune.core.stutter.FixOffer;
import io.github.chaotix345.rigtune.core.stutter.FixOffers;
import io.github.chaotix345.rigtune.core.stutter.FixSpec;
import io.github.chaotix345.rigtune.core.stutter.FixStore;
import io.github.chaotix345.rigtune.core.stutter.FixText;
import io.github.chaotix345.rigtune.core.stutter.FixTracker;
import io.github.chaotix345.rigtune.core.stutter.SessionOutcome;
import io.github.chaotix345.rigtune.core.stutter.StutterAdvisor;
import io.github.chaotix345.rigtune.core.stutter.StutterAnalyzer;
import io.github.chaotix345.rigtune.core.stutter.StutterReport;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

// docs/v0.5/SPEC.md 5 (C20): the Stutter Doctor's one-click fixes. Reached only through RealController.v05() (X4); nothing
// happens in the constructor. The offers are worked out with each analysis (StutterService, on its worker: evaluate); a
// fix is an ordinary RealController.apply of one SetSetting (one journal entry, Undo this), tracked in stutter-fixes.json.
// Threads (X8): stutter-fixes.json is read and written only off the render thread, its writes on StutterService's ordered
// io chain; the render thread sees the records through a cache (null until read) and the record apply() has just made.
public final class StutterFixService {
	static final long REFRESH_NANOS = 5_000_000_000L;
	private static final long MIB = 1024 * 1024;

	private final @Nullable RealController controller;
	private volatile @Nullable List<FixTracker.Record> records;
	// The record apply() made, until its write on the io chain has landed and the cache holds it.
	private volatile FixTracker.@Nullable Record adding;
	private volatile boolean writable = true;
	private volatile boolean loadQueued;
	// The rules the specs were read from, weakly: holding the document would keep a replaced one (and its conditions) alive.
	private volatile WeakReference<@Nullable RulesDocument> specsFor = new WeakReference<>(null);
	private volatile List<FixSpec> specs = List.of();
	// Render thread.
	private long lastRefresh;
	// history.json (the tests stand in for it).
	volatile Supplier<Journal.Snapshot> history = () -> ClientJournal.get().snapshot();

	// What an analysis needs from the render thread for the fixes: the rules' fix entries, whether another fix is staged
	// or measuring, whether the store can be written, the live server limits, whether the capture ran around a benchmark,
	// the conditions now and at the capture's start (both without the measurement flags, which come with the capture's
	// copy), where the config lives.
	record Inputs(List<FixSpec> specs, boolean busy, boolean writable, @Nullable ServerLimits live, boolean aroundBenchmark, FixConditions conditionsNow,
			@Nullable FixConditions atStart, Path configDir) {
	}

	// An analysis' fix side: per fired advice its offer (or why not yet), the session's outcome and conditions (the before
	// side if the player applies one), whether it's excluded (WS-B's M4 rule) or mostly idle (RW-17), and the session's start
	// and source.
	public record Fixes(Map<String, FixOffer> offers, SessionOutcome outcome, FixConditions conditions, boolean excluded, boolean idle,
			Instant startedAt, String source) {
	}

	public StutterFixService(RealController controller) {
		this.controller = controller;
	}

	private FixStore store() {
		return FixStore.shared(Objects.requireNonNull(controller).configDir());
	}

	private StutterService stutter() {
		return Objects.requireNonNull(controller).stutterService();
	}

	// ---- The records ----

	// Off the render thread: the records, read once.
	private List<FixTracker.Record> loaded() {
		List<FixTracker.Record> r = records;
		if (r == null) {
			reload();
			r = records;
		}
		return r == null ? List.of() : r;
	}

	// Off the render thread (the io chain or a worker).
	private void reload() {
		try {
			FixStore store = store();
			List<FixTracker.Record> read = store.records();
			writable = store.writable();
			records = List.copyOf(read);
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Stutter Doctor: could not read the tracked fixes", e);
			records = List.of();
		}
	}

	// Render thread: the cached records with the one being added, or null while they haven't been read (a read is queued).
	private @Nullable List<FixTracker.Record> cached() {
		// `adding` first: the io chain reloads the records before it clears `adding`, so the record is in one of them.
		FixTracker.Record a = adding;
		List<FixTracker.Record> r = records;
		if (r == null) {
			if (!loadQueued && controller != null) {
				loadQueued = true;
				stutter().io(this::reload);
			}
			return null;
		}
		if (a == null || r.stream().anyMatch(x -> x.entryId().equals(a.entryId()))) {
			return r;
		}
		List<FixTracker.Record> with = new ArrayList<>(r);
		with.add(a);
		return with;
	}

	private static boolean anyActive(@Nullable List<FixTracker.Record> records) {
		return records != null && records.stream().anyMatch(FixTracker.Record::active);
	}

	// Render thread: the fix the "Your stutter fix" block shows (the newest one not dismissed), or null.
	public FixTracker.@Nullable Record tracked() {
		List<FixTracker.Record> r = cached();
		if (r == null) {
			return null;
		}
		for (int i = r.size() - 1; i >= 0; i--) {
			if (!r.get(i).dismissed()) {
				return r.get(i);
			}
		}
		return null;
	}

	// Render thread (StutterScreen's refresh): at most every REFRESH_NANOS, the records follow the journal (Undo, Discard)
	// on the io chain.
	void refresh() {
		long now = System.nanoTime();
		if (controller == null || lastRefresh != 0 && now - lastRefresh < REFRESH_NANOS) {
			return;
		}
		lastRefresh = now;
		stutter().io(() -> advanceAll(null));
	}

	// io chain: every record that can still change follows the journal and, if one just ended, the session. The state and
	// the entries come from one read of history.json; a read that fails changes nothing (a fix never expires because the
	// file couldn't be read for a moment).
	void advanceAll(FixTracker.@Nullable SessionEnd session) {
		try {
			Journal.Snapshot read = history.get();
			if (read.state() != Journal.State.OK && read.state() != Journal.State.MISSING) {
				return;
			}
			Instant now = Instant.now();
			FixStore store = store();
			for (FixTracker.Record r : store.records()) {
				FixTracker.Record next = FixTracker.advance(r, read.state(), read.entries(), session, now);
				if (!next.equals(r)) {
					store.update(r.entryId(), x -> next);
				}
			}
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Stutter Doctor: could not update the tracked fixes", e);
		} finally {
			reload();
		}
	}

	// ---- The analysis side ----

	// Render thread, with each analysis' machine snapshot.
	Inputs inputs(Minecraft minecraft, StutterMonitor.@Nullable Capture capture, SettingsSnapshot settings) {
		RealController c = Objects.requireNonNull(controller);
		List<FixTracker.Record> r = cached();
		return new Inputs(specs(c.rules()), adding != null || anyActive(r), writable, c.serverLimits(), capture != null && capture.aroundBenchmark,
				conditions(minecraft, capture == null ? null : capture.worldKind, settings), capture == null ? null : capture.fixAtStart, c.configDir());
	}

	private List<FixSpec> specs(@Nullable RulesDocument rules) {
		if (rules != specsFor.get()) {
			specs = FixSpec.of(rules);
			specsFor = new WeakReference<>(rules);
		}
		return specs;
	}

	// Render thread: the conditions a fix's sessions are compared under (FixConditions), the measurement flags left false.
	static FixConditions conditions(Minecraft minecraft, @Nullable String worldKind, SettingsSnapshot settings) {
		return new FixConditions(HardwareProbe.minecraftVersion(), BenchmarkConditions.modSetHash(), Runtime.getRuntime().maxMemory() / MIB,
				StutterCapture.GC.collector(), minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight(), minecraft.options.fullscreen().get(), worldKind,
				false, false, FixConditions.settingsOf(settings.values()));
	}

	// Render thread, at a session's start: the kind of world (ServerLimits.Kind's name), or null outside one.
	static @Nullable String worldKind(RealController controller, Minecraft minecraft) {
		ServerLimits live = controller.serverLimits();
		if (live != null) {
			return live.kind().name();
		}
		return minecraft.hasSingleplayerServer() ? ServerLimits.Kind.SINGLEPLAYER.name() : null;
	}

	// The analysis worker: the offers under the advice that fired, the session's outcome and conditions. Settings as the next
	// restart leaves them (staged ops included) decide the offers' "from".
	static Fixes evaluate(StutterAnalyzer.Result result, StutterReport report, List<StutterAdvisor.Fired> advice, StutterCapture.Copy copy,
			@Nullable RulesDocument rules, @Nullable HardwareProfile hardware, @Nullable List<InstalledMod> mods, SettingsSnapshot settings, Goal goal,
			Inputs in) {
		SessionOutcome outcome = SessionOutcome.of(result, copy.startNanos());
		boolean excluded = excluded(result, in.aroundBenchmark(), OptionalMods.dhLoaded());
		FixConditions conditions = in.conditionsNow().withMeasurement(copy.phaseTiming(), copy.gcMeasured());
		Map<String, FixOffer> offers = Map.of();
		if (rules != null && hardware != null && !advice.isEmpty() && !in.specs().isEmpty()) {
			SettingsSnapshot effective = effective(settings, in.configDir());
			offers = FixOffers.evaluate(in.specs(), advice.stream().map(StutterAdvisor.Fired::id).toList(), report, outcome, excluded,
					changedDuring(in.atStart(), in.conditionsNow()), StutterAdvisor.context(rules, hardware, mods, settings, goal, result.facts()), effective,
					ModScanner.loadedIds(), in.live(), in.busy(), in.writable());
			offers = withProfiles(offers, in.configDir());
		}
		if (DevFixCalibration.ON) {
			RigTune.LOGGER.info("Dev fix calibration: evidence: claimed {} %, dominated spikes {}, unmeasured {}, tags {}; outcome {}; excluded {}; offers {}",
					result.facts().claimedShares(), result.facts().causeSpikes(), result.facts().unmeasured(), result.facts().taggedShares(), outcome, excluded,
					offers);
		}
		return new Fixes(offers, outcome, conditions, excluded, FixGate.idle(report), copy.startedAt(), copy.source());
	}

	// review-11 STUTTER-3: the before side is one setup: nothing FixConditions compares moved between the capture's start and
	// the analysis. A key read on one side only (a config file mid-write) isn't a change; unknown start conditions can't tell.
	static boolean changedDuring(@Nullable FixConditions atStart, FixConditions now) {
		if (atStart == null) {
			return true;
		}
		return atStart.differences(now, "").stream().anyMatch(d -> d.reason() != FixConditions.Reason.SETTING || !d.args().get(1).isEmpty()
				&& !d.args().get(2).isEmpty());
	}

	// WS-B's M4 rule for C20: a session around a benchmark run, or one in which Distant Horizons generated terrain, is no
	// comparison side: any sustained minute of the whole capture counts (review-11 STUTTER-4), and with Distant Horizons
	// loaded but nothing sampled it fails closed.
	static boolean excluded(StutterAnalyzer.Result result, boolean aroundBenchmark, boolean dhLoaded) {
		return aroundBenchmark || dhLoaded && !Boolean.FALSE.equals(DhGeneration.generating(result.dhWorldGenPeakCores(), true));
	}

	// sf §1.7: an offer row names the active saved profile when it also sets the key.
	private static Map<String, FixOffer> withProfiles(Map<String, FixOffer> offers, Path configDir) {
		if (offers.values().stream().noneMatch(o -> o instanceof FixOffer.Offer)) {
			return offers;
		}
		try {
			ProfileStore.Snapshot profiles = ProfileStore.shared(configDir).snapshot();
			ProfileStore.Profile active = profiles.active() == null ? null : profiles.profile(profiles.active());
			if (active == null || active.name() == null) {
				return offers;
			}
			Journal journal = ClientJournal.get();
			if (!ActiveProfile.inEffect(profiles.activeEntry(), journal.state(), journal.entries())) {
				return offers;
			}
			Map<String, FixOffer> out = new LinkedHashMap<>();
			offers.forEach((id, o) -> out.put(id, o instanceof FixOffer.Offer offer && active.settings().containsKey(offer.key())
					? offer.withProfile(active.name()) : o));
			return out;
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Stutter Doctor: could not read the active profile", e);
			return offers;
		}
	}

	// ---- A session ended (StutterService's save: on the io chain, or at quit on the render thread, which then waits for
	// the io chain) ----

	void sessionEnded(Fixes fixes, @Nullable FixConditions atStart) {
		if (controller == null || atStart == null) {
			return;
		}
		FixConditions start = atStart.withMeasurement(fixes.conditions().phaseTiming(), fixes.conditions().gcMeasured());
		FixTracker.SessionEnd end = new FixTracker.SessionEnd(fixes.startedAt(), fixes.source(), fixes.outcome(), start, fixes.conditions(),
				fixes.excluded(), fixes.idle());
		stutter().io(() -> advanceAll(end));
	}

	// ---- The player's actions ----

	// Off the render thread (PreviewScreen's loader): the one change, as Apply would make it.
	public ApplyPreview preview(FixOffer.Offer offer) {
		if (controller == null) {
			return ApplyPreview.EMPTY;
		}
		Recommendation rec = recommendation(offer, controller.rules());
		return effectivePreview(controller.preview(List.of(rec)), rec, controller.configDir());
	}

	// Render thread (PreviewScreen's Apply), like a profile switch: the shared busy check first (C8), then C20's own
	// refusals; appliedAt is stamped before anything changes, and a vanilla fix restarts the session only after that and
	// after the settings write, so the next session starts at the target and after appliedAt (M4's contract).
	public Component apply(FixOffer.Offer offer) {
		RealController c = controller;
		Minecraft minecraft = c == null ? null : c.minecraft();
		if (c == null || minecraft == null) {
			return Component.translatable("rigtune.status.nothing");
		}
		Text refused = Busy.refusal(controller);
		if (refused != null) {
			return Texts.component(refused);
		}
		List<FixTracker.Record> recs = cached();
		Fixes shown = stutter().shownFixes();
		if (recs == null || shown == null) {
			return Texts.component(FixText.later());
		}
		if (adding != null || anyActive(recs)) {
			return Texts.component(FixText.notYet(new FixOffer.NotYet(offer.adviceId(), FixOffer.Reason.BUSY, List.of())));
		}
		if (!writable) {
			return Texts.component(FixText.notYet(new FixOffer.NotYet(offer.adviceId(), FixOffer.Reason.STORE, List.of())));
		}
		if (gone(offer, shown, () -> effective(SettingsBridge.read(minecraft), c.configDir()))) {
			return Texts.component(FixText.gone());
		}
		RulesDocument rules = c.rules();
		String entryId = ChangeRecorder.newEntryId();
		// To the second, as a session's startedAt is (StutterCapture): the session restarted below starts no earlier.
		Instant appliedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		FixTracker.Record record = new FixTracker.Record(entryId, offer.adviceId(), offer.key(), offer.from(), offer.to(), appliedAt,
				rules == null ? 0 : rules.revision, offer.now(), offer.now() ? FixTracker.State.MEASURING : FixTracker.State.STAGED, shown.outcome(),
				shown.conditions(), null, 0, null, null, false);
		adding = record;
		FixTracker.Record stored;
		boolean queued = false;
		try {
			Component result = c.apply(List.of(recommendation(offer, rules)), entryId);
			JournalChange change = change(entryId, offer.key());
			if (change == null) {
				return result;
			}
			stored = record.withState(JournalChange.APPLIED.equals(change.status()) ? FixTracker.State.MEASURING : FixTracker.State.STAGED);
			adding = stored;
			stutter().io(() -> {
				try {
					if (!store().add(stored)) {
						RigTune.LOGGER.warn("Stutter Doctor: the fix was applied but stutter-fixes.json couldn't be written; it isn't tracked");
					}
				} finally {
					reload();
					adding = null;
				}
			});
			queued = true;
		} finally {
			if (!queued) {
				adding = null;
			}
		}
		if (stored.state() == FixTracker.State.MEASURING) {
			try {
				stutter().restartSession(minecraft);
			} catch (RuntimeException e) {
				// The setting and the record stand; only this session goes on, so the next one is the first measured.
				RigTune.LOGGER.warn("Stutter Doctor: the session couldn't restart after the fix", e);
			}
		}
		return Texts.component(FixText.applied(stored.state() == FixTracker.State.MEASURING, FixTracker.afterTarget(stored.before())));
	}

	// Hides the block; a staged or measuring fix stops being tracked (the setting stays).
	public void dismiss(String entryId) {
		List<FixTracker.Record> r = records;
		if (controller == null || r == null) {
			return;
		}
		records = r.stream().map(x -> x.entryId().equals(entryId) ? x.dismiss() : x).toList();
		stutter().io(() -> {
			try {
				store().dismiss(entryId);
			} finally {
				reload();
			}
		});
	}

	// The report post-step (V05Hooks.afterRecommend), on the rebuild's worker: the fixes in effect, their journal state as it
	// is now. Without a controller (V05ServicesTest) nothing is read.
	public List<FixHold.Hold> holds() {
		if (controller == null) {
			return List.of();
		}
		FixTracker.Record a = adding;
		List<FixTracker.Record> r = new ArrayList<>(loaded());
		if (a != null && r.stream().noneMatch(x -> x.entryId().equals(a.entryId()))) {
			r.add(a);
		}
		try {
			Journal.Snapshot read = history.get();
			Instant now = Instant.now();
			r.replaceAll(x -> FixTracker.advance(x, read.state(), read.entries(), null, now));
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Stutter Doctor: could not read History for the fixes' holds", e);
		}
		return FixHold.holds(r, ZoneId.systemDefault());
	}

	// ---- Helpers ----

	// Apply's last check: the offer is still one the Stutter Doctor shows, and the setting, as the next restart leaves it,
	// still at its "from". A read that fails (a config file mid-write) refuses the fix the same way; it never throws.
	static boolean gone(FixOffer.Offer offer, @Nullable Fixes shown, Supplier<SettingsSnapshot> effective) {
		if (shown == null || shown.offers().values().stream().noneMatch(o -> o instanceof FixOffer.Offer same && same.sameChange(offer))) {
			return true;
		}
		try {
			return !SettingValues.same(effective.get().get(offer.key()), offer.from());
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Stutter Doctor: could not read the settings for the fix", e);
			return true;
		}
	}

	// The change an offer makes: one SetSetting, its reason naming the advice.
	static Recommendation recommendation(FixOffer.Offer offer, @Nullable RulesDocument rules) {
		RulesDocument.SettingLabel label = rules == null || rules.settingLabels == null ? null : rules.settingLabels.get(offer.key());
		String title = offer.adviceId();
		if (rules != null && rules.stutterAdvice != null) {
			for (RulesDocument.AdviceRule a : rules.stutterAdvice) {
				if (offer.adviceId().equals(a.id) && a.title != null) {
					title = a.title;
				}
			}
		}
		return Recommendation.of("stutterfix:" + offer.adviceId(), Category.SETTING, Impact.MEDIUM, SettingValues.describe(label, offer.key(), offer.from(),
				offer.to()), FixText.reason(title), new Action.SetSetting(offer.key(), offer.from(), offer.to()), true);
	}

	private static @Nullable JournalChange change(String entryId, String key) {
		try {
			for (JournalEntry e : ClientJournal.get().entries()) {
				if (entryId.equals(e.id())) {
					JournalChange found = null;
					for (JournalChange ch : e.changes()) {
						if (ch.isSetting() && key.equals(ch.key())) {
							found = ch;
						}
					}
					return found;
				}
			}
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Could not read RigTune's history", e);
		}
		return null;
	}

	// The settings as the next restart leaves them: the files, with each staged key at its last pending op's value.
	static SettingsSnapshot effective(SettingsSnapshot files, Path configDir) {
		Map<String, Path> fileByPrefix = new LinkedHashMap<>();
		ConfigTargets.all(configDir).forEach(t -> fileByPrefix.put(t.prefix(), t.file()));
		return EffectiveSettings.of(files, pendingOps(configDir), fileByPrefix);
	}

	private static List<PendingActions.Op> pendingOps(Path configDir) {
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

	// As ProfileService does for a switch: PreviewPlanner compares a staged key with its file, so when an earlier Apply in
	// this start already staged the key, the change shows from the effective value.
	private static ApplyPreview effectivePreview(ApplyPreview preview, Recommendation rec, Path configDir) {
		if (!(rec.action() instanceof Action.SetSetting set)) {
			return preview;
		}
		List<ApplyPreview.Setting> atRestart = new ArrayList<>();
		for (ApplyPreview.Setting setting : preview.atRestart()) {
			atRestart.add(rec.id().equals(setting.recommendationId())
					? new ApplyPreview.Setting(setting.recommendationId(), setting.file(), setting.key(), set.currentValue(), setting.newValue()) : setting);
		}
		List<ApplyPreview.Skipped> skipped = new ArrayList<>();
		for (ApplyPreview.Skipped skip : preview.skipped()) {
			ConfigTargets.Target target = rec.id().equals(skip.recommendationId()) ? ConfigTargets.forKey(ConfigTargets.all(configDir), set.key()) : null;
			if (target != null && skip.reason() == ApplyPreview.Reason.UNCHANGED && !SettingValues.same(set.currentValue(), set.newValue())) {
				atRestart.add(new ApplyPreview.Setting(skip.recommendationId(), target.file(), set.key().substring(target.prefix().length()),
						set.currentValue(), set.newValue()));
			} else {
				skipped.add(skip);
			}
		}
		return new ApplyPreview(preview.now(), atRestart, preview.downloads(), preview.disables(), skipped, preview.resolved(), preview.notes());
	}
}
