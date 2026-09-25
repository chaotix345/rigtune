package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.UndoScreen;
import io.github.chaotix345.rigtune.core.apply.ApplyExecutor;
import io.github.chaotix345.rigtune.core.apply.ApplyLock;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.SodiumConfigPatcher;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// AC3.4: apply a vanilla + Sodium change through the real controller, undo it from the Undo screen, and check the
// values, pending.json and history.json. Then the post-exit path: the helper applies the Sodium change, and undoing it
// stages the old value for the next exit.
public class UndoGameTest implements FabricClientGameTest {
	private static final String SHADOWS = "vanilla.entityShadows";
	private static final String FOG = "sodium.performance.use_fog_occlusion";

	private final Path configDir = FabricLoader.getInstance().getConfigDir();
	private final Path pendingFile = PendingActions.defaultPath(configDir);
	private final Path sodiumFile = configDir.resolve("sodium-options.json");
	private final Journal journal = new Journal(configDir, null, null, (message, error) -> {
		throw new AssertionError(message, error);
	});

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
		context.waitFor(mc -> RigTuneClient.controller().report() != null, 1200);
		RigTuneController controller = RigTuneClient.controller();
		context.runOnClient(mc -> controller.discardPending());
		String fog = sodiumFog();

		// Apply a vanilla and a Sodium change: one journal entry, the vanilla change applied, the Sodium one staged.
		boolean shadows = context.computeOnClient(mc -> mc.options.entityShadows().get());
		context.runOnClient(mc -> controller.apply(List.of(setting(SHADOWS, Boolean.toString(shadows), Boolean.toString(!shadows)),
				setting(FOG, fog, flip(fog)))));
		check(context.computeOnClient(mc -> mc.options.entityShadows().get()) == !shadows, "vanilla setting applied");
		JournalEntry applied = lastEntry(JournalEntry.APPLY);
		JournalChange shadowChange = change(applied, SHADOWS);
		JournalChange fogChange = change(applied, FOG);
		check(JournalChange.APPLIED.equals(shadowChange.status()) && Boolean.toString(shadows).equals(shadowChange.before()), "shadows journaled: " + shadowChange);
		check(JournalChange.STAGED.equals(fogChange.status()) && fog.equals(fogChange.before()), "fog journaled: " + fogChange);
		check(pendingOpIds().contains(fogChange.opId()), "journal op id is the one in pending.json: " + fogChange);

		// Undo last: the vanilla change is put back now, the staged Sodium change is dropped from pending.json.
		UndoPlan plan = openUndo(context, controller, false, "undo-last");
		check(applied.id().equals(plan.undoOf()), "undo last targets the apply: " + plan);
		check(hasItem(plan, UndoPlan.Action.REVERT, shadowChange.id()), "shadows will be reverted: " + plan);
		check(hasItem(plan, UndoPlan.Action.DISCARD_STAGED, fogChange.id()), "fog will be cancelled: " + plan);
		pressByKey(context, "rigtune.undo.confirm");
		context.waitTicks(3);
		context.takeScreenshot("undo-last-done");
		check(context.computeOnClient(mc -> mc.options.entityShadows().get()) == shadows, "vanilla setting reverted");
		check(!pendingOpIds().contains(fogChange.opId()), "sodium op removed from pending.json");
		check(JournalChange.REVERTED.equals(change(entry(applied.id()), SHADOWS).status()), "shadows REVERTED");
		check(JournalChange.DISCARDED.equals(change(entry(applied.id()), FOG).status()), "fog DISCARDED");
		JournalEntry undo = lastEntry(JournalEntry.UNDO);
		check(applied.id().equals(undo.undoOf()) && undo.changes().stream().anyMatch(c -> shadowChange.id().equals(c.reverts())), "undo journaled: " + undo);
		check(fog.equals(sodiumFog()), "sodium file untouched");

		// After a restart: the helper applies the Sodium change, then undoing it stages the old value.
		context.runOnClient(mc -> controller.apply(List.of(setting(FOG, fog, flip(fog)))));
		JournalChange staged = change(lastEntry(JournalEntry.APPLY), FOG);
		runHelper();
		check(flip(fog).equals(sodiumFog()), "helper patched sodium");
		check(JournalChange.APPLIED.equals(change(lastEntry(JournalEntry.APPLY), FOG).status()), "helper marked it APPLIED");
		plan = openUndo(context, controller, false, "undo-restart");
		check(plan.items().stream().anyMatch(i -> i.action() == UndoPlan.Action.REVERT && i.needsRestart() && i.changeIds().contains(staged.id())),
				"sodium reverted after a restart: " + plan);
		pressByKey(context, "rigtune.undo.confirm");
		context.waitTicks(3);
		check(stagedPatches().contains(Map.of("performance.use_fog_occlusion", fog)), "old sodium value staged: " + stagedPatches());
		runHelper();
		check(fog.equals(sodiumFog()), "helper put the old sodium value back");
		check(JournalChange.REVERTED.equals(change(entry(lastEntry(JournalEntry.APPLY).id()), FOG).status()), "sodium change REVERTED");

