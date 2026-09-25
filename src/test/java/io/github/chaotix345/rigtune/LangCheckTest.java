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
// (a) No hard-coded words in client code: a string literal with a letter inside literal(...), and in client/ui one with
//     two or more letters inside text(...) / centeredText(...) or next to + / +=. Translation keys and key prefixes are
//     exempt; anything else needs an ALLOWED entry with its reason.
// (b) Every translation key written out in client or core code (Component.translatable, Text.of, LauncherInfo's
//     tables, HistoryModel, UndoPlan problems, ...) is in en_us.json.
// (c) Every en_us.json key is used: written out in client or core code, or one of the dynamic families below, whose
//     suffixes come from the code (enums, the history model, the tier calculator) and whose prefixes the code writes.
//     A family key missing from en_us.json fails too, and every Text.of("key", "English"...) in core has the
//     en_us.json value as its English.
// (d) Every other <locale>.json is a flat object of strings with a subset of en_us.json's keys, each with the same
//     arguments (%s, %n$s) as en_us.json, and every template (en_us.json's too) is one Minecraft can format.
class LangCheckTest {
	static final String LANG_DIR = "src/main/resources/assets/rigtune/lang";

	// (a) exceptions: "<path>|<literal>" -> why the words aren't translated. None needed today.
	static final Map<String, String> ALLOWED = Map.of();

	// (b) literals shaped like keys that aren't translation keys.
	static final Map<String, String> NOT_KEYS = Map.of(
			"rigtune.json", "the file RigTune keeps its state in (ClientState)",
			"rigtune.dev.autorun", "a system property of the dev-only autorun (DevAutorun)");

