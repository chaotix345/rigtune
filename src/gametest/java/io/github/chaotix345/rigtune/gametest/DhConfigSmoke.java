package io.github.chaotix345.rigtune.gametest;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import com.seibel.distanthorizons.api.interfaces.config.client.IDhApiGraphicsConfig;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.compat.OptionalMods;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.TomlConfigPatcher;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;

// AC7.3 with the player's Distant Horizons, from the title screen only: the harness deadlocks leaving a world while DH
// is loaded (docs/smoke/README.md). -PsmokeDh=stage applies the report's DH setting recommendations and returns, so the
// harness quits through Minecraft.stop(), as the Quit button does, and the post-exit helper patches DistantHorizons.toml.
// -PsmokeDh=check, on the next launch (without -PuserConfigDir), checks that DH loaded the patched file: the staged
// values are live in DH, and so are the file's other non-default quality values. The staged value can be DH's default,
// which a DH that fell back to its defaults would show too; the non-default ones can't come from the defaults.
final class DhConfigSmoke {
	private static final Path GAME_DIR = FabricLoader.getInstance().getGameDir();
	private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir();
	private static final Path TOML = CONFIG_DIR.resolve("DistantHorizons.toml");
	private static final Path STAGED = GAME_DIR.resolve("rigtune-ac73-stage.properties");
	private static final String QUALITY = "client.advanced.graphics.quality.";
	// TOML keys (without "dh.") that DH's API exposes, so their live values can be compared with the file.
	private static final Map<String, Function<IDhApiGraphicsConfig, IDhApiConfigValue<?>>> LIVE = new LinkedHashMap<>();

	static {
		LIVE.put(QUALITY + "lodChunkRenderDistanceRadius", IDhApiGraphicsConfig::chunkRenderDistance);
		LIVE.put(QUALITY + "verticalQuality", IDhApiGraphicsConfig::verticalQuality);
		LIVE.put(QUALITY + "horizontalQuality", IDhApiGraphicsConfig::horizontalQuality);
		LIVE.put(QUALITY + "maxHorizontalResolution", IDhApiGraphicsConfig::maxHorizontalResolution);
	}

	private DhConfigSmoke() {
	}

	static void run(ClientGameTestContext context, String mode, Report report) {
		check(OptionalMods.dhLoaded(), "Distant Horizons is loaded");
		switch (mode) {
			case "stage" -> stage(context, report);
			case "check" -> verify(context, report);
			default -> throw new AssertionError("Unknown -Drigtune.smoke.dh=" + mode + " (stage or check)");
		}
	}

	private static void stage(ClientGameTestContext context, Report report) {
		List<Recommendation> dh = report.recommendations().stream()
				.filter(r -> r.action() instanceof Action.SetSetting set && set.key().startsWith(SettingKeys.DH_PREFIX))
				.toList();
		check(!dh.isEmpty(), "the report has a Distant Horizons setting recommendation");
		// After DH's own save at startup, so the helper's change is the only difference from this copy.
		copy(TOML, GAME_DIR.resolve("rigtune-ac73-toml-at-stage.toml"));
		Map<String, String> before = TomlConfigPatcher.readValues(TOML);
		Map<String, String> wanted = new LinkedHashMap<>();
		dh.forEach(r -> {
			Action.SetSetting set = (Action.SetSetting) r.action();
			wanted.put(set.key().substring(SettingKeys.DH_PREFIX.length()), set.newValue());
		});

		Component status = context.computeOnClient(mc -> RigTuneClient.controller().apply(dh));
		Path pendingFile = PendingActions.defaultPath(CONFIG_DIR);
		PendingActions pending = load(() -> PendingActions.load(pendingFile));
		Map<String, String> staged = new LinkedHashMap<>();
		pending.ops().stream()
				.filter(op -> op.type() == PendingActions.Type.PATCH_TOML && TOML.equals(Path.of(op.path())))
				.forEach(op -> staged.putAll(op.patches()));
		copy(pendingFile, GAME_DIR.resolve("rigtune-ac73-pending.json"));

		List<String> lines = new ArrayList<>();
		lines.add("AC7.3 stage at " + Instant.now() + ", Distant Horizons " + dhVersion());
		lines.add("apply status: " + status.getString());
		Properties out = new Properties();
		wanted.forEach((key, value) -> {
			lines.add("dh." + key + ": " + before.get(key) + " -> " + value + " (staged " + staged.get(key) + ")");
			out.setProperty(key, value);
		});
		lines.add("pending.json: " + pending.ops().size() + " op(s): " + pending.ops().stream().map(op -> op.type() + " " + op.patches()).toList());
		boolean passed = staged.equals(wanted) && pending.ops().stream().allMatch(op -> op.type() == PendingActions.Type.PATCH_TOML);
		lines.add("AC7.3 stage " + (passed ? "PASSED" : "FAILED"));
		write(GAME_DIR.resolve("rigtune-ac73-stage.txt"), lines);
		try (Writer writer = Files.newBufferedWriter(STAGED)) {
			out.store(writer, "AC7.3 staged DH keys and their new values");
		} catch (IOException e) {
			throw new AssertionError("Could not write " + STAGED, e);
		}
		check(passed, "exactly the DH recommendations are staged, as PATCH_TOML ops only: " + lines);

		context.clickScreenButton("rigtune.button");
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(5);
		context.takeScreenshot("ac73-staged");
		context.runOnClient(mc -> mc.gui.screen().onClose());
		context.waitForScreen(TitleScreen.class);
		// Returning ends the test on the title screen; the harness then stops the game and CLIENT_STOPPING starts the helper.
	}

