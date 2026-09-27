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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
	private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId(6000L);

	// What the start comparison found, until the player acts on it.
	record Found(String key, List<OutsideOptions.Change> changes) {
	}

	// The watched keys and RigTune's values: null until the start hook has read the journal.
	private static volatile @Nullable Map<String, String> applied;
	private static volatile @Nullable Found found;

	private OutsideChanges() {
	}

	public static void snapshotAtStop(RealController controller, Minecraft minecraft) {
		Map<String, String> snapshot = stopSnapshot(BenchmarkController.running() || Busy.tryItRunning.getAsBoolean(), applied,
				() -> SettingsBridge.readVanilla(minecraft.options));
		if (snapshot != null) {
			AwarenessStore.shared(controller.configDir()).setOptionsAtExit(snapshot);
		}
	}

	// The snapshot to store at a clean exit, or null for none: while a benchmark or a Try it runs, before the journal was
	// read, when RigTune never applied a vanilla key, or when none of them is among the game's options.
	static @Nullable Map<String, String> stopSnapshot(boolean busy, @Nullable Map<String, String> watched, Supplier<Map<String, String>> vanillaNow) {
		if (busy || watched == null || watched.isEmpty()) {
			return null;
		}
		Map<String, String> snapshot = OutsideOptions.snapshot(watched.keySet(), vanillaNow.get());
		return snapshot.isEmpty() ? null : snapshot;
	}

	public static void compareAtStart(RealController controller) {
		Map<String, String> snapshot = AwarenessStore.shared(controller.configDir()).takeOptionsAtExit();
		Map<String, String> watched = new LinkedHashMap<>(OutsideOptions.applied(ClientJournal.get().entries()));
		Map<String, String> thisSession = applied;
		if (thisSession != null) {
			watched.putAll(thisSession);
		}
		applied = watched;
		if (snapshot == null) {
			return;
		}
		List<OutsideOptions.Change> changes = OutsideOptions.compare(snapshot, OutsideOptions.parseOptions(options()), watched);
		if (!changes.isEmpty()) {
			RigTune.LOGGER.info("RigTune: {} setting(s) RigTune applied were changed outside the game since the last clean exit: {}", changes.size(),
					changes.stream().map(OutsideOptions.Change::key).toList());
		}
		found(changes);
	}

	// options.txt now; nothing when it can't be read.
	private static List<String> options() {
		Path file = FabricLoader.getInstance().getGameDir().resolve("options.txt");
		try {
			return Files.isRegularFile(file) && Files.size(file) <= MAX_OPTIONS_BYTES ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("Could not read options.txt for the settings-changed-outside check", e);
			return List.of();
		}
	}

	public static void afterApply(RealController controller, V05Hooks.ApplyFacts facts, List<Component> parts) {
		watch(facts);
		Component line = syncLine(controller.launcher(), facts.settingsOk() > 0);
		if (line != null) {
			parts.add(line);
		}
	}

	// This session's Apply: its changeable vanilla keys join the watched ones (its own selection, no I/O).
	static void watch(V05Hooks.ApplyFacts facts) {
		Map<String, String> watched = new LinkedHashMap<>();
		Map<String, String> before = applied;
		if (before != null) {
			watched.putAll(before);
		}
		for (Recommendation r : facts.selected()) {
			if (r.action() instanceof Action.SetSetting set && set.key().startsWith(SettingKeys.VANILLA_PREFIX) && SettingKeys.changeable(set.key())) {
				watched.put(set.key(), set.newValue());
			}
		}
		applied = watched;
	}

	// AC4h.4: in a Modrinth App instance, whose game-settings sync copies options.txt to the app's other synced instances,
	// when settings were written now (Preview's "Written now" and the Apply status).
	public static @Nullable Component syncLine(LauncherInfo launcher, boolean settingsWritten) {
		return settingsWritten && launcher.launcher() == Launcher.MODRINTH_APP ? Component.translatable("rigtune.outside.sync_line") : null;
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
			names.add(SettingValues.describe(labels.get(change.key()), change.key(), change.before(), change.now()));
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
					Text.of("rigtune.outside.reason", "RigTune's value before it was changed outside the game."),
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
		Map<String, String> watched = applied;
		return watched == null ? Map.of() : watched;
	}

	static void reset() {
		applied = null;
		found = null;
	}
}
