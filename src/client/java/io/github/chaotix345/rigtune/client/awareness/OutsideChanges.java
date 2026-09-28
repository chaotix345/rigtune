package io.github.chaotix345.rigtune.client.awareness;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.Busy;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.V05Hooks;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.ui.Texts;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.core.awareness.AwarenessStore;
import io.github.chaotix345.rigtune.core.awareness.OutsideOptions;
import io.github.chaotix345.rigtune.core.footprint.StartupTimesStore;
import io.github.chaotix345.rigtune.core.history.JournalCache;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeAction;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesDocument.SettingLabel;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

// docs/v0.5/SPEC.md 4h: settings changed outside the game (docs/research/v0.5/launcher-managed-mods.md §2), a one-shot
// start-up comparison, not a monitor (OutsideOptions has the rules). snapshotAtStop: a clean CLIENT_STOPPING
// (V05Services.registerEvents runs it before RigTune's own exit work): the values of the vanilla keys RigTune applied, in
// one AwarenessStore update; nothing during a benchmark or a Try it (their values aren't the player's). compareAtStart:
// the start hook, on Probes.EXECUTOR, before the player can change anything: the snapshot is taken (consumed), the
// watched keys are read from the journal and options.txt is compared. afterApply: the Apply status's Modrinth App line
// (V05Hooks.afterApply, second), and this session's applied keys join the watched ones. What the comparison found is the
// SETTINGS_CHANGED_OUTSIDE notice (OutsideChangesNoticeSource) until the player acts: "Apply RigTune's values again" (an
// ordinary Apply: one journal entry, undoable) or Keep. It never reverts anything by itself.
public final class OutsideChanges {
	public static final String KEY_PREFIX = "settings-changed-outside:";
	public static final String REAPPLY = "reapply";
	public static final String KEEP = "keep";
	// Changes the detail names before "…".
	private static final int MAX_NAMES = 8;
	// options.txt larger than this isn't the game's (it is a few KiB).
	private static final long MAX_OPTIONS_BYTES = 1024 * 1024;
	private static final String OPTIONS_FILE = "options.txt";
	// A logs folder keeps every archive vanilla ever made; more than this many isn't listed further.
	private static final int MAX_LOG_ARCHIVES = 10_000;
	private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId(6000L);

	// What the start comparison found, until the player acts on it.
	record Found(String key, List<OutsideOptions.Change> changes) {
	}

	// The watched keys and RigTune's values: null until the start hook has read the journal. Updated atomically: the start
	// hook's worker and an Apply on the render thread can both add to it (review L4).
	private static final AtomicReference<@Nullable Map<String, String>> APPLIED = new AtomicReference<>();
	private static volatile @Nullable Found found;

	private OutsideChanges() {
	}

	public static void snapshotAtStop(RealController controller, Minecraft minecraft) {
		Map<String, String> snapshot = stopSnapshot(BenchmarkController.running() || Busy.tryItRunning.getAsBoolean(), APPLIED.get(),
				() -> SettingsBridge.readVanilla(minecraft.options), Instant.now());
		if (snapshot != null) {
			AwarenessStore.shared(controller.configDir()).setOptionsAtExit(snapshot);
		}
	}

	// The snapshot to store at a clean exit (stamped with the exit time), or null for none: while a benchmark or a Try it
	// runs, before the journal was read, when RigTune never applied a vanilla key, or when none of them is among the
	// game's options.
	static @Nullable Map<String, String> stopSnapshot(boolean busy, @Nullable Map<String, String> watched, Supplier<Map<String, String>> vanillaNow,
			Instant exitAt) {
		if (busy || watched == null || watched.isEmpty()) {
			return null;
		}
		Map<String, String> snapshot = OutsideOptions.snapshot(watched.keySet(), vanillaNow.get());
		return snapshot.isEmpty() ? null : OutsideOptions.stamped(snapshot, exitAt);
	}

