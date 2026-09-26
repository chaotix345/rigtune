package io.github.chaotix345.rigtune.v030.core.rules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.chaotix345.rigtune.RigTune;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class RulesLoader {
	// This client reads schemaVersion 1 (rules-v1.json, a subset of v2) and 2. A future v3 lives in its own file.
	public static final int MIN_SCHEMA_VERSION = 1;
	public static final int SCHEMA_VERSION = 2;
	public static final String BUNDLED_RESOURCE = "/rigtune/rules-v2.json";
	public static final String SOURCE_BUNDLED = "bundled";
	public static final String SOURCE_CACHE = "cache";
	public static final String SOURCE_REMOTE = "remote";
	public static final long MAX_RULES_BYTES = 2L << 20;

	// Highest revision; on a tie the newer schema, then remote > cache > bundled.
	private static final Comparator<Candidate> NEWEST = Comparator
			.comparingInt((Candidate c) -> c.document().revision)
			.thenComparingInt(c -> c.document().schemaVersion)
			.thenComparingInt(c -> sourceRank(c.source()));

	private static final Gson GSON = new GsonBuilder().registerTypeAdapterFactory(new ConditionAdapterFactory()).create();

	public record Candidate(String source, RulesDocument document) {
	}

	private RulesLoader() {
	}

	public static RulesDocument parse(String json) {
		RulesDocument doc;
		try {
			doc = GSON.fromJson(json, RulesDocument.class);
		} catch (JsonParseException | IllegalStateException e) {
			throw new IllegalArgumentException("Rules file is not valid JSON: " + e.getMessage(), e);
		} catch (OutOfMemoryError e) {
			throw new IllegalArgumentException("Rules file is too large to parse", e);
		}
		if (doc == null) {
			throw new IllegalArgumentException("Rules file is empty");
		}
		if (doc.schemaVersion < MIN_SCHEMA_VERSION || doc.schemaVersion > SCHEMA_VERSION) {
			throw new IllegalArgumentException("Unsupported rules schemaVersion " + doc.schemaVersion);
		}
		doc.fillDefaults();
		doc.gpuTiers.removeIf(rule -> invalidPattern("gpuTiers", rule));
		doc.cpuTiers.removeIf(rule -> invalidPattern("cpuTiers", rule));
		return doc;
	}

	static Condition condition(String json) {
		return GSON.fromJson(json, Condition.class);
	}

	public static RulesDocument loadBundled() {
		try (InputStream in = RulesLoader.class.getResourceAsStream(BUNDLED_RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("Bundled rules missing: " + BUNDLED_RESOURCE);
			}
			RulesDocument doc = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			doc.setSource(SOURCE_BUNDLED);
			return doc;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static Optional<RulesDocument> loadCache(Path file) {
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		try {
			if (Files.size(file) > MAX_RULES_BYTES) {
				throw new IllegalArgumentException("larger than " + MAX_RULES_BYTES + " bytes");
			}
			RulesDocument doc = parse(Files.readString(file, StandardCharsets.UTF_8));
			doc.setSource(SOURCE_CACHE);
			return Optional.of(doc);
		} catch (IOException | IllegalArgumentException e) {
			RigTune.LOGGER.warn("Ignoring cached rules at {}: {}", file, e.getMessage());
			return Optional.empty();
		}
	}

	public static void saveCache(Path file, String json) throws IOException {
		Path parent = file.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, json, StandardCharsets.UTF_8);
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public static Optional<RulesDocument> pickNewest(List<Candidate> candidates) {
		Candidate best = null;
		for (Candidate candidate : candidates) {
			if (candidate == null || candidate.document() == null) {
				continue;
			}
			if (best == null || NEWEST.compare(candidate, best) > 0) {
				best = candidate;
			}
		}
		if (best == null) {
			return Optional.empty();
		}
		best.document().setSource(best.source());
		return Optional.of(best.document());
	}

	// Whether a is strictly newer than b by revision, then schemaVersion (the source doesn't count).
	static boolean newer(RulesDocument a, RulesDocument b) {
		if (b == null) {
			return true;
		}
		return a.revision != b.revision ? a.revision > b.revision : a.schemaVersion > b.schemaVersion;
	}

	private static int sourceRank(String source) {
		if (source == null) {
			return 0;
		}
		return switch (source) {
			case SOURCE_REMOTE -> 3;
			case SOURCE_CACHE -> 2;
			case SOURCE_BUNDLED -> 1;
			default -> 0;
		};
	}

	private static boolean invalidPattern(String section, RulesDocument.PatternRule rule) {
		if (rule.compiled() != null) {
			return false;
		}
		RigTune.LOGGER.warn("Skipping {} rule with an invalid or overlong regex: {}", section, rule.pattern);
		return true;
	}
}
