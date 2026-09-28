package io.github.chaotix345.rigtune.client.launcher;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.HelperToasts;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.client.undo.ModsFolder;
import io.github.chaotix345.rigtune.client.undo.Staging;
import io.github.chaotix345.rigtune.core.apply.ApplyExecutor;
import io.github.chaotix345.rigtune.core.apply.ApplyLock;
import io.github.chaotix345.rigtune.core.apply.HelperLauncher;
import io.github.chaotix345.rigtune.core.apply.InstanceDirs;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.history.HistoryUpdates;
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
import java.util.ArrayList;
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
// history.json, the mods folder); the notices' current(), asked on screen init and rebuild only (never per frame), read
// the last result, the policy and, under LAUNCHER or PENDING, three stats that tell whether it is stale (the coordinator's
// X8 ruling). A new result that changes what shows asks for a rebuild, so the screens ask again.
public final class LauncherRepairService {
	public static final String HELD_KEY = "held-mod-changes";
	public static final String CANCEL = "cancel";
	public static final String APPLY = "apply";
	public static final String COPY = "copy";
	// The title screen's leftover toasts (the game test finds them by these).
	public static final SystemToast.SystemToastId LEFTOVER_TOAST = new SystemToast.SystemToastId(10000L);
	public static final SystemToast.SystemToastId HELD_TOAST = new SystemToast.SystemToastId(10000L);
	public static final SystemToast.SystemToastId BUSY_TOAST = new SystemToast.SystemToastId(8000L);

	// Game tests only: the policy to use instead of the controller's (null: the controller's).
	private static volatile @Nullable ModFilesPolicy policyOverride;

	// Everything the service reaches; unit tests build their own. stagedChanged: pending.json was changed here (the
	// controller recounts its staged changes and rebuilds).
	record Env(Path configDir, Supplier<ModFilesPolicy> policy, Supplier<Launcher> launcher, Supplier<Journal> journal,
			Function<Path, UndoPlanner.Folder> folder, Runnable rebuild, Runnable stagedChanged, Runnable optIn, Executor executor,
			Consumer<String> clipboard, Consumer<List<HelperToasts.Toast>> toasts, Consumer<String> warn) {
		Path pendingFile() {
			return PendingActions.defaultPath(configDir);
		}

		Path modsDir() {
			return InstanceDirs.modsDirOf(pendingFile());
		}
	}

