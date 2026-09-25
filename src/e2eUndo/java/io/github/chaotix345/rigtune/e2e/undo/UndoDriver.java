package io.github.chaotix345.rigtune.e2e.undo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.UndoScreen;
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
 * Drives RigTune 0.2 for the end-to-end undo after a restart (plan review M14; tools/e2e/README.md). Compiled against
 * this repository's sources, unlike the self-update driver. Inert unless -Drigtune.e2e.phase is one of:
 * <ul>
 * <li>{@code mod-apply}: applies, in one Apply, "add" of the Modrinth project -Drigtune.e2e.addProject (slug
 * -Drigtune.e2e.addSlug) and "disable" of the loaded mod -Drigtune.e2e.disable; waits until both are staged; quits.</li>
 * <li>{@code mod-undo}: Undo last apply: records the plan, screenshots the confirmation screen, presses its Undo button,
 * waits until the reversals are staged, quits.</li>
 * <li>{@code mod-check}: records the loaded mods and what is left to undo, screenshots the undo screen, quits.</li>
 * </ul>
 * Results go to -Drigtune.e2e.out as driver-&lt;phase&gt;.json; screenshots to the instance's screenshots folder.
 */
public final class UndoDriver implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("RigTune E2E undo");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
	private static final int SECOND = 20;
	private static final int READY_TIMEOUT = 180 * SECOND;
	private static final int STAGE_TIMEOUT = 120 * SECOND;
	private static final int WATCHDOG = 360 * SECOND;

	private enum Step {
		WAIT_TITLE, WAIT_READY, ACT, WAIT_STAGED, SHOT, QUIT, DONE
	}

	private final String phase = System.getProperty("rigtune.e2e.phase");
	private final String addSlug = System.getProperty("rigtune.e2e.addSlug");
	private final String addProject = System.getProperty("rigtune.e2e.addProject");
	private final String disable = System.getProperty("rigtune.e2e.disable");
	private final Map<String, Object> result = new LinkedHashMap<>();
	private final List<String> events = new ArrayList<>();
	private Path out;
	private Step step = Step.WAIT_TITLE;
	private int ticks;
	private int stepTicks;
	// mod-apply: the jar being disabled.
	private String disableFile;

	@Override
	public void onInitializeClient() {
		if (phase == null || !List.of("mod-apply", "mod-undo", "mod-check").contains(phase)) {
			return;
		}
		out = Path.of(System.getProperty("rigtune.e2e.out", "e2e-out")).toAbsolutePath();
		result.put("phase", phase);
		result.put("ok", false);
		result.put("error", null);
		result.put("events", events);
		event("undo driver loaded, phase " + phase + ", output " + out);
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
						result.put("loadedMods", FabricLoader.getInstance().getAllMods().stream().map(m -> m.getMetadata().getId()).sorted().toList());
						event("title screen");
						next(Step.WAIT_READY);
					}
				}
				// The report means the scan is done (the downloads need it) and no download is running.
				case WAIT_READY -> {
					if (stepTicks % SECOND == 0 && controller.report() != null) {
						event("report ready (online=" + controller.report().online() + ")");
						next(Step.ACT);
					} else if (stepTicks > READY_TIMEOUT) {
						fail(minecraft, "no report within " + READY_TIMEOUT / SECOND + " s");
					}
				}
				case ACT -> act(minecraft, controller);
				case WAIT_STAGED -> {
					if (stepTicks % 10 != 0) {
						return;
					}
					List<Map<String, Object>> ops = pendingOps();
					if (staged(ops)) {
						Files.createDirectories(out);
						Files.copy(pendingFile(), out.resolve("pending-" + phase + ".json"), StandardCopyOption.REPLACE_EXISTING);
						result.put("pendingOps", ops);
						Component status = controller.status();
						result.put("status", status == null ? null : status.getString());
						event("staged: " + ops.size() + " op(s) in pending.json");
						next(Step.SHOT);
					} else if (stepTicks > STAGE_TIMEOUT) {
						result.put("pendingOps", ops);
						fail(minecraft, "the changes were not staged within " + STAGE_TIMEOUT / SECOND + " s");
					}
				}
				case SHOT -> {
					if (stepTicks == SECOND) {
						screenshot(minecraft, "e2e-" + phase + "-2-after.png");
					} else if (stepTicks == 3 * SECOND) {
						next(Step.QUIT);
					}
				}
				case QUIT -> finish(minecraft);
				default -> {
				}
			}
		} catch (Throwable t) {
			LOGGER.error("E2E undo driver failed", t);
			fail(minecraft, t.toString());
		}
	}

	private void act(Minecraft minecraft, RigTuneController controller) {
		switch (phase) {
			case "mod-apply" -> {
				if (stepTicks == 1) {
					RigTuneClient.open(minecraft.gui.screen());
				} else if (stepTicks == SECOND) {
					screenshot(minecraft, "e2e-mod-apply-1-report.png");
				} else if (stepTicks == 2 * SECOND) {
					Path jar = modJar(disable);
					if (jar == null) {
						fail(minecraft, "mod " + disable + " isn't loaded from a jar in mods/");
						return;
					}
					disableFile = jar.getFileName().toString();
					// What the RigTune screen's Apply does with these two rows ticked.
					List<Recommendation> chosen = List.of(
							new Recommendation("add:" + addSlug, Category.ADD_MOD, Impact.LOW, "Install " + addSlug, "E2E test mod",
									new Action.AddMod(addSlug, addProject, addSlug), true),
							new Recommendation("disable:" + disable, Category.REMOVE_MOD, Impact.LOW, "Disable " + disable, "E2E test mod",
									new Action.DisableMod(disable, jar), true));
					Component message = controller.apply(chosen);
					result.put("applyMessage", message.getString());
					event("apply " + chosen.stream().map(Recommendation::id).toList() + ": " + message.getString());
					next(Step.WAIT_STAGED);
				}
			}
			case "mod-undo" -> {
				if (stepTicks == 1) {
					UndoPlan plan = controller.undoPlan(false);
					recordPlan("undoPlan", plan);
					if (plan == null || plan.problem() != null || plan.isEmpty()) {
						fail(minecraft, "no undo plan: " + (plan == null ? "null" : plan.problem()));
						return;
					}
					result.put("undoOf", plan.undoOf());
					minecraft.gui.setScreen(new UndoScreen(minecraft.gui.screen(), controller, false));
				} else if (stepTicks == 2 * SECOND) {
					screenshot(minecraft, "e2e-mod-undo-1-plan.png");
				} else if (stepTicks == 3 * SECOND) {
					// Press the confirmation screen's Undo button, as a player would; it carries out the plan it shows.
					Button confirm = minecraft.gui.screen() == null ? null : minecraft.gui.screen().children().stream()
							.filter(Button.class::isInstance).map(Button.class::cast)
							.filter(b -> b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("rigtune.undo.confirm"))
							.findFirst().orElse(null);
					if (confirm == null || !confirm.active) {
						fail(minecraft, "no active Undo button on " + minecraft.gui.screen());
						return;
					}
					result.put("undoButton", confirm.getMessage().getString());
					confirm.onPress(new MouseButtonEvent(confirm.getX() + 1, confirm.getY() + 1, new MouseButtonInfo(0, 0)));
					event("pressed " + confirm.getMessage().getString());
					next(Step.WAIT_STAGED);
				}
			}
			case "mod-check" -> {
				if (stepTicks == 1) {
					UndoPlan plan = controller.undoPlan(false);
					recordPlan("undoPlanAfter", plan);
					result.put("undoableItems", plan == null ? -1
							: (int) plan.items().stream().filter(i -> i.action() != UndoPlan.Action.SKIP).count());
					minecraft.gui.setScreen(new UndoScreen(minecraft.gui.screen(), controller, false));
				} else if (stepTicks == 2 * SECOND) {
					screenshot(minecraft, "e2e-mod-check-1-undo.png");
				} else if (stepTicks == 3 * SECOND) {
					next(Step.QUIT);
				}
			}
			default -> fail(minecraft, "unknown phase " + phase);
		}
	}

	private boolean staged(List<Map<String, Object>> ops) {
		if (phase.equals("mod-apply")) {
			return ops.stream().anyMatch(op -> "ENABLE_FILE".equals(op.get("type")) && addSlug.equals(op.get("modId")))
					&& ops.stream().anyMatch(op -> "DISABLE_FILE".equals(op.get("type")) && fileName(op.get("path")).equals(disableFile));
		}
		// mod-undo: one reversal per change: a disable of the added jar, an enable of the disabled one.
		return ops.stream().anyMatch(op -> "DISABLE_FILE".equals(op.get("type")) && fileName(op.get("path")).startsWith(addSlug))
				&& ops.stream().anyMatch(op -> "ENABLE_FILE".equals(op.get("type")) && disable.equals(op.get("modId")));
	}

	private static String fileName(Object path) {
		return path instanceof String p ? Path.of(p).getFileName().toString() : "";
	}

	private void recordPlan(String key, UndoPlan plan) {
		if (plan == null) {
			result.put(key, null);
			return;
		}
		List<Map<String, Object>> items = new ArrayList<>();
		for (UndoPlan.Item item : plan.items()) {
			Map<String, Object> summary = new LinkedHashMap<>();
			summary.put("description", item.description());
			summary.put("action", item.action().name());
			summary.put("reason", item.reason());
			summary.put("needsRestart", item.needsRestart());
			summary.put("changeIds", item.changeIds());
			summary.put("opIds", item.opIds());
			items.add(summary);
		}
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("undoOf", plan.undoOf());
		summary.put("at", plan.at());
		summary.put("problem", plan.problem());
		result.put(key + "Meta", summary);
		result.put(key, items);
		event(key + ": " + items.size() + " item(s), undoOf " + plan.undoOf() + ", problem " + plan.problem());
	}

	private static Path pendingFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("rigtune").resolve("pending.json");
	}

	private static List<Map<String, Object>> pendingOps() throws IOException {
		if (!Files.isRegularFile(pendingFile())) {
			return List.of();
		}
		JsonObject plan = JsonParser.parseString(Files.readString(pendingFile(), StandardCharsets.UTF_8)).getAsJsonObject();
		List<Map<String, Object>> out = new ArrayList<>();
		for (JsonElement op : plan.getAsJsonArray("ops")) {
			Map<String, Object> summary = new LinkedHashMap<>();
			for (String field : List.of("type", "from", "to", "path", "modId", "group", "id")) {
				JsonElement value = op.getAsJsonObject().get(field);
				summary.put(field, value == null || value.isJsonNull() ? null : value.getAsString());
			}
			out.add(summary);
		}
		return out;
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
