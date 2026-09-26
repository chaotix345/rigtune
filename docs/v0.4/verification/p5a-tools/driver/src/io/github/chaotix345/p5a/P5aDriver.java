package io.github.chaotix345.p5a;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkWorld;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.ui.BenchmarkHistoryScreen;
import io.github.chaotix345.rigtune.client.ui.HistoryScreen;
import io.github.chaotix345.rigtune.client.ui.JvmScreen;
import io.github.chaotix345.rigtune.client.ui.NoticeScreen;
import io.github.chaotix345.rigtune.client.ui.ProfileImportScreen;
import io.github.chaotix345.rigtune.client.ui.ProfilesScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.client.ui.StutterScreen;
import io.github.chaotix345.rigtune.client.ui.Texts;
import io.github.chaotix345.rigtune.client.ui.ToolsScreen;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.FrameStats;
import io.github.chaotix345.rigtune.core.benchmark.SessionResult;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.profile.ProfileImport;
import io.github.chaotix345.rigtune.core.profile.ProfileView;
import io.github.chaotix345.rigtune.core.stutter.StutterView;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Test-only driver for RigTune v0.4's Phase 5 real runs (P5-A). Inert unless -Dp5a.script is set: a comma-separated list
 * of actions run one after the other on the client tick; see {@link #step}. Output: log lines "[P5A] ..." and
 * -Dp5a.out/p5a-&lt;label&gt;.json; screenshots in the instance's screenshots folder.
 */
public final class P5aDriver implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("P5A");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
	private static final int SECOND = 20;

	private final String script = System.getProperty("p5a.script");
	private final String label = System.getProperty("p5a.label", "run");
	private final int watchdogSeconds = Integer.getInteger("p5a.watchdog", 900);
	private List<String> actions;
	private int index;
	private int ticks;
	private int stepTicks;
	private boolean done;
	private final Map<String, Object> result = new LinkedHashMap<>();
	private final List<String> events = new ArrayList<>();
	private Path out;
	// per-action state
	private Object state;
	private float holdYaw;
	private double[] holdPos;
	private Map<String, long[]> gcAt;

	@Override
	public void onInitializeClient() {
		if (script == null || script.isBlank()) {
			return;
		}
		actions = List.of(script.split(","));
		out = Path.of(System.getProperty("p5a.out", "p5a-out")).toAbsolutePath();
		result.put("label", label);
		result.put("script", actions);
		result.put("ok", false);
		result.put("events", events);
		event("driver loaded: " + actions);
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		// World saves on the integrated server (autosave included), for the Stutter Doctor's save windows.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.BEFORE_SAVE.register((server, flush, force) ->
				LOGGER.info("[P5A] BEFORE_SAVE at JVM uptime {} ms (flush {}, force {})", ManagementFactory.getRuntimeMXBean().getUptime(), flush, force));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) ->
				LOGGER.info("[P5A] AFTER_SAVE at JVM uptime {} ms (flush {}, force {})", ManagementFactory.getRuntimeMXBean().getUptime(), flush, force));
	}

	private void tick(Minecraft mc) {
		if (done) {
			return;
		}
		ticks++;
		stepTicks++;
		if (ticks > watchdogSeconds * SECOND) {
			fail(mc, "watchdog after " + watchdogSeconds + " s in action " + current());
			return;
		}
		try {
			if (index >= actions.size()) {
				finish(mc);
				return;
			}
			if (step(mc, current())) {
				index++;
				stepTicks = 0;
				state = null;
			}
		} catch (Throwable t) {
			LOGGER.error("[P5A] action {} threw", current(), t);
			fail(mc, "action " + current() + " threw " + t);
		}
	}

	private String current() {
		return index < actions.size() ? actions.get(index).trim() : "<end>";
	}

	// One action; true when it's complete.
	private boolean step(Minecraft mc, String action) throws Exception {
		String name = action.contains(":") ? action.substring(0, action.indexOf(':')) : action;
		String arg = action.contains(":") ? action.substring(action.indexOf(':') + 1) : "";
		RigTuneController c = RigTuneClient.controller();
		switch (name) {
			case "waitTitle" -> {
				if (mc.gui.screen() instanceof TitleScreen && mc.gui.overlay() == null && stepTicks > 40) {
					mc.options.pauseOnLostFocus = false;
					result.put("rigtuneVersion", FabricLoader.getInstance().getModContainer("rigtune").orElseThrow().getMetadata().getVersion().getFriendlyString());
					result.put("mods", FabricLoader.getInstance().getAllMods().stream()
							.map(m -> m.getMetadata().getId() + " " + m.getMetadata().getVersion().getFriendlyString()).sorted().toList());
					event("title screen at JVM uptime " + ManagementFactory.getRuntimeMXBean().getUptime() + " ms");
					return true;
				}
				return false;
			}
			case "waitReport" -> {
				if (stepTicks % 10 == 0 && c.report() != null) {
					event("report ready (online " + c.report().online() + ", rules r" + c.report().rulesRevision() + " " + c.report().rulesSource() + ")");
					return true;
				}
				if (stepTicks > 240 * SECOND) {
					throw new IllegalStateException("no report within 240 s");
				}
				return false;
			}
			case "sleep" -> {
				return stepTicks >= Double.parseDouble(arg) * SECOND;
			}
			case "shot" -> {
				if (stepTicks == 1) {
					dumpScreen(mc, arg);
					Screenshot.grab(mc.gameDirectory, "p5a-" + label + "-" + arg + ".png", mc.gameRenderer.mainRenderTarget(), 1,
							m -> event("screenshot " + arg + ": " + m.getString()));
				}
				return stepTicks >= 10;
			}
			case "dump" -> {
				dumpScreen(mc, arg);
				return true;
			}
			case "open" -> {
				Screen parent = mc.gui.screen();
				Screen screen = switch (arg) {
					case "rigtune" -> new RigTuneScreen(null, c);
					case "tools" -> new ToolsScreen(parent, c);
					case "stutter" -> new StutterScreen(parent, c);
					case "jvm" -> new JvmScreen(parent, c);
					case "history" -> new HistoryScreen(parent, c);
					case "profiles" -> new ProfilesScreen(parent, c);
					case "benchhist" -> new BenchmarkHistoryScreen(parent, c);
					case "notice" -> new NoticeScreen(parent, c);
					case "import" -> new ProfileImportScreen(parent == null ? new TitleScreen() : parent, c);
					case "title" -> new TitleScreen();
					case "none" -> null;
					default -> throw new IllegalArgumentException("unknown screen " + arg);
				};
				mc.gui.setScreen(screen);
				event("opened " + (screen == null ? "no screen" : screen.getClass().getSimpleName()));
				return true;
			}
			case "reflectScreen" -> {
				// reflectScreen:<class name>:<static method or ctor(Screen)> — e.g. a mod's own config screen
				String[] p = arg.split("#");
				Class<?> cls = Class.forName(p[0]);
				Screen parent = mc.gui.screen();
				Object screen;
				if (p.length > 1) {
					java.lang.reflect.Method m = Arrays.stream(cls.getMethods()).filter(x -> x.getName().equals(p[1]) && x.getParameterCount() == 1).findFirst().orElseThrow();
					screen = m.invoke(null, parent);
				} else {
					screen = Arrays.stream(cls.getConstructors()).filter(k -> k.getParameterCount() == 1).findFirst().orElseThrow().newInstance(parent);
				}
				mc.gui.setScreen((Screen) screen);
				event("opened " + screen.getClass().getName());
				return true;
			}
			case "vanillaVideo" -> {
				Screen parent = mc.gui.screen();
				mc.gui.setScreen(new net.minecraft.client.gui.screens.options.VideoSettingsScreen(parent, mc, mc.options));
				event("opened vanilla video settings -> " + mc.gui.screen().getClass().getName());
				return true;
			}
			case "click" -> {
				Screen screen = mc.gui.screen();
				AbstractWidget w = find(screen, arg);
				if (w == null) {
					if (stepTicks > 5 * SECOND) {
						throw new IllegalStateException("no widget containing '" + arg + "' on " + (screen == null ? null : screen.getClass().getName()));
					}
					return false;
				}
				event("click '" + w.getMessage().getString() + "' (" + w.getClass().getSimpleName() + ", active " + w.active + ")");
				if (w instanceof Button b) {
					b.onPress(new MouseButtonEvent(b.getX() + 1, b.getY() + 1, new MouseButtonInfo(leftButton(), 0)));
				} else {
					w.mouseClicked(new MouseButtonEvent(w.getX() + 2, w.getY() + 2, new MouseButtonInfo(leftButton(), 0)), false);
				}
				return true;
			}
			case "type" -> {
				EditBox box = firstEditBox(mc.gui.screen());
				String text = arg.startsWith("@") ? Files.readString(Path.of(arg.substring(1)), StandardCharsets.UTF_8).trim() : arg;
				box.setValue(text);
				event("typed " + text.length() + " characters into the edit box");
				return true;
			}
			case "notices" -> {
				List<Map<String, Object>> list = new ArrayList<>();
				for (Notice n : c.notices()) {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("key", n.key());
					m.put("priority", n.priority().name());
					m.put("message", Texts.component(n.message()).getString());
					m.put("detail", n.detail() == null ? null : Texts.component(n.detail()).getString());
					m.put("actions", n.actions().stream().map(a -> Texts.component(a.label()).getString()).toList());
					m.put("dismissible", n.dismissible());
					list.add(m);
				}
				put("notices", arg, list);
				event("notices " + list);
				return true;
			}
			case "report" -> {
				Report r = c.report();
				List<Map<String, Object>> recs = new ArrayList<>();
				for (Recommendation rec : r.recommendations()) {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rec.id());
					m.put("title", Texts.component(rec.titleText()).getString());
					m.put("reason", Texts.component(rec.reasonText()).getString());
					if (rec.action() instanceof Action.SetSetting s) {
						m.put("key", s.key());
						m.put("current", s.currentValue());
						m.put("new", s.newValue());
					} else {
						m.put("action", rec.action().getClass().getSimpleName());
					}
					m.put("selected", rec.selectedByDefault());
					recs.add(m);
				}
				put("report", arg, Map.of("goal", r.goal().name(), "rules", r.rulesRevision(), "online", r.online(), "tier", String.valueOf(r.tier()), "recommendations", recs));
				for (Map<String, Object> m : recs) {
					if ("vanilla.renderDistance".equals(m.get("key")) || "vanilla.simulationDistance".equals(m.get("key"))) {
						event("recommendation " + m);
					}
				}
				event("report: " + recs.size() + " recommendations");
				return true;
			}
			case "hardware" -> {
				HardwareProfile h = c.report().hardware();
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("gpuVendor", h.gpu().vendorString());
				m.put("gpuRenderer", h.gpu().renderer());
				m.put("gpuDriver", h.gpu().driverVersion());
				m.put("backend", h.gpu().backend().name());
				m.put("vramMb", h.gpu().vramMb());
				m.put("cpu", String.valueOf(h.cpu()));
				m.put("ramMb", h.totalRamMb());
				m.put("maxHeapMb", h.maxHeapMb());
				m.put("flags", new TreeMap<>(Map.of("flags", String.join(" ", new java.util.TreeSet<>(h.flags())))));
				m.put("gpuClass", String.valueOf(c.report().gpuClass()));
				put("hardware", arg, m);
				event("hardware " + m);
				return true;
			}
			case "jvm" -> {
				event("jvm report " + c.jvmReport());
				put("jvm", arg, String.valueOf(c.jvmReport()));
				return true;
			}
			case "settings" -> {
				Map<String, String> v = new TreeMap<>(SettingsBridge.read(mc).values());
				put("settings", arg, v);
				event("settings (" + v.size() + " keys)");
				return true;
			}
			case "monitor" -> {
				c.setStutterMonitor("on".equals(arg));
				event("stutter monitor " + arg);
				return true;
			}
			case "benchWorld" -> {
				if (stepTicks == 1) {
					boolean opened = BenchmarkWorld.open(mc, mc.gui.screen());
					event("opening the benchmark world: " + opened);
					if (!opened) {
						throw new IllegalStateException("benchmark world didn't open");
					}
					return false;
				}
				if (BenchmarkWorld.state() == BenchmarkWorld.State.READY && mc.level != null && mc.player != null) {
					event("in the benchmark world at " + mc.player.position() + ", state READY");
					return true;
				}
				if (BenchmarkWorld.state() == BenchmarkWorld.State.FAILED) {
					throw new IllegalStateException("benchmark world FAILED");
				}
				return false;
			}
			case "leaveWorld" -> {
				if (stepTicks == 1) {
					mc.gui.setScreen(null);
					BenchmarkWorld.leave(mc, null);
					event("leaving the benchmark world");
					return false;
				}
				return mc.level == null && stepTicks > 5 * SECOND;
			}
			case "tp" -> {
				command(mc, "tp @a " + arg.replace('_', ' '));
				return true;
			}
			case "cmd" -> {
				command(mc, arg.replace('_', ' '));
				return true;
			}
			case "save" -> {
				IntegratedServer server = mc.getSingleplayerServer();
				event("save-all (saveEverything with flush)");
				server.execute(() -> server.saveEverything(false, true, true));
				return true;
			}
			case "stutterReport" -> {
				if (stepTicks == 1) {
					mc.gui.setScreen(new StutterScreen(null, c));
					event("opened the Stutter Doctor");
					return false;
				}
				StutterView view = c.stutter();
				if (stepTicks % 20 == 0 && view.report() != null && !view.analysing()) {
					String summary = c.stutterSummary();
					event("stutter view: monitorOn " + view.monitorOn() + ", recording " + view.recording() + ", live " + view.live()
							+ ", advice " + view.advice().stream().map(f -> f.toString()).toList());
					put("stutterSummary", arg, summary);
					put("stutterReport", arg, view.report());
					LOGGER.info("[P5A] stutter summary ({}):\n{}", arg, summary);
					return true;
				}
				if (stepTicks > 60 * SECOND) {
					throw new IllegalStateException("no stutter report within 60 s");
				}
				return false;
			}
			case "stutterJson" -> {
				logFile(FabricLoader.getInstance().getConfigDir().resolve("rigtune/stutter.json"), "stutter.json");
				return true;
			}
			case "file" -> {
				logFile(FabricLoader.getInstance().getGameDir().resolve(arg), arg);
				return true;
			}
			case "gc" -> {
				System.gc();
				event("System.gc()");
				return true;
			}
			case "measure" -> {
				if (stepTicks == 1) {
					P5aFrames.reset();
					P5aFrames.mirrorBenchmark = true;
					gcAt = gcSnapshot();
					String refusal = BenchmarkController.tryStart(mc, new BenchmarkRequest(BenchmarkRequest.Mode.MEASURE, BenchmarkRequest.Scene.BENCHMARK_WORLD, null),
							BenchmarkController.defaultConfig());
					event("Measure in the benchmark world at RD " + mc.options.renderDistance().get() + ": " + (refusal == null ? "started" : "refused " + refusal));
					if (refusal != null) {
						throw new IllegalStateException("benchmark refused: " + refusal);
					}
					return false;
				}
				if (stepTicks > 3 * SECOND && !BenchmarkController.running() && !BenchmarkWorld.busy() && mc.level == null) {
					P5aFrames.mirrorBenchmark = false;
					BenchmarkController.Outcome o = BenchmarkController.lastOutcome();
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("screen", mc.gui.screen() == null ? null : mc.gui.screen().getClass().getSimpleName());
					m.put("cancelled", o == null ? null : o.cancelled());
					m.put("recordId", o == null || o.record() == null ? null : o.record().id());
					List<Map<String, Object>> meas = new ArrayList<>();
					if (o != null) {
						for (SessionResult.Measured x : o.session().measurements()) {
							meas.add(stats(x.step().kind() + " RD " + x.step().knobs().renderDistance(), x.stats()));
						}
					}
					m.put("measurements", meas);
					m.put("rawFrames", frameStats(P5aFrames.copy()));
					m.put("gcInWindowsApprox", gcDelta(gcAt));
					put("measure", arg, m);
					event("benchmark done: " + m);
					return true;
				}
				if (stepTicks > 600 * SECOND) {
					throw new IllegalStateException("benchmark didn't finish within 600 s");
				}
				return false;
			}
			case "capped" -> {
				// capped:<sweeps>:<seconds per sweep>[:<warm-up s>] in the benchmark world at the options' frame cap.
				String[] p = arg.split(":");
				int sweeps = Integer.parseInt(p[0]);
				double seconds = Double.parseDouble(p[1]);
				double warm = p.length > 2 ? Double.parseDouble(p[2]) : 10;
				if (stepTicks == 1) {
					holdYaw = mc.player.getYRot();
					holdPos = new double[] {mc.player.getX(), mc.player.getY(), mc.player.getZ()};
					mc.gui.setScreen(null);
					event("capped measurement: frame limit " + mc.options.framerateLimit().get() + ", vsync " + mc.options.enableVsync().get()
							+ ", RD " + mc.options.renderDistance().get() + ", window active " + mc.isWindowActive());
				}
				double t = (stepTicks - 1) / (double) SECOND;
				double total = warm + sweeps * seconds;
				if (t < warm) {
					hold(mc, holdYaw - (float) (360.0 * (1 - t / warm)), 0);
				} else if (t < total) {
					if (!P5aFrames.recording) {
						P5aFrames.reset();
						gcAt = gcSnapshot();
						P5aFrames.recording = true;
						event("recording frames");
					}
					double inSweep = ((t - warm) % seconds) / seconds;
					hold(mc, holdYaw + (float) (360.0 * inSweep), 0);
				} else {
					P5aFrames.recording = false;
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("frameLimit", mc.options.framerateLimit().get());
					m.put("vsync", mc.options.enableVsync().get());
					m.put("renderDistance", mc.options.renderDistance().get());
					m.put("frames", frameStats(P5aFrames.copy()));
					m.put("gc", gcDelta(gcAt));
					MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
					m.put("heapCommittedMb", heap.getCommitted() >> 20);
					m.put("heapUsedMb", heap.getUsed() >> 20);
					m.put("jvmArgs", ManagementFactory.getRuntimeMXBean().getInputArguments().stream().filter(a -> a.startsWith("-X")).toList());
					put("capped", arg, m);
					event("capped done: " + m);
					return true;
				}
				return false;
			}
			case "connect" -> {
				if (stepTicks == 1) {
					ServerData data = new ServerData("P5A test server", arg, ServerData.Type.OTHER);
					ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(arg), data, false, null);
					event("connecting to " + arg);
					return false;
				}
				if (mc.level != null && mc.player != null && stepTicks > 5 * SECOND) {
					event("connected: level loaded, server limits " + c.serverLimits());
					return true;
				}
				if (stepTicks > 90 * SECOND) {
					throw new IllegalStateException("not connected within 90 s; screen " + mc.gui.screen());
				}
				return false;
			}
			case "serverLimits" -> {
				ServerLimits l = c.serverLimits();
				put("serverLimits", arg, String.valueOf(l));
				event("serverLimits " + l + "; options RD " + mc.options.renderDistance().get() + ", effective " + mc.options.getEffectiveRenderDistance());
				return true;
			}
			case "disconnect" -> {
				if (stepTicks == 1) {
					mc.gui.setScreen(null);
					mc.level.disconnect(Component.literal("P5A disconnect"));
					mc.disconnectWithSavingScreen();
					event("disconnecting");
					return false;
				}
				return mc.level == null && stepTicks > 3 * SECOND;
			}
			case "profiles" -> {
				List<Map<String, Object>> list = new ArrayList<>();
				for (ProfileView p : c.profiles()) {
					list.add(Map.of("id", p.id(), "name", Texts.component(p.name()).getString(), "source", p.source(), "active", p.active()));
				}
				put("profiles", arg, list);
				event("profiles " + list);
				return true;
			}
			case "preview" -> {
				ApplyPreview p = c.previewProfile(profileId(c, arg));
				put("preview", arg, preview(p));
				event("preview " + arg + ": " + preview(p));
				return true;
			}
			case "switch" -> {
				Component msg = c.switchProfile(profileId(c, arg));
				put("switch", arg, msg.getString());
				event("switched to " + arg + ": " + msg.getString());
				return true;
			}
			case "history" -> {
				HistoryModel.View v = c.history();
				List<Map<String, Object>> entries = new ArrayList<>();
				if (v != null) {
					for (HistoryModel.Entry e : v.entries()) {
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("id", e.id());
						m.put("kind", e.kind());
						m.put("at", e.at());
						m.put("profile", e.profile());
						m.put("undoable", e.undoable());
						m.put("undoOf", e.undoOf());
						m.put("changes", e.changes().stream().map(ch -> ch.label() + ": " + ch.before() + " -> " + ch.after() + " [" + ch.status() + "]").toList());
						entries.add(m);
					}
				}
				put("history", arg, Map.of("state", v == null ? "null" : String.valueOf(v.state()), "entries", entries));
				event("history: " + entries.size() + " entries; profiles " + entries.stream().map(e -> e.get("profile")).toList());
				return true;
			}
			case "undoProfile" -> {
				HistoryModel.View v = c.history();
				HistoryModel.Entry entry = v.entries().stream().filter(e -> arg.equals(e.profile())).findFirst()
						.orElseThrow(() -> new IllegalStateException("no history entry labelled " + arg));
				UndoPlan plan = c.undoPlanFor(entry.id());
				event("Undo this on " + entry.id() + " (" + arg + "): plan " + plan);
				Component msg = c.undo(plan);
				put("undo", arg, Map.of("entry", entry.id(), "plan", String.valueOf(plan), "message", msg.getString()));
				event("undo result: " + msg.getString());
				return true;
			}
			case "export" -> {
				String code = c.exportProfileCode(profileId(c, arg));
				Files.createDirectories(out);
				Files.writeString(out.resolve("code-" + arg.replace(' ', '_') + ".txt"), code == null ? "" : code, StandardCharsets.UTF_8);
				put("code", arg, code);
				event("share code for " + arg + ": " + code);
				return true;
			}
			case "decode" -> {
				String code = Files.readString(Path.of(arg), StandardCharsets.UTF_8).trim();
				ProfileImport imp = c.importProfileCode(code);
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("name", imp.name());
				m.put("error", imp.error() == null ? null : Texts.component(imp.error()).getString());
				m.put("unknownKeys", imp.unknownKeys());
				m.put("values", new TreeMap<>(imp.values()));
				m.put("preview", preview(imp.preview()));
				put("decode", arg, m);
				event("decoded " + m);
				return true;
			}
			case "screenIs" -> {
				Screen s = mc.gui.screen();
				if (s != null && s.getClass().getSimpleName().equals(arg)) {
					event("screen is " + arg);
					return true;
				}
				if (stepTicks > 30 * SECOND) {
					throw new IllegalStateException("screen is " + (s == null ? null : s.getClass().getName()) + ", not " + arg);
				}
				return false;
			}
			case "scroll" -> {
				Screen screen = mc.gui.screen();
				boolean moved = false;
				for (GuiEventListener l : screen.children()) {
					for (java.lang.reflect.Method m : l.getClass().getMethods()) {
						if (m.getName().equals("setScrollAmount") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == double.class) {
							m.invoke(l, Double.parseDouble(arg));
							moved = true;
						}
					}
				}
				event("scroll " + arg + ": " + moved);
				return true;
			}
			case "waitScreen" -> {
				Screen s = mc.gui.screen();
				return s != null && s.getClass().getSimpleName().equals(arg);
			}
			case "rd" -> {
				mc.options.renderDistance().set(Integer.parseInt(arg));
				mc.options.save();
				event("render distance set to " + arg);
				return true;
			}
			case "quit" -> {
				finish(mc);
				return true;
			}
			default -> throw new IllegalArgumentException("unknown action " + action);
		}
	}

	private static int leftButton() {
		try {
			return com.mojang.blaze3d.platform.InputConstants.class.getField("MOUSE_BUTTON_LEFT").getInt(null);
		} catch (ReflectiveOperationException e) {
			return 0;
		}
	}

	private static String profileId(RigTuneController c, String name) {
		for (ProfileView p : c.profiles()) {
			if (Texts.component(p.name()).getString().equalsIgnoreCase(name) || p.id().equals(name)) {
				return p.id();
			}
		}
		throw new IllegalStateException("no profile named " + name);
	}

	private static Map<String, Object> preview(ApplyPreview p) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("now", p.now().stream().map(s -> s.key() + ": " + s.oldValue() + " -> " + s.newValue()).toList());
		m.put("atRestart", p.atRestart().stream().map(s -> s.key() + ": " + s.oldValue() + " -> " + s.newValue()).toList());
		m.put("skipped", p.skipped().stream().map(s -> s.recommendationId() + " " + s.reason() + " " + s.detail()).toList());
		m.put("notes", String.valueOf(p.notes()));
		return m;
	}

	private void hold(Minecraft mc, float yaw, float pitch) {
		if (mc.player != null && holdPos != null) {
			mc.player.snapTo(holdPos[0], holdPos[1], holdPos[2], yaw, pitch);
			mc.player.setDeltaMovement(0, 0, 0);
		}
	}

	private static Map<String, long[]> gcSnapshot() {
		Map<String, long[]> m = new LinkedHashMap<>();
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			m.put(gc.getName(), new long[] {gc.getCollectionCount(), gc.getCollectionTime()});
		}
		return m;
	}

	private static Map<String, String> gcDelta(Map<String, long[]> at) {
		Map<String, String> m = new LinkedHashMap<>();
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			long[] a = at == null ? new long[2] : at.getOrDefault(gc.getName(), new long[2]);
			m.put(gc.getName(), "count " + (gc.getCollectionCount() - a[0]) + ", time " + (gc.getCollectionTime() - a[1]) + " ms");
		}
		return m;
	}

	private static Map<String, Object> stats(String name, FrameStats s) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("step", name);
		m.put("frames", s.frames());
		m.put("avgFps", round(s.avgFps()));
		m.put("onePercentLowFps", round(s.onePercentLowFps()));
		m.put("p99Ms", round(s.p99FrameMs()));
		m.put("maxMs", round(s.maxFrameMs()));
		return m;
	}

	private static Map<String, Object> frameStats(long[] frames) {
		Map<String, Object> m = stats("raw", FrameStats.of(frames));
		int over8 = 0;
		int over16 = 0;
		int over33 = 0;
		for (long f : frames) {
			if (f > 8_000_000L) {
				over8++;
			}
			if (f > 16_667_000L) {
				over16++;
			}
			if (f > 33_333_000L) {
				over33++;
			}
		}
		m.put("over8ms", over8);
		m.put("over16_7ms", over16);
		m.put("over33ms", over33);
		return m;
	}

	private static double round(double v) {
		return Math.round(v * 100) / 100.0;
	}

	private void command(Minecraft mc, String command) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null) {
			throw new IllegalStateException("no integrated server for " + command);
		}
		event("command: " + command);
		server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
	}

	private static AbstractWidget find(Screen screen, String text) {
		if (screen == null) {
			return null;
		}
		List<AbstractWidget> all = new ArrayList<>();
		collect(screen.children(), all);
		String want = text.toLowerCase(Locale.ROOT);
		for (AbstractWidget w : all) {
			if (w.visible && w.getMessage().getString().toLowerCase(Locale.ROOT).equals(want)) {
				return w;
			}
		}
		for (AbstractWidget w : all) {
			if (w.visible && w.getMessage().getString().toLowerCase(Locale.ROOT).contains(want)) {
				return w;
			}
		}
		return null;
	}

	private static EditBox firstEditBox(Screen screen) {
		List<AbstractWidget> all = new ArrayList<>();
		collect(screen.children(), all);
		return all.stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
	}

	private static void collect(List<? extends GuiEventListener> children, List<AbstractWidget> into) {
		for (GuiEventListener l : children) {
			if (l instanceof AbstractWidget w) {
				into.add(w);
			}
			if (l instanceof net.minecraft.client.gui.components.events.ContainerEventHandler ch) {
				collect(ch.children(), into);
			}
		}
	}

	private void dumpScreen(Minecraft mc, String name) {
		Screen s = mc.gui.screen();
		List<String> widgets = new ArrayList<>();
		if (s != null) {
			List<AbstractWidget> all = new ArrayList<>();
			collect(s.children(), all);
			for (AbstractWidget w : all) {
				widgets.add(w.getClass().getSimpleName() + " '" + w.getMessage().getString() + "'" + (w.active ? "" : " (inactive)") + (w.visible ? "" : " (hidden)"));
			}
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("screen", s == null ? null : s.getClass().getName());
		m.put("title", s == null ? null : s.getTitle().getString());
		m.put("widgets", widgets);
		m.put("window", mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight() + " scale " + mc.getWindow().getGuiScale());
		put("screen", name, m);
		event("screen " + name + ": " + m);
	}

	private void logFile(Path file, String name) throws IOException {
		String text = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
		put("file", name, text);
		LOGGER.info("[P5A] file {}:\n{}", name, text);
	}

	@SuppressWarnings("unchecked")
	private void put(String kind, String arg, Object value) {
		Map<String, Object> m = (Map<String, Object>) result.computeIfAbsent(kind, k -> new LinkedHashMap<String, Object>());
		m.put(arg.isEmpty() ? String.valueOf(m.size()) : arg, value);
	}

	private void event(String message) {
		String line = String.format(Locale.ROOT, "%.1fs %s", ticks / (double) SECOND, message);
		events.add(line);
		LOGGER.info("[P5A] {}", line);
	}

	private void fail(Minecraft mc, String error) {
		result.put("error", error);
		event("FAILED: " + error);
		finish(mc);
	}

	private void finish(Minecraft mc) {
		if (done) {
			return;
		}
		if (result.get("error") == null) {
			result.put("ok", true);
		}
		done = true;
		event("quitting");
		try {
			Files.createDirectories(out);
			Files.writeString(out.resolve("p5a-" + label + ".json"), GSON.toJson(result), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.error("[P5A] could not write the result", e);
		}
		mc.stop();
	}
}