	public static void compareAtStart(RealController controller) {
		Map<String, String> snapshot = AwarenessStore.shared(controller.configDir()).takeOptionsAtExit();
		// review 11 PERF-2 (a marked WS-H edit): the start hook's readers share one parse of history.json.
		Map<String, String> fromJournal = OutsideOptions.applied(JournalCache.snapshot(ClientJournal.get()).entries());
		Map<String, String> watched = APPLIED.updateAndGet(thisSession -> {
			Map<String, String> merged = new LinkedHashMap<>(fromJournal);
			if (thisSession != null) {
				merged.putAll(thisSession);
			}
			return Collections.unmodifiableMap(merged);
		});
		if (snapshot == null) {
			return;
		}
		// Review L6: a launch of another RigTune version since that exit (0.5 -> 0.4.0 -> 0.5) may have changed options in
		// game; the snapshot is from before it, so nothing is compared.
		// Review-11 COMPAT-3: the same for any launch in between (0.3.0 and older record no startup run), from the log archives.
		if (OutsideOptions.anotherVersionSince(snapshot, new StartupTimesStore(controller.configDir()).runs(), controller.modVersion())
				|| OutsideOptions.anotherLaunchSince(snapshot, logArchives(FabricLoader.getInstance().getGameDir().resolve("logs")))) {
			RigTune.LOGGER.info("RigTune: the game ran since the last clean exit of this RigTune; settings changed outside the game aren't checked this time");
			return;
		}
		List<OutsideOptions.Change> changes = OutsideOptions.compare(snapshot, OutsideOptions.parseOptions(options()), watched);
		if (!changes.isEmpty()) {
			RigTune.LOGGER.info("RigTune: {} setting(s) RigTune applied were changed outside the game since the last clean exit: {}", changes.size(),
					changes.stream().map(OutsideOptions.Change::key).toList());
		}
		found(changes);
	}

	// The modification times of logs/*.log.gz (one folder, not recursive; at most MAX_LOG_ARCHIVES entries looked at);
	// nothing when the folder can't be read.
	static List<Instant> logArchives(Path logs) {
		List<Instant> out = new ArrayList<>();
		if (!Files.isDirectory(logs)) {
			return out;
		}
		try (DirectoryStream<Path> archives = Files.newDirectoryStream(logs, "*.log.gz")) {
			for (Path archive : archives) {
				if (out.size() >= MAX_LOG_ARCHIVES) {
					break;
				}
				out.add(Files.getLastModifiedTime(archive).toInstant());
			}
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.debug("Could not list the log archives", e);
		}
		return out;
	}

	// options.txt now; nothing when it can't be read.
	private static List<String> options() {
		Path file = FabricLoader.getInstance().getGameDir().resolve(OPTIONS_FILE);
		try {
			return Files.isRegularFile(file) && Files.size(file) <= MAX_OPTIONS_BYTES ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("Could not read options.txt for the settings-changed-outside check", e);
			return List.of();
		}
	}

	public static void afterApply(RealController controller, V05Hooks.ApplyFacts facts, List<Component> parts) {
		watch(facts);
		Component line = applyLine(controller.launcher(), facts);
		if (line != null) {
			parts.add(line);
		}
	}

	// This session's Apply: its changeable vanilla keys join the watched ones (its own selection, no I/O).
	static void watch(V05Hooks.ApplyFacts facts) {
		Map<String, String> added = new LinkedHashMap<>();
		for (Recommendation r : facts.selected()) {
			if (vanilla(r)) {
				added.put(((Action.SetSetting) r.action()).key(), ((Action.SetSetting) r.action()).newValue());
			}
		}
		APPLIED.updateAndGet(before -> {
			Map<String, String> watched = before == null ? new LinkedHashMap<>() : new LinkedHashMap<>(before);
			watched.putAll(added);
			return Collections.unmodifiableMap(watched);
		});
	}

	private static boolean vanilla(Recommendation r) {
		return r.action() instanceof Action.SetSetting set && set.key().startsWith(SettingKeys.VANILLA_PREFIX) && SettingKeys.changeable(set.key());
	}

	// AC4h.4 (review M2): the Apply status's line, only when a vanilla setting (options.txt, what the app syncs) was
	// written now.
	static @Nullable Component applyLine(LauncherInfo launcher, V05Hooks.ApplyFacts facts) {
		return syncLine(launcher, facts.settingsOk() > 0 && facts.selected().stream().anyMatch(OutsideChanges::vanilla));
	}

	// AC4h.4 (review M2): Preview's line under "Written now", only when options.txt is among what is written now.
	public static @Nullable Component previewLine(LauncherInfo launcher, ApplyPreview preview) {
		return syncLine(launcher, preview.now().stream().anyMatch(s -> s.file() != null && s.file().getFileName() != null
				&& OPTIONS_FILE.equals(s.file().getFileName().toString())));
	}

	// In a Modrinth App instance, whose game-settings sync copies options.txt to the app's other synced instances.
	public static @Nullable Component syncLine(LauncherInfo launcher, boolean optionsWritten) {
		return optionsWritten && launcher.launcher() == Launcher.MODRINTH_APP ? Component.translatable("rigtune.outside.sync_line") : null;
	}

	// The notice while the comparison's changes are waiting for the player; no file I/O.
	public static @Nullable Notice notice(RealController controller) {
		Found f = found;
		if (f == null) {
			return null;
		}
		RulesDocument rules = controller.rules();
		return notice(f.key(), f.changes(), rules == null ? Map.of() : rules.settingLabels, controller.launcher());
	}

