package io.github.chaotix345.rigtune.e2e.driver;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Drives RigTune in a production client for the self-update end-to-end test (tools/e2e/README.md). It is compiled
 * against the released v0.1.0 jar, so it only uses API that 0.1.0 has, and runs against whichever RigTune is in the
 * instance's mods folder. Inert unless -Drigtune.e2e.phase is set:
 * <ul>
 * <li>{@code update}: sets the goal to QUALITY, waits for the "Update RigTune" recommendation, applies only it, waits
 * until it is staged in pending.json (copied out, since the helper deletes it), and quits like the Quit button.</li>
 * <li>{@code verify}: records what the (updated) RigTune reports, with screenshots of the title screen (apply toast)
 * and the RigTune screen, and quits.</li>
 * </ul>
 * Results go to -Drigtune.e2e.out as driver-&lt;phase&gt;.json and report-&lt;phase&gt;.txt; screenshots to the instance's
 * screenshots folder. A watchdog quits the game if a phase takes longer than six minutes.
 */
public final class SelfUpdateDriver implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("RigTune E2E");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
	private static final String UPDATE_ID = "update:rigtune";
	private static final int SECOND = 20;
	private static final int REPORT_TIMEOUT = 180 * SECOND;
	private static final int STAGE_TIMEOUT = 120 * SECOND;
	private static final int RESCAN_AFTER = 20 * SECOND;
	private static final int WATCHDOG = 360 * SECOND;

	private enum Step {
		WAIT_TITLE, WAIT_UPDATE, SHOT_REPORT, WAIT_STAGED, SHOT_STAGED, SHOT_TITLE, WAIT_ONLINE, SHOT_VERIFY, QUIT, DONE
	}

	private final String phase = System.getProperty("rigtune.e2e.phase");
	private final Map<String, Object> result = new LinkedHashMap<>();
	private final List<String> events = new ArrayList<>();
	private final List<Map<String, Object>> statuses = new ArrayList<>();
	private String lastStatus;
	private Path out;
	private Step step = Step.WAIT_TITLE;
	private int ticks;
	private int stepTicks;
	private Recommendation update;
	private boolean rescanned;
	// A test mod to disable in the same apply as the update (phase update).
	private final String alsoDisable = System.getProperty("rigtune.e2e.alsoDisable");

	@Override
	public void onInitializeClient() {
		if (phase == null) {
			return;
		}
		out = Path.of(System.getProperty("rigtune.e2e.out", "e2e-out")).toAbsolutePath();
		result.put("phase", phase);
		result.put("ok", false);
		result.put("error", null);
		result.put("events", events);
		result.put("statuses", statuses);
		event("driver loaded, phase " + phase + ", output " + out);
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void tick(Minecraft minecraft) {
		if (step == Step.DONE) {
			return;
		}
		ticks++;
		stepTicks++;
		try {
			recordStatus(RigTuneClient.controller());
			if (ticks > WATCHDOG) {
				fail(minecraft, "watchdog: still in step " + step + " after " + WATCHDOG / SECOND + " s");
				return;
			}
			if (phase.equals("update")) {
				updatePhase(minecraft);
			} else if (phase.equals("verify")) {
				verifyPhase(minecraft);
			} else {
				fail(minecraft, "unknown phase " + phase);
			}
		} catch (Throwable t) {
			LOGGER.error("E2E driver failed", t);
			fail(minecraft, t.toString());
		}
	}

	private void updatePhase(Minecraft minecraft) throws IOException {
		RigTuneController controller = RigTuneClient.controller();
		switch (step) {
			case WAIT_TITLE -> {
				if (onTitleScreen(minecraft)) {
					recordRigTune();
					controller.setGoal(Goal.QUALITY);
					event("title screen; goal set to QUALITY");
					next(Step.WAIT_UPDATE);
				}
			}
			case WAIT_UPDATE -> {
				if (stepTicks % SECOND != 0) {
					return;
				}
				Report report = controller.report();
				Recommendation found = report == null ? null : report.recommendations().stream()
						.filter(r -> r.id().equals(UPDATE_ID) && r.action() instanceof Action.UpdateMod)
						.findFirst().orElse(null);
				if (found == null) {
					rescanIfStillOffline(controller, report);
				}
				if (found != null) {
					update = found;
					Action.UpdateMod action = (Action.UpdateMod) found.action();
					Map<String, Object> offered = new LinkedHashMap<>();
					offered.put("title", found.title());
					offered.put("reason", found.reason());
					offered.put("selectedByDefault", found.selectedByDefault());
					offered.put("currentFile", String.valueOf(action.currentFile()));
					offered.put("currentVersion", action.update().currentVersion());
					offered.put("newVersion", action.update().newVersionNumber());
					offered.put("url", action.update().file().url());
					offered.put("filename", action.update().file().filename());
					offered.put("sha512", action.update().file().sha512());
					result.put("update", offered);
					result.put("updateOffered", true);
					writeReport(report);
					event("offered: " + found.title() + " -> " + action.update().file().filename());
					RigTuneClient.open(minecraft.gui.screen());
					next(Step.SHOT_REPORT);
				} else if (stepTicks > REPORT_TIMEOUT) {
					if (report != null) {
						writeReport(report);
					}
					fail(minecraft, "no " + UPDATE_ID + " recommendation within " + REPORT_TIMEOUT / SECOND + " s (report "
							+ (report == null ? "missing" : "online=" + report.online()) + ")");
				}
			}
			case SHOT_REPORT -> {
				if (stepTicks == SECOND / 2) {
					scrollTo(minecraft, controller.report(), UPDATE_ID);
				} else if (stepTicks == SECOND) {
					screenshot(minecraft, "e2e-update-1-report.png");
				} else if (stepTicks == 2 * SECOND) {
					List<Recommendation> chosen = new ArrayList<>(List.of(update));
					Path other = alsoDisable == null ? null : modJar(alsoDisable);
					if (alsoDisable != null) {
						if (other == null) {
							fail(minecraft, "mod " + alsoDisable + " (-Drigtune.e2e.alsoDisable) isn't loaded from a jar");
							return;
						}
						// As if the player also ticked a "Disable" row: the 0.2 legacy import needs a change that isn't RigTune's.
						chosen.add(new Recommendation("disable:" + alsoDisable, Category.REMOVE_MOD, Impact.LOW, "Disable " + alsoDisable,
								"E2E test mod", new Action.DisableMod(alsoDisable, other), true));
					}
					Component message = controller.apply(chosen);
					result.put("applyMessage", message.getString());
					event("apply " + chosen.stream().map(Recommendation::id).toList() + ": " + message.getString());
					next(Step.WAIT_STAGED);
				}
			}
			case WAIT_STAGED -> {
				if (stepTicks % 10 != 0) {
					return;
				}
				String filename = ((Action.UpdateMod) update.action()).update().file().filename();
				Path pending = FabricLoader.getInstance().getConfigDir().resolve("rigtune").resolve("pending.json");
				List<Map<String, Object>> ops = Files.isRegularFile(pending) ? ops(pending) : List.of();
				Path other = alsoDisable == null ? null : modJar(alsoDisable);
				boolean staged = ops.stream().anyMatch(op -> "ENABLE_FILE".equals(op.get("type"))
						&& op.get("to") instanceof String to && Path.of(to).getFileName().toString().equals(filename))
						&& (other == null || ops.stream().anyMatch(op -> "DISABLE_FILE".equals(op.get("type"))
						&& op.get("path") instanceof String path && Path.of(path).getFileName().equals(other.getFileName())));
				if (staged) {
					Files.createDirectories(out);
					Files.copy(pending, out.resolve("pending-before-exit.json"), StandardCopyOption.REPLACE_EXISTING);
					result.put("pendingOps", ops);
					event("staged: " + ops.size() + " op(s) in pending.json");
					next(Step.SHOT_STAGED);
				} else if (stepTicks > STAGE_TIMEOUT) {
					fail(minecraft, "the update was not staged within " + STAGE_TIMEOUT / SECOND + " s");
				}
			}
			case SHOT_STAGED -> {
				Component status = controller.status();
				if (stepTicks == 2 * SECOND) {
					result.put("status", status == null ? null : status.getString());
					screenshot(minecraft, "e2e-update-2-staged.png");
				} else if (stepTicks == 4 * SECOND) {
					next(Step.QUIT);
				}
			}
			case QUIT -> finish(minecraft);
			default -> {
			}
		}
	}

	private void verifyPhase(Minecraft minecraft) throws IOException {
		RigTuneController controller = RigTuneClient.controller();
		switch (step) {
			case WAIT_TITLE -> {
				if (onTitleScreen(minecraft)) {
					recordRigTune();
					event("title screen");
					next(Step.SHOT_TITLE);
				}
			}
			case SHOT_TITLE -> {
				// The apply toast slides in on the first title screen.
				if (stepTicks == 2 * SECOND) {
					screenshot(minecraft, "e2e-verify-1-title.png");
					next(Step.WAIT_ONLINE);
				}
			}
			case WAIT_ONLINE -> {
				if (stepTicks % SECOND != 0) {
					return;
				}
				Report report = controller.report();
				boolean online = report != null && report.online();
				if (!online) {
					rescanIfStillOffline(controller, report);
				}
				if (online || stepTicks > REPORT_TIMEOUT) {
					result.put("goal", controller.goal().name());
					result.put("reportOnline", online);
					result.put("updateOffered", report != null && report.recommendations().stream().anyMatch(r -> r.id().equals(UPDATE_ID)));
					if (report != null) {
						writeReport(report);
					}
					event("report: " + (report == null ? "missing" : report.recommendations().size() + " recommendations, online=" + online)
							+ ", goal " + controller.goal());
					if (minecraft.gui.screen() instanceof TitleScreen) {
						RigTuneClient.open(minecraft.gui.screen());
					}
					next(Step.SHOT_VERIFY);
				}
			}
			case SHOT_VERIFY -> {
				if (stepTicks == SECOND) {
					screenshot(minecraft, "e2e-verify-2-report.png");
				} else if (stepTicks == 3 * SECOND) {
					next(Step.QUIT);
				}
			}
			case QUIT -> finish(minecraft);
			default -> {
			}
		}
	}

	// RigTune (0.1.0 and 0.2 so far) skips its Modrinth lookups on a launch where the hardware/mod scan finishes before the
	// rules are loaded and the remote rules aren't newer (docs/v0.2/design/ws-g.md), and stays offline until Rescan. Press
	// Rescan once, as a player would, and record it.
	private void rescanIfStillOffline(RigTuneController controller, Report report) {
		if (rescanned || stepTicks < RESCAN_AFTER || report != null && report.online()) {
			return;
		}
		rescanned = true;
		result.put("rescanned", true);
		event("report still " + (report == null ? "missing" : "offline") + " after " + RESCAN_AFTER / SECOND + " s: pressing Rescan");
		controller.rescan();
	}

	// Every status line RigTune shows, as it changes (e.g. the notice when it cancels a staged update, plan review H-M2).
	private void recordStatus(RigTuneController controller) {
		Component status = controller == null ? null : controller.status();
		String text = status == null ? null : status.getString();
		if (text == null || text.equals(lastStatus)) {
			return;
		}
		lastStatus = text;
		Map<String, Object> seen = new LinkedHashMap<>();
		seen.put("t", String.format(Locale.ROOT, "%.1f", ticks / (double) SECOND));
		seen.put("key", status.getContents() instanceof TranslatableContents t ? t.getKey() : null);
		seen.put("text", text);
		statuses.add(seen);
		event("status: " + text);
	}

	// Every file under mods/ (relative path with '/', sha256) just before quitting, so the harness can tell what changed
	// at exit.
	private static Map<String, String> modsListing() {
		Path mods = FabricLoader.getInstance().getGameDir().resolve("mods");
		Map<String, String> out = new TreeMap<>();
		try (Stream<Path> files = Files.walk(mods)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
				out.put(mods.relativize(file).toString().replace('\\', '/'), HexFormat.of().formatHex(hash));
			}
		} catch (IOException | NoSuchAlgorithmException e) {
			out.put("(error)", e.toString());
		}
		return out;
	}

	// The title screen, once the startup loading overlay has faded.
	private static boolean onTitleScreen(Minecraft minecraft) {
		return minecraft.gui.screen() instanceof TitleScreen && minecraft.gui.overlay() == null;
	}

	// Scrolls the RigTune screen's list so the recommendation's row shows. The list has a header entry per category, then
	// that category's rows, in Category order (RigTuneScreen.populate). Only for the screenshot, so a miss is harmless.
	private void scrollTo(Minecraft minecraft, Report report, String id) {
		AbstractSelectionList<?> list = minecraft.gui.screen() == null || report == null ? null : minecraft.gui.screen().children().stream()
				.filter(AbstractSelectionList.class::isInstance)
				.map(c -> (AbstractSelectionList<?>) c)
				.findFirst().orElse(null);
		if (list == null) {
			return;
		}
		int index = 0;
		for (Category category : Category.values()) {
			List<Recommendation> rows = report.recommendations().stream().filter(r -> r.category() == category).toList();
			if (rows.isEmpty()) {
				continue;
			}
			index++;
			for (Recommendation r : rows) {
				if (r.id().equals(id) && index < list.children().size()) {
					// Start the view at the entry above the row (its category header for the first row).
					Object above = list.children().get(index - 1);
					list.setScrollAmount(list.scrollAmount() + ((LayoutElement) above).getY() - list.getY());
					return;
				}
				index++;
			}
		}
		event("could not find " + id + " in the list");
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

	private static Path modJar(String modId) {
		ModContainer mod = FabricLoader.getInstance().getModContainer(modId).orElse(null);
		List<Path> paths = mod == null ? List.of() : mod.getOrigin().getPaths();
		return paths.size() == 1 && paths.getFirst().getFileName().toString().endsWith(".jar") ? paths.getFirst() : null;
	}

	private void recordRigTune() {
		ModContainer rigtune = FabricLoader.getInstance().getModContainer("rigtune").orElse(null);
		result.put("rigtuneVersion", rigtune == null ? null : rigtune.getMetadata().getVersion().getFriendlyString());
		result.put("rigtuneOrigin", rigtune == null ? List.of() : rigtune.getOrigin().getPaths().stream().map(Path::toString).toList());
	}

	private static List<Map<String, Object>> ops(Path pending) throws IOException {
		JsonObject plan = JsonParser.parseString(Files.readString(pending, StandardCharsets.UTF_8)).getAsJsonObject();
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

	private void writeReport(Report report) throws IOException {
		StringBuilder text = new StringBuilder();
		text.append("rules r").append(report.rulesRevision()).append(" (").append(report.rulesSource()).append("), online=")
				.append(report.online()).append(", goal ").append(report.goal()).append('\n');
		for (Recommendation r : report.recommendations()) {
			text.append(r.id()).append(" | ").append(r.category()).append(" | ").append(r.impact()).append(" | ")
					.append(r.selectedByDefault() ? "ticked" : "unticked").append(" | ").append(r.title()).append(" | ")
					.append(r.reason().replace('\n', ' ')).append('\n');
		}
		Files.createDirectories(out);
		Files.writeString(out.resolve("report-" + phase + ".txt"), text, StandardCharsets.UTF_8);
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
		result.put("modsAtQuit", modsListing());
		try {
			Files.createDirectories(out);
			Files.writeString(out.resolve("driver-" + phase + ".json"), GSON.toJson(result), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.error("Could not write the E2E result", e);
		}
		// What the title screen's Quit button does, so RigTune's CLIENT_STOPPING hook starts the apply helper.
		minecraft.stop();
	}
}