	// held: what the next exit's helper would hold; findings: null until wanted (the repair notice is asked under
	// LAUNCHER). Each as of the modification times it was read at (null: the file isn't there).
	private record State(List<Op> held, @Nullable FileTime pendingModified, @Nullable FileTime modsModified,
			LauncherRepair.@Nullable Findings findings, @Nullable FileTime historyModified) {
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

	// Made on first use (after startup), so the constructor only keeps the controller (contracts: V05Services' skeletons).
	// The policy is read through the ModFilesService resolved here, so a call is ModFilesService.policy() alone, never
	// V05Services' synchronized getter (the leftover listener asks it each tick while it waits).
	private Env env() {
		Env made = env;
		if (made == null) {
			synchronized (this) {
				made = env;
				if (made == null) {
					RealController real = Objects.requireNonNull(controller);
					ModFilesService files = real.v05().modFiles();
					made = new Env(real.configDir(), () -> {
						ModFilesPolicy forced = policyOverride;
						return forced != null ? forced : files.policy();
					}, () -> real.launcher().launcher(), ClientJournal::get, ModsFolder::current, real::rebuild, real::stagedChanged,
							() -> optIn(real), Probes.EXECUTOR, text -> Minecraft.getInstance().keyboardHandler.setClipboard(text),
							LauncherRepairService::show, RigTune.LOGGER::warn);
					env = made;
				}
			}
		}
		return made;
	}

	public static void overridePolicy(@Nullable ModFilesPolicy policy) {
		policyOverride = policy;
	}

	// "Let RigTune apply them": the settings row's "Let RigTune change them anyway" (WS-L1's setOptIn: settings.json through
	// SettingsSaver, then a rebuild under the new policy).
	private static void optIn(RealController controller) {
		controller.v05().modFiles().setOptIn(true);
	}

	private static void show(List<HelperToasts.Toast> toasts) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> toasts.forEach(t -> SystemToast.add(minecraft.gui.toastManager(), switch (t.kind()) {
			case HELD -> HELD_TOAST;
			case BUSY -> BUSY_TOAST;
			default -> LEFTOVER_TOAST;
		}, t.title(), t.body())));
	}

	// ---- HELD_MOD_CHANGES (4d)

	// Render thread: under LAUNCHER or PENDING, "N mod changes from an earlier Apply are waiting" with Cancel them and Let
	// RigTune apply them. Not dismissible: they wait for one of the two.
	public @Nullable Notice heldNotice() {
		ModFilesPolicy policy = env().policy().get();
		if (policy == ModFilesPolicy.RIGTUNE) {
			return null;
		}
		State current = current(policy);
		if (current == null || current.held().isEmpty()) {
			return null;
		}
		return heldNotice(LauncherRepair.modChanges(current.held()), policy, env().launcher().get());
	}

	// The ops the next exit's helper holds, as last read, while the policy holds them; none before the first read. For
	// RealController's "restart to apply N" (review-11 APPLY-3): a restart never applies these.
	public List<Op> heldOps() {
		State current = state;
		return current == null || !HelperLauncher.holds(env().policy().get()) ? List.of() : current.held();
	}

	public int heldChanges() {
		return LauncherRepair.modChanges(heldOps());
	}

	// The notice itself (also the A11y walk's canned one).
	public static Notice heldNotice(int changes, ModFilesPolicy policy, Launcher launcher) {
		Text message = policy == ModFilesPolicy.PENDING
				? Text.of("rigtune.repair.held.message.pending", "%s mod change(s) from an earlier Apply are waiting while RigTune checks which"
						+ " launcher manages this instance's mods.", changes)
				: Text.of("rigtune.repair.held.message", "%s mod change(s) from an earlier Apply are waiting: %s manages this instance's mods.", changes,
						LauncherRepair.name(launcher));
		return new Notice(HELD_KEY, NoticePriority.HELD_MOD_CHANGES, message,
				Text.of("rigtune.repair.held.detail", "RigTune changes no mod file in an instance whose launcher keeps its own list of mods, so it"
						+ " holds these at exit. Cancel them, or let RigTune apply them at the next exit (that turns on \"Let RigTune change them"
						+ " anyway\" in Settings)."),
				List.of(new NoticeAction(CANCEL, Text.of("rigtune.repair.held.cancel", "Cancel them")),
						new NoticeAction(APPLY, Text.of("rigtune.repair.held.apply", "Let RigTune apply them"))), false);
	}

	public void heldAction(String actionId) {
		if (CANCEL.equals(actionId)) {
			// Only what the notice counted (review-11 APPLY-6): a group staged since, or no longer held, stays.
			State shown = state;
			List<Op> counted = shown == null ? List.of() : shown.held();
			env().executor().execute(() -> cancelHeld(counted));
		} else if (APPLY.equals(actionId)) {
			env().optIn().run();
		}
	}

	// Under the apply lock, while the policy still holds, the ops the notice counted that the helper would still hold now
	// (ApplyExecutor.held: never a group RigTune's records prove started) leave pending.json, matched by sameOp (an op
	// 0.1.0 staged without an id included); their downloads become .rigtune-superseded unless a kept op still uses them,
	// and their journal changes DISCARDED, as Staging's unstage path does. Then the controller recounts its staged changes
	// and rebuilds. A busy lock (the last session's helper still running) says so in a toast.
	void cancelHeld(List<Op> counted) {
		if (!HelperLauncher.holds(env().policy().get())) {
			RigTune.LOGGER.info("RigTune: nothing cancelled; RigTune changes this instance's mod files now");
			return;
		}
		Path pendingFile = env().pendingFile();
		Staging staging = new Staging(env().configDir(), pendingFile, List.of(), env().journal().get());
		try (ApplyLock lock = staging.lock()) {
			if (lock == null) {
				RigTune.LOGGER.warn("RigTune: the held mod changes weren't cancelled; the apply lock is busy");
				env().toasts().accept(List.of(HelperToasts.cancelBusy()));
				return;
			}
			if (Files.isRegularFile(pendingFile)) {
				PendingActions plan = PendingActions.load(pendingFile);
				List<Op> held = ApplyExecutor.held(plan, pendingFile).stream()
						.filter(h -> h != null && counted.stream().anyMatch(c -> c != null && c.sameOp(h))).toList();
				List<Op> kept = new ArrayList<>();
				List<Op> dropped = new ArrayList<>();
				for (Op op : plan.ops()) {
					(op != null && held.stream().anyMatch(h -> h.sameOp(op)) ? dropped : kept).add(op);
				}
				if (!dropped.isEmpty()) {
					if (kept.isEmpty()) {
						Files.deleteIfExists(pendingFile);
					} else {
						plan.withOps(kept).save(pendingFile);
					}
					Path mods = env().modsDir();
					for (Op op : dropped) {
						if (kept.stream().noneMatch(k -> k != null && op.from() != null && op.from().equals(k.from()))) {
							PendingActions.retireDownload(op, mods);
						}
					}
					List<String> ids = dropped.stream().map(Op::id).filter(Objects::nonNull).toList();
					Journal journal = env().journal().get();
					if (!ids.isEmpty() && journal.exists()) {
						journal.updateExisting(entries -> HistoryUpdates.discard(entries, ids));
					}
				}
				RigTune.LOGGER.info("RigTune: cancelled {} held mod change operation(s)", dropped.size());
			}
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("RigTune: could not cancel the held mod changes", e);
		}
		refreshNow();
		env().stagedChanged().run();
	}

	// ---- LAUNCHER_REPAIR (4g)

	// Render thread: only under LAUNCHER, and only while a finding needs a step in this launcher. Dismissed by its key,
	// which changes with the set of findings that need one.
	public @Nullable Notice repairNotice() {
		ModFilesPolicy policy = env().policy().get();
		if (policy != ModFilesPolicy.LAUNCHER) {
			return null;
		}
		State current = current(policy);
		LauncherRepair.Findings findings = current == null ? null : current.findings();
		return findings == null ? null : repairNotice(findings, env().launcher().get());
	}

	// The notice itself, or null when nothing needs a step in this launcher (also the A11y walk's canned one).
	public static @Nullable Notice repairNotice(LauncherRepair.Findings findings, Launcher launcher) {
		if (!LauncherRepair.actionable(findings, launcher)) {
			return null;
		}
		return new Notice(findings.key(launcher), NoticePriority.LAUNCHER_REPAIR, LauncherRepair.message(launcher),
				LauncherRepair.detail(findings, launcher), List.of(new NoticeAction(COPY, Text.of("rigtune.repair.copy", "Copy list"))), true);
	}

	public void repairAction(String actionId) {
		State current = state;
		if (COPY.equals(actionId) && current != null && current.findings() != null) {
			env().clipboard().accept(LauncherRepair.copyList(current.findings(), env().launcher().get()));
		}
	}

	// ---- The state, read on a worker

	// The last result, or null while none is ready. A refresh starts when there's none; when pending.json (Discard, Undo, a
	// drop at a rebuild) or the mods folder changed since, or, for the findings, history.json; and under LAUNCHER while the
	// findings haven't been read. Until it's done the last result stays (a refresh that changes what shows asks for a
	// rebuild).
	private @Nullable State current(ModFilesPolicy policy) {
		if (policy == ModFilesPolicy.LAUNCHER) {
			findingsWanted = true;
		}
		State current = state;
		if (current == null || findingsWanted && current.findings() == null
				|| !Objects.equals(current.pendingModified(), modified(env().pendingFile()))
				|| !Objects.equals(current.modsModified(), modified(env().modsDir()))
				|| current.findings() != null && !Objects.equals(current.historyModified(), modified(Journal.file(env().configDir())))) {
			refresh();
		}
		return current;
	}

	void refresh() {
		if (refreshing.compareAndSet(false, true)) {
			env().executor().execute(() -> {
				try {
					State before = state;
					State after = refreshNow();
					// The findings were wanted while this read was already under way (the first screen asks for the held notice
					// first): read again rather than leave them unread until the next ask.
					if (after != null && findingsWanted && after.findings() == null) {
						after = refreshNow();
					}
					boolean shows = after != null && (!after.held().isEmpty() || after.findings() != null && !after.findings().isEmpty());
					if (after != null && (before == null ? shows
							: !before.held().equals(after.held()) || !Objects.equals(before.findings(), after.findings()))) {
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
			boolean wanted = findingsWanted;
			FileTime pending = modified(pendingFile);
			FileTime mods = modified(env().modsDir());
			FileTime history = modified(Journal.file(env().configDir()));
			List<Op> held = pending == null ? List.of() : ApplyExecutor.held(PendingActions.load(pendingFile), pendingFile);
			LauncherRepair.Findings findings = !wanted ? null : LauncherRepair.find(env().journal().get().entries(), env().folder().apply(env().modsDir()));
			State fresh = new State(held, pending, mods, findings, history);
			state = fresh;
			return fresh;
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("RigTune: could not read the staged mod changes or RigTune's records", e);
			return null;
		}
	}

	private static @Nullable FileTime modified(Path path) {
		try {
			return Files.exists(path) ? Files.getLastModifiedTime(path) : null;
		} catch (IOException e) {
			return null;
		}
	}

	// ---- The title screen's leftover toasts (4d)

	// leftoverOps: the staged ops preLaunch found, some in mod-file groups. Whether those are retried at the next exit or
	// held for the player's choice depends on the policy, so the WARN and the toasts wait for it, in this service's own
	// END_CLIENT_TICK listener: registered here on first need (never at init, never on RigTuneClient.onTick's timed path),
	// returning at once while nothing waits (one volatile read, no allocation), and while the policy is PENDING reading
	// ModFilesService.policy() alone. Its footprint keys come with the post-Wave-B checkpoint (SPEC 1h).
	public void leftoverAtTitle(int leftoverOps) {
		env();
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
