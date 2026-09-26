package io.github.chaotix345.rigtune.core.footprint;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

// tools/footprint-budgets.json (docs/v0.4/SPEC.md 10): the footprint guard's limits, read by FrameHookBudgetTest (JUnit)
// and FootprintGameTest (every CI game-test leg). Each budget has a limit and, where the SPEC sets one, a ceiling the limit
// may never exceed. mode "warn" logs a value past its limit; "fail" fails the test. A metric that wasn't measured (null)
// is skipped.
public final class FootprintBudgets {
	public static final String PROPERTY = "rigtune.footprint.budgetsFile";
	public static final String REPO_PATH = "tools/footprint-budgets.json";
	// Budgets on shallow object sizes (a class histogram's bytes). Without compressed oops (ZGC always; any heap over 32 GB)
	// every reference is 8 bytes instead of 4 and object headers grow, so the same objects measure up to about twice as big
	// (P5-A F5: 120,224 bytes under ZGC for what G1 runs measure at about 72 KB, with no instance growth).
	public static final Set<String> SHALLOW_SIZE_KEYS = Set.of("rigtuneClassBytesIdle");
	public static final double UNCOMPRESSED_OOPS_FACTOR = 2;

	public enum Mode { WARN, FAIL }

	public record Budget(String key, double limit, @Nullable Double ceiling, String what) {
	}

	public record Violation(String key, double value, double limit, String what) {
		public String message() {
			return String.format(Locale.ROOT, "footprint budget %s: %s > %s (%s)", key, number(value), number(limit), what);
		}
	}

	private final Mode mode;
	private final Map<String, Budget> budgets;

	private FootprintBudgets(Mode mode, Map<String, Budget> budgets) {
		this.mode = mode;
		this.budgets = Collections.unmodifiableMap(budgets);
	}

	public Mode mode() {
		return mode;
	}

	public Map<String, Budget> budgets() {
		return budgets;
	}

	// These budgets for a JVM with or without compressed oops (HotSpot's UseCompressedOops): without, each shallow-size
	// limit doubles, never past its ceiling. Every other budget, the leak checks included, is unchanged.
	public FootprintBudgets forCompressedOops(boolean compressedOops) {
		if (compressedOops) {
			return this;
		}
		Map<String, Budget> scaled = new LinkedHashMap<>(budgets);
		for (String key : SHALLOW_SIZE_KEYS) {
			Budget b = scaled.get(key);
			if (b != null) {
				double limit = b.limit() * UNCOMPRESSED_OOPS_FACTOR;
				scaled.put(key, new Budget(key, b.ceiling() == null ? limit : Math.min(limit, b.ceiling()), b.ceiling(), b.what() + " (doubled: no compressed oops)"));
			}
		}
		return new FootprintBudgets(mode, scaled);
	}

	// -Drigtune.footprint.budgetsFile (set by build.gradle), else tools/footprint-budgets.json above the working directory.
	public static Path locate() {
		String property = System.getProperty(PROPERTY);
		if (property != null && !property.isBlank()) {
			return Path.of(property);
		}
		for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
			Path candidate = dir.resolve(REPO_PATH);
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
		}
		throw new IllegalStateException("No " + REPO_PATH + " above " + Path.of("").toAbsolutePath() + " and no -D" + PROPERTY);
	}

	public static FootprintBudgets load() throws IOException {
		return load(locate());
	}

	public static FootprintBudgets load(Path file) throws IOException {
		return parse(Files.readString(file, StandardCharsets.UTF_8));
	}

	// Strict: an unknown mode, a missing or negative limit, or a limit above its ceiling is an error.
	public static FootprintBudgets parse(String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		Mode mode = switch (root.get("mode").getAsString()) {
			case "warn" -> Mode.WARN;
			case "fail" -> Mode.FAIL;
			default -> throw new IllegalArgumentException("mode must be warn or fail: " + root.get("mode"));
		};
		Map<String, Budget> budgets = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("budgets").entrySet()) {
			JsonObject b = entry.getValue().getAsJsonObject();
			double limit = b.get("limit").getAsDouble();
			Double ceiling = b.has("ceiling") && !b.get("ceiling").isJsonNull() ? b.get("ceiling").getAsDouble() : null;
			String what = b.has("what") ? b.get("what").getAsString() : entry.getKey();
			if (limit < 0 || ceiling != null && limit > ceiling) {
				throw new IllegalArgumentException(entry.getKey() + ": limit " + limit + " must be >= 0 and <= its ceiling " + ceiling);
			}
			budgets.put(entry.getKey(), new Budget(entry.getKey(), limit, ceiling, what));
		}
		return new FootprintBudgets(mode, budgets);
	}

	// Every measured value (null = not measured) above its budget's limit; keys without a budget are ignored.
	public List<Violation> check(Map<String, ? extends @Nullable Number> measured) {
		List<Violation> out = new ArrayList<>();
		for (Budget budget : budgets.values()) {
			Number value = measured.get(budget.key());
			if (value != null && value.doubleValue() > budget.limit()) {
				out.add(new Violation(budget.key(), value.doubleValue(), budget.limit(), budget.what()));
			}
		}
		return out;
	}

	// FAIL: one AssertionError naming every violation. WARN: each goes to warn.
	public void enforce(List<Violation> violations, Consumer<String> warn) {
		if (violations.isEmpty()) {
			return;
		}
		if (mode == Mode.FAIL) {
			throw new AssertionError(String.join("; ", violations.stream().map(Violation::message).toList()));
		}
		violations.forEach(v -> warn.accept("WARN-ONLY " + v.message()));
	}

	private static String number(double value) {
		return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value) : String.format(Locale.ROOT, "%.2f", value);
	}
}
