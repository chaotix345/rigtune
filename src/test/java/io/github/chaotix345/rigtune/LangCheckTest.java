package io.github.chaotix345.rigtune;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.hardware.TierCalculator;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.Impact;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.3/SPEC.md item 9 (AC9.1, AC6.4; amendment G-M2): a source scan (no bytecode) that keeps every word the
// client shows in the lang files.
// (a) No hard-coded words in client code: a string literal with a letter inside literal(...) anywhere in client code,
//     and in client/ui any string literal with two or more letters (inside text(...), centeredText(...), next to +, in a
//     constant, ...). Exempt: translation keys and key prefixes/suffixes, dotted identifiers (setting keys), and the
//     arguments of LOGGER calls, regex and date-pattern calls and thread names; anything else needs an ALLOWED entry
//     with its reason.
// (b) Every translation key written out in client or core code (Component.translatable, Text.of, LauncherInfo's
//     tables, HistoryModel, UndoPlan problems, ...) is in en_us.json.
// (c) Every en_us.json key is used: written out in client or core code, or one of the dynamic families below, whose
//     suffixes come from the code (enums, the history model, the tier calculator) and whose prefixes the code writes.
//     A family key missing from en_us.json fails too.
// (e) Every Text.of(...) in client or core code writes its key out, and its English (string literals and this file's
//     String constants joined with +) is en_us.json's value for that key.
// (d) Every other <locale>.json is a flat object of strings with a subset of en_us.json's keys, each with the same
//     arguments (%s, %n$s) as en_us.json, and every template (en_us.json's too) is one Minecraft can format. Minecraft
//     reads %d / %.1f in a lang file as %s; en_us.json may not use them, since the code's English fallback isn't rewritten.
class LangCheckTest {
	static final String LANG_DIR = "src/main/resources/assets/rigtune/lang";

	// (a) exceptions: "<path>|<literal>" -> why the words aren't translated.
	private static final String SCREEN = "src/client/java/io/github/chaotix345/rigtune/client/ui/RigTuneScreen.java|";
	static final Map<String, String> ALLOWED = Map.of(
			SCREEN + "OpenGL", "the graphics API's name, shown as it is (like the GPU's own name)",
			SCREEN + "Vulkan", "the graphics API's name, shown as it is (like the GPU's own name)",
			SCREEN + "true", "an option value compared with, not shown (shown as the game's On)",
			SCREEN + "false", "an option value compared with, not shown (shown as the game's Off)");

	// (b) literals shaped like keys that aren't translation keys.
	static final Map<String, String> NOT_KEYS = Map.of(
			"rigtune.json", "the file RigTune keeps its state in (ClientState)",
			"rigtune.dev.autorun", "a system property of the dev-only autorun (DevAutorun)");

