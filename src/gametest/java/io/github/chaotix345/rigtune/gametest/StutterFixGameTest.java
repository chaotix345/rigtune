package io.github.chaotix345.rigtune.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.stutter.StutterHooks;
import io.github.chaotix345.rigtune.client.stutter.StutterMonitor;
import io.github.chaotix345.rigtune.client.ui.PreviewScreen;
import io.github.chaotix345.rigtune.client.ui.StutterScreen;
import io.github.chaotix345.rigtune.client.ui.UndoScreen;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.SodiumConfigPatcher;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import io.github.chaotix345.rigtune.core.stutter.Attributor;
import io.github.chaotix345.rigtune.core.stutter.FixComparison;
import io.github.chaotix345.rigtune.core.stutter.FixOffer;
import io.github.chaotix345.rigtune.core.stutter.FixStore;
import io.github.chaotix345.rigtune.core.stutter.FixTracker;
import io.github.chaotix345.rigtune.core.stutter.SpikeDetector;
import io.github.chaotix345.rigtune.core.stutter.StutterAnalyzer;
import io.github.chaotix345.rigtune.core.stutter.StutterFacts;
import io.github.chaotix345.rigtune.core.stutter.StutterReport;
import io.github.chaotix345.rigtune.core.stutter.StutterStore;
import io.github.chaotix345.rigtune.core.stutter.StutterView;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;

