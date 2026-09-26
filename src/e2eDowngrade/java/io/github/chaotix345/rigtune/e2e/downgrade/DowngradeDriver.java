package io.github.chaotix345.rigtune.e2e.downgrade;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.ui.HistoryScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.UndoScreen;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Drives the downgrade end-to-end run (docs/v0.4/SPEC.md AC3.2; tools/e2e/README.md "v0.4 runs"). Compiled against the
 * RELEASED 0.3.0 jar (-Pe2e.oldJar), like the self-update driver, so it uses only API 0.3.0 has; it runs on 0.3.0 and
 * then on 0.4. Inert unless -Drigtune.e2e.phase is:
 * <ul>
 * <li>{@code downgrade-old} (0.3.0 on files 0.4 wrote): records the History screen's model and screenshots it; Undo
 * last (the plan, the undo screen, its Undo button; waits for the undo entry); then Apply of "disable
 * -Drigtune.e2e.disable" (waits until it is staged); quits, so 0.3.0's helper applies it.</li>
 * <li>{@code downgrade-new} (0.4 again): records the loaded version and the History model, screenshots History; quits.</li>
 * </ul>
 * Results go to -Drigtune.e2e.out as driver-&lt;phase&gt;.json; screenshots to the instance's screenshots folder.
 */
