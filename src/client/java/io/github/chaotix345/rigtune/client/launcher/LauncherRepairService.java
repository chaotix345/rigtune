package io.github.chaotix345.rigtune.client.launcher;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.HelperToasts;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.SettingsSaver;
import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.client.undo.ModsFolder;
import io.github.chaotix345.rigtune.client.undo.Staging;
import io.github.chaotix345.rigtune.core.apply.ApplyExecutor;
import io.github.chaotix345.rigtune.core.apply.ApplyLock;
import io.github.chaotix345.rigtune.core.apply.InstanceDirs;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.LauncherRepair;
import io.github.chaotix345.rigtune.core.history.UndoPlanner;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeAction;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

// docs/v0.5/SPEC.md 4d, 4g (P0.4): the held pending mod changes (HELD_MOD_CHANGES), the repair findings (LAUNCHER_REPAIR)
// and the title screen's leftover toasts that wait for the policy. Reached only through RealController.v05() (X4); the
// constructor only keeps references. Files are read on a worker (pending.json and RigTune's own records next to it,
// history.json, the mods folder); the notices' current() read the last result and the policy, plus a stat of
// pending.json while something is held. A new result asks for a rebuild, so the screens ask again.
public final class LauncherRepairService {
	public static final String HELD_KEY = "held-mod-changes";
	public static final String CANCEL = "cancel";
	public static final String APPLY = "apply";
	public static final String COPY = "copy";

	// Game tests only: the policy to use instead of the controller's (null: the controller's).
	private static volatile @Nullable ModFilesPolicy policyOverride;

	// Everything the service reaches; unit tests build their own.
	record Env(Path configDir, Supplier<ModFilesPolicy> policy, Supplier<Launcher> launcher, Supplier<Journal> journal,
			Function<Path, UndoPlanner.Folder> folder, Runnable rebuild, Runnable optIn, Executor executor, Consumer<String> clipboard,
			Consumer<List<HelperToasts.Toast>> toasts, Consumer<String> warn) {
		Path pendingFile() {
			return PendingActions.defaultPath(configDir);
		}
	}

	// held: what the next exit's helper would hold, as of pendingModified (null: no pending.json); findings: null until
	// wanted (the repair notice is asked under LAUNCHER).
	private record State(List<Op> held, @Nullable FileTime pendingModified, LauncherRepair.@Nullable Findings findings) {
	}

	private final @Nullable RealController controller;
	private volatile @Nullable Env env;
	private volatile @Nullable State state;
	private final AtomicBoolean refreshing = new AtomicBoolean();
	private volatile boolean findingsWanted;
	// The leftover ops preLaunch counted, waiting for the policy; -1: nothing waits.
	private volatile int leftover = -1;
	private final AtomicBoolean listening = new AtomicBoolean();

	public LauncherRepairService(RealController controller) {
		this.controller = controller;
	}

	LauncherRepairService(Env env) {
		this.controller = null;
		this.env = env;
	}

	// Made on first use, so the constructor only keeps the controller (contracts: V05Services' skeletons).
	private Env env() {
		Env made = env;
		if (made == null) {
			RealController real = Objects.requireNonNull(controller);
			made = new Env(real.configDir(), () -> policy(real), () -> real.launcher().launcher(), ClientJournal::get, ModsFolder::current,
					real::rebuild, () -> optIn(real), Probes.EXECUTOR, text -> Minecraft.getInstance().keyboardHandler.setClipboard(text),
					LauncherRepairService::show, RigTune.LOGGER::warn);
			env = made;
		}
		return made;
	}

	public static void overridePolicy(@Nullable ModFilesPolicy policy) {
		policyOverride = policy;
	}

	private static ModFilesPolicy policy(RealController controller) {
		ModFilesPolicy forced = policyOverride;
		return forced != null ? forced : controller.modFiles();
	}

	// "Let RigTune apply them": what the settings row's "Let RigTune change them anyway" does (settings.json through
	// SettingsSaver, then a rebuild under the new policy).
	private static void optIn(RealController controller) {
		ClientSettings settings = controller.settings();
		settings.modFilesByRigTune = true;
		SettingsSaver.shared().save(settings, controller.configDir());
		controller.rebuild();
	}