// docs/v0.5/SPEC.md 5 (C20; AC5.5-AC5.7, AC5.10-AC5.12): the Stutter Doctor's one-click fixes on the real StutterScreen,
// PreviewScreen, Apply, journal, Undo and Discard, with RigTune's network off and the bundled rules (X1). CI's software
// rendering can't be relied on for chunk-dominated stutter, so the analyses are stood in through StutterHooks.injectAnalysis
// (each built from the capture's own start); the advice, the offers, Apply, the session restart and the tracking are real.
public class StutterFixGameTest implements FabricClientGameTest {
	private static final String RD = "vanilla.renderDistance";
	private static final String DEFER = "sodium.performance.chunk_build_defer_mode";
	private static final String CHUNKS = "stutter-chunk-loading";
	private static final String SODIUM = "stutter-sodium-defer";
	private static final long MS = 1_000_000L;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		V05TestContext v05 = V05TestContext.of(context);
		context.waitForScreen(TitleScreen.class);
		RealController controller = v05.realController();
		Path configDir = v05.configDir();
		context.waitFor(mc -> controller.report() != null, 1200);
		boolean networkBefore = GameTestNet.set(context, controller, false);
		int renderDistance = context.computeOnClient(mc -> mc.options.renderDistance().get());
		int simulationDistance = context.computeOnClient(mc -> mc.options.simulationDistance().get());
		Path sodiumFile = configDir.resolve("sodium-options.json");
		String defer = SettingsBridge.readSodium(sodiumFile).get(DEFER);
		// What the test leaves behind goes (review L11): its tracked fixes and its sessions' fake summaries.
		List<String> fixesBefore = FixStore.shared(configDir).records().stream().map(FixTracker.Record::entryId).toList();
		byte[] fixesFile = read(FixStore.file(configDir));
		List<StutterReport> sessionsBefore = new StutterStore(configDir).sessions();
		try {
			check(controller.rules() != null && controller.rules().stutterFixes != null && controller.rules().stutterFixes.size() >= 2,
					"the bundled rules carry the stutterFixes seeds (revision " + (controller.rules() == null ? "?" : controller.rules().revision) + ")");
			v05.resize(854, 480, 2);
			context.runOnClient(mc -> {
				mc.options.renderDistance().set(12);
				controller.setStutterMonitor(true);
			});
			try (TestSingleplayerContext ignored = GameTestWorlds.create(context)) {
				context.waitFor(mc -> StutterMonitor.session() != null, 400);
				renderDistanceFix(v05, controller, configDir);
				sodiumFix(context, controller, configDir, sodiumFile);
				negatives(context, controller);
				context.runOnClient(mc -> mc.gui.setScreen(null));
			}
			context.waitForScreen(TitleScreen.class);
		} finally {
			StutterHooks.injectAnalysis(null);
			context.runOnClient(mc -> {
				controller.setStutterMonitor(false);
				mc.options.renderDistance().set(renderDistance);
				mc.options.simulationDistance().set(simulationDistance);
			});
			if (defer != null) {
				patchSodium(sodiumFile, defer);
			}
			cleanUp(context, controller, configDir, fixesBefore, fixesFile, sessionsBefore);
			GameTestNet.set(context, controller, networkBefore);
			v05.resize(854, 480, 0);
		}
		RigTune.LOGGER.info("StutterFixGameTest: passed");
	}

	// AC5.5-AC5.7, AC5.10, AC5.11: render distance -2, now.
	private static void renderDistanceFix(V05TestContext v05, RealController controller, Path configDir) {
		ClientGameTestContext context = v05.context();
		StutterHooks.injectAnalysis(session(20, 400, Map.of("chunkLoad", 60.0, "unknown", 40.0), Map.of("chunkLoad", 10), StutterReport.MONITOR));
		freshSession(context, controller);
		openStutter(context, controller);
		FixOffer.Offer offer = (FixOffer.Offer) waitForFix(context, CHUNKS, FixOffer.Offer.class);
		check(offer.key().equals(RD) && offer.from().equals("12") && offer.to().equals("10") && offer.now(), "the RD offer 12 -> 10, now: " + offer);
		check(shown(context).contains("Try it in one click: Render Distance: 12 → 10"), "the offer row: " + shown(context));
		// A session is one setup (review-11 STUTTER-3): after a resize the offer needs a session that started at that size.
		for (int[] size : V05TestContext.SIZES) {
			v05.resize(size[0], size[1], size[2]);
			freshSession(context, controller);
			openStutter(context, controller);
			waitForFix(context, CHUNKS, FixOffer.Offer.class);
			checkLayout(context, "offer " + size[0] + "x" + size[1] + "@" + size[2]);
			context.takeScreenshot("stutterfix-offer-" + size[0] + "x" + size[1] + "-scale" + size[2]);
		}
		// X12: at 1280x720@3 the list scrolls to its last row.
		v05.resize(1280, 720, 3);
		freshSession(context, controller);
		openStutter(context, controller);
		waitForFix(context, CHUNKS, FixOffer.Offer.class);
		context.runOnClient(mc -> ((StutterScreen) mc.gui.screen()).list().setScrollAmount(Double.MAX_VALUE));
		context.waitTicks(2);
		context.takeScreenshot("stutterfix-offer-1280x720-scale3-bottom");
		v05.resize(854, 480, 2);
		// The before side's conditions are the analysis' own: a fresh session at the size the after sessions play at.
		freshSession(context, controller);
		openStutter(context, controller);
		waitForFix(context, CHUNKS, FixOffer.Offer.class);

		// Try this fix… (review-12 R12STUTTER-6): nothing changes; one session as it is is measured first, from a restart of
		// the running session. The advice row has no "another fix" line (the block explains). review-13: the triggering
		// session's analyses keep their 20 hitches, every later session gets 16, so the ready record shows its before side is
		// the baseline session, never the trigger (whose end is saved after the restart).
		int entries = context.computeOnClient(mc -> ClientJournal.get().entries().size());
		StutterMonitor.Capture trigger = StutterMonitor.session();
		chooseFix(context, trigger, session(20, 400, Map.of("chunkLoad", 60.0, "unknown", 40.0), Map.of("chunkLoad", 10), StutterReport.MONITOR),
				session(16, 400, Map.of("chunkLoad", 60.0, "unknown", 40.0), Map.of("chunkLoad", 10), StutterReport.MONITOR));
		check(status(context).startsWith("Measuring your play as it is."), "the status: " + status(context));
		FixTracker.Record chosen = waitForRecord(context, configDir, r -> r.state().beforeApply(), "a chosen fix");
		check(context.computeOnClient(mc -> mc.options.renderDistance().get()) == 12, "Try this fix… changed nothing");
		check(context.computeOnClient(mc -> ClientJournal.get().entries().size()) == entries, "Try this fix… wrote no History entry");
		StutterMonitor.Capture baseline = StutterMonitor.session();
		check(baseline != null && baseline != trigger && baseline.startedAt().isAfter(chosen.appliedAt()), "the baseline session started after the trigger");
		if (chosen.state() == FixTracker.State.BASELINE) {
			context.waitFor(mc -> mc.gui.screen() instanceof StutterScreen s && s.shownView().tracked() != null
					&& s.shownView().tracked().state().beforeApply(), 200);
			check(shown(context).contains("Nothing has changed yet") || shown(context).contains("Your play as it is"), "the block: " + shown(context));
			check(!shown(context).contains("Another fix is still being measured"), "no busy line under the advice: " + shown(context));
			context.takeScreenshot("stutterfix-baseline-854x480-scale2");
		}
		// The baseline session ends (16 hitches in 6:40): ready, measured from it.
		endSession(context, controller);
		FixTracker.Record ready = waitForRecord(context, configDir, r -> r.state() == FixTracker.State.READY, "a ready record");
		check(ready.entryId().equals(chosen.entryId()) && ready.before().hitches() == 16, "the baseline session measured, not the trigger: " + ready);
		freshSession(context, controller);
		openStutter(context, controller);
		context.waitFor(mc -> mc.gui.screen() instanceof StutterScreen s && s.shownView().tracked() != null
				&& s.shownView().tracked().state() == FixTracker.State.READY, 200);
		check(shown(context).contains("Your play as it is: 2.4 hitches a minute over 6:40."), "the ready block: " + shown(context));
		context.takeScreenshot("stutterfix-ready-854x480-scale2");

		// Apply the fix…: its preview, then Cancel: nothing written, still ready.
		pressFix(context, "rigtune.stutter.fix.apply");
		ApplyPreview preview = waitForPreview(context);
		check(preview.now().size() == 1 && preview.atRestart().isEmpty(), "one change, now: " + preview);
		context.takeScreenshot("stutterfix-preview-854x480-scale2");
		context.runOnClient(mc -> press(((PreviewScreen) mc.gui.screen()).cancelButton()));
		context.waitForScreen(StutterScreen.class);
		check(context.computeOnClient(mc -> mc.options.renderDistance().get()) == 12, "Cancel changed nothing");
		check(context.computeOnClient(mc -> ClientJournal.get().entries().size()) == entries, "Cancel wrote no History entry");
		check(latest(configDir).state() == FixTracker.State.READY, "Cancel left the fix ready");

		// Apply: one entry (the chosen fix's id), RD 10, one measuring record with the baseline as its before side; the
		// session restarts after appliedAt, at the new value (M4).
		StutterMonitor.Capture before = StutterMonitor.session();
		pressFix(context, "rigtune.stutter.fix.apply");
		waitForPreview(context);
		Object reportBefore = context.computeOnClient(mc -> {
			Object report = controller.report();
			press(((PreviewScreen) mc.gui.screen()).applyButton());
			return report;
		});
		context.waitForScreen(StutterScreen.class);
		check(context.computeOnClient(mc -> mc.options.renderDistance().get()) == 10, "Apply set render distance 10");
		check(status(context).startsWith("Fix applied."), "the status: " + status(context));
		JournalEntry entry = context.computeOnClient(mc -> ClientJournal.get().entries().getLast());
		check(entry.changes().size() == 1 && RD.equals(entry.changes().getFirst().key()) && JournalChange.APPLIED.equals(entry.changes().getFirst().status()),
				"one apply entry, the change applied: " + entry);
		FixTracker.Record record = waitForRecord(context, configDir, r -> r.state() == FixTracker.State.MEASURING, "a measuring record");
		check(record.entryId().equals(entry.id()) && record.entryId().equals(chosen.entryId()) && record.before().equals(ready.before())
				&& record.to().equals("10"), "the record: " + record);
		StutterMonitor.Capture after = StutterMonitor.session();
		check(after != null && after != before && !after.startedAt().isBefore(record.appliedAt()),
				"the session restarted after appliedAt: " + (after == null ? null : after.startedAt()) + " vs " + record.appliedAt());
		holdOnTheMainList(context, controller, reportBefore);

		// The first session after: a setting changed while it ran, so it doesn't count.
		StutterHooks.injectAnalysis(session(4, 400, Map.of("chunkLoad", 20.0, "unknown", 80.0), Map.of(), StutterReport.MONITOR));
		int simulation = context.computeOnClient(mc -> mc.options.simulationDistance().get());
		context.runOnClient(mc -> mc.options.simulationDistance().set(simulation == 8 ? 10 : 8));
		endSession(context, controller);
		record = waitForRecord(context, configDir, r -> r.skipped() == 1, "a skipped session");
		check(record.lastSkip() != null && "setting".equals(record.lastSkip().reason()) && record.state() == FixTracker.State.MEASURING,
				"it didn't count because a setting changed: " + record);
		// The next one counts: 400 s reach the target, and the comparison runs.
		context.runOnClient(mc -> mc.options.simulationDistance().set(simulation));
		freshSession(context, controller);
		endSession(context, controller);
		record = waitForRecord(context, configDir, r -> r.state() == FixTracker.State.COMPARED, "the comparison");
		check(record.verdict() != null && record.verdict().kind() == FixComparison.Kind.LESS, "less stutter: " + record.verdict());
		freshSession(context, controller);
		openStutter(context, controller);
		context.waitFor(mc -> mc.gui.screen() instanceof StutterScreen s && s.shownView().tracked() != null
				&& s.shownView().tracked().state() == FixTracker.State.COMPARED, 400);
		String text = shown(context);
		check(text.contains("Less stutter after the change: 0.6 hitches a minute (was 2.4)"), "the verdict with both rates: " + text);
		check(text.contains("Undo this change…") && text.contains("Dismiss"), "the block's buttons: " + text);
		for (int[] size : V05TestContext.SIZES) {
			v05.resize(size[0], size[1], size[2]);
			checkLayout(context, "compared " + size[0] + "x" + size[1] + "@" + size[2]);
			context.takeScreenshot("stutterfix-compared-" + size[0] + "x" + size[1] + "-scale" + size[2]);
		}
		v05.resize(854, 480, 2);

		// Undo this change…: the fix is reverted and the record reads undone.
		pressFix(context, "rigtune.stutter.fix.undo");
		context.waitForScreen(UndoScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof UndoScreen u && u.plan() != null, 200);
		UndoPlan plan = context.computeOnClient(mc -> ((UndoScreen) mc.gui.screen()).plan());
		check(plan.problem() == null, "the undo plan: " + plan);
		context.runOnClient(mc -> controller.undo(plan));
		check(context.computeOnClient(mc -> mc.options.renderDistance().get()) == 12, "Undo put render distance back to 12");
		openStutter(context, controller);
		context.waitFor(mc -> mc.gui.screen() instanceof StutterScreen s && s.shownView().tracked() != null
				&& s.shownView().tracked().state() == FixTracker.State.UNDONE, 600);
		check(shown(context).contains("You undid this change."), "the block says undone: " + shown(context));
		context.takeScreenshot("stutterfix-undone-854x480-scale2");
	}

	// AC5.5/AC5.6's staged path: Sodium's Chunk Updates -> Deferred, staged; Discard pending -> not applied.
	private static void sodiumFix(ClientGameTestContext context, RealController controller, Path configDir, Path sodiumFile) {
		patchSodium(sodiumFile, "ZERO_FRAMES");
		StutterHooks.injectAnalysis(session(20, 400, Map.of("chunkBuild", 60.0, "unknown", 40.0), Map.of("chunkBuild", 10), StutterReport.MONITOR));
		freshSession(context, controller);
		openStutter(context, controller);
		FixOffer.Offer offer = (FixOffer.Offer) waitForFix(context, SODIUM, FixOffer.Offer.class);
		check(offer.key().equals(DEFER) && "ALWAYS".equals(offer.to()) && !offer.now(), "the Sodium offer, at the next restart: " + offer);
		chooseFix(context, StutterMonitor.session(), session(20, 400, Map.of("chunkBuild", 60.0, "unknown", 40.0), Map.of("chunkBuild", 10),
				StutterReport.MONITOR), session(16, 400, Map.of("chunkBuild", 60.0, "unknown", 40.0), Map.of("chunkBuild", 10), StutterReport.MONITOR));
		waitForRecord(context, configDir, r -> r.state().beforeApply() && r.key().equals(DEFER), "a chosen fix");
		endSession(context, controller);
		waitForRecord(context, configDir, r -> r.state() == FixTracker.State.READY && r.key().equals(DEFER) && r.before().hitches() == 16,
				"a ready record measured from the baseline session");
		freshSession(context, controller);
		openStutter(context, controller);
		context.waitFor(mc -> mc.gui.screen() instanceof StutterScreen s && s.shownView().tracked() != null
				&& s.shownView().tracked().state() == FixTracker.State.READY, 200);
		pressFix(context, "rigtune.stutter.fix.apply");
		ApplyPreview preview = waitForPreview(context);
		check(preview.atRestart().size() == 1 && preview.now().isEmpty(), "one change, at the next restart: " + preview);
		context.runOnClient(mc -> press(((PreviewScreen) mc.gui.screen()).applyButton()));
		context.waitForScreen(StutterScreen.class);
		JournalEntry entry = context.computeOnClient(mc -> ClientJournal.get().entries().getLast());
		check(entry.changes().size() == 1 && JournalChange.STAGED.equals(entry.changes().getFirst().status()), "one staged change: " + entry);
		List<PendingActions.Op> ops = pendingOps(configDir);
		check(ops.size() == 1 && ops.getFirst().type() == PendingActions.Type.PATCH_JSON && ops.getFirst().patches().containsValue("ALWAYS"),
				"one PATCH_JSON op: " + ops);
		waitForRecord(context, configDir, r -> r.state() == FixTracker.State.STAGED && r.key().equals(DEFER), "a staged record");
		context.waitFor(mc -> mc.gui.screen() instanceof StutterScreen s && s.shownView().tracked() != null
				&& s.shownView().tracked().state() == FixTracker.State.STAGED, 200);
		check(shown(context).contains("Waiting for a restart"), "the block says staged: " + shown(context));
		context.takeScreenshot("stutterfix-staged-854x480-scale2");
		context.runOnClient(mc -> controller.discardPending());
		openStutter(context, controller);
		context.waitFor(mc -> mc.gui.screen() instanceof StutterScreen s && s.shownView().tracked() != null
				&& s.shownView().tracked().state() == FixTracker.State.NOT_APPLIED, 600);
		check(shown(context).contains("The change wasn't applied"), "the block says not applied: " + shown(context));
	}

	// AC5.3's player-visible blocks on the real screen: each one line, no Try button.
	private static void negatives(ClientGameTestContext context, RealController controller) {
		notYet(context, controller, session(20, 400, Map.of("chunkLoad", 60.0, "unknown", 40.0), Map.of("chunkLoad", 10), StutterReport.BENCHMARK),
				"One-click fixes are offered for your own play sessions, not for benchmark runs.");
		notYet(context, controller, session(7, 400, Map.of("chunkLoad", 60.0, "unknown", 40.0), Map.of("chunkLoad", 7), StutterReport.MONITOR),
				"(this one: 6:40, 7)");
		notYet(context, controller, session(20, 299, Map.of("chunkLoad", 60.0, "unknown", 40.0), Map.of("chunkLoad", 10), StutterReport.MONITOR),
				"(this one: 4:59, 20)");
		notYet(context, controller, session(20, 400, Map.of("chunkLoad", 60.0, "unknown", 40.0), Map.of("chunkLoad", 4), StutterReport.MONITOR),
				"The measurements don't point at this clearly enough for a one-click fix");
		context.takeScreenshot("stutterfix-not-yet-854x480-scale2");
	}

	private static void notYet(ClientGameTestContext context, RealController controller, BiFunction<StutterAnalyzer.Result, Long, StutterAnalyzer.Result> probe,
			String line) {
		StutterHooks.injectAnalysis(probe);
		freshSession(context, controller);
		openStutter(context, controller);
		waitForFix(context, CHUNKS, FixOffer.NotYet.class);
		check(shown(context).contains(line), "the one line \"" + line + "\": " + shown(context));
		check(context.computeOnClient(mc -> ((StutterScreen) mc.gui.screen()).fixButtons().stream()
				.noneMatch(b -> b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("rigtune.stutter.fix.try"))), "no Try button");
	}

	// AC5.10 on the real main list: a render-distance recommendation that would move the fixed key back up is unticked with
	// the hold's reason (only when this machine's tier proposes one).
	private static void holdOnTheMainList(ClientGameTestContext context, RealController controller, Object reportBefore) {
		// The apply rebuilds the report (RealController.apply); its post-step reads the fix.
		context.waitFor(mc -> controller.report() != reportBefore, 400);
		List<Recommendation> recs = context.computeOnClient(mc -> controller.report().recommendations());
		int held = 0;
		for (Recommendation r : recs) {
			if (r.action() instanceof Action.SetSetting set && RD.equals(set.key()) && set.newValue().matches("\\d+") && Integer.parseInt(set.newValue()) > 10) {
				check(!r.selectedByDefault() && r.reasonText().english().contains("with the Stutter Doctor's fix"), "held: " + r);
				held++;
			}
		}
		RigTune.LOGGER.info("StutterFixGameTest: {} main-list render-distance recommendation(s) held by the fix", held);
	}

	// ---- helpers

	// A session's analysis as the probe returns it: `hitches` spikes of 80 ms spread over `gameplay` seconds from the
	// capture's start, the shares and dominated-spike counts given, phase timing on.
	private static BiFunction<StutterAnalyzer.Result, Long, StutterAnalyzer.Result> session(int hitches, double gameplay, Map<String, Double> claimed,
			Map<String, Integer> dominated, String source) {
		return (real, start) -> {
			List<Attributor.Attribution> attributions = new ArrayList<>();
			for (int i = 0; i < hitches; i++) {
				long end = start + (long) ((i + 0.5) * gameplay / hitches * 1e9);
				SpikeDetector.Spike spike = new SpikeDetector.Spike(end, 80 * MS, 16 * MS);
				attributions.add(new Attributor.Attribution(spike, Map.of(), List.of(), Set.of(), spike.lost()));
			}
			Map<String, Double> causes = new LinkedHashMap<>();
			claimed.forEach((cause, percent) -> causes.put(cause, percent / 100));
			StutterReport report = new StutterReport(Instant.now().toString(), source, "26.2", "G1", 4096, gameplay + 20, gameplay, 50_000, 120, 60, null, null,
					new StutterReport.Spikes(hitches, 0, 0, 0), hitches * 64.0, causes, Map.of(), List.of(), null, List.of(), true, true, hitches);
			StutterFacts facts = new StutterFacts(claimed, Map.of(), 0, 0, 0, null, null, null, hitches / (gameplay / 60), "g1", true, Set.of(Attributor.RENDER),
					dominated);
			return new StutterAnalyzer.Result(report, facts, attributions, null);
		};
	}

	// Ends the running session and starts a new one (its analysis is the probe's).
	private static void freshSession(ClientGameTestContext context, RealController controller) {
		endSession(context, controller);
		context.runOnClient(mc -> controller.setStutterMonitor(true));
		context.waitFor(mc -> StutterMonitor.session() != null, 200);
	}

	private static void endSession(ClientGameTestContext context, RealController controller) {
		context.runOnClient(mc -> {
			mc.gui.setScreen(null);
			controller.setStutterMonitor(false);
		});
		context.waitFor(mc -> StutterMonitor.session() == null, 200);
	}

	private static void openStutter(ClientGameTestContext context, RealController controller) {
		context.runOnClient(mc -> mc.gui.setScreen(new StutterScreen(null, controller)));
		context.waitForScreen(StutterScreen.class);
		context.getInput().setCursorPos(1, 1);
	}

	private static FixOffer waitForFix(ClientGameTestContext context, String adviceId, Class<? extends FixOffer> kind) {
		context.waitFor(mc -> mc.gui.screen() instanceof StutterScreen s && kind.isInstance(s.shownView().fixes().get(adviceId)), 600);
		context.waitTicks(3);
		StutterView view = context.computeOnClient(mc -> ((StutterScreen) mc.gui.screen()).shownView());
		return view.fixes().get(adviceId);
	}

	private static ApplyPreview waitForPreview(ClientGameTestContext context) {
		context.waitForScreen(PreviewScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof PreviewScreen p && p.preview() != null && !p.loading(), 400);
		context.waitTicks(2);
		return context.computeOnClient(mc -> ((PreviewScreen) mc.gui.screen()).preview());
	}

	private static FixTracker.Record waitForRecord(ClientGameTestContext context, Path configDir, Predicate<FixTracker.Record> test, String what) {
		context.waitFor(mc -> {
			FixTracker.Record r = latest(configDir);
			return r != null && test.test(r);
		}, 600);
		FixTracker.Record r = latest(configDir);
		check(r != null && test.test(r), what + ": " + r);
		return r;
	}

	private static FixTracker.@Nullable Record latest(Path configDir) {
		List<FixTracker.Record> records = FixStore.shared(configDir).records();
		return records.isEmpty() ? null : records.getLast();
	}

	private static String shown(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			StringBuilder out = new StringBuilder();
			if (mc.gui.screen() instanceof StutterScreen s) {
				for (Component c : s.shownText()) {
					out.append(c.getString()).append('\n');
				}
			}
			return out.toString();
		});
	}

	// Try this fix… on the offer, once the clock is in a later second than the triggering session's start (only a session
	// that started after it can be the baseline). The triggering session's analyses come from `trigger`, every other
	// session's from `baseline`.
	private static void chooseFix(ClientGameTestContext context, StutterMonitor.@Nullable Capture session,
			BiFunction<StutterAnalyzer.Result, Long, StutterAnalyzer.Result> trigger, BiFunction<StutterAnalyzer.Result, Long, StutterAnalyzer.Result> baseline) {
		check(session != null, "a running session to choose the fix in");
		long triggerStart = session.startNanos();
		StutterHooks.injectAnalysis((real, start) -> (start == triggerStart ? trigger : baseline).apply(real, start));
		context.waitFor(mc -> Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).isAfter(session.startedAt()), 100);
		pressFix(context, "rigtune.stutter.fix.try");
	}

	private static String status(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			Component s = mc.gui.screen() instanceof StutterScreen screen ? screen.status() : null;
			return s == null ? "" : s.getString();
		});
	}

	private static void pressFix(ClientGameTestContext context, String key) {
		context.runOnClient(mc -> {
			Button button = null;
			for (Button b : ((StutterScreen) mc.gui.screen()).fixButtons()) {
				if (b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(key)) {
					button = b;
				}
			}
			check(button != null, "a '" + key + "' button");
			press(button);
		});
	}

	private static void press(@Nullable Button button) {
		check(button != null && button.active, "an active button");
		button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
	}

	// The footer's 5 buttons inside the screen and not overlapping, the list above them, every fix button's label fitting
	// it and the button fitting the row.
	private static void checkLayout(ClientGameTestContext context, String name) {
		context.waitTicks(2);
		context.runOnClient(mc -> {
			Screen screen = mc.gui.screen();
			StutterScreen stutter = (StutterScreen) screen;
			List<AbstractWidget> footer = new ArrayList<>();
			for (var child : screen.children()) {
				if (child instanceof Button b && b.visible) {
					footer.add(b);
				}
			}
			check(footer.size() == 5, name + ": the 5 footer buttons");
			for (AbstractWidget w : footer) {
				check(w.getX() >= 0 && w.getY() >= 0 && w.getX() + w.getWidth() <= screen.width && w.getY() + w.getHeight() <= screen.height,
						name + ": " + w.getMessage().getString() + " inside the screen");
				for (AbstractWidget o : footer) {
					check(o == w || w.getX() >= o.getX() + o.getWidth() || o.getX() >= w.getX() + w.getWidth() || w.getY() >= o.getY() + o.getHeight()
							|| o.getY() >= w.getY() + w.getHeight(), name + ": " + w.getMessage().getString() + " overlaps " + o.getMessage().getString());
				}
			}
			int top = footer.stream().mapToInt(AbstractWidget::getY).min().orElse(screen.height);
			check(stutter.list().getY() + stutter.list().getHeight() <= top, name + ": the list ends above the buttons");
			check(stutter.list().getRowRight() <= screen.width, name + ": the rows end inside the screen");
			check(stutter.list().barsFit(), name + ": every bar's label and value fit their columns");
			for (Button b : stutter.fixButtons()) {
				check(mc.font.width(b.getMessage()) <= b.getWidth() - 4, name + ": " + b.getMessage().getString() + " fits its button");
				check(b.getWidth() <= stutter.list().getRowWidth(), name + ": " + b.getMessage().getString() + " fits the row");
			}
		});
	}

	// The test's fixes are dismissed (so the service's cache shows none), then stutter-fixes.json is put back as it was; its
	// sessions' fake summaries leave stutter.json (Clear, then the sessions from before the test are written back).
	private static void cleanUp(ClientGameTestContext context, RealController controller, Path configDir, List<String> fixesBefore,
			byte @Nullable [] fixesFile, List<StutterReport> sessionsBefore) {
		List<String> made = FixStore.shared(configDir).records().stream().map(FixTracker.Record::entryId).filter(id -> !fixesBefore.contains(id)).toList();
		context.runOnClient(mc -> made.forEach(controller::dismissStutterFix));
		context.waitFor(mc -> FixStore.shared(configDir).records().stream().filter(r -> made.contains(r.entryId())).allMatch(FixTracker.Record::dismissed), 200);
		context.runOnClient(mc -> controller.clearStutter());
		context.waitFor(mc -> new StutterStore(configDir).sessions().isEmpty(), 200);
		try {
			if (fixesFile == null) {
				Files.deleteIfExists(FixStore.file(configDir));
			} else {
				Files.write(FixStore.file(configDir), fixesFile);
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		StutterStore store = new StutterStore(configDir);
		sessionsBefore.forEach(store::add);
		check(new StutterStore(configDir).sessions().size() == sessionsBefore.size(), "stutter.json holds the sessions from before the test again");
	}

	private static byte @Nullable [] read(Path file) {
		try {
			return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void patchSodium(Path file, String value) {
		try {
			SodiumConfigPatcher.patchFile(file, Map.of(DEFER.substring("sodium.".length()), value));
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static List<PendingActions.Op> pendingOps(Path configDir) {
		Path file = PendingActions.defaultPath(configDir);
		try {
			return Files.isRegularFile(file) ? PendingActions.load(file).ops() : List.of();
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("StutterFixGameTest: " + what);
		}
	}
}