		// Undo everything, at the layouts the other screens are checked at.
		context.runOnClient(mc -> controller.apply(List.of(setting(SHADOWS, Boolean.toString(shadows), Boolean.toString(!shadows)))));
		plan = openUndo(context, controller, true, "undo-all-854x480");
		check(hasSkipWithReason(plan),"earlier changes the user reverted are listed as skipped: " + plan);
		screenshotAt(context, 1280, 720, 2, "undo-all-1280x720-scale2");
		screenshotAt(context, 1280, 720, 3, "undo-all-1280x720-scale3");
		screenshotAt(context, 854, 480, 2, "undo-all-854x480-scale2");
		pressByKey(context, "rigtune.undo.confirm");
		context.waitTicks(3);
		context.takeScreenshot("undo-all-done");
		check(context.computeOnClient(mc -> mc.options.entityShadows().get()) == shadows, "undo everything reverted the vanilla setting");
		screenshotAt(context, 854, 480, 0, "undo-all-restored");
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
	}

	private static boolean hasSkipWithReason(UndoPlan plan) {
		return plan.items().stream().anyMatch(i -> i.action() == UndoPlan.Action.SKIP && i.reason() != null && !i.reason().isBlank());
	}

	private UndoPlan openUndo(ClientGameTestContext context, RigTuneController controller, boolean all, String screenshot) {
		context.runOnClient(mc -> mc.gui.setScreen(new UndoScreen(new TitleScreen(), controller, all)));
		context.waitForScreen(UndoScreen.class);
		context.waitTicks(3);
		context.takeScreenshot(screenshot);
		UndoPlan plan = context.computeOnClient(mc -> ((UndoScreen) mc.gui.screen()).plan());
		check(plan != null, "undo plan available");
		return plan;
	}

	private static Recommendation setting(String key, String from, String to) {
		return new Recommendation("set:" + key, Category.SETTING, Impact.LOW, key, "undo game test", new Action.SetSetting(key, from, to), true);
	}

	private static String flip(String bool) {
		return Boolean.toString(!Boolean.parseBoolean(bool));
	}

	private String sodiumFog() {
		String key = FOG.substring("sodium.".length());
		try {
			String value = SettingsBridge.readSodium(sodiumFile).get(FOG);
			if (value == null) {
				SodiumConfigPatcher.patchFile(sodiumFile, Map.of(key, "true"));
				value = SettingsBridge.readSodium(sodiumFile).get(FOG);
			}
			return value;
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private List<JournalEntry> entries() {
		return journal.entries();
	}

	private JournalEntry entry(String id) {
		return entries().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow(() -> new AssertionError("no entry " + id));
	}

	private JournalEntry lastEntry(String kind) {
		List<JournalEntry> all = entries().stream().filter(e -> kind.equals(e.kind())).toList();
		check(!all.isEmpty(), "history.json has a " + kind + " entry: " + entries());
		return all.getLast();
	}

	private static JournalChange change(JournalEntry entry, String key) {
		return entry.changes().stream().filter(c -> key.equals(c.key())).findFirst()
				.orElseThrow(() -> new AssertionError("no change for " + key + " in " + entry));
	}

	private static boolean hasItem(UndoPlan plan, UndoPlan.Action action, String changeId) {
		return plan.items().stream().anyMatch(i -> i.action() == action && i.changeIds().contains(changeId));
	}

	private List<String> pendingOpIds() {
		List<String> ids = new ArrayList<>();
		if (Files.exists(pendingFile)) {
			try {
				PendingActions.load(pendingFile).ops().forEach(op -> ids.add(op.id()));
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		}
		return ids;
	}

	private List<Map<String, String>> stagedPatches() {
		List<Map<String, String>> out = new ArrayList<>();
		try {
			if (Files.exists(pendingFile)) {
				for (Op op : PendingActions.load(pendingFile).ops()) {
					if (op.patches() != null) {
						out.add(op.patches());
					}
				}
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		return out;
	}

	// What the post-exit helper does, in process: under the apply lock, run pending.json and update the journal.
	private void runHelper() {
		try (ApplyLock lock = ApplyLock.acquire(ApplyLock.defaultPath(configDir), Duration.ofSeconds(10))) {
			check(lock != null, "took the apply lock");
			new ApplyExecutor(2, 50).run(PendingActions.load(pendingFile), pendingFile);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void screenshotAt(ClientGameTestContext context, int width, int height, int guiScale, String name) {
		context.getInput().resizeWindow(width, height);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(guiScale);
			mc.resizeGui();
		});
		context.waitTicks(3);
		context.takeScreenshot(name);
	}

	private static void pressByKey(ClientGameTestContext context, String key) {
		context.runOnClient(mc -> {
			Button button = Screens.getWidgets(mc.gui.screen()).stream()
					.filter(w -> w instanceof Button && w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(key))
					.map(Button.class::cast)
					.findFirst()
					.orElseThrow(() -> new AssertionError("No button " + key));
			check(button.active, key + " is active");
			button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(0, 0)));
		});
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
	}
}
