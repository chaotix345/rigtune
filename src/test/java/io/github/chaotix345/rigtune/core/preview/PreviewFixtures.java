package io.github.chaotix345.rigtune.core.preview;

import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.apply.PropertiesConfigPatcher;
import io.github.chaotix345.rigtune.core.apply.SodiumConfigPatcher;
import io.github.chaotix345.rigtune.core.apply.TomlConfigPatcher;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

// A temp game instance with every file Apply can touch: options.txt, mods/, and the three config files (the DH and
// Iris ones are the real-format fixtures the patcher tests use).
public final class PreviewFixtures {
	public static final String SODIUM_JSON = "{\"quality\":{\"weather_quality\":\"FANCY\"},\"performance\":{\"chunk_builder_threads\":0}}";

	public final Path game;
	public final Path mods;
	public final Path config;
	public final Path options;
	public final Path sodium;
	public final Path dh;
	public final Path iris;

	public PreviewFixtures(Path game) throws IOException {
		this.game = game;
		this.mods = Files.createDirectories(game.resolve("mods"));
		this.config = Files.createDirectories(game.resolve("config"));
		this.options = Files.writeString(game.resolve("options.txt"), "version:4671\nrenderDistance:12\nparticles:0\n");
		this.sodium = Files.writeString(config.resolve("sodium-options.json"), SODIUM_JSON);
		this.dh = Files.writeString(config.resolve("DistantHorizons.toml"), resource("/dh/DistantHorizons.toml"));
		this.iris = Files.writeString(config.resolve("iris.properties"), resource("/iris/iris.properties"));
	}

	public List<PreviewPlanner.ConfigFile> configFiles() {
		return List.of(
				new PreviewPlanner.ConfigFile("sodium.", sodium, SodiumConfigPatcher::stage, PreviewFixtures::readSodium),
				new PreviewPlanner.ConfigFile("dh.", dh, TomlConfigPatcher::stage, TomlConfigPatcher::readValues),
				new PreviewPlanner.ConfigFile("iris.", iris, PropertiesConfigPatcher::stage, PropertiesConfigPatcher::readValues));
	}

	// As ConfigTargets' Sodium reader: the file flattened, keys without the namespace.
	public static Map<String, String> readSodium(Path file) {
		try {
			return Files.exists(file) ? SodiumConfigPatcher.flatten(JsonParser.parseString(Files.readString(file)).getAsJsonObject(), "") : Map.of();
		} catch (IOException e) {
			return Map.of();
		}
	}

	public static Map<String, String> vanillaNow() {
		return Map.of("renderDistance", "12", "particles", "all", "maxFps", "120");
	}

	public static Recommendation rec(String id, Action action) {
		return new Recommendation(id, Category.SETTING, Impact.LOW, "Title of " + id, "", action, true);
	}

	public static Recommendation setting(String key, String current, String next) {
		return rec("setting:" + key, new Action.SetSetting(key, current, next));
	}

	// Every regular file under the instance, by relative path, with its SHA-256.
	public TreeMap<String, String> tree() throws IOException {
		TreeMap<String, String> out = new TreeMap<>();
		try (Stream<Path> files = Files.walk(game)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				out.put(game.relativize(file).toString().replace('\\', '/'), sha256(Files.readAllBytes(file)));
			}
		}
		return out;
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String resource(String name) throws IOException {
		try (InputStream in = PreviewFixtures.class.getResourceAsStream(name)) {
			if (in == null) {
				throw new UncheckedIOException(new IOException("Missing test resource " + name));
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