public final class DowngradeDriver implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("RigTune E2E downgrade");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
	private static final int SECOND = 20;
	private static final int READY_TIMEOUT = 180 * SECOND;
	private static final int STAGE_TIMEOUT = 120 * SECOND;
	private static final int WATCHDOG = 360 * SECOND;

	private enum Step {
		WAIT_TITLE, WAIT_READY, HISTORY, UNDO, WAIT_UNDONE, APPLY, WAIT_STAGED, SHOT, QUIT, DONE
	}

	private final String phase = System.getProperty("rigtune.e2e.phase");
	private final String disable = System.getProperty("rigtune.e2e.disable");
	private final Map<String, Object> result = new LinkedHashMap<>();
	private final List<String> events = new ArrayList<>();
	private Path out;
	private Step step = Step.WAIT_TITLE;
	private int ticks;
	private int stepTicks;
	private int undosBefore;
	private String disableFile;

	@Override
	public void onInitializeClient() {
		if (!"downgrade-old".equals(phase) && !"downgrade-new".equals(phase)) {
			return;
		}
		out = Path.of(System.getProperty("rigtune.e2e.out", "e2e-out")).toAbsolutePath();
		result.put("phase", phase);
		result.put("ok", false);
		result.put("error", null);
		result.put("events", events);
		event("downgrade driver loaded, phase " + phase + ", output " + out);
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void tick(Minecraft minecraft) {
		if (step == Step.DONE) {
			return;
		}
		ticks++;
		stepTicks++;
		try {
			if (ticks > WATCHDOG) {
				fail(minecraft, "watchdog: still in step " + step + " after " + WATCHDOG / SECOND + " s");
				return;
			}
			RigTuneController controller = RigTuneClient.controller();
			switch (step) {
				case WAIT_TITLE -> {
					if (minecraft.gui.screen() instanceof TitleScreen && minecraft.gui.overlay() == null) {
						ModContainer rigtune = FabricLoader.getInstance().getModContainer("rigtune").orElseThrow();
						result.put("rigtuneVersion", rigtune.getMetadata().getVersion().getFriendlyString());
						result.put("rigtuneOrigin", rigtune.getOrigin().getPaths().stream().map(Path::toString).toList());
						event("title screen");
						next(Step.WAIT_READY);
					}
				}
				case WAIT_READY -> {
					if (stepTicks % SECOND == 0 && controller.report() != null) {
						event("report ready (online=" + controller.report().online() + ")");
						next(Step.HISTORY);
					} else if (stepTicks > READY_TIMEOUT) {
						fail(minecraft, "no report within " + READY_TIMEOUT / SECOND + " s");
					}
				}
				// History lists every entry (the model its screen shows), with a screenshot.
				case HISTORY -> {
					if (stepTicks == 1) {
						recordHistory(controller, "history");
						minecraft.gui.setScreen(new HistoryScreen(minecraft.gui.screen(), controller));
					} else if (stepTicks == 2 * SECOND) {
						screenshot(minecraft, "e2e-" + phase + "-1-history.png");
					} else if (stepTicks == 3 * SECOND) {
						minecraft.gui.setScreen(null);
						next(phase.equals("downgrade-old") ? Step.UNDO : Step.QUIT);
					}
				}
				case UNDO -> {
					if (stepTicks == 1) {
						undosBefore = undoCount();
						if (undosBefore < 0) {
							fail(minecraft, "couldn't read history.json before the undo");
							return;
						}
						UndoPlan plan = controller.undoPlan(false);
						Map<String, Object> summary = new LinkedHashMap<>();
						summary.put("undoOf", plan == null ? null : plan.undoOf());
						summary.put("problem", plan == null ? "null plan" : plan.problem());
						List<Map<String, Object>> items = new ArrayList<>();
						for (UndoPlan.Item item : plan == null ? List.<UndoPlan.Item>of() : plan.items()) {
							Map<String, Object> i = new LinkedHashMap<>();
							i.put("action", item.action().name());
							i.put("description", item.description());
							i.put("reason", item.reason());
							i.put("needsRestart", item.needsRestart());
							i.put("changeIds", item.changeIds());
							items.add(i);
						}
						summary.put("items", items);
						result.put("undoPlan", summary);
						event("Undo last plan: " + summary);
						if (plan == null || plan.problem() != null || plan.isEmpty()) {
							fail(minecraft, "no Undo last plan: " + (plan == null ? "null" : plan.problem()));
							return;
						}
						minecraft.gui.setScreen(new UndoScreen(minecraft.gui.screen(), controller, false));
					} else if (stepTicks == 2 * SECOND) {
						screenshot(minecraft, "e2e-downgrade-old-2-undo.png");
					} else if (stepTicks == 3 * SECOND && pressConfirm(minecraft)) {
						next(Step.WAIT_UNDONE);
					}
				}
				case WAIT_UNDONE -> {
					if (stepTicks % 10 != 0) {
						return;
					}
					int count = undoCount();
					if (count >= 0 && count > undosBefore) {
						Component status = controller.status();
						result.put("undoStatus", status == null ? null : status.getString());
						event("undo recorded");
						minecraft.gui.setScreen(null);
						next(Step.APPLY);
					} else if (stepTicks > STAGE_TIMEOUT) {
						fail(minecraft, "the undo wasn't recorded in history.json within " + STAGE_TIMEOUT / SECOND + " s");
					}
				}
				// 0.3.0's own Apply: disable a test mod, as the RigTune screen's Apply does with that row ticked.
				case APPLY -> {
					if (stepTicks == SECOND) {
						Path jar = modJar(disable);
						if (jar == null) {
							fail(minecraft, "mod " + disable + " isn't loaded from a jar in mods/");
							return;
						}
						disableFile = jar.getFileName().toString();
						Component message = controller.apply(List.of(new Recommendation("disable:" + disable, Category.REMOVE_MOD, Impact.LOW,
								"Disable " + disable, "E2E test mod", new Action.DisableMod(disable, jar), true)));
						result.put("applyMessage", message.getString());
						event("apply disable " + disable + ": " + message.getString());
						next(Step.WAIT_STAGED);
					}
				}
				case WAIT_STAGED -> {
					if (stepTicks % 10 != 0) {
						return;
					}
					Path pending = FabricLoader.getInstance().getConfigDir().resolve("rigtune").resolve("pending.json");
					if (Files.isRegularFile(pending) && Files.readString(pending, StandardCharsets.UTF_8).contains(disableFile)) {
						Files.createDirectories(out);
						Files.copy(pending, out.resolve("pending-" + phase + ".json"), StandardCopyOption.REPLACE_EXISTING);
						event("staged");
						next(Step.SHOT);
					} else if (stepTicks > STAGE_TIMEOUT) {
						fail(minecraft, "the disable wasn't staged within " + STAGE_TIMEOUT / SECOND + " s");
					}
				}
				case SHOT -> {
					if (stepTicks == 1) {
						recordHistory(controller, "historyAtQuit");
						minecraft.gui.setScreen(new HistoryScreen(minecraft.gui.screen(), controller));
					} else if (stepTicks == 2 * SECOND) {
						screenshot(minecraft, "e2e-downgrade-old-3-history.png");
					} else if (stepTicks == 3 * SECOND) {
						next(Step.QUIT);
					}
				}
				case QUIT -> finish(minecraft);
				default -> {
				}
			}
		} catch (Throwable t) {
			LOGGER.error("E2E downgrade driver failed", t);
			fail(minecraft, t.toString());
		}
	}

	private void recordHistory(RigTuneController controller, String key) {
		HistoryModel.View view = controller.history();
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("state", view == null ? null : String.valueOf(view.state()));
		List<Map<String, Object>> entries = new ArrayList<>();
		for (HistoryModel.Entry entry : view == null ? List.<HistoryModel.Entry>of() : view.entries()) {
			Map<String, Object> e = new LinkedHashMap<>();
			e.put("id", entry.id());
			e.put("kind", entry.kind());
			e.put("kindKey", entry.kindKey());
			e.put("undoable", entry.undoable());
			e.put("changes", entry.changes().size());
			e.put("labels", entry.changes().stream().map(c -> c.label() + ": " + c.status()).toList());
			entries.add(e);
		}
		summary.put("entries", entries);
		result.put(key, summary);
		event(key + ": " + summary.get("state") + ", " + entries.size() + " entries");
	}

	private boolean pressConfirm(Minecraft minecraft) {
		Button confirm = minecraft.gui.screen() == null ? null : minecraft.gui.screen().children().stream()
				.filter(Button.class::isInstance).map(Button.class::cast)
				.filter(b -> b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("rigtune.undo.confirm"))
				.findFirst().orElse(null);
		if (confirm == null || !confirm.active) {
			fail(minecraft, "no active Undo button on " + minecraft.gui.screen());
			return false;
		}
		result.put("undoButton", confirm.getMessage().getString());
		confirm.onPress(new MouseButtonEvent(confirm.getX() + 1, confirm.getY() + 1, new MouseButtonInfo(0, 0)));
		event("pressed " + confirm.getMessage().getString());
		return true;
	}

	private static int undoCount() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("rigtune").resolve("history.json");
		try {
			if (!Files.isRegularFile(file)) {
				return 0;
			}
			int count = 0;
			for (JsonElement entry : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("entries")) {
				JsonObject e = entry.getAsJsonObject();
				count += e.has("kind") && "undo".equals(e.get("kind").getAsString()) ? 1 : 0;
			}
			return count;
		} catch (IOException | RuntimeException e) {
			return -1;
		}
	}

	private static Path modJar(String modId) {
		ModContainer mod = modId == null ? null : FabricLoader.getInstance().getModContainer(modId).orElse(null);
		List<Path> paths = mod == null ? List.of() : mod.getOrigin().getPaths();
		return paths.size() == 1 && paths.getFirst().getFileName().toString().endsWith(".jar") ? paths.getFirst() : null;
	}

	private void next(Step nextStep) {
		step = nextStep;
		stepTicks = 0;
	}

	private void event(String message) {
		String line = String.format(Locale.ROOT, "t+%.1fs %s", ticks / (double) SECOND, message);
		events.add(line);
		LOGGER.info("[E2E] {}", line);
	}

	private void screenshot(Minecraft minecraft, String name) {
		Screenshot.grab(minecraft.gameDirectory, name, minecraft.gameRenderer.mainRenderTarget(), 1,
				message -> event("screenshot " + name + ": " + message.getString()));
	}

	private void fail(Minecraft minecraft, String error) {
		result.put("error", error);
		event("FAILED: " + error);
		finish(minecraft);
	}

	private void finish(Minecraft minecraft) {
		if (result.get("error") == null) {
			result.put("ok", true);
		}
		step = Step.DONE;
		event("quitting");
		try {
			Files.createDirectories(out);
			Files.writeString(out.resolve("driver-" + phase + ".json"), GSON.toJson(result), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.error("Could not write the E2E result", e);
		}
		// The title screen's Quit button, so RigTune's CLIENT_STOPPING hook starts the apply helper.
		minecraft.stop();
	}
}