	// A key, a key prefix ("rigtune.goal."), a dotted identifier ("vanilla.renderDistance") or a key suffix (".tooltip").
	private static final Pattern KEY_SHAPED = Pattern.compile("\\.?[a-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+\\.?|\\.[a-z][a-z0-9_]*");
	private static final Pattern KEY = Pattern.compile("(rigtune|key\\.rigtune)(\\.[a-z0-9_]+)+");
	private static final Pattern CALL = Pattern.compile("(?<![A-Za-z0-9_$])(literal|text|centeredText)\\s*\\(");
	// Calls whose string arguments are never shown: log lines, regular expressions, date patterns, thread names.
	private static final Pattern NOT_SHOWN = Pattern.compile(
			"(?<![A-Za-z0-9_$])(LOGGER\\.[a-z]+|replaceAll|replaceFirst|matches|split|compile|ofPattern|Thread)\\s*\\(");
	private static final Pattern TEXT_OF_CALL = Pattern.compile("(?<![A-Za-z0-9_$.])(Text\\.of)\\(");
	private static final Pattern FORMAT = Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%]|$)");
	// net.minecraft.locale.Language.loadFromJson's rewrite (26.2 and 26.3, javap).
	private static final Pattern LANG_NUMBER = Pattern.compile("%(\\d+\\$)?[\\d.]*[df]");

	// A string literal in a source file: where it is and its value.
	record Literal(int start, int end, String value) {
	}

	// A source with its comments blanked and its string contents blanked (same length), and its string literals.
	record Scanned(String masked, List<Literal> literals) {
		static Scanned of(String src) {
			StringBuilder masked = new StringBuilder(src);
			List<Literal> literals = new ArrayList<>();
			int n = src.length();
			int i = 0;
			while (i < n) {
				char c = src.charAt(i);
				if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
					int end = src.indexOf('\n', i);
					end = end < 0 ? n : end;
					blank(masked, i, end);
					i = end;
				} else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
					int end = src.indexOf("*/", i + 2);
					end = end < 0 ? n : end + 2;
					blank(masked, i, end);
					i = end;
				} else if (src.startsWith("\"\"\"", i)) {
					int end = src.indexOf("\"\"\"", i + 3);
					end = end < 0 ? n : end + 3;
					literals.add(new Literal(i, end, src.substring(Math.min(src.indexOf('\n', i) + 1, end - 3), end - 3)));
					blank(masked, i + 3, end - 3);
					i = end;
				} else if (c == '"' || c == '\'') {
					int j = i + 1;
					while (j < n && src.charAt(j) != c && src.charAt(j) != '\n') {
						j += src.charAt(j) == '\\' ? 2 : 1;
					}
					if (c == '"') {
						literals.add(new Literal(i, j + 1, unescape(src.substring(i + 1, Math.min(j, n)))));
					}
					blank(masked, i + 1, Math.min(j, n));
					i = j + 1;
				} else {
					i++;
				}
			}
			return new Scanned(masked.toString(), literals);
		}

		private static void blank(StringBuilder s, int from, int to) {
			for (int k = from; k < to; k++) {
				if (s.charAt(k) != '\n') {
					s.setCharAt(k, ' ');
				}
			}
		}

		private static String unescape(String s) {
			StringBuilder out = new StringBuilder();
			for (int k = 0; k < s.length(); k++) {
				char c = s.charAt(k);
				if (c != '\\' || k + 1 >= s.length()) {
					out.append(c);
					continue;
				}
				char e = s.charAt(++k);
				switch (e) {
					case 'n' -> out.append('\n');
					case 't' -> out.append('\t');
					case 'u' -> {
						out.append((char) Integer.parseInt(s.substring(k + 1, k + 5), 16));
						k += 4;
					}
					default -> out.append(e);
				}
			}
			return out.toString();
		}

		int line(int pos) {
			int line = 1;
			for (int k = 0; k < pos && k < masked.length(); k++) {
				if (masked.charAt(k) == '\n') {
					line++;
				}
			}
			return line;
		}
	}

	private static int letters(String s) {
		return (int) s.chars().filter(Character::isLetter).count();
	}

	private static boolean keyShaped(String s) {
		return KEY_SHAPED.matcher(s).matches();
	}

	private static int closing(String masked, int open) {
		int depth = 0;
		for (int k = open; k < masked.length(); k++) {
			char c = masked.charAt(k);
			if (c == '(') {
				depth++;
			} else if (c == ')' && --depth == 0) {
				return k;
			}
		}
		return masked.length();
	}

	private static char before(String masked, int pos) {
		int k = pos - 1;
		while (k >= 0 && Character.isWhitespace(masked.charAt(k))) {
			k--;
		}
		return k < 0 ? 0 : masked.charAt(k);
	}

	private static String beforeTwo(String masked, int pos) {
		int k = pos - 1;
		while (k >= 0 && Character.isWhitespace(masked.charAt(k))) {
			k--;
		}
		return k < 1 ? "" : masked.substring(k - 1, k + 1);
	}

	private static char after(String masked, int pos) {
		int k = pos;
		while (k < masked.length() && Character.isWhitespace(masked.charAt(k))) {
			k++;
		}
		return k >= masked.length() ? 0 : masked.charAt(k);
	}

	// The ranges (open paren, close paren) of the calls `pattern` finds.
	private static List<int[]> calls(String masked, Pattern pattern) {
		List<int[]> out = new ArrayList<>();
		Matcher m = pattern.matcher(masked);
		while (m.find()) {
			int open = m.end() - 1;
			out.add(new int[]{open, closing(masked, open), m.start(1)});
		}
		return out;
	}

	// The name of the innermost call of `found` around the literal, or null.
	private static String inside(Scanned scanned, List<int[]> found, Literal literal) {
		// found: {open paren, close paren, start of the call's name}
		int[] best = null;
		for (int[] call : found) {
			if (call[0] < literal.start() && literal.end() <= call[1] && (best == null || call[0] > best[0])) {
				best = call;
			}
		}
		return best == null ? null : scanned.masked().substring(best[2], best[0]).trim();
	}

	// (a) sources: path (with /, from the repository root) -> text; only src/client/java is scanned.
	static List<String> hardCodedText(Map<String, String> sources, Map<String, String> allowed) {
		List<String> out = new ArrayList<>();
		sources.forEach((path, src) -> {
			if (!path.startsWith("src/client/java/")) {
				return;
			}
			boolean ui = path.contains("/client/ui/");
			Scanned scanned = Scanned.of(src);
			String masked = scanned.masked();
			List<int[]> shown = calls(masked, CALL);
			List<int[]> notShown = calls(masked, NOT_SHOWN);
			for (Literal literal : scanned.literals()) {
				int letters = letters(literal.value());
				if (letters == 0 || keyShaped(literal.value()) || inside(scanned, notShown, literal) != null) {
					continue;
				}
				String call = inside(scanned, shown, literal);
				String why;
				if ("literal".equals(call)) {
					why = "in literal(...)";
				} else if (!ui || letters < 2) {
					continue;
				} else if (call != null) {
					why = "in " + call + "(...)";
				} else if (before(masked, literal.start()) == '+' && !beforeTwo(masked, literal.start()).equals("++")
						|| beforeTwo(masked, literal.start()).equals("+=") || after(masked, literal.end()) == '+') {
					why = "joined with +";
				} else {
					why = "in client/ui code";
				}
				if (!allowed.containsKey(path + "|" + literal.value())) {
					out.add(path + ":" + scanned.line(literal.start()) + ": \"" + literal.value() + "\" " + why);
				}
			}
		});
		return out;
	}

	// Every literal shaped like a RigTune key, with where it is.
	static Map<String, String> keyLiterals(Map<String, String> sources) {
		Map<String, String> out = new TreeMap<>();
		sources.forEach((path, src) -> {
			Scanned scanned = Scanned.of(src);
			for (Literal literal : scanned.literals()) {
				if (KEY.matcher(literal.value()).matches()) {
					out.putIfAbsent(literal.value(), path + ":" + scanned.line(literal.start()));
				}
			}
		});
		return out;
	}

	// (b)
	static List<String> missingKeys(Map<String, String> sources, Set<String> lang, Map<String, String> notKeys) {
		List<String> out = new ArrayList<>();
		keyLiterals(sources).forEach((key, where) -> {
			if (!lang.contains(key) && !notKeys.containsKey(key)) {
				out.add(where + ": \"" + key + "\" isn't in en_us.json");
			}
		});
		return out;
	}

	// A dynamic key family: the code writes `prefix` (and each of `suffixes`) and adds a value from the code.
	record Family(String prefix, List<String> suffixes, Set<String> keys) {
	}

	// (c)
	static List<String> unusedKeys(Map<String, String> sources, Map<String, String> lang, List<Family> families) {
		List<String> out = new ArrayList<>();
		Set<String> written = new TreeSet<>();
		sources.values().forEach(src -> Scanned.of(src).literals().forEach(l -> written.add(l.value())));
		Set<String> dynamic = new TreeSet<>();
		for (Family family : families) {
			Stream.concat(Stream.of(family.prefix()), family.suffixes().stream())
					.filter(literal -> !written.contains(literal))
					.forEach(literal -> out.add("family " + family.prefix() + "*: the code doesn't write \"" + literal + "\""));
			for (String key : family.keys()) {
				dynamic.add(key);
				if (!lang.containsKey(key)) {
					out.add(key + " (family " + family.prefix() + "*) isn't in en_us.json");
				}
			}
		}
		Set<String> used = keyLiterals(sources).keySet();
		for (String key : lang.keySet()) {
			if (!used.contains(key) && !dynamic.contains(key)) {
				out.add(key + " is in en_us.json but nothing uses it");
			}
		}
		return out;
	}

	// The top-level arguments of the call whose parenthesis opens at `open`, as ranges.
	private static List<int[]> arguments(String masked, int open, int close) {
		List<int[]> out = new ArrayList<>();
		int depth = 0;
		int from = open + 1;
		for (int k = open + 1; k < close; k++) {
			char c = masked.charAt(k);
			if (c == '(' || c == '[' || c == '{') {
				depth++;
			} else if (c == ')' || c == ']' || c == '}') {
				depth--;
			} else if (c == ',' && depth == 0) {
				out.add(new int[]{from, k});
				from = k + 1;
			}
		}
		out.add(new int[]{from, close});
		return out;
	}

	// The string an expression of literals and this file's `static final String` constants joined with + stands for,
	// or null when it's anything else.
	private static String constant(Scanned scanned, int from, int to, int depth) {
		if (depth > 5) {
			return null;
		}
		StringBuilder out = new StringBuilder();
		String masked = scanned.masked();
		int at = from;
		while (at < to) {
			int plus = at;
			while (plus < to && masked.charAt(plus) != '+') {
				plus++;
			}
			int start = at;
			int end = plus;
			while (start < end && Character.isWhitespace(masked.charAt(start))) {
				start++;
			}
			while (end > start && Character.isWhitespace(masked.charAt(end - 1))) {
				end--;
			}
			String piece = null;
			for (Literal literal : scanned.literals()) {
				if (literal.start() == start && literal.end() == end) {
					piece = literal.value();
				}
			}
			String name = masked.substring(start, end);
			if (piece == null && name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
				Matcher definition = Pattern.compile("static\\s+final\\s+String\\s+" + name + "\\s*=").matcher(masked);
				if (definition.find()) {
					piece = constant(scanned, definition.end(), masked.indexOf(';', definition.end()), depth + 1);
				}
			}
			if (piece == null) {
				return null;
			}
			out.append(piece);
			at = plus + 1;
		}
		return out.toString();
	}

	// (e)
	static List<String> textOfEnglish(Map<String, String> sources, Map<String, String> lang) {
		List<String> out = new ArrayList<>();
		sources.forEach((path, src) -> {
			Scanned scanned = Scanned.of(src);
			String masked = scanned.masked();
			for (int[] call : calls(masked, TEXT_OF_CALL)) {
				String where = path + ":" + scanned.line(call[0]);
				List<int[]> args = arguments(masked, call[0], call[1]);
				String key = args.isEmpty() ? null : constant(scanned, args.get(0)[0], args.get(0)[1], 5);
				if (key == null || args.size() < 2) {
					out.add(where + ": Text.of needs its key written out and its English next to it");
					continue;
				}
				String english = constant(scanned, args.get(1)[0], args.get(1)[1], 0);
				if (english == null) {
					out.add(where + ": can't work out the English of " + key + " (use literals and String constants)");
				} else if (lang.containsKey(key) && !lang.get(key).equals(english)) {
					out.add(where + ": the English of " + key + " isn't en_us.json's: \"" + english + "\"");
				}
			}
		});
		return out;
	}

	// The arguments a template uses (%s in order, %n$s by position), or null when Minecraft can't format it.
	static Set<Integer> arguments(String template) {
		return arguments(template, false);
	}

	// langFile: read as Minecraft reads a lang file, which turns %d, %.1f, %2$d into %s / %2$s first.
	static Set<Integer> arguments(String template, boolean langFile) {
		if (langFile) {
			template = LANG_NUMBER.matcher(template).replaceAll("%$1s");
		}
		Set<Integer> out = new TreeSet<>();
		Matcher m = FORMAT.matcher(template);
		int next = 0;
		int at = 0;
		while (m.find(at)) {
			if (template.substring(at, m.start()).indexOf('%') >= 0) {
				return null;
			}
			if ("%".equals(m.group(2)) && "%%".equals(m.group())) {
				at = m.end();
				continue;
			}
			if (!"s".equals(m.group(2))) {
				return null;
			}
			try {
				out.add(m.group(1) != null ? Integer.parseInt(m.group(1)) - 1 : next++);
			} catch (NumberFormatException e) {
				return null;
			}
			at = m.end();
		}
		return template.substring(at).indexOf('%') >= 0 ? null : out;
	}

	// (d) locales: file name -> its JSON text (en_us.json included).
	static List<String> localeProblems(Map<String, String> files) {
		List<String> out = new ArrayList<>();
		Map<String, String> english = strings("en_us.json", files.get("en_us.json"), out);
		english.forEach((key, template) -> {
			if (arguments(template) == null) {
				out.add("en_us.json: " + key + ": use %s for every argument (the code's English can't format \"" + template + "\")");
			}
		});
		files.forEach((file, json) -> {
			if (file.equals("en_us.json")) {
				return;
			}
			if (!file.matches("[a-z]{2,4}(_[a-z0-9]{2,4})?\\.json")) {
				out.add(file + ": not a Minecraft language code (<language>_<region>.json)");
			}
			strings(file, json, out).forEach((key, template) -> {
				if (!english.containsKey(key)) {
					out.add(file + ": " + key + " isn't in en_us.json");
					return;
				}
				Set<Integer> args = arguments(template, true);
				if (args == null) {
					out.add(file + ": " + key + ": Minecraft can't format \"" + template + "\"");
				} else if (!args.equals(arguments(english.get(key)))) {
					out.add(file + ": " + key + ": arguments " + args + " but en_us.json has " + arguments(english.get(key)));
				}
			});
		});
		return out;
	}

	private static Map<String, String> strings(String file, String json, List<String> problems) {
		Map<String, String> out = new LinkedHashMap<>();
		JsonElement root;
		try {
			root = JsonParser.parseString(json);
		} catch (RuntimeException e) {
			problems.add(file + ": not JSON: " + e.getMessage());
			return out;
		}
		if (!root.isJsonObject()) {
			problems.add(file + ": not a JSON object");
			return out;
		}
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
			if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isString()) {
				out.put(e.getKey(), e.getValue().getAsString());
			} else {
				problems.add(file + ": " + e.getKey() + " isn't a string");
			}
		}
		return out;
	}

	// --- the repository

	static Map<String, String> sources() throws IOException {
		Map<String, String> out = new TreeMap<>();
		Path root = RepoFiles.root();
		for (String dir : List.of("src/client/java", "src/main/java")) {
			try (Stream<Path> files = Files.walk(root.resolve(dir))) {
				for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
					out.put(root.relativize(file).toString().replace('\\', '/'), Files.readString(file));
				}
			}
		}
		return out;
	}

	static Map<String, String> langFiles() throws IOException {
		Map<String, String> out = new TreeMap<>();
		try (Stream<Path> files = Files.list(RepoFiles.resolve(LANG_DIR))) {
			for (Path file : files.toList()) {
				out.put(file.getFileName().toString(), Files.readString(file));
			}
		}
		return out;
	}

	static Map<String, String> english() throws IOException {
		Map<String, String> out = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> e : JsonParser.parseString(langFiles().get("en_us.json")).getAsJsonObject().entrySet()) {
			out.put(e.getKey(), e.getValue().getAsString());
		}
		return out;
	}

	private static String lower(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT);
	}

	// The keys the code builds at run time, with the values taken from the code itself.
	static List<Family> families() {
		List<Family> out = new ArrayList<>();
		Set<String> goals = new TreeSet<>();
		for (Goal goal : Goal.values()) {
			goals.add("rigtune.goal." + lower(goal));
			goals.add("rigtune.goal." + lower(goal) + ".tooltip");
		}
		out.add(new Family("rigtune.goal.", List.of(".tooltip"), goals));
		out.add(new Family("rigtune.category.", List.of(), keys("rigtune.category.", Stream.of(Category.values()).map(LangCheckTest::lower))));
		out.add(new Family("rigtune.impact.", List.of(), keys("rigtune.impact.", Stream.of(Impact.values()).map(LangCheckTest::lower))));
		// TierResult.limitingFactor: whatever TierCalculator can answer, over every tier and goal.
		Set<String> limits = new TreeSet<>();
		for (int gpu = 0; gpu <= 5; gpu++) {
			for (int cpu = 0; cpu <= 5; cpu++) {
				for (int mem = 0; mem <= 5; mem++) {
					for (Goal goal : Goal.values()) {
						limits.add(TierCalculator.calculate(gpu, cpu, mem, goal).limitingFactor());
					}
				}
			}
		}
		out.add(new Family("rigtune.limit.", List.of(), keys("rigtune.limit.", limits.stream())));
		out.add(new Family("rigtune.benchmark.scene.", List.of(),
				keys("rigtune.benchmark.scene.", Stream.of(BenchmarkRequest.Scene.values()).map(LangCheckTest::lower))));
		out.add(new Family("rigtune.benchmark.menu.scene.", List.of(".hint"),
				keys("rigtune.benchmark.menu.scene.", Stream.of(BenchmarkRequest.Scene.values()).map(s -> lower(s) + ".hint"))));
		// HistoryModel.kindKey/statusKey build rigtune.history.kind.* / .status.* from its PREFIX.
		Set<String> history = new TreeSet<>();
		for (String kind : List.of(JournalEntry.APPLY, JournalEntry.BENCHMARK, JournalEntry.UNDO, JournalEntry.LEGACY_IMPORT, "?")) {
			history.add(HistoryModel.kindKey(kind));
		}
		for (String status : List.of(JournalChange.APPLIED, JournalChange.STAGED, JournalChange.ABANDONED, JournalChange.DISCARDED, JournalChange.REVERTED, "?")) {
			history.add(HistoryModel.statusKey(status));
		}
		out.add(new Family("rigtune.history.", List.of("status.", "kind."), history));
		// BenchmarkController's end-of-run toasts: `key + ".title"` / `key + ".body"` for its cancelled and throttled keys.
		out.add(new Family("rigtune.benchmark.cancelled", List.of("rigtune.benchmark.throttled", ".title", ".body"),
				Set.of("rigtune.benchmark.cancelled.title", "rigtune.benchmark.cancelled.body", "rigtune.benchmark.throttled.title",
						"rigtune.benchmark.throttled.body")));
		// Minecraft names a key-mapping category key.category.<namespace>.<path>: RigTuneClient registers (MOD_ID, "rigtune").
		out.add(new Family("rigtune", List.of(), Set.of("key.category." + RigTune.MOD_ID + ".rigtune")));
		return out;
	}

	private static Set<String> keys(String prefix, Stream<String> suffixes) {
		Set<String> out = new TreeSet<>();
		suffixes.forEach(s -> out.add(prefix + s));
		return out;
	}

	@Test
	void noHardCodedTextInClientCode() throws IOException {
		assertEquals(List.of(), hardCodedText(sources(), ALLOWED));
	}

	@Test
	void everyKeyTheCodeWritesIsInEnUs() throws IOException {
		assertEquals(List.of(), missingKeys(sources(), english().keySet(), NOT_KEYS));
	}

	@Test
	void everyEnUsKeyIsUsed() throws IOException {
		assertTrue(Files.readString(RepoFiles.resolve("src/client/java/io/github/chaotix345/rigtune/client/RigTuneClient.java"))
				.contains("KeyMapping.Category.register(Identifier.fromNamespaceAndPath(RigTune.MOD_ID, \"rigtune\"))"));
		assertEquals(List.of(), unusedKeys(sources(), english(), families()));
	}

	@Test
	void everyTextOfHasEnUsEnglish() throws IOException {
		assertEquals(List.of(), textOfEnglish(sources(), english()));
	}

	@Test
	void everyLocaleFileMatchesEnUs() throws IOException {
		assertEquals(List.of(), localeProblems(langFiles()));
	}

	// --- self-test (AC9.1): each check fails on a planted problem

	private static final String UI = "src/client/java/io/github/chaotix345/rigtune/client/ui/Planted.java";

	@Test
	void aPlantedLiteralFails() {
		String src = """
				class Planted {
					// Component.literal("Commented out")
					void a() { Component.literal("Hello"); }
					void b() { graphics.text(font, "Hi there", 0, 0, 0); }
					void c() { String s = refresh + " Hz"; }
					void d() { s += "GB"; }
					void e() { Component.literal(" · " + count); graphics.text(font, Component.translatable("rigtune.x"), 0, 0, 0); }
					void f() { Component.translatable("rigtune.goal." + goal + ".tooltip"); String x = "a" + "b"; }
					void g() { graphics.centeredText(font, value == null ? "?" : "Unknown", 0, 0, 0); }
					static final String LABEL = "Words in a constant";
					void h() { RigTune.LOGGER.warn("Could not do {}", x); s.replaceAll("(?i)Core Processor", ""); new Thread(r, "RigTune worker"); }
					void i() { set("vanilla.renderDistance"); graphics.text(font, "OK", 0, 0, 0); }
				}
				""";
		List<String> all = List.of(UI + ":3: \"Hello\" in literal(...)", UI + ":4: \"Hi there\" in text(...)", UI + ":5: \" Hz\" joined with +",
				UI + ":6: \"GB\" joined with +", UI + ":9: \"Unknown\" in centeredText(...)", UI + ":10: \"Words in a constant\" in client/ui code",
				UI + ":12: \"OK\" in text(...)");
		assertEquals(all, hardCodedText(Map.of(UI, src), Map.of()));
		assertEquals(all.subList(1, all.size()), hardCodedText(Map.of(UI, src), Map.of(UI + "|Hello", "a test")));
		// Outside client/ui only literal(...) counts; core isn't scanned.
		String probe = "src/client/java/io/github/chaotix345/rigtune/client/probe/P.java";
		assertEquals(List.of(probe + ":1: \"X\" in literal(...)"),
				hardCodedText(Map.of(probe, "class P { void a() { Component.literal(\"X\"); log(\"words \" + x); } }"), Map.of()));
		assertEquals(List.of(), hardCodedText(Map.of("src/main/java/Core.java", "class C { String s = Component.literal(\"Words\"); }"), Map.of()));
	}

	@Test
	void aPlantedTextOfProblemFails() {
		String core = "src/main/java/io/github/chaotix345/rigtune/core/Planted.java";
		Map<String, String> src = Map.of(core, """
				class Planted {
					static final String NOTE = "(alpha build)";
					static final String OUTSIDE = "It isn't here, so";
					static final Text A = Text.of("rigtune.a", NOTE);
					static final Text B = Text.of("rigtune.b", OUTSIDE + " remove it.");
					static final Text C = Text.of("rigtune.c", "Wrong %s", x);
					static final Text D = Text.of(key, "Anything");
					static final Text E = Text.of("rigtune.e", english());
					static final Text F = Text.of("rigtune.f", "Right, %s", Text.of("rigtune.a", NOTE));
				}
				""");
		Map<String, String> lang = Map.of("rigtune.a", "(alpha build)", "rigtune.b", "It isn't here, so remove it.", "rigtune.c", "Right %s",
				"rigtune.e", "E", "rigtune.f", "Right, %s");
		assertEquals(List.of(core + ":6: the English of rigtune.c isn't en_us.json's: \"Wrong %s\"",
				core + ":7: Text.of needs its key written out and its English next to it",
				core + ":8: can't work out the English of rigtune.e (use literals and String constants)"), textOfEnglish(src, lang));
	}

	@Test
	void aPlantedMissingKeyFails() {
		Map<String, String> src = Map.of(UI, """
				class Planted {
					void a() { Component.translatable("rigtune.screen.title"); Component.translatable("rigtune.nope"); }
					static final String FILE = "rigtune.json";
					static final String PREFIX = "rigtune.goal.";
				}
				""");
		assertEquals(List.of(UI + ":2: \"rigtune.nope\" isn't in en_us.json"), missingKeys(src, Set.of("rigtune.screen.title"), NOT_KEYS));
	}

	@Test
	void aPlantedUnusedKeyAndABadFamilyFail() {
		Map<String, String> src = Map.of(UI, """
				class Planted {
					void a() { Component.translatable("rigtune.a"); Component.translatable("rigtune.impact." + i); }
					static final Text T = Text.of("rigtune.b", "B %s", x);
				}
				""");
		Map<String, String> lang = new LinkedHashMap<>();
		lang.put("rigtune.a", "A");
		lang.put("rigtune.b", "B %s");
		lang.put("rigtune.impact.low", "Low");
		lang.put("rigtune.dead", "Dead");
		List<Family> families = List.of(new Family("rigtune.impact.", List.of(), Set.of("rigtune.impact.low", "rigtune.impact.high")),
				new Family("rigtune.goal.", List.of(), Set.of()));
		assertEquals(List.of("rigtune.impact.high (family rigtune.impact.*) isn't in en_us.json", "family rigtune.goal.*: the code doesn't write \"rigtune.goal.\"",
				"rigtune.dead is in en_us.json but nothing uses it"), unusedKeys(src, lang, families));
	}

	@Test
	void aPlantedLocaleProblemFails() {
		Map<String, String> files = new TreeMap<>();
		files.put("en_us.json", "{\"a\": \"%s and %s\", \"b\": \"1%% low\", \"c\": \"C %s\", \"d\": \"%2$s %1$s\"}");
		files.put("de_de.json", "{\"a\": \"%2$s und %1$s\", \"b\": \"1%% Tief\", \"c\": \"C %.1f\", \"d\": \"%s %s\"}");
		files.put("lzh.json", "{\"b\": \"low\"}");
		files.put("zlm_arab.json", "{}");
		assertEquals(List.of(), localeProblems(files));
		files.put("fr_fr.json", "{\"a\": \"%s\", \"b\": \"50% bas\", \"extra\": \"x\", \"c\": 3, \"d\": \"%3$s\"}");
		files.put("notes.json", "{}");
		assertEquals(List.of("fr_fr.json: c isn't a string", "fr_fr.json: a: arguments [0] but en_us.json has [0, 1]",
				"fr_fr.json: b: Minecraft can't format \"50% bas\"", "fr_fr.json: extra isn't in en_us.json", "fr_fr.json: d: arguments [2] but en_us.json has [0, 1]",
				"notes.json: not a Minecraft language code (<language>_<region>.json)"), localeProblems(files));
		files.clear();
		files.put("en_us.json", "{\"a\": \"%d items\", \"b\": \"%99999999999$s\"}");
		assertEquals(List.of("en_us.json: a: use %s for every argument (the code's English can't format \"%d items\")",
				"en_us.json: b: use %s for every argument (the code's English can't format \"%99999999999$s\")"), localeProblems(files));
	}

	@Test
	void theKeyFamiliesComeFromTheCode() {
		Map<String, Set<String>> byPrefix = new TreeMap<>();
		families().forEach(f -> byPrefix.put(f.prefix(), f.keys()));
		assertEquals(Set.of("rigtune.limit.cpu", "rigtune.limit.gpu", "rigtune.limit.mem"), byPrefix.get("rigtune.limit."));
		assertTrue(byPrefix.get("rigtune.history.").contains("rigtune.history.status.unknown"));
		assertEquals(Category.values().length, byPrefix.get("rigtune.category.").size());
	}
}