	static Notice notice(String key, List<OutsideOptions.Change> changes, Map<String, SettingLabel> labels, LauncherInfo launcher) {
		List<Text> names = new ArrayList<>();
		for (OutsideOptions.Change change : changes.subList(0, Math.min(MAX_NAMES, changes.size()))) {
			SettingLabel label = labels.get(change.key());
			Text described = SettingValues.describe(label, change.key(), change.before(), change.now());
			// Review-11 FEAT-5: "Apply RigTune's values again" sets RigTune's value, which the player may have changed in game
			// before the outside change: then it is named too (when Apply again would set it).
			boolean third = !SettingValues.same(change.before(), change.rigtune()) && !SettingValues.same(change.now(), change.rigtune());
			names.add(!third ? described
					: Text.of("rigtune.outside.change_and_rigtune", "%s; RigTune's value: %s", described,
							Text.literal(SettingValues.valueLabel(label, change.rigtune()))));
		}
		if (changes.size() > MAX_NAMES) {
			names.add(Text.literal("…"));
		}
		Text message = changes.size() == 1
				? Text.of("rigtune.outside.changed.one", "A setting was changed outside the game since you last played (%s).", names.getFirst())
				: Text.of("rigtune.outside.changed.many", "%s settings were changed outside the game since you last played (e.g. %s).", changes.size(),
						names.getFirst());
		Text detail = Text.of("rigtune.outside.detail", "Changed: %s. \"Apply RigTune's values again\" sets RigTune's values back as one History entry you "
				+ "can undo; Keep leaves them.", Text.join(", ", names));
		if (launcher.launcher() == Launcher.MODRINTH_APP) {
			detail = Text.sentences(detail, Text.of("rigtune.outside.detail.modrinth_app", "The Modrinth App's game-settings sync can do this. To "
					+ "stop it: App settings → Synced settings → Sync game options, or this instance → Instance settings → Sync overrides → Unsync game settings."));
		}
		return new Notice(key, NoticePriority.SETTINGS_CHANGED_OUTSIDE, message, detail,
				List.of(new NoticeAction(REAPPLY, Text.of("rigtune.outside.reapply", "Apply RigTune's values again")),
						new NoticeAction(KEEP, Text.of("rigtune.outside.keep", "Keep"))), false);
	}

	// Render thread (the notice's buttons). Keep retires the notice; Apply again applies RigTune's values as an ordinary
	// Apply (unless the shared busy check refuses: the notice then stays).
	public static void act(RealController controller, String actionId) {
		if (REAPPLY.equals(actionId)) {
			Text refusal = Busy.refusal(controller);
			if (refusal != null) {
				toast(controller, Texts.component(refusal));
				return;
			}
			Found f = take();
			if (f == null) {
				return;
			}
			RulesDocument rules = controller.rules();
			List<Recommendation> again = reapply(f.changes(), rules == null ? Map.of() : rules.settingLabels);
			if (!again.isEmpty()) {
				toast(controller, controller.apply(again));
			}
		} else if (KEEP.equals(actionId)) {
			take();
		}
	}

	// One SetSetting per changed key whose value isn't RigTune's already, to RigTune's last applied value.
	static List<Recommendation> reapply(List<OutsideOptions.Change> changes, Map<String, SettingLabel> labels) {
		List<Recommendation> out = new ArrayList<>();
		for (OutsideOptions.Change change : changes) {
			if (SettingValues.same(change.now(), change.rigtune())) {
				continue;
			}
			out.add(Recommendation.of("set:" + change.key(), Category.SETTING, Impact.LOW,
					SettingValues.describe(labels.get(change.key()), change.key(), change.now(), change.rigtune()),
					Text.of("rigtune.outside.reason", "The value RigTune last applied."),
					new Action.SetSetting(change.key(), change.now(), change.rigtune()), true));
		}
		return out;
	}

	private static void toast(RealController controller, Component body) {
		Minecraft minecraft = controller.minecraft();
		if (minecraft != null) {
			SystemToast.addOrUpdate(minecraft.gui.toastManager(), TOAST_ID, Component.translatable("rigtune.outside.toast.title"), body);
		}
	}

	static void found(List<OutsideOptions.Change> changes) {
		found = changes.isEmpty() ? null : new Found(KEY_PREFIX + Instant.now().toEpochMilli(), List.copyOf(changes));
	}

	static synchronized @Nullable Found take() {
		Found f = found;
		found = null;
		return f;
	}

	// For the unit tests: the watched keys.
	static Map<String, String> watched() {
		Map<String, String> watched = APPLIED.get();
		return watched == null ? Map.of() : watched;
	}

	static void reset() {
		APPLIED.set(null);
		found = null;
	}
}
