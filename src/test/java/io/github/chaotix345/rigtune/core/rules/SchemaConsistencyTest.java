package io.github.chaotix345.rigtune.core.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.RepoFiles;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * tools/update_rules.py keeps its own copy of the schema (condition keys, rule fields, value vocabularies) to build and
 * check rules-v1.json. This compares it with the Java classes: the v1 sets with the pinned v0.1.0 copy, the v2 sets with
 * the current code. Runs Python; skipped locally when there is none, required in CI.
 */
class SchemaConsistencyTest {
	private static final String DUMP = """
			import json, sys
			sys.path.insert(0, "tools")
			import update_rules as u
			def plain(v):
			    if isinstance(v, dict):
			        return {k: plain(x) for k, x in v.items()}
			    if isinstance(v, (set, frozenset, tuple, list)):
			        return sorted(v)
			    return v
			print(json.dumps(plain({
			    "v1ConditionKeys": u.V1_CONDITION_KEYS, "v2ConditionKeys": u.V2_CONDITION_KEYS,
			    "stutterConditionKeys": u.STUTTER_CONDITION_KEYS,
			    "booleanKeys": u.BOOLEAN_CONDITION_KEYS, "int32Keys": u.INT32_CONDITION_KEYS,
			    "listKeys": u.LIST_CONDITION_KEYS, "stringKeys": u.STRING_CONDITION_KEYS,
			    "v1RuleFields": u.V1_RULE_FIELDS, "v2OnlyRuleFields": u.V2_ONLY_RULE_FIELDS,
			    "v1Vocabularies": u.V1_VOCABULARIES, "v2Vocabularies": u.V2_VOCABULARIES,
			    "sodiumWorkaroundFlag": u.SODIUM_WORKAROUND_FLAG, "maxPatternLength": u.MAX_PATTERN_LENGTH,
			    "sourceOnlyTierFields": u.SOURCE_ONLY_TIER_FIELDS,
			})))
			""";
	private static final Map<String, Class<?>> V1_RULES = Map.of(
			"mods", io.github.chaotix345.rigtune.v010.core.rules.RulesDocument.ModRule.class,
			"obsolete", io.github.chaotix345.rigtune.v010.core.rules.RulesDocument.ObsoleteRule.class,
			"settings", io.github.chaotix345.rigtune.v010.core.rules.RulesDocument.SettingRule.class,
			"advice", io.github.chaotix345.rigtune.v010.core.rules.RulesDocument.AdviceRule.class,
			"gpuTiers", io.github.chaotix345.rigtune.v010.core.rules.RulesDocument.GpuTierRule.class,
			"cpuTiers", io.github.chaotix345.rigtune.v010.core.rules.RulesDocument.CpuTierRule.class,
			"heapTiers", io.github.chaotix345.rigtune.v010.core.rules.RulesDocument.HeapTierRule.class);
	private static final Map<String, Class<?>> V2_RULES = Map.of(
			"mods", RulesDocument.ModRule.class,
			"obsolete", RulesDocument.ObsoleteRule.class,
			"settings", RulesDocument.SettingRule.class,
			"advice", RulesDocument.AdviceRule.class,
			"gpuTiers", RulesDocument.GpuTierRule.class,
			"cpuTiers", RulesDocument.CpuTierRule.class,
			"heapTiers", RulesDocument.HeapTierRule.class);

	private static JsonObject python;

	@BeforeAll
	static void dumpPythonSchema() throws IOException, InterruptedException {
		for (String executable : List.of("python3", "python")) {
			String out = run(null, executable, "--version");
			if (out != null && out.startsWith("Python 3")) {
				String dump = run(DUMP, executable, "-");
				Assertions.assertNotNull(dump, "tools/update_rules.py couldn't be imported with " + executable);
				python = JsonParser.parseString(dump).getAsJsonObject();
				return;
			}
		}
		Assertions.assertNull(System.getenv("CI"), "Python 3 is required for this test in CI");
		Assumptions.abort("No Python 3 on PATH; skipping the Python/Java schema comparison");
	}