	private static void verify(ClientGameTestContext context, Report report) {
		Properties staged = new Properties();
		try (Reader reader = Files.newBufferedReader(STAGED)) {
			staged.load(reader);
		} catch (IOException e) {
			throw new AssertionError("No " + STAGED + ": run -PsmokeDh=stage first", e);
		}
		context.waitFor(mc -> DhApi.Delayed.configs != null, 1200);
		Map<String, String> file = TomlConfigPatcher.readValues(TOML);
		List<String> lines = new ArrayList<>();
		lines.add("AC7.3 check at " + Instant.now() + ", Distant Horizons " + dhVersion());
		boolean passed = true;

		for (String key : staged.stringPropertyNames()) {
			boolean ok = Objects.equals(staged.getProperty(key), file.get(key));
			lines.add("file dh." + key + " = " + file.get(key) + " (staged " + staged.getProperty(key) + ") " + (ok ? "ok" : "WRONG"));
			passed &= ok;
		}

		// Every exposed key must be live with the file's value, the staged ones included; at least one must differ from
		// DH's default, or the check couldn't tell a loaded file from a fallback to the defaults.
		int nonDefault = 0;
		for (Map.Entry<String, Function<IDhApiGraphicsConfig, IDhApiConfigValue<?>>> entry : LIVE.entrySet()) {
			String key = entry.getKey();
			String[] live;
			try {
				live = context.computeOnClient(mc -> {
					IDhApiConfigValue<?> value = entry.getValue().apply(DhApi.Delayed.configs.graphics());
					return new String[] {String.valueOf(value.getValue()), String.valueOf(value.getDefaultValue())};
				});
			} catch (LinkageError | RuntimeException e) {
				lines.add("live dh." + key + ": not readable through this DH API (" + e + ")");
				continue;
			}
			boolean matches = live[0].equals(file.get(key));
			boolean isDefault = live[0].equals(live[1]);
			nonDefault += matches && !isDefault ? 1 : 0;
			lines.add("live dh." + key + " = " + live[0] + ", file " + file.get(key) + ", DH default " + live[1]
					+ (matches ? "" : " MISMATCH") + (staged.containsKey(key) ? " (staged)" : ""));
			passed &= matches;
		}
		lines.add("live values that differ from DH's defaults and match the file: " + nonDefault);
		passed &= nonDefault > 0;

		ApplyResult result = load(() -> ApplyResult.load(ApplyResult.defaultPath(CONFIG_DIR)));
		List<ApplyResult.OpResult> tomlOps = result.results().stream()
				.filter(r -> r.op().type() == PendingActions.Type.PATCH_TOML).toList();
		boolean helperOk = !tomlOps.isEmpty() && tomlOps.stream().allMatch(r -> r.status() == ApplyResult.Status.OK);
		lines.add("last-apply.json (" + result.finishedAt() + "): " + tomlOps.stream().map(r -> r.op().patches() + " " + r.status()).toList());
		boolean noPending = !Files.exists(PendingActions.defaultPath(CONFIG_DIR));
		lines.add("pending.json gone " + noPending);
		passed &= helperOk && noPending;

		Journal journal = new Journal(CONFIG_DIR, null, null, (message, error) -> RigTune.LOGGER.warn("Smoke: journal: {}", message, error));
		List<JournalChange> changes = journal.entries().stream().flatMap(e -> e.changes().stream())
				.filter(c -> c.key() != null && c.key().startsWith(SettingKeys.DH_PREFIX)).toList();
		boolean journaled = !changes.isEmpty() && changes.stream().allMatch(c -> "APPLIED".equals(c.status()));
		lines.add("history.json: " + changes.stream().map(c -> c.key() + " " + c.before() + " -> " + c.after() + " " + c.status()).toList());
		passed &= journaled;

		List<String> stillOffered = report.recommendations().stream()
				.filter(r -> r.action() instanceof Action.SetSetting set && set.key().startsWith(SettingKeys.DH_PREFIX)
						&& staged.containsKey(set.key().substring(SettingKeys.DH_PREFIX.length())))
				.map(Recommendation::title).toList();
		lines.add("still recommended: " + stillOffered);
		passed &= stillOffered.isEmpty();

		lines.add("AC7.3 " + (passed ? "PASSED" : "FAILED"));
		write(GAME_DIR.resolve("rigtune-ac73-check.txt"), lines);
		check(passed, "Distant Horizons loaded the patched config: " + lines);
	}

	private static String dhVersion() {
		return FabricLoader.getInstance().getModContainer(OptionalMods.DISTANT_HORIZONS)
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}

	private interface IoSupplier<T> {
		T get() throws IOException;
	}

	private static <T> T load(IoSupplier<T> supplier) {
		try {
			return supplier.get();
		} catch (IOException e) {
			throw new AssertionError(e.getMessage(), e);
		}
	}

	private static void copy(Path from, Path to) {
		try {
			Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new AssertionError("Could not copy " + from, e);
		}
	}

	private static void write(Path file, List<String> lines) {
		try {
			Files.write(file, lines);
		} catch (IOException e) {
			throw new AssertionError("Could not write " + file, e);
		}
		RigTune.LOGGER.info("Smoke: {}", String.join(" | ", lines));
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
	}
}
