package io.github.chaotix345.rigtune.core.history;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The 0.1.0 files under src/test/resources/v010/ (hand-written) and v010/captured/ (captured from the released jar by
// the self-update E2E run). Paths in them are templated: "${MODS}/name", "${CONFIG}/name", "${MODS}", "${CONFIG}".
public final class V010Fixtures {
	private static final Pattern IN_DIR = Pattern.compile("\\$\\{(MODS|CONFIG)}/([^\"]+)");

	private V010Fixtures() {
	}

	public static String read(String resource) {
		try (InputStream in = V010Fixtures.class.getResourceAsStream("/v010/" + resource)) {
			if (in == null) {
				throw new IllegalArgumentException("no fixture v010/" + resource);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static String template(String json, Path mods, Path config) {
		Matcher m = IN_DIR.matcher(json);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			Path dir = m.group(1).equals("MODS") ? mods : config;
			m.appendReplacement(out, Matcher.quoteReplacement(escape(dir.resolve(m.group(2)))));
		}
		m.appendTail(out);
		return out.toString().replace("${MODS}", escape(mods)).replace("${CONFIG}", escape(config));
	}

	private static String escape(Path path) {
		return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
	}

	// Writes the templated fixture to target.
	public static Path install(String resource, Path target, Path mods, Path config) throws IOException {
		Files.createDirectories(target.toAbsolutePath().getParent());
		return Files.writeString(target, template(read(resource), mods, config));
	}

	// The captured-fixture folder, or null until the E2E run has added one.
	public static Path capturedDir() {
		URL url = V010Fixtures.class.getResource("/v010/captured");
		if (url == null || !"file".equals(url.getProtocol())) {
			return null;
		}
		try {
			return Path.of(url.toURI());
		} catch (URISyntaxException e) {
			return null;
		}
	}
}