	private static final Pattern KEY_SHAPED = Pattern.compile("\\.?[a-z0-9_]+(\\.[a-z0-9_]+)*\\.?");
	private static final Pattern KEY = Pattern.compile("(rigtune|key\\.rigtune)(\\.[a-z0-9_]+)+");
	private static final Pattern CALL = Pattern.compile("(?<![A-Za-z0-9_$])(literal|text|centeredText)\\s*\\(");
	private static final Pattern TEXT_OF = Pattern.compile("Text\\.of\\(\\s*$");
	private static final Pattern FORMAT = Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%]|$)");

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
		return s.contains(".") && KEY_SHAPED.matcher(s).matches();
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

	// (a) sources: path (with /, from the repository root) -> text; only src/client/java is scanned.
	static List<String> hardCodedText(Map<String, String> sources, Map<String, String> allowed) {
		List<String> out = new ArrayList<>();
		sources.forEach((path, src) -> {
			if (!path.startsWith("src/client/java/")) {
				return;
			}
			boolean ui = path.contains("/client/ui/");
			Scanned scanned = Scanned.of(src);
			Set<Literal> flagged = new LinkedHashSet<>();
			Map<Literal, String> why = new LinkedHashMap<>();
			Matcher call = CALL.matcher(scanned.masked());
			while (call.find()) {
				String name = call.group(1);
				if (!ui && !name.equals("literal")) {
					continue;
				}
				int open = call.end() - 1;
				int close = closing(scanned.masked(), open);
				int min = name.equals("literal") ? 1 : 2;
				for (Literal literal : scanned.literals()) {
					if (literal.start() > open && literal.end() <= close && letters(literal.value()) >= min && !keyShaped(literal.value()) && flagged.add(literal)) {
						why.put(literal, "in " + name + "(...)");
					}
				}
			}
			if (ui) {
				for (Literal literal : scanned.literals()) {
					String masked = scanned.masked();
					boolean plusBefore = before(masked, literal.start()) == '+' && !beforeTwo(masked, literal.start()).equals("++")
							|| beforeTwo(masked, literal.start()).equals("+=");
					boolean plusAfter = after(masked, literal.end()) == '+';
					if ((plusBefore || plusAfter) && letters(literal.value()) >= 2 && !keyShaped(literal.value()) && flagged.add(literal)) {
						why.put(literal, "joined with +");
					}
				}
			}
			for (Literal literal : flagged) {
				if (!allowed.containsKey(path + "|" + literal.value())) {
					out.add(path + ":" + scanned.line(literal.start()) + ": \"" + literal.value() + "\" " + why.get(literal));
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
		// Text.of("key", "English"...) in core: the English is en_us.json's.
		sources.forEach((path, src) -> {
			Scanned scanned = Scanned.of(src);
			List<Literal> literals = scanned.literals();
			for (int k = 0; k + 1 < literals.size(); k++) {
				Literal key = literals.get(k);
				Literal english = literals.get(k + 1);
				if (TEXT_OF.matcher(scanned.masked().substring(Math.max(0, key.start() - 40), key.start())).find()
						&& scanned.masked().substring(key.end(), english.start()).trim().equals(",")
						&& lang.containsKey(key.value()) && !lang.get(key.value()).equals(english.value())) {
					out.add(path + ":" + scanned.line(key.start()) + ": the English of " + key.value() + " isn't en_us.json's: \"" + english.value() + "\"");
				}
			}
		});
		return out;
	}

	// The arguments a template uses (%s in order, %n$s by position), or null when Minecraft can't format it.
	static Set<Integer> arguments(String template) {
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
			out.add(m.group(1) != null ? Integer.parseInt(m.group(1)) - 1 : next++);
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
				out.add("en_us.json: " + key + ": Minecraft can't format \"" + template + "\"");
			}
		});
		files.forEach((file, json) -> {
			if (file.equals("en_us.json")) {
				return;
			}
			if (!file.matches("[a-z]{2,3}_[a-z0-9]{2,3}\\.json")) {
				out.add(file + ": not a <language>_<region>.json locale file");
			}
			strings(file, json, out).forEach((key, template) -> {
				if (!english.containsKey(key)) {
					out.add(file + ": " + key + " isn't in en_us.json");
					return;
				}
				Set<Integer> args = arguments(template);
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
		out.add(new Family("rigtune.benchmark.scene.", List.of(), keys("rigtune.benchmark.scene.", Stream.of(BenchmarkRequest.Scene.values()).map(LangCheckTest::lower))));
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
				}
				""";
		List<String> found = hardCodedText(Map.of(UI, src), Map.of());
		assertEquals(List.of(UI + ":3: \"Hello\" in literal(...)", UI + ":4: \"Hi there\" in text(...)", UI + ":9: \"Unknown\" in centeredText(...)",
				UI + ":5: \" Hz\" joined with +", UI + ":6: \"GB\" joined with +"), found);
		assertEquals(List.of(UI + ":4: \"Hi there\" in text(...)", UI + ":9: \"Unknown\" in centeredText(...)", UI + ":5: \" Hz\" joined with +",
				UI + ":6: \"GB\" joined with +"), hardCodedText(Map.of(UI, src), Map.of(UI + "|Hello", "a test")));
		// Outside client/ui only literal(...) counts; core isn't scanned.
		String probe = "src/client/java/io/github/chaotix345/rigtune/client/probe/P.java";
		assertEquals(List.of(probe + ":1: \"X\" in literal(...)"),
				hardCodedText(Map.of(probe, "class P { void a() { Component.literal(\"X\"); log(\"words \" + x); } }"), Map.of()));
		assertEquals(List.of(), hardCodedText(Map.of("src/main/java/Core.java", "class C { String s = Component.literal(\"Words\"); }"), Map.of()));
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
					static final Text T = Text.of("rigtune.b", "Bee %s", x);
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
				"rigtune.dead is in en_us.json but nothing uses it", UI + ":3: the English of rigtune.b isn't en_us.json's: \"Bee %s\""),
				unusedKeys(src, lang, families).stream().sorted((x, y) -> order(x) - order(y)).toList());
	}

	private static int order(String problem) {
		return problem.startsWith("rigtune.impact") ? 0 : problem.startsWith("family") ? 1 : problem.startsWith("rigtune.dead") ? 2 : 3;
	}

	@Test
	void aPlantedLocaleProblemFails() {
		Map<String, String> files = new TreeMap<>();
		files.put("en_us.json", "{\"a\": \"%s and %s\", \"b\": \"1%% low\", \"c\": \"C\", \"d\": \"%2$s %1$s\"}");
		files.put("de_de.json", "{\"a\": \"%2$s und %1$s\", \"b\": \"1%% Tief\", \"d\": \"%s %s\"}");
		assertEquals(List.of(), localeProblems(files));
		files.put("fr_fr.json", "{\"a\": \"%s\", \"b\": \"50% bas\", \"extra\": \"x\", \"c\": 3, \"d\": \"%3$s\"}");
		files.put("notes.json", "{}");
		assertEquals(List.of("fr_fr.json: c isn't a string", "fr_fr.json: a: arguments [0] but en_us.json has [0, 1]",
				"fr_fr.json: b: Minecraft can't format \"50% bas\"", "fr_fr.json: extra isn't in en_us.json", "fr_fr.json: d: arguments [2] but en_us.json has [0, 1]",
				"notes.json: not a <language>_<region>.json locale file"), localeProblems(files));
		files.clear();
		files.put("en_us.json", "{\"a\": \"%d items\"}");
		assertEquals(List.of("en_us.json: a: Minecraft can't format \"%d items\""), localeProblems(files));
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