	// The script goes in on stdin: Windows mangles quotes in command-line arguments.
	private static String run(String stdin, String... command) throws IOException, InterruptedException {
		Process process;
		try {
			process = new ProcessBuilder(command).directory(RepoFiles.root().toFile()).redirectErrorStream(true).start();
		} catch (IOException e) {
			return null;
		}
		try (var in = process.getOutputStream()) {
			if (stdin != null) {
				in.write(stdin.getBytes(StandardCharsets.UTF_8));
			}
		}
		byte[] out = process.getInputStream().readAllBytes();
		if (!process.waitFor(60, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			return null;
		}
		return process.exitValue() == 0 ? new String(out, StandardCharsets.UTF_8).trim() : null;
	}

	private static Set<String> strings(JsonElement array) {
		Set<String> out = new TreeSet<>();
		for (JsonElement e : (JsonArray) array) {
			out.add(e.getAsString());
		}
		return out;
	}

	private static Set<String> set(String key) {
		return strings(python.get(key));
	}

	private static Set<String> fields(Class<?> type) {
		return Arrays.stream(type.getFields())
				.filter(f -> !Modifier.isStatic(f.getModifiers()) && !Modifier.isTransient(f.getModifiers()))
				.map(Field::getName)
				.collect(Collectors.toCollection(TreeSet::new));
	}

	private static Set<String> keysOfType(Class<?> type) {
		return ConditionAdapterFactory.KNOWN_KEYS.entrySet().stream().filter(e -> e.getValue() == type).map(Map.Entry::getKey)
				.collect(Collectors.toCollection(TreeSet::new));
	}

	private static Set<String> lowerNames(Enum<?>[] values) {
		return Arrays.stream(values).map(v -> v.name().toLowerCase(Locale.ROOT)).collect(Collectors.toCollection(TreeSet::new));
	}

	@Test
	void conditionKeysMatch() {
		assertEquals(fields(io.github.chaotix345.rigtune.v010.core.rules.Condition.class), set("v1ConditionKeys"));
		// v0.4: the stutter keys are Condition fields too, but the updater allows them only inside stutterAdvice.
		Set<String> v2AndStutter = new TreeSet<>(set("v2ConditionKeys"));
		v2AndStutter.addAll(set("stutterConditionKeys"));
		assertEquals(new TreeSet<>(ConditionAdapterFactory.KNOWN_KEYS.keySet()), v2AndStutter);
		Set<String> overlap = new TreeSet<>(set("v2ConditionKeys"));
		overlap.retainAll(set("stutterConditionKeys"));
		assertEquals(Set.of(), overlap);
		Assertions.assertTrue(set("v2ConditionKeys").contains("driverVersion") && !set("v1ConditionKeys").contains("driverVersion"));
		assertEquals(keysOfType(Boolean.class), set("booleanKeys"));
		assertEquals(keysOfType(Integer.class), set("int32Keys"));
		Set<String> stringLists = keysOfType(List.class);
		stringLists.remove("anyOf");
		assertEquals(stringLists, set("listKeys"));
		assertEquals(keysOfType(String.class), set("stringKeys"));
	}

	@Test
	void ruleFieldsMatch() {
		JsonObject v1 = python.getAsJsonObject("v1RuleFields");
		JsonObject v2Only = python.getAsJsonObject("v2OnlyRuleFields");
		for (Map.Entry<String, Class<?>> kind : V1_RULES.entrySet()) {
			assertEquals(fields(kind.getValue()), strings(v1.get(kind.getKey())), kind.getKey());
		}
		for (Map.Entry<String, Class<?>> kind : V2_RULES.entrySet()) {
			Set<String> expected = new TreeSet<>(strings(v1.get(kind.getKey())));
			if (v2Only.has(kind.getKey())) {
				expected.addAll(strings(v2Only.get(kind.getKey())));
			}
			assertEquals(fields(kind.getValue()), expected, kind.getKey());
		}
	}

	@Test
	void sourceOnlyTierFieldsAreNoClientField() {
		JsonObject sourceOnly = python.getAsJsonObject("sourceOnlyTierFields");
		assertEquals(Set.of("gpuTiers", "cpuTiers"), sourceOnly.keySet());
		for (String kind : sourceOnly.keySet()) {
			for (Class<?> type : List.of(V1_RULES.get(kind), V2_RULES.get(kind))) {
				Set<String> overlap = new TreeSet<>(fields(type));
				overlap.retainAll(strings(sourceOnly.get(kind)));
				assertEquals(Set.of(), overlap, type.getName());
			}
		}
	}

	@Test
	void v2VocabulariesMatchTheEvaluator() {
		JsonObject v2 = python.getAsJsonObject("v2Vocabularies");
		assertEquals(ConditionEvaluator.GPU_VENDORS, strings(v2.get("gpuVendor")));
		assertEquals(ConditionEvaluator.BACKENDS, strings(v2.get("backend")));
		assertEquals(new TreeSet<>(ConditionEvaluator.OS_FAMILIES), strings(v2.get("os")));
		assertEquals(ConditionEvaluator.GOALS, strings(v2.get("goal")));
		assertEquals(ConditionEvaluator.FLAGS, strings(v2.get("flags")));
		assertEquals(ConditionEvaluator.SODIUM_WORKAROUND_FLAG, python.get("sodiumWorkaroundFlag").getAsString());
		assertEquals(RulesDocument.PatternRule.MAX_PATTERN_LENGTH, python.get("maxPatternLength").getAsInt());
	}

	@Test
	void v1VocabulariesMatchV010() {
		JsonObject v1 = python.getAsJsonObject("v1Vocabularies");
		assertEquals(lowerNames(io.github.chaotix345.rigtune.v010.core.model.GpuVendor.values()), strings(v1.get("gpuVendor")));
		assertEquals(lowerNames(io.github.chaotix345.rigtune.v010.core.model.Goal.values()), strings(v1.get("goal")));
		assertEquals(Set.of("opengl", "vulkan"), strings(v1.get("backend")));
		assertEquals(Set.of("windows", "macos", "linux"), strings(v1.get("os")));
		assertEquals(Set.of("backend-vulkan", "shaders-enabled"), strings(v1.get("flags")));
	}
}
