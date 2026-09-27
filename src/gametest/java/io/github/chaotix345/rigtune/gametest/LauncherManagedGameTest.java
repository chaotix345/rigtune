package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.SettingsSaver;
import io.github.chaotix345.rigtune.client.launcher.LauncherRepairService;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.LauncherRepair;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.TitleScreen;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

// docs/v0.5/SPEC.md 4j (AC4j.2 and the P0.4 game-test checks): RigTune in an instance whose launcher keeps its own
// record of the mods. Contracts skeleton (WS-K): registered at its C6 place in fabric.mod.json, returns at once under
// rigtune.smoke; one skeleton method per owner, each called once from runTest with the contracts' context record. An
// owner edits only its own method's body (and its own private helpers below it); each block puts back what it changed
// (the launcher brand property, fixture files, the network switch through GameTestNet).
public class LauncherManagedGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		V05TestContext v05 = V05TestContext.of(context);
		policyAndAdvice(v05);
		heldAndRepair(v05);
	}

	// ---- WS-L1 (AC4j.2, AC4a.3, AC4b.2, AC4b.6, AC4e.2): the policy, the launcher's steps, the refused Apply, the opt-in,
	// MOD_FILES_NEWS.

	private static void policyAndAdvice(V05TestContext v05) {
	}

	// ---- WS-L2 (AC4d.2, AC4d.4, AC4g.2): held pending file groups and the repair notice.

	// With the real controller and the network off (X1). The policy is forced to LAUNCHER through the service's test seam
	// (LauncherRepairService.overridePolicy) until WS-L1's detection answers here. Seeded as a 0.4 instance would have
	// them: an applied update pair still in effect and an added jar (history.json + mods/), and a staged 0.4 file group
	// (pending.json, its download, its STAGED journal changes). Everything is put back afterwards: pending.json,
	// history.json, awareness.json, the opt-in, the fixture jars, the network switch.
	private static void heldAndRepair(V05TestContext v05) {
		ClientGameTestContext context = v05.context();
		RealController real = v05.realController();
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		context.waitForScreen(TitleScreen.class);
		context.waitFor(mc -> real.report() != null, 1200);
		Path config = v05.configDir();
		Path pending = PendingActions.defaultPath(config);
		Path history = Journal.file(config);
		Path awareness = real.awarenessService().file();
		byte[] pendingBefore = read(pending);
		byte[] historyBefore = read(history);
		byte[] awarenessBefore = read(awareness);
		boolean optIn = real.settings().modFilesByRigTune;
		List<Path> made = new ArrayList<>();
		boolean network = GameTestNet.set(context, real, false);
		LauncherRepairService.overridePolicy(ModFilesPolicy.LAUNCHER);
		try {
			String repairKey = seedRepairRecords(real, made);
			List<Op> held = seedHeldGroup(real, made, "a");
			openRigTune(context);
			Notice heldNotice = waitForNotice(context, real, LauncherRepairService.HELD_KEY);
			check(heldNotice.priority() == NoticePriority.HELD_MOD_CHANGES && !heldNotice.dismissible(), "the held notice: " + heldNotice);
			check(heldNotice.message().english().startsWith("1 mod change(s) from an earlier Apply are waiting:"), heldNotice.message().english());
			for (int[] size : V05TestContext.SIZES) {
				v05.resize(size[0], size[1], size[2]);
				openRigTune(context);
				screenshot(context, "launcher-held-notice-" + size[0] + "x" + size[1] + "-scale" + size[2]);
			}
			v05.resize(1280, 720, 2);

			// AC4g.2: the repair notice, its Copy list (names only) and Dismiss.
			Notice repair = waitForNotice(context, real, repairKey);
			check(repair.priority() == NoticePriority.LAUNCHER_REPAIR && repair.dismissible(), "the repair notice: " + repair);
			String detail = Objects.requireNonNull(repair.detail()).english();
			check(detail.contains("rigtunetestpair-1.0.jar.disabled") && detail.contains("rigtunetestadded-1.0.jar"), detail);
			context.runOnClient(mc -> mc.keyboardHandler.setClipboard(""));
			context.runOnClient(mc -> real.noticeAction(repairKey, LauncherRepairService.COPY));
			String copied = context.computeOnClient(mc -> mc.keyboardHandler.getClipboard());
			check(copied.equals("rigtunetestpair-1.0.jar.disabled"), "Copy list puts the file names only on the clipboard: '" + copied + "'");
			context.runOnClient(mc -> real.dismissNotice(repairKey));
			check(find(notices(context, real), repairKey) == null, "the repair notice is gone once dismissed");

			// AC4d.2: Cancel them.
			context.runOnClient(mc -> real.noticeAction(LauncherRepairService.HELD_KEY, LauncherRepairService.CANCEL));
			Path download = Path.of(held.get(1).from());
			context.waitFor(mc -> !hasFileOp(pending) && !Files.exists(download), 200);
			String superseded = download.getFileName().toString().replace(PendingActions.PENDING_SUFFIX, PendingActions.SUPERSEDED_SUFFIX);
			check(Files.exists(download.resolveSibling(superseded)), "the download is .rigtune-superseded");
			List<String> statuses = statuses(held);
			check(statuses.equals(List.of(JournalChange.DISCARDED, JournalChange.DISCARDED)), "the journal marks them DISCARDED: " + statuses);
			context.waitFor(mc -> find(real.notices(), LauncherRepairService.HELD_KEY) == null, 200);

			// AC4d.4: the title screen's leftover toast waits for the policy and says "waiting for your choice"; under RIGTUNE it
			// is today's.
			seedHeldGroup(real, made, "b");
			leftoverToast(context, real, ModFilesPolicy.LAUNCHER, LauncherRepairService.HELD_TOAST, LauncherRepairService.LEFTOVER_TOAST);
			leftoverToast(context, real, ModFilesPolicy.RIGTUNE, LauncherRepairService.LEFTOVER_TOAST, LauncherRepairService.HELD_TOAST);
			LauncherRepairService.overridePolicy(ModFilesPolicy.LAUNCHER);

			// AC4d.2: Let RigTune apply them turns the opt-in on.
			openRigTune(context);
			waitForNotice(context, real, LauncherRepairService.HELD_KEY);
			context.runOnClient(mc -> real.noticeAction(LauncherRepairService.HELD_KEY, LauncherRepairService.APPLY));
			context.waitFor(mc -> real.settings().modFilesByRigTune, 200);
			check(SettingsSaver.shared().flush(5_000), "settings.json saved");
			check(ClientSettings.load(config).modFilesByRigTune, "settings.json has the opt-in");
			RigTune.LOGGER.info("LauncherManagedGameTest: held changes (notice, Cancel them, Let RigTune apply them, the leftover toasts) and the"
					+ " repair notice (text, Copy list, Dismiss) checked");
		} finally {
			LauncherRepairService.overridePolicy(null);
			context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
			ClientSettings settings = real.settings();
			settings.modFilesByRigTune = optIn;
			SettingsSaver.shared().save(settings, config);
			SettingsSaver.shared().flush(5_000);
			restore(pending, pendingBefore);
			restore(history, historyBefore);
			restore(awareness, awarenessBefore);
			made.forEach(LauncherManagedGameTest::delete);
			GameTestNet.set(context, real, network);
			v05.resize(854, 480, 0);
		}
	}

	// An applied update of "rigtunetestpair" still in effect (its old copy disabled, its new one there) and an added
	// "rigtunetestadded", as 0.1.0-0.4's journal records them. Returns the repair notice's key.
	private static String seedRepairRecords(RealController real, List<Path> made) {
		Path mods = real.modsDir();
		jar(mods.resolve("rigtunetestpair-1.0.jar.disabled"), "rigtunetestpair", made);
		jar(mods.resolve("rigtunetestpair-2.0.jar"), "rigtunetestpair", made);
		jar(mods.resolve("rigtunetestadded-1.0.jar"), "rigtunetestadded", made);
		ClientJournal.get().record("gametest-l2-repair-" + UUID.randomUUID(), JournalEntry.APPLY, List.of(
				JournalChange.file(JournalChange.DISABLE, "rigtunetestpair", "rigtunetestpair-1.0.jar", JournalChange.APPLIED, UUID.randomUUID().toString(),
						"g-pair").withResultFile("rigtunetestpair-1.0.jar.disabled"),
				JournalChange.file(JournalChange.ENABLE, "rigtunetestpair", "rigtunetestpair-2.0.jar", JournalChange.APPLIED, UUID.randomUUID().toString(),
						"g-pair"),
				JournalChange.file(JournalChange.ENABLE, "rigtunetestadded", "rigtunetestadded-1.0.jar", JournalChange.APPLIED, UUID.randomUUID().toString(),
						"g-added")));
		return new LauncherRepair.Findings(List.of(new LauncherRepair.Pair("rigtunetestpair", "rigtunetestpair-1.0.jar.disabled", "rigtunetestpair-2.0.jar")),
				List.of("rigtunetestadded-1.0.jar"), List.of()).key();
	}

	// A 0.4-staged update of "rigtunetestheld<tag>": pending.json, its download, and its STAGED journal changes.
	private static List<Op> seedHeldGroup(RealController real, List<Path> made, String tag) {
		Path mods = real.modsDir();
		String modId = "rigtunetestheld" + tag;
		Path old = jar(mods.resolve(modId + "-1.0.jar"), modId, made);
		Path download = jar(mods.resolve(modId + "-2.0.jar" + PendingActions.PENDING_SUFFIX), modId, made);
		made.add(mods.resolve(modId + "-2.0.jar" + PendingActions.SUPERSEDED_SUFFIX));
		List<Op> group = PendingActions.group(Op.disableFile(old), Op.enableFile(download, mods.resolve(modId + "-2.0.jar")).withModId(modId));
		try {
			PendingActions.create(1, mods, real.configDir(), group).save(PendingActions.defaultPath(real.configDir()));
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		ClientJournal.get().record("gametest-l2-held-" + tag + "-" + UUID.randomUUID(), JournalEntry.APPLY, List.of(
				JournalChange.file(JournalChange.DISABLE, modId, old.getFileName().toString(), JournalChange.STAGED, group.get(0).id(), group.get(0).group()),
				JournalChange.file(JournalChange.ENABLE, modId, modId + "-2.0.jar", JournalChange.STAGED, group.get(1).id(), group.get(1).group())));
		return group;
	}

	// The leftover toasts for two staged ops under `policy`: none while the policy is PENDING, then `shown` and never
	// `absent`.
	private static void leftoverToast(ClientGameTestContext context, RealController real, ModFilesPolicy policy, SystemToast.SystemToastId shown,
			SystemToast.SystemToastId absent) {
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		context.waitForScreen(TitleScreen.class);
		context.runOnClient(mc -> mc.gui.toastManager().clear());
		LauncherRepairService.overridePolicy(ModFilesPolicy.PENDING);
		context.runOnClient(mc -> real.v05().launcherRepair().leftoverAtTitle(2));
		context.waitTicks(5);
		check(context.computeOnClient(mc -> mc.gui.toastManager().getToast(SystemToast.class, shown) == null
				&& mc.gui.toastManager().getToast(SystemToast.class, absent) == null), "no leftover toast while the policy isn't known");
		LauncherRepairService.overridePolicy(policy);
		context.waitFor(mc -> mc.gui.toastManager().getToast(SystemToast.class, shown) != null, 200);
		// Past the toast's slide-in, so the screenshot shows all of it.
		context.waitTicks(30);
		check(context.computeOnClient(mc -> mc.gui.toastManager().getToast(SystemToast.class, absent) == null), "under " + policy + " only its own toast");
		context.getInput().setCursorPos(1, 1);
		context.takeScreenshot("launcher-leftover-toast-" + policy.name().toLowerCase(Locale.ROOT));
	}

	private static void openRigTune(ClientGameTestContext context) {
		context.runOnClient(mc -> RigTuneClient.open(new TitleScreen()));
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(3);
	}

	// Asks on the render thread (the notice sources' thread) until the notice is there; the first ask starts the read.
	private static Notice waitForNotice(ClientGameTestContext context, RealController real, String key) {
		context.waitFor(mc -> find(real.notices(), key) != null, 400);
		return Objects.requireNonNull(find(notices(context, real), key));
	}

	private static List<Notice> notices(ClientGameTestContext context, RealController real) {
		return context.computeOnClient(mc -> real.notices());
	}

	private static @Nullable Notice find(List<Notice> notices, String key) {
		return notices.stream().filter(n -> n.key().equals(key)).findFirst().orElse(null);
	}

	private static boolean hasFileOp(Path pending) {
		try {
			return Files.isRegularFile(pending) && PendingActions.load(pending).ops().stream()
					.anyMatch(op -> op.type() == PendingActions.Type.ENABLE_FILE || op.type() == PendingActions.Type.DISABLE_FILE);
		} catch (IOException e) {
			return true;
		}
	}

	private static List<String> statuses(List<Op> ops) {
		List<String> out = new ArrayList<>();
		for (JournalEntry entry : ClientJournal.get().entries()) {
			for (JournalChange change : entry.changes()) {
				if (ops.stream().anyMatch(op -> op.id().equals(change.opId()))) {
					out.add(change.status());
				}
			}
		}
		return out;
	}

	private static Path jar(Path file, String modId, List<Path> made) {
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json"));
			zip.write(("{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0\"}").getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		made.add(file);
		return file;
	}

	private static byte @Nullable [] read(Path file) {
		try {
			return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
		} catch (IOException e) {
			throw new AssertionError("Could not read " + file, e);
		}
	}

	private static void restore(Path file, byte @Nullable [] bytes) {
		try {
			if (bytes == null) {
				Files.deleteIfExists(file);
			} else {
				Files.write(file, bytes);
			}
		} catch (IOException e) {
			throw new AssertionError("Could not restore " + file, e);
		}
	}

	private static void delete(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			RigTune.LOGGER.warn("LauncherManagedGameTest: could not delete {}", file.getFileName(), e);
		}
	}

	// No toast over the notice line; the cursor in a corner.
	private static void screenshot(ClientGameTestContext context, String name) {
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> mc.gui.toastManager().clear());
		context.waitTicks(2);
		context.takeScreenshot(name);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
	}
}