	private static void show(List<HelperToasts.Toast> toasts) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> toasts.forEach(t -> SystemToast.add(minecraft.gui.toastManager(), new SystemToast.SystemToastId(10000L), t.title(), t.body())));
	}

	// ---- HELD_MOD_CHANGES (4d)

	// Render thread: under LAUNCHER or PENDING, "N mod changes from an earlier Apply are waiting" with Cancel them and Let
	// RigTune apply them. Not dismissible: they wait for one of the two.
	public @Nullable Notice heldNotice() {
		ModFilesPolicy policy = env().policy().get();
		if (policy == ModFilesPolicy.RIGTUNE) {
			return null;
		}
		State current = current();
		if (current == null || current.held().isEmpty()) {
			return null;
		}
		int changes = LauncherRepair.modChanges(current.held());
		Text message = policy == ModFilesPolicy.PENDING
				? Text.of("rigtune.repair.held.message.pending", "%s mod change(s) from an earlier Apply are waiting while RigTune checks which"
						+ " launcher manages this instance's mods.", changes)
				: Text.of("rigtune.repair.held.message", "%s mod change(s) from an earlier Apply are waiting: %s manages this instance's mods.", changes,
						LauncherRepair.name(env().launcher().get()));
		return new Notice(HELD_KEY, NoticePriority.HELD_MOD_CHANGES, message,
				Text.of("rigtune.repair.held.detail", "RigTune changes no mod file in an instance whose launcher keeps its own list of mods, so it"
						+ " holds these at exit. Cancel them, or let RigTune apply them at the next exit (that turns on \"Let RigTune change them"
						+ " anyway\" in Settings)."),
				List.of(new NoticeAction(CANCEL, Text.of("rigtune.repair.held.cancel", "Cancel them")),
						new NoticeAction(APPLY, Text.of("rigtune.repair.held.apply", "Let RigTune apply them"))), false);
	}

	public void heldAction(String actionId) {
		if (CANCEL.equals(actionId)) {
			env().executor().execute(this::cancelHeld);
		} else if (APPLY.equals(actionId)) {
			env().optIn().run();
		}
	}

	// Under the apply lock, with what the helper would hold now: those ops leave pending.json with their groups, their
	// downloads become .rigtune-superseded and their journal changes DISCARDED (Staging's unstage-by-op-id path). A group
	// RigTune's records show half done isn't held (ApplyExecutor.held), so it is never cancelled here.
	void cancelHeld() {
		Path pendingFile = env().pendingFile();
		Staging staging = new Staging(env().configDir(), pendingFile, List.of(), env().journal().get());
		try (ApplyLock lock = staging.lock()) {
			if (lock == null) {
				RigTune.LOGGER.warn("RigTune: the held mod changes weren't cancelled; the apply lock is busy");
				return;
			}
			if (Files.isRegularFile(pendingFile)) {
				List<String> ids = ApplyExecutor.held(PendingActions.load(pendingFile), pendingFile).stream()
						.filter(Objects::nonNull).map(Op::id).filter(Objects::nonNull).toList();
				List<Op> dropped = staging.unstageLocked(ids);
				RigTune.LOGGER.info("RigTune: cancelled {} held mod change operation(s)", dropped.size());
			}
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("RigTune: could not cancel the held mod changes", e);
		}
		refreshNow();
		env().rebuild().run();
	}

	// ---- LAUNCHER_REPAIR (4g)

	// Render thread: only under LAUNCHER, and only while a finding needs a step in this launcher. Dismissed by its key,
	// which changes with the set of findings.
	public @Nullable Notice repairNotice() {
		if (env().policy().get() != ModFilesPolicy.LAUNCHER) {
			return null;
		}
		findingsWanted = true;
		State current = current();
		if (current != null && current.findings() == null) {
			refresh();
			return null;
		}
		LauncherRepair.Findings findings = current == null ? null : current.findings();
		Launcher launcher = env().launcher().get();
		if (findings == null || !LauncherRepair.actionable(findings, launcher)) {
			return null;
		}
		return new Notice(findings.key(), NoticePriority.LAUNCHER_REPAIR, LauncherRepair.message(launcher), LauncherRepair.detail(findings, launcher),
				List.of(new NoticeAction(COPY, Text.of("rigtune.repair.copy", "Copy list"))), true);
	}

	public void repairAction(String actionId) {
		State current = state;
		if (COPY.equals(actionId) && current != null && current.findings() != null) {
			env().clipboard().accept(LauncherRepair.copyList(current.findings(), env().launcher().get()));
		}
	}

	// ---- The state, read on a worker

	// The last result, or null while none is ready (a refresh then starts). One whose pending.json changed since (Discard,
	// Undo, a drop at a rebuild) is stale: null and a refresh.
	private @Nullable State current() {
		State current = state;
		if (current == null || !current.held().isEmpty() && !Objects.equals(current.pendingModified(), modified(env().pendingFile()))) {
			refresh();
			return null;
		}
		return current;
	}

	void refresh() {
		if (refreshing.compareAndSet(false, true)) {
			env().executor().execute(() -> {
				try {
					State before = state;
					State after = refreshNow();
					if (after != null && !after.equals(before) && (!after.held().isEmpty() || after.findings() != null && !after.findings().isEmpty())) {
						env().rebuild().run();
					}
				} finally {
					refreshing.set(false);
				}
			});
		}
	}

	private @Nullable State refreshNow() {
		try {
			Path pendingFile = env().pendingFile();
			FileTime modified = modified(pendingFile);
			List<Op> held = modified == null ? List.of() : ApplyExecutor.held(PendingActions.load(pendingFile), pendingFile);
			LauncherRepair.Findings findings = !findingsWanted ? null
					: LauncherRepair.find(env().journal().get().entries(), env().folder().apply(InstanceDirs.modsDirOf(pendingFile)));
			State fresh = new State(held, modified, findings);
			state = fresh;
			return fresh;
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("RigTune: could not read the staged mod changes or RigTune's records", e);
			return null;
		}
	}

	private static @Nullable FileTime modified(Path file) {
		try {
			return Files.isRegularFile(file) ? Files.getLastModifiedTime(file) : null;
		} catch (IOException e) {
			return null;
		}
	}

	// ---- The title screen's leftover toasts (4d)

	// leftoverOps: the staged ops preLaunch found, some in mod-file groups. Whether those are retried at the next exit or
	// held for the player's choice depends on the policy, so the WARN and the toasts wait for it, in this service's own
	// END_CLIENT_TICK listener: registered here on first need (never at init, never on RigTuneClient.onTick's timed path),
	// returning at once while nothing waits (one volatile read, no allocation).
	public void leftoverAtTitle(int leftoverOps) {
		waitForPolicy(leftoverOps);
		if (listening.compareAndSet(false, true)) {
			ClientTickEvents.END_CLIENT_TICK.register(minecraft -> tickLeftover());
		}
	}

	void waitForPolicy(int leftoverOps) {
		leftover = leftoverOps;
	}

	void tickLeftover() {
		int waiting = leftover;
		if (waiting < 0) {
			return;
		}
		ModFilesPolicy policy = env().policy().get();
		if (policy == ModFilesPolicy.PENDING) {
			return;
		}
		leftover = -1;
		if (policy == ModFilesPolicy.RIGTUNE) {
			report(waiting, 0);
			return;
		}
		env().executor().execute(() -> {
			Path pendingFile = env().pendingFile();
			try {
				if (Files.isRegularFile(pendingFile)) {
					PendingActions plan = PendingActions.load(pendingFile);
					List<Op> held = ApplyExecutor.held(plan, pendingFile);
					report(plan.ops().size() - held.size(), LauncherRepair.modChanges(held));
				}
			} catch (IOException | RuntimeException e) {
				RigTune.LOGGER.warn("RigTune: could not read the staged changes", e);
			}
		});
	}

	private void report(int retried, int held) {
		HelperToasts.warnLines(retried, held).forEach(env().warn());
		List<HelperToasts.Toast> toasts = HelperToasts.leftover(retried, held);
		if (!toasts.isEmpty()) {
			env().toasts().accept(toasts);
		}
	}
}
