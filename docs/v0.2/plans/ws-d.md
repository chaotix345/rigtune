# WS-D: Distant Horizons + Iris settings — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development for every task below. Steps use checkbox (`- [ ]`) syntax for tracking. This workstream is executed inline by its own agent (not dispatched to fresh subagents), one task per commit.

**Goal:** Implement the code path for SPEC item 7 (P1): let RigTune read and patch Distant Horizons' `config/DistantHorizons.toml` and Iris's `config/iris.properties`, so `dh.*`/`iris.*` settings keys can appear in `SettingsSnapshot`, be gated through `SettingKeys`, resolved by `Recommender` (already generic), and staged/applied through the existing `PATCH_TOML`/`PATCH_PROPERTIES` op types (already wired into `PendingActions` and `ApplyExecutor`).

**Architecture:** `ConfigTargets.all(configDir)` (already committed, unmodified by this plan) is the single seam: it lists three `Target`s (sodium, dh, iris), each with a `file`, a `Stager::stage`, and a `Reader::read`. `RealController.apply()` already routes `SetSetting` actions through `ConfigTargets.forKey(...)` to the matching target's `stage()` — no RealController change needed. This plan implements the dh/iris `Target`s' `stage`/`readValues`/`patchFile` (mirroring `SodiumConfigPatcher`'s existing API shape exactly, so `ConfigTargets`, `PendingActions`, and `ApplyExecutor` compile and behave against them unchanged), extends `SettingKeys` to allow the two new namespaces, and wires `SettingsBridge.read()` to include their values (through the same `ConfigTargets.all()` readers, cached by file modification time per plan-review L11).

**Tech Stack:** Java 25, Gson (not needed by the new classes themselves — they're plain-text parsers), JUnit 5, `java.util.Properties` (ISO-8859-1).

**Spec:** docs/v0.2/SPEC.md item 7 (top + "Amendments from the plan review" → WS-D), docs/v0.2/PLAN.md (Global Constraints, WS-D section, "Plan-review fixes by workstream" → WS-D), docs/research/v0.2/dh-iris.md §2/§5/§8.

## Global Constraints (from PLAN.md, apply to every task below)
- `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before every `./gradlew`. `./gradlew build` must pass for BOTH MC versions before finishing.
- `core/` has no Minecraft imports. The new `core/apply` classes must also stay helper-classpath-safe: no `RigTune.LOGGER` (SLF4J), no Fabric Loader, no MC classes — only JDK + Gson (Gson isn't actually needed by these classes, so effectively JDK-only).
- Don't edit `client/RealController.java` — `apply()` already routes `dh.`/`iris.` keys through `ConfigTargets`.
- Don't edit `client/ConfigTargets.java` — it already has the right shape and calls `TomlConfigPatcher`/`PropertiesConfigPatcher` with matching signatures.
- Never write under `%APPDATA%\ModrinthApp`. The user's real DH/Iris config files are read-only reference material for building trimmed fixtures (settings only, nothing personal).
- Language keys (if any UI text were needed — none is for this workstream): prefix `rigtune.dh.*`/`rigtune.iris.*`, alphabetical position. Not expected to be touched by this plan.
- Windows/Git Bash: absolute paths; no Python string literals with Windows backslashes.
- Commit messages end with:
  `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_01BzjDikL2wqunaSiMjefimU`

## File Structure
- `src/main/java/io/github/chaotix345/rigtune/core/apply/TomlDocument.java` (new) — minimal TOML line-reader: parses `key = value` entries into dotted paths, tracking quoting and source line, for reuse by both the reader and the patcher.
- `src/main/java/io/github/chaotix345/rigtune/core/apply/TomlConfigPatcher.java` (replace stub) — `readValues`, `patch`, `patchFile`, `stage`, mirroring `SodiumConfigPatcher`'s shape.
- `src/main/java/io/github/chaotix345/rigtune/core/apply/PropertiesConfigPatcher.java` (replace stub) — same shape, backed by `java.util.Properties`.
- `src/main/java/io/github/chaotix345/rigtune/core/model/SettingKeys.java` (modify) — add `DH_PREFIX`/`IRIS_PREFIX` with a safe dotted-identifier charset.
- `src/client/java/io/github/chaotix345/rigtune/client/probe/SettingsBridge.java` (modify) — `read()` pulls every `ConfigTargets.all(configDir)` target through a new `readTargets`/mtime-cache helper, replacing the sodium-only line.
- Tests: `TomlDocumentTest.java`, `TomlConfigPatcherTest.java`, `PropertiesConfigPatcherTest.java`, `SettingKeysTest.java` (all new), `SettingsBridgeTest.java` (append), `RecommenderTest.java` (append).
- Fixtures: `src/test/resources/dh/DistantHorizons.toml`, `src/test/resources/iris/iris.properties` (new — trimmed from the user's real files, settings only).

---

### Task 1: `TomlDocument` — the minimal TOML reader

**Files:**
- Create: `src/main/java/io/github/chaotix345/rigtune/core/apply/TomlDocument.java`
- Test: `src/test/java/io/github/chaotix345/rigtune/core/apply/TomlDocumentTest.java`

**Interfaces:**
- Produces: `TomlDocument.Value(String raw, boolean quoted, int line)` record; `TomlDocument.parse(String text) -> Map<String, Value>` (dotted path → value, in file order); `TomlDocument.lines(String text) -> List<String>` (splits on `\r\n` or `\n`, keeps a trailing empty element so re-joining round-trips exactly).
- Consumes: nothing (pure text parsing).

Real-format facts this reader relies on (verified against the user's live `DistantHorizons.toml`, read-only, and docs/research/v0.2/dh-iris.md §2/§8): a `[section.dotted.path]` header's bracket text is *already* the full dotted path from the document root — headers are never nested implicitly, so `[client.advanced.graphics.quality]` can appear with no `[client]` or `[client.advanced]` header ever existing on its own. Comments are always full `#`-prefixed lines, never trailing after a value. Every double/float field is written as a *quoted* string (`threadRunTimeRatio = "1.0"`); every int/bool is bare; enums are quoted strings.

- [ ] **Step 1: Write the failing test**

```java
package io.github.chaotix345.rigtune.core.apply;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TomlDocumentTest {
	@Test
	void sectionHeadersCarryTheirFullDottedPath() {
		String text = """
				_version = 4

				[common.multiThreading]
					numberOfThreads = 8

				[client.advanced.graphics.quality]
					verticalQuality = "HIGH"
				""";
		Map<String, TomlDocument.Value> parsed = TomlDocument.parse(text);

		assertEquals("4", parsed.get("_version").raw());
		assertFalse(parsed.get("_version").quoted());
		assertEquals("8", parsed.get("common.multiThreading.numberOfThreads").raw());
		assertFalse(parsed.get("common.multiThreading.numberOfThreads").quoted());
		assertEquals("HIGH", parsed.get("client.advanced.graphics.quality.verticalQuality").raw());
		assertTrue(parsed.get("client.advanced.graphics.quality.verticalQuality").quoted());
	}

	@Test
	void quotedFloatsAndBareIntsAreDistinguished() {
		Map<String, TomlDocument.Value> parsed = TomlDocument.parse("""
				[a]
					overdrawPrevention = "-1.0"
					lodBiomeBlending = 3
				""");

		assertTrue(parsed.get("a.overdrawPrevention").quoted());
		assertEquals("-1.0", parsed.get("a.overdrawPrevention").raw());
		assertFalse(parsed.get("a.lodBiomeBlending").quoted());
		assertEquals("3", parsed.get("a.lodBiomeBlending").raw());
	}

	@Test
	void commentsAndBlankLinesAreSkipped() {
		Map<String, TomlDocument.Value> parsed = TomlDocument.parse("""
				[a]
					#
					# A comment describing b.
					b = 1
				""");

		assertEquals(1, parsed.size());
		assertEquals("1", parsed.get("a.b").raw());
	}

	@Test
	void crlfLineEndingsParseTheSameAsLf() {
		String crlf = "[a]\r\n\tb = \"x\"\r\n\tc = 2\r\n";
		Map<String, TomlDocument.Value> parsed = TomlDocument.parse(crlf);

		assertEquals("x", parsed.get("a.b").raw());
		assertEquals("2", parsed.get("a.c").raw());
	}

	@Test
	void linesRoundTripsExactlyIncludingATrailingNewline() {
		String text = "a = 1\nb = 2\n";
		assertEquals(text, String.join("\n", TomlDocument.lines(text)));
		String noTrailingNewline = "a = 1\nb = 2";
		assertEquals(noTrailingNewline, String.join("\n", TomlDocument.lines(noTrailingNewline)));
	}

	@Test
	void recordsTheSourceLineOfEachValue() {
		Map<String, TomlDocument.Value> parsed = TomlDocument.parse("[a]\n\tb = 1\n\tc = 2\n");
		assertEquals(1, parsed.get("a.b").line());
		assertEquals(2, parsed.get("a.c").line());
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1" && ./gradlew :26.2:test --tests "io.github.chaotix345.rigtune.core.apply.TomlDocumentTest"`
Expected: FAIL to compile (`TomlDocument` doesn't exist).

- [ ] **Step 3: Write minimal implementation**

```java
package io.github.chaotix345.rigtune.core.apply;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// A minimal reader for Distant Horizons' config/DistantHorizons.toml: sections and `key = value` lines only, no
// arrays, inline tables or multi-line strings (DH doesn't write any; docs/research/v0.2/dh-iris.md §2/§8). Section
// headers already carry their full dotted path (`[client.advanced.graphics.quality]`), so a header is never
// implicitly nested under an earlier one; indentation is purely cosmetic. Comments are always whole lines.
public final class TomlDocument {
	private static final Pattern SECTION = Pattern.compile("\\[([^\\[\\]]+)]");

	// The value token found for one dotted path: its unquoted text, whether the source token was quoted, and the
	// 0-based line it came from (so a patcher can rewrite just that line).
	public record Value(String raw, boolean quoted, int line) {
	}

	private TomlDocument() {
	}

	// Splits on \r\n or \n. A trailing newline produces one trailing empty element, so re-joining with the same
	// separator reproduces the original text exactly.
	public static List<String> lines(String text) {
		return Arrays.asList(text.split("\r\n|\n", -1));
	}

	// Dotted path -> value, in file order. A repeated key keeps its last occurrence.
	public static Map<String, Value> parse(String text) {
		Map<String, Value> out = new LinkedHashMap<>();
		List<String> lines = lines(text);
		String section = "";
		for (int i = 0; i < lines.size(); i++) {
			String trimmed = lines.get(i).strip();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			Matcher sectionMatch = SECTION.matcher(trimmed);
			if (sectionMatch.matches()) {
				section = sectionMatch.group(1).strip();
				continue;
			}
			int eq = trimmed.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String key = trimmed.substring(0, eq).strip();
			if (key.isEmpty()) {
				continue;
			}
			String token = trimmed.substring(eq + 1).strip();
			boolean quoted = token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"");
			String raw = quoted ? token.substring(1, token.length() - 1) : token;
			out.put(section.isEmpty() ? key : section + "." + key, new Value(raw, quoted, i));
		}
		return out;
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1" && ./gradlew :26.2:test --tests "io.github.chaotix345.rigtune.core.apply.TomlDocumentTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/chaotix345/rigtune/core/apply/TomlDocument.java src/test/java/io/github/chaotix345/rigtune/core/apply/TomlDocumentTest.java
git commit -m "feat(dh-iris): add a minimal TOML reader for DistantHorizons.toml"
```

---

### Task 2: DH fixture + `TomlConfigPatcher`

**Files:**
- Create: `src/test/resources/dh/DistantHorizons.toml` (trimmed from the user's real, read-only `C:/Users/Admin/AppData/Roaming/ModrinthApp/profiles/Fabric 26.2/config/DistantHorizons.toml` — settings only, comments shortened, nothing personal; the `[server]` section, which carries an autogenerated `serverId`, is dropped entirely)
- Modify: `src/main/java/io/github/chaotix345/rigtune/core/apply/TomlConfigPatcher.java`
- Test: `src/test/java/io/github/chaotix345/rigtune/core/apply/TomlConfigPatcherTest.java`

**Interfaces:**
- Consumes: `TomlDocument.parse`/`.lines` (Task 1); `SodiumConfigPatcher.Staged` (existing); `PendingActions.Op.patchToml` (existing); `AtomicFiles.writeString` (existing).
- Produces: `TomlConfigPatcher.readValues(Path) -> Map<String,String>`, `TomlConfigPatcher.patch(String text, Map<String,String> patches) -> String` (throws `IllegalArgumentException` naming the first key that doesn't fit), `TomlConfigPatcher.patchFile(Path, Map<String,String>) -> boolean` (throws `IOException`), `TomlConfigPatcher.stage(Path, Map<String,String>) -> SodiumConfigPatcher.Staged` — same signatures `ConfigTargets`/`ApplyExecutor` already call.

- [ ] **Step 1: Create the fixture**

```bash
mkdir -p src/test/resources/dh
```

Write `src/test/resources/dh/DistantHorizons.toml`:
```toml
_version = 4

[common.multiThreading]
	#
	# How many threads should be used by Distant Horizons?
	numberOfThreads = 8
	#
	# A value between 1.0 and 0.0 for the percentage of time each thread can run before going idle.
	threadRunTimeRatio = "1.0"

[client.advanced.debugging]
	#
	# What renderer is active?
	# DEFAULT: Default lod renderer
	# DEBUG_TRIANGLE: Debug testing renderer
	# DISABLED: Disable rendering
	rendererMode = "DISABLED"

[client.advanced.graphics.culling]
	#
	# Determines how far from the camera Distant Horizons will start rendering.
	# -1 = auto, overdraw will change based on the vanilla render distance.
	overdrawPrevention = "-1.0"
	disableShadowPassFrustumCulling = false

[client.advanced.graphics.quality]
	#
	# What is the maximum detail LODs can render at?
	# Fastest: CHUNK
	# Fanciest: BLOCK
	maxHorizontalResolution = "BLOCK"
	#
	# How well LODs will represent overhangs, caves, floating islands, etc.
	verticalQuality = "HIGH"
	#
	# The radius of the mod's render distance. (measured in chunks)
	lodChunkRenderDistanceRadius = 256
	#
	# This indicates how far apart drops in LOD quality are.
	horizontalQuality = "HIGH"
	#
	# How should LOD transparency be handled.
	# COMPLETE: LODs will render transparent.
	# DISABLED: LODs will be opaque.
	transparency = "COMPLETE"
	#
	# The same as vanilla Biome Blending settings for the LOD area.
	lodBiomeBlending = 3
```

- [ ] **Step 2: Write the failing tests**

```java
package io.github.chaotix345.rigtune.core.apply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TomlConfigPatcherTest {
	private static String fixture() throws IOException {
		return Files.readString(Path.of("src/test/resources/dh/DistantHorizons.toml"), StandardCharsets.UTF_8);
	}

	@Test
	void readValuesFlattensTheFixtureByDottedPath(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("DistantHorizons.toml"), fixture());
		Map<String, String> values = TomlConfigPatcher.readValues(file);

		assertEquals("8", values.get("common.multiThreading.numberOfThreads"));
		assertEquals("1.0", values.get("common.multiThreading.threadRunTimeRatio"));
		assertEquals("HIGH", values.get("client.advanced.graphics.quality.verticalQuality"));
		assertEquals("256", values.get("client.advanced.graphics.quality.lodChunkRenderDistanceRadius"));
		assertEquals("-1.0", values.get("client.advanced.graphics.culling.overdrawPrevention"));
	}

	@Test
	void readValuesIsEmptyForAMissingFile(@TempDir Path dir) {
		assertTrue(TomlConfigPatcher.readValues(dir.resolve("absent.toml")).isEmpty());
	}

	@Test
	void quotedFloatStaysQuotedAndBareIntStaysBare() {
		String patched = TomlConfigPatcher.patch(fixtureUnchecked(), Map.of(
				"common.multiThreading.threadRunTimeRatio", "0.5",
				"client.advanced.graphics.quality.lodChunkRenderDistanceRadius", "128"));

		Map<String, TomlDocument.Value> reparsed = TomlDocument.parse(patched);
		assertTrue(reparsed.get("common.multiThreading.threadRunTimeRatio").quoted());
		assertEquals("0.5", reparsed.get("common.multiThreading.threadRunTimeRatio").raw());
		assertFalse(reparsed.get("client.advanced.graphics.quality.lodChunkRenderDistanceRadius").quoted());
		assertEquals("128", reparsed.get("client.advanced.graphics.quality.lodChunkRenderDistanceRadius").raw());
	}

	@Test
	void enumStringStaysQuoted() {
		String patched = TomlConfigPatcher.patch(fixtureUnchecked(), Map.of("client.advanced.graphics.quality.verticalQuality", "MEDIUM"));
		Map<String, TomlDocument.Value> reparsed = TomlDocument.parse(patched);
		assertTrue(reparsed.get("client.advanced.graphics.quality.verticalQuality").quoted());
		assertEquals("MEDIUM", reparsed.get("client.advanced.graphics.quality.verticalQuality").raw());
	}

	@Test
	void sectionScopingKeepsSameNamedKeysInDifferentSectionsSeparate() {
		// "numberOfThreads" only exists under common.multiThreading in the fixture; patching it must not touch
		// any other key, and a lookup under the wrong section must be refused as missing.
		String patched = TomlConfigPatcher.patch(fixtureUnchecked(), Map.of("common.multiThreading.numberOfThreads", "4"));
		Map<String, TomlDocument.Value> reparsed = TomlDocument.parse(patched);
		assertEquals("4", reparsed.get("common.multiThreading.numberOfThreads").raw());
		assertEquals("DISABLED", reparsed.get("client.advanced.debugging.rendererMode").raw());

		assertThrows(IllegalArgumentException.class,
				() -> TomlConfigPatcher.patch(fixtureUnchecked(), Map.of("client.advanced.graphics.quality.numberOfThreads", "4")));
	}

	@Test
	void missingKeyIsRefused() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> TomlConfigPatcher.patch(fixtureUnchecked(), Map.of("client.advanced.graphics.quality.noSuchKey", "1")));
		assertTrue(e.getMessage().contains("noSuchKey"), e.getMessage());
	}

	@Test
	void everythingElseInTheFileIsUntouched() {
		String patched = TomlConfigPatcher.patch(fixtureUnchecked(), Map.of("client.advanced.graphics.quality.verticalQuality", "LOW"));
		String original = fixtureUnchecked();
		// Every line except the one holding verticalQuality is byte-identical.
		List<String> patchedLines = TomlDocument.lines(patched);
		List<String> originalLines = TomlDocument.lines(original);
		assertEquals(originalLines.size(), patchedLines.size());
		int changedLine = TomlDocument.parse(original).get("client.advanced.graphics.quality.verticalQuality").line();
		for (int i = 0; i < originalLines.size(); i++) {
			if (i == changedLine) {
				continue;
			}
			assertEquals(originalLines.get(i), patchedLines.get(i), "line " + i);
		}
	}

	@Test
	void crlfFileRoundTripsAsCrlf() {
		String crlf = "[a]\r\n\tb = \"x\"\r\n";
		String patched = TomlConfigPatcher.patch(crlf, Map.of("a.b", "y"));
		assertTrue(patched.contains("\r\n"));
		assertEquals("y", TomlDocument.parse(patched).get("a.b").raw());
	}

	@Test
	void patchFileWritesAtomicallyAndReportsNoOp(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("DistantHorizons.toml"), fixture());

		assertTrue(TomlConfigPatcher.patchFile(file, Map.of("common.multiThreading.numberOfThreads", "2")));
		assertEquals("2", TomlConfigPatcher.readValues(file).get("common.multiThreading.numberOfThreads"));

		assertFalse(TomlConfigPatcher.patchFile(file, Map.of("common.multiThreading.numberOfThreads", "2")));
		try (var files = Files.list(dir)) {
			assertEquals(1, files.count(), "no leftover temp file");
		}
	}

	@Test
	void patchFileThrowsForAMissingFile(@TempDir Path dir) {
		assertThrows(IOException.class, () -> TomlConfigPatcher.patchFile(dir.resolve("absent.toml"), Map.of("a.b", "1")));
	}

	@Test
	void stagingRefusesAMissingKeyAndStagesTheRest(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("DistantHorizons.toml"), fixture());
		Map<String, String> patches = new LinkedHashMap<>();
		patches.put("common.multiThreading.numberOfThreads", "4");
		patches.put("client.advanced.graphics.quality.noSuchKey", "1");

		SodiumConfigPatcher.Staged staged = TomlConfigPatcher.stage(file, patches);

		assertEquals(1, staged.ops().size());
		assertEquals(List.of("client.advanced.graphics.quality.noSuchKey"), List.copyOf(staged.refused().keySet()));
	}

	@Test
	void stagingRefusesEverythingForAMissingFile(@TempDir Path dir) {
		Map<String, String> patches = Map.of("a.b", "1");
		SodiumConfigPatcher.Staged staged = TomlConfigPatcher.stage(dir.resolve("absent.toml"), patches);
		assertTrue(staged.ops().isEmpty());
		assertEquals(patches.keySet(), staged.refused().keySet());
	}

	private static String fixtureUnchecked() {
		try {
			return fixture();
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1" && ./gradlew :26.2:test --tests "io.github.chaotix345.rigtune.core.apply.TomlConfigPatcherTest"`
Expected: FAIL (the stub throws/returns "not supported yet" for everything, and `patch(String,Map)` doesn't exist yet).

- [ ] **Step 4: Replace the stub**

```java
package io.github.chaotix345.rigtune.core.apply;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

// Patches config/DistantHorizons.toml: replaces only the value token of an existing key in its section, keeping
// whatever quoting the file already used for it (DH quotes every double/float and enum, but writes ints and bools
// bare -- docs/research/v0.2/dh-iris.md §8.1). Never adds, removes or reorders a key or section; a key not already
// present in its section is refused. This reader never throws on malformed content (see TomlDocument); it simply
// doesn't find the key, which the "missing key" refusal already covers.
public final class TomlConfigPatcher {
	// A bare (unquoted) token can't contain whitespace, '#', '"', '[' or ']' without corrupting the line; a quoted
	// token can't contain a '"' since this reader doesn't unescape one.
	private static final Pattern UNSAFE_BARE = Pattern.compile("[\\s#\"\\[\\]]");

	private TomlConfigPatcher() {
	}

	// The file's current values as flat dotted keys; empty if the file is missing or unreadable.
	public static Map<String, String> readValues(Path file) {
		if (!Files.isRegularFile(file)) {
			return Map.of();
		}
		try {
			Map<String, String> out = new LinkedHashMap<>();
			TomlDocument.parse(Files.readString(file, StandardCharsets.UTF_8)).forEach((k, v) -> out.put(k, v.raw()));
			return out;
		} catch (IOException | RuntimeException e) {
			return Map.of();
		}
	}

	// Applies every patch to `text`, or throws IllegalArgumentException naming the first key that doesn't fit
	// (missing, or a value that can't be written in this key's quoting style).
	public static String patch(String text, Map<String, String> patches) {
		Map<String, TomlDocument.Value> parsed = TomlDocument.parse(text);
		List<String> lines = new ArrayList<>(TomlDocument.lines(text));
		for (Map.Entry<String, String> entry : patches.entrySet()) {
			String key = entry.getKey();
			TomlDocument.Value existing = parsed.get(key);
			if (existing == null) {
				throw new IllegalArgumentException("No such key: " + key);
			}
			String problem = unsafe(entry.getValue(), existing.quoted());
			if (problem != null) {
				throw new IllegalArgumentException("Cannot set " + key + " to \"" + entry.getValue() + "\": " + problem);
			}
			lines.set(existing.line(), withValue(lines.get(existing.line()), existing.quoted(), entry.getValue()));
		}
		return String.join(text.contains("\r\n") ? "\r\n" : "\n", lines);
	}

	// Returns true when the file changed, false when it already had these values.
	public static boolean patchFile(Path file, Map<String, String> patches) throws IOException {
		if (!Files.isRegularFile(file)) {
			throw new IOException("No such file: " + file);
		}
		String text = Files.readString(file, StandardCharsets.UTF_8);
		String patched = patch(text, patches);
		if (patched.equals(text)) {
			return false;
		}
		AtomicFiles.writeString(file, patched);
		return true;
	}

	// One op per key; a value that doesn't fit the file as it is now is refused (key -> reason).
	public static SodiumConfigPatcher.Staged stage(Path file, Map<String, String> patches) {
		String text;
		try {
			if (!Files.isRegularFile(file)) {
				throw new IOException("missing");
			}
			text = Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			Map<String, String> refused = new LinkedHashMap<>();
			patches.keySet().forEach(key -> refused.put(key, "can't read " + file.getFileName()));
			return new SodiumConfigPatcher.Staged(List.of(), refused);
		}
		List<PendingActions.Op> ops = new ArrayList<>();
		Map<String, String> refused = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : patches.entrySet()) {
			Map<String, String> single = Collections.singletonMap(entry.getKey(), entry.getValue());
			try {
				patch(text, single);
				ops.add(PendingActions.Op.patchToml(file, single));
			} catch (IllegalArgumentException e) {
				refused.put(entry.getKey(), e.getMessage());
			}
		}
		return new SodiumConfigPatcher.Staged(List.copyOf(ops), refused);
	}

	private static String unsafe(String value, boolean quoted) {
		if (value == null) {
			return "no value";
		}
		if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
			return "can't contain a line break";
		}
		if (quoted) {
			return value.indexOf('"') >= 0 ? "can't contain a quote" : null;
		}
		return UNSAFE_BARE.matcher(value).find() ? "needs quoting, and the file writes this key unquoted" : null;
	}

	// Rewrites only the value token on `originalLine` (found between '=' and the end of line, trimmed), keeping the
	// key, the surrounding whitespace and the quote style exactly as they were.
	private static String withValue(String originalLine, boolean quoted, String newValue) {
		int eq = originalLine.indexOf('=');
		String before = originalLine.substring(0, eq + 1);
		String after = originalLine.substring(eq + 1);
		int leading = after.length() - after.stripLeading().length();
		int trailing = after.length() - after.stripTrailing().length();
		String rendered = quoted ? "\"" + newValue + "\"" : newValue;
		return before + after.substring(0, leading) + rendered + after.substring(after.length() - trailing);
	}
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1" && ./gradlew :26.2:test --tests "io.github.chaotix345.rigtune.core.apply.TomlConfigPatcherTest"`
Expected: PASS (13 tests).

- [ ] **Step 6: Commit**

```bash
git add src/test/resources/dh/DistantHorizons.toml src/main/java/io/github/chaotix345/rigtune/core/apply/TomlConfigPatcher.java src/test/java/io/github/chaotix345/rigtune/core/apply/TomlConfigPatcherTest.java
git commit -m "feat(dh-iris): implement TomlConfigPatcher against a trimmed DistantHorizons.toml fixture"
```

---

### Task 3: Iris fixture + `PropertiesConfigPatcher`

**Files:**
- Create: `src/test/resources/iris/iris.properties` (trimmed from the user's real, read-only `C:/Users/Admin/AppData/Roaming/ModrinthApp/profiles/Fabric 26.2/config/iris.properties`; drops only the auto-generated timestamp comment line)
- Modify: `src/main/java/io/github/chaotix345/rigtune/core/apply/PropertiesConfigPatcher.java`
- Test: `src/test/java/io/github/chaotix345/rigtune/core/apply/PropertiesConfigPatcherTest.java`

**Interfaces:**
- Consumes: `SodiumConfigPatcher.Staged`, `PendingActions.Op.patchProperties`, `AtomicFiles.writeString` (existing).
- Produces: `PropertiesConfigPatcher.readValues(Path) -> Map<String,String>`, `.patchFile(Path, Map<String,String>) -> boolean` (throws `IOException`), `.stage(Path, Map<String,String>) -> SodiumConfigPatcher.Staged` — same signatures as the stub.

- [ ] **Step 1: Create the fixture**

```bash
mkdir -p src/test/resources/iris
```

Write `src/test/resources/iris/iris.properties`:
```properties
#This file stores configuration options for Iris, such as the currently active shaderpack
allowUnknownShaders=false
colorSpace=SRGB
disableUpdateMessage=false
enableDebugOptions=false
enableShaders=false
maxShadowRenderDistance=32
shaderPack=ComplementaryReimagined_r5.9.3.zip
```

- [ ] **Step 2: Write the failing tests**

```java
package io.github.chaotix345.rigtune.core.apply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertiesConfigPatcherTest {
	private static String fixture() throws IOException {
		return Files.readString(Path.of("src/test/resources/iris/iris.properties"), StandardCharsets.UTF_8);
	}

	@Test
	void readValuesReadsEveryKey(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("iris.properties"), fixture());
		Map<String, String> values = PropertiesConfigPatcher.readValues(file);

		assertEquals("false", values.get("enableShaders"));
		assertEquals("32", values.get("maxShadowRenderDistance"));
		assertEquals("SRGB", values.get("colorSpace"));
		assertEquals("ComplementaryReimagined_r5.9.3.zip", values.get("shaderPack"));
	}

	@Test
	void readValuesIsEmptyForAMissingFile(@TempDir Path dir) {
		assertTrue(PropertiesConfigPatcher.readValues(dir.resolve("absent.properties")).isEmpty());
	}

	@Test
	void patchFileSetsAnExistingKeyAndReportsChange(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("iris.properties"), fixture());

		assertTrue(PropertiesConfigPatcher.patchFile(file, Map.of("maxShadowRenderDistance", "16")));
		assertEquals("16", PropertiesConfigPatcher.readValues(file).get("maxShadowRenderDistance"));

		assertFalse(PropertiesConfigPatcher.patchFile(file, Map.of("maxShadowRenderDistance", "16")));
	}

	@Test
	void patchFileWritesAFileJavaPropertiesCanReadBack(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("iris.properties"), fixture());
		PropertiesConfigPatcher.patchFile(file, Map.of("enableShaders", "true"));

		Properties reloaded = new Properties();
		try (var in = Files.newInputStream(file)) {
			reloaded.load(in);
		}
		assertEquals("true", reloaded.getProperty("enableShaders"));
		assertEquals("32", reloaded.getProperty("maxShadowRenderDistance"), "other keys untouched");
	}

	@Test
	void patchFileThrowsForAMissingFile(@TempDir Path dir) {
		assertThrows(IOException.class, () -> PropertiesConfigPatcher.patchFile(dir.resolve("absent.properties"), Map.of("a", "1")));
	}

	@Test
	void patchFileThrowsForAMissingKeyAndLeavesTheFileAlone(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("iris.properties"), fixture());
		String before = Files.readString(file, StandardCharsets.UTF_8);

		assertThrows(IOException.class, () -> PropertiesConfigPatcher.patchFile(file, Map.of("noSuchKey", "1")));
		assertEquals(before, Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test
	void stagingRefusesAMissingKeyAndStagesTheRest(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("iris.properties"), fixture());
		Map<String, String> patches = new LinkedHashMap<>();
		patches.put("maxShadowRenderDistance", "16");
		patches.put("noSuchKey", "1");

		var staged = PropertiesConfigPatcher.stage(file, patches);

		assertEquals(1, staged.ops().size());
		assertEquals(List.of("noSuchKey"), List.copyOf(staged.refused().keySet()));
	}

	@Test
	void stagingRefusesEverythingForAMissingOrBrokenFile(@TempDir Path dir) throws IOException {
		Map<String, String> patches = Map.of("maxShadowRenderDistance", "16");
		var missing = PropertiesConfigPatcher.stage(dir.resolve("absent.properties"), patches);
		assertTrue(missing.ops().isEmpty());
		assertEquals(patches.keySet(), missing.refused().keySet());

		Path broken = Files.writeString(dir.resolve("broken.properties"), "maxShadowRenderDistance=\\u12");
		var brokenStaged = PropertiesConfigPatcher.stage(broken, patches);
		assertTrue(brokenStaged.ops().isEmpty());
		assertEquals(patches.keySet(), brokenStaged.refused().keySet());
	}
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1" && ./gradlew :26.2:test --tests "io.github.chaotix345.rigtune.core.apply.PropertiesConfigPatcherTest"`
Expected: FAIL (stub behavior).

- [ ] **Step 4: Replace the stub**

```java
package io.github.chaotix345.rigtune.core.apply;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

// Patches config/iris.properties: java.util.Properties, ISO-8859-1 with \uXXXX escapes for anything outside it --
// this is what Properties.load/store already do by default (docs/research/v0.2/dh-iris.md §5/§8.4), so no explicit
// charset handling is needed here. Like TomlConfigPatcher, never sets a key that isn't already in the file: Iris
// writes every key this mod proposes as soon as it has run once, so a missing key means Iris hasn't saved that
// field yet, and guessing its position would be unsafe. Any call that saves the file (including Iris's own
// setShadersEnabledAndApply) rewrites the whole file and drops hand-added comments; that's inherent to
// Properties.store() and already true of Iris's own saves, not something to preserve here.
public final class PropertiesConfigPatcher {
	private PropertiesConfigPatcher() {
	}

	public static Map<String, String> readValues(Path file) {
		Properties properties = load(file);
		if (properties == null) {
			return Map.of();
		}
		Map<String, String> out = new LinkedHashMap<>();
		properties.stringPropertyNames().forEach(name -> out.put(name, properties.getProperty(name)));
		return out;
	}

	// Returns true when the file changed, false when it already had these values.
	public static boolean patchFile(Path file, Map<String, String> patches) throws IOException {
		Properties properties = load(file);
		if (properties == null) {
			throw new IOException("No such file: " + file);
		}
		if (!apply(properties, patches)) {
			return false;
		}
		AtomicFiles.writeString(file, store(properties));
		return true;
	}

	// One op per key; a key not already in the file is refused (key -> reason).
	public static SodiumConfigPatcher.Staged stage(Path file, Map<String, String> patches) {
		Properties properties = load(file);
		if (properties == null) {
			Map<String, String> refused = new LinkedHashMap<>();
			patches.keySet().forEach(key -> refused.put(key, "can't read " + file.getFileName()));
			return new SodiumConfigPatcher.Staged(List.of(), refused);
		}
		List<PendingActions.Op> ops = new ArrayList<>();
		Map<String, String> refused = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : patches.entrySet()) {
			if (!properties.containsKey(entry.getKey())) {
				refused.put(entry.getKey(), "no such key in " + file.getFileName());
				continue;
			}
			ops.add(PendingActions.Op.patchProperties(file, Collections.singletonMap(entry.getKey(), entry.getValue())));
		}
		return new SodiumConfigPatcher.Staged(List.copyOf(ops), refused);
	}

	// null when the file is missing or its content can't be parsed as properties (e.g. a malformed \uXXXX escape).
	private static Properties load(Path file) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		Properties properties = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			properties.load(in);
			return properties;
		} catch (IOException | IllegalArgumentException e) {
			return null;
		}
	}

	// Sets only keys already present; throws for the first one that isn't. Returns whether any value actually
	// changed, so a no-op patch doesn't rewrite the file.
	private static boolean apply(Properties properties, Map<String, String> patches) throws IOException {
		boolean changed = false;
		for (Map.Entry<String, String> entry : patches.entrySet()) {
			if (!properties.containsKey(entry.getKey())) {
				throw new IOException("No such key: " + entry.getKey());
			}
			if (!Objects.equals(properties.getProperty(entry.getKey()), entry.getValue())) {
				properties.setProperty(entry.getKey(), entry.getValue());
				changed = true;
			}
		}
		return changed;
	}

	private static String store(Properties properties) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		properties.store(buffer, null);
		return buffer.toString(StandardCharsets.ISO_8859_1);
	}
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1" && ./gradlew :26.2:test --tests "io.github.chaotix345.rigtune.core.apply.PropertiesConfigPatcherTest"`
Expected: PASS (8 tests).

- [ ] **Step 6: Commit**

```bash
git add src/test/resources/iris/iris.properties src/main/java/io/github/chaotix345/rigtune/core/apply/PropertiesConfigPatcher.java src/test/java/io/github/chaotix345/rigtune/core/apply/PropertiesConfigPatcherTest.java
git commit -m "feat(dh-iris): implement PropertiesConfigPatcher against a trimmed iris.properties fixture"
```

---

### Task 4: `SettingKeys` allowlist for `dh.`/`iris.`

**Files:**
- Modify: `src/main/java/io/github/chaotix345/rigtune/core/model/SettingKeys.java`
- Test: `src/test/java/io/github/chaotix345/rigtune/core/model/SettingKeysTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `SettingKeys.DH_PREFIX = "dh."`, `SettingKeys.IRIS_PREFIX = "iris."` (constants); `SettingKeys.changeable(String)` now also accepts a `dh.`/`iris.` key whose suffix is one or more dot-separated `[A-Za-z0-9_]+` segments. `Recommender.settings()` (unchanged) already calls `SettingKeys.changeable(key)` as its gate, so this is what lets a `dh.`/`iris.` `SettingRule` ever produce a recommendation.

- [ ] **Step 1: Write the failing test**

```java
package io.github.chaotix345.rigtune.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingKeysTest {
	@Test
	void vanillaAndSodiumBehaviourIsUnchanged() {
		assertTrue(SettingKeys.changeable("vanilla.renderDistance"));
		assertFalse(SettingKeys.changeable("vanilla.notAKey"));
		assertTrue(SettingKeys.changeable("sodium.performance.chunk_builder_threads"));
		assertFalse(SettingKeys.changeable("sodium."));
	}

	@Test
	void dhAndIrisAcceptDottedIdentifierKeys() {
		assertTrue(SettingKeys.changeable("dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius"));
		assertTrue(SettingKeys.changeable("dh.common.multiThreading.numberOfThreads"));
		assertTrue(SettingKeys.changeable("iris.maxShadowRenderDistance"));
		assertFalse(SettingKeys.changeable("dh."));
		assertFalse(SettingKeys.changeable("iris."));
	}

	@Test
	void dhAndIrisRejectPathTraversalSlashesAndControlCharacters() {
		assertFalse(SettingKeys.changeable("dh.../../etc/passwd"));
		assertFalse(SettingKeys.changeable("dh.client..quality"));
		assertFalse(SettingKeys.changeable("dh..client"));
		assertFalse(SettingKeys.changeable("dh.client.advanced."));
		assertFalse(SettingKeys.changeable("dh.client/advanced"));
		assertFalse(SettingKeys.changeable("dh.client\\advanced"));
		assertFalse(SettingKeys.changeable("dh.client advanced"));
		assertFalse(SettingKeys.changeable("dh.client.advanced\u0000"));
		assertFalse(SettingKeys.changeable("iris.max#Shadow"));
		assertFalse(SettingKeys.changeable("iris.\"maxShadow\""));
	}

	@Test
	void unrelatedNamespacesAndNullStayRejected() {
		assertFalse(SettingKeys.changeable("distanthorizons.foo"));
		assertFalse(SettingKeys.changeable(null));
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1" && ./gradlew :26.2:test --tests "io.github.chaotix345.rigtune.core.model.SettingKeysTest"`
Expected: FAIL (`dh.*`/`iris.*` currently rejected).

- [ ] **Step 3: Extend SettingKeys**

Replace the file's body (add the two constants, the pattern, and extend `changeable`; leave `VANILLA_PREFIX`/`SODIUM_PREFIX`/`VANILLA_ALLOWED`/`safeValue` untouched):

```java
package io.github.chaotix345.rigtune.core.model;

import java.util.Set;
import java.util.regex.Pattern;

public final class SettingKeys {
	public static final String VANILLA_PREFIX = "vanilla.";
	public static final String SODIUM_PREFIX = "sodium.";
	// v0.2 item 7: DistantHorizons.toml / iris.properties, read through ConfigTargets.
	public static final String DH_PREFIX = "dh.";
	public static final String IRIS_PREFIX = "iris.";
	public static final Set<String> VANILLA_ALLOWED = Set.of(
			"vanilla.renderDistance",
			"vanilla.simulationDistance",
			"vanilla.entityDistanceScaling",
			"vanilla.graphicsPreset",
			"vanilla.maxFps",
			"vanilla.enableVsync",
			"vanilla.inactivityFpsLimit",
			"vanilla.particles",
			"vanilla.biomeBlendRadius",
			"vanilla.weatherRadius",
			"vanilla.textureFiltering",
			"vanilla.renderClouds",
			"vanilla.prioritizeChunkUpdates",
			"vanilla.improvedTransparency",
			"vanilla.entityShadows",
			"vanilla.cutoutLeaves");

	// One or more dot-separated identifier segments: letters, digits, underscore only. No path traversal ("..", "/",
	// "\"), no leading/trailing/empty segment, no whitespace, quotes or other characters that could break the TOML
	// or properties syntax the DH/Iris patchers speak.
	private static final Pattern SAFE_DOTTED_KEY = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*");

	private SettingKeys() {
	}

	public static boolean changeable(String key) {
		return key != null && (VANILLA_ALLOWED.contains(key)
				|| key.startsWith(SODIUM_PREFIX) && key.length() > SODIUM_PREFIX.length()
				|| safeNamespaced(key, DH_PREFIX)
				|| safeNamespaced(key, IRIS_PREFIX));
	}

	private static boolean safeNamespaced(String key, String prefix) {
		return key.startsWith(prefix) && key.length() > prefix.length()
				&& SAFE_DOTTED_KEY.matcher(key.substring(prefix.length())).matches();
	}

	public static boolean safeValue(String value) {
		return value != null && value.chars().noneMatch(Character::isISOControl);
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1" && ./gradlew :26.2:test --tests "io.github.chaotix345.rigtune.core.model.SettingKeysTest"`
Expected: PASS (4 tests). Also rerun the full core test module quickly to confirm nothing that depends on `SettingKeys.changeable` regressed: `./gradlew :26.2:test --tests "io.github.chaotix345.rigtune.core.recommend.*"`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/chaotix345/rigtune/core/model/SettingKeys.java src/test/java/io/github/chaotix345/rigtune/core/model/SettingKeysTest.java
git commit -m "feat(dh-iris): allow dh./iris. setting keys with a safe dotted-identifier charset"
```

---

### Task 5: `SettingsBridge` reads every `ConfigTargets` namespace, cached by mtime

**Files:**
- Modify: `src/client/java/io/github/chaotix345/rigtune/client/probe/SettingsBridge.java`
- Test: `src/test/java/io/github/chaotix345/rigtune/client/probe/SettingsBridgeTest.java` (append)

**Interfaces:**
- Consumes: `ConfigTargets.all(Path)`, `ConfigTargets.Target` (existing, unmodified); `FabricLoader.getInstance().getConfigDir()`.
- Produces: `SettingsBridge.readTargets(List<ConfigTargets.Target>) -> Map<String,String>` (new, public, directly testable without a `Minecraft` instance); `SettingsBridge.read(Minecraft)` now includes `dh.*`/`iris.*` alongside `vanilla.*`/`sodium.*`. `readSodium`/`sodiumConfig` keep their existing signatures and behaviour (still used directly by `ConfigTargets` and by existing tests).

This addresses plan-review L11: `SettingsBridge.read` runs on the render thread in `RealController.rebuild()`; with DH's 1000+-line TOML added, cache each target file's parsed values by its last-modified time instead of re-parsing on every read.

- [ ] **Step 1: Write the failing tests**

Append to `SettingsBridgeTest.java` (new imports: `io.github.chaotix345.rigtune.client.ConfigTargets`, `java.nio.file.attribute.FileTime`, `java.util.List`, `java.util.concurrent.atomic.AtomicInteger`):

```java
	@Test
	void readTargetsPrefixesKeysFromEachTargetsReader(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("thing.toml"), "x");
		ConfigTargets.Target target = new ConfigTargets.Target("dh.", file, (f, p) -> null, f -> Map.of("a.b", "1"));

		assertEquals(Map.of("dh.a.b", "1"), SettingsBridge.readTargets(List.of(target)));
	}

	@Test
	void readTargetsIsEmptyForAMissingFile(@TempDir Path dir) {
		ConfigTargets.Target target = new ConfigTargets.Target("dh.", dir.resolve("absent.toml"), (f, p) -> null, f -> Map.of("x", "1"));

		assertTrue(SettingsBridge.readTargets(List.of(target)).isEmpty());
	}

	@Test
	void readTargetsCachesByLastModifiedTimeAndRereadsWhenItChanges(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("thing.properties"), "a=1");
		AtomicInteger calls = new AtomicInteger();
		ConfigTargets.Target target = new ConfigTargets.Target("iris.", file, (f, p) -> null, f -> {
			calls.incrementAndGet();
			return Map.of("a", "1");
		});

		SettingsBridge.readTargets(List.of(target));
		SettingsBridge.readTargets(List.of(target));
		assertEquals(1, calls.get(), "an unchanged mtime should reuse the cached read");

		FileTime original = Files.getLastModifiedTime(file);
		Files.setLastModifiedTime(file, FileTime.from(original.toInstant().plusSeconds(5)));
		SettingsBridge.readTargets(List.of(target));
		assertEquals(2, calls.get(), "a changed mtime should re-read");
	}
```

(Add `import java.util.List;` and `import java.util.concurrent.atomic.AtomicInteger;` and `import java.nio.file.attribute.FileTime;` and `import io.github.chaotix345.rigtune.client.ConfigTargets;` to the existing import block.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1" && ./gradlew :26.2:test --tests "io.github.chaotix345.rigtune.client.probe.SettingsBridgeTest"`
Expected: FAIL to compile (`readTargets` doesn't exist).

- [ ] **Step 3: Implement `readTargets` and wire it into `read`**

In `SettingsBridge.java`, add imports:
```java
import io.github.chaotix345.rigtune.client.ConfigTargets;

import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
```

Replace the body of `read(Minecraft minecraft)`:
```java
	public static SettingsSnapshot read(Minecraft minecraft) {
		Map<String, String> values = new LinkedHashMap<>();
		try {
			readVanilla(minecraft.options).forEach((k, v) -> values.put(VANILLA_PREFIX + k, v));
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Could not read vanilla options", e);
		}
		values.putAll(readTargets(ConfigTargets.all(FabricLoader.getInstance().getConfigDir())));
		return new SettingsSnapshot(Map.copyOf(values));
	}
```

Add, near `readSodium`:
```java
	private static final Map<Path, CachedRead> CONFIG_CACHE = new ConcurrentHashMap<>();

	private record CachedRead(FileTime mtime, Map<String, String> values) {
	}

	// Every ConfigTargets namespace's current values, prefixed. Each file's parsed content is cached by its
	// last-modified time, so re-reading it on every rebuild() (the render thread) only re-parses when the file
	// actually changed -- DH's TOML alone can run past 1000 lines (docs/v0.2/plan-review.md L11).
	public static Map<String, String> readTargets(List<ConfigTargets.Target> targets) {
		Map<String, String> out = new LinkedHashMap<>();
		for (ConfigTargets.Target target : targets) {
			readCached(target).forEach((k, v) -> out.put(target.prefix() + k, v));
		}
		return out;
	}

	private static Map<String, String> readCached(ConfigTargets.Target target) {
		Path file = target.file();
		FileTime mtime;
		try {
			mtime = Files.getLastModifiedTime(file);
		} catch (IOException e) {
			CONFIG_CACHE.remove(file);
			return Map.of();
		}
		CachedRead cached = CONFIG_CACHE.get(file);
		if (cached != null && cached.mtime().equals(mtime)) {
			return cached.values();
		}
		Map<String, String> values = target.reader().read(file);
		CONFIG_CACHE.put(file, new CachedRead(mtime, values));
		return values;
	}
```

(`readSodium`, `sodiumConfig` and the direct `values.putAll(readSodium(sodiumConfig()))` line are removed from `read()` — sodium is now read the same way as dh/iris, through its `ConfigTargets.Target`. `readSodium`/`sodiumConfig` themselves are untouched and stay public, since `ConfigTargets.all()` and the existing `SettingsBridgeTest` tests call them directly.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1" && ./gradlew :26.2:test --tests "io.github.chaotix345.rigtune.client.probe.SettingsBridgeTest"`
Expected: PASS (all existing tests plus the 3 new ones — `flattensSodiumConfig`/`missingOrBrokenSodiumConfigIsEmpty` must still pass unchanged since `readSodium` itself didn't change).

- [ ] **Step 5: Commit**

```bash
git add src/client/java/io/github/chaotix345/rigtune/client/probe/SettingsBridge.java src/test/java/io/github/chaotix345/rigtune/client/probe/SettingsBridgeTest.java
git commit -m "feat(dh-iris): read every ConfigTargets namespace into the settings snapshot, cached by mtime"
```

---

### Task 6: Recommender scenario tests for `dh.`/`iris.` keys

**Files:**
- Modify: `src/test/java/io/github/chaotix345/rigtune/core/recommend/RecommenderTest.java` (append; no production code changes — this proves the existing generic `Recommender.settings()` path already works end-to-end once Task 4/5 are in place)

**Interfaces:**
- Consumes: `RulesLoader.parse` / the file's existing `rules(String body)` helper, `Recommender.recommend`, `SettingsSnapshot`.
- Produces: nothing new; documents the intended code path for WS-H, which will add the real DH/Iris rule content to `rules/source/knowledge.json` later.

- [ ] **Step 1: Write the failing tests**

Append to `RecommenderTest.java`:

```java
	@Test
	void dhSettingKeyIsRecommendedWhenPresentInTheSnapshot() {
		RulesDocument rules = rules("""
				"settings":[
				 {"key":"dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius","value":128,
				  "when":{"modPresent":["distanthorizons"]},"reason":"Match the tier's LOD budget."}
				]""");
		Map<String, String> settings = Map.of("dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius", "256");

		Report withDh = run(rules, Fixtures.userRig(), Fixtures.mods("distanthorizons"), settings, OnlineData.offline());
		Recommendation rec = byId(withDh).get("set:dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius");
		assertEquals(new Action.SetSetting("dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius", "256", "128"),
				rec.action());
		assertEquals(Category.SETTING, rec.category());

		Report withoutDh = run(rules, Fixtures.userRig(), List.of(), settings, OnlineData.offline());
		assertFalse(byId(withoutDh).containsKey("set:dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius"),
				"modPresent gates the rule off when Distant Horizons isn't installed");
	}

	@Test
	void irisSettingKeyIsRecommendedWhenPresentInTheSnapshot() {
		RulesDocument rules = rules("""
				"settings":[
				 {"key":"iris.maxShadowRenderDistance","max":24,"when":{"modPresent":["iris"],"tierAtMost":2},
				  "reason":"Shadows cost more at low tiers."}
				]""");
		Map<String, String> settings = Map.of("iris.maxShadowRenderDistance", "32");

		Report report = run(rules, Fixtures.lowEndLaptop(), Fixtures.mods("iris"), settings, OnlineData.offline());
		assertEquals("24", setting(report, "iris.maxShadowRenderDistance").orElseThrow().newValue());
	}

	@Test
	void dhAndIrisKeysMissingFromTheSnapshotProduceNoRecommendation() {
		// snapshot.has(key) gates every settings key uniformly (Recommender.settings()); a dh./iris. key the
		// snapshot doesn't have (mod not installed, so SettingsBridge never populated it) never fires, the same way
		// a vanilla or sodium key would not.
		RulesDocument rules = rules("""
				"settings":[
				 {"key":"dh.client.advanced.graphics.quality.verticalQuality","value":"HIGH","reason":"x"},
				 {"key":"iris.maxShadowRenderDistance","value":16,"reason":"y"}
				]""");
		Report report = run(rules, Fixtures.userRig(), List.of(), Map.of(), OnlineData.offline());
		assertFalse(setting(report, "dh.client.advanced.graphics.quality.verticalQuality").isPresent());
		assertFalse(setting(report, "iris.maxShadowRenderDistance").isPresent());
	}
```

(`setting(Report, String)` and `byId`/`run`/`rules` already exist in this file; `Fixtures.mods(String...)` already exists per `RecommenderScenarioTest.java`'s usage.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1" && ./gradlew :26.2:test --tests "io.github.chaotix345.rigtune.core.recommend.RecommenderTest"`
Expected: FAIL before Task 4 (SettingKeys rejects `dh.`/`iris.`) — since Task 4 is already done by this point in sequence, expected: PASS immediately, confirming the wiring. If run standalone before Task 4, this is the test that proves Task 4 is necessary.

- [ ] **Step 3: (no production code — this task only adds tests)**

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1" && ./gradlew :26.2:test --tests "io.github.chaotix345.rigtune.core.recommend.RecommenderTest"`
Expected: PASS (all existing tests plus the 3 new ones).

- [ ] **Step 5: Commit**

```bash
git add src/test/java/io/github/chaotix345/rigtune/core/recommend/RecommenderTest.java
git commit -m "test(dh-iris): scenario-test the Recommender path for dh./iris. setting keys"
```

---

### Task 7: Full build, both MC versions

- [ ] Run: `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1" && ./gradlew build`
  Expected: BUILD SUCCESSFUL — both `:26.2` and `:26.3` compile, unit test, and compile game-test sources. No game-test run needed (this workstream is pure file logic, per PLAN.md's WS-D game-test note).
- [ ] If 26.3 fails to compile only because of the active-version switch state, run `./gradlew "Reset active project"` first and retry; never commit while switched to 26.3 (see README "Build from source").
- [ ] Fix any failures (most likely: a stray import, or the `SettingsBridgeTest` lambda target's functional-interface arg count) before moving on.

---

### Task 8: Self-review

- [ ] Dispatch a `code-reviewer` subagent (per superpowers:requesting-code-review) on the full diff (`git diff origin/feat/v0.2.0...HEAD`).
- [ ] Fix every high- and medium-severity finding; re-run the affected tests; commit fixes as new commits (not amends).

---

### Task 9: Finish

- [ ] `git fetch origin && git rebase origin/feat/v0.2.0` (resolve conflicts in any hotspot file by keeping both sides' intent; WS-D touches no hotspot files, so conflicts are unlikely).
- [ ] `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1" && ./gradlew build` green for both versions after the rebase.
- [ ] `git push -u origin feat/dh-iris`.
- [ ] `gh run watch <id> --exit-status` until CI is green.
- [ ] Write `docs/v0.2/design/ws-d.md`: the TomlDocument/patcher design, the "refuse a missing key" policy for both patchers and why, the SettingKeys charset choice, the mtime-cache in SettingsBridge, and explicit note of what's UNVERIFIED by this workstream (AC7.3's production-smoke verification is Phase 5, not WS-D).
- [ ] Use superpowers:verification-before-completion before reporting done.
- [ ] Do not merge. Do not edit SPEC.md/PLAN.md/PROGRESS.md.

---

## Self-review against the SPEC (item 7) and plan-review WS-D fixes
- "`dh.<dotted TOML path>` read from config/DistantHorizons.toml by a minimal reader" → Task 1 (`TomlDocument`), Task 2 (`TomlConfigPatcher.readValues`).
- "`iris.<key>` read from config/iris.properties (java.util.Properties)" → Task 3.
- "staged post-exit like Sodium, as PATCH_TOML/PATCH_PROPERTIES... one op per key... validated at staging" → Task 2/3 `stage()` (op types and `ApplyExecutor` dispatch already exist and are unmodified).
- "AC7.1 Unit tests for the TOML reader/patcher (quoted floats stay quoted, bare ints stay bare, section scoping, missing key refused, CRLF, comments) and the properties patcher" → Task 1 + Task 2 tests cover every one of these explicitly; Task 3 tests cover the properties patcher.
- "AC7.2 Recommender scenario tests for DH/Iris profiles" → Task 6.
- "AC7.3 the production smoke run..." → explicitly out of scope for this workstream (Phase 5, coordinator + WS-G); noted as such in `docs/v0.2/design/ws-d.md`.
- Plan-review WS-D fixes: "Don't edit RealController" → honored (Task list never touches it). "Implement TomlConfigPatcher/PropertiesConfigPatcher readValues, stage and patchFile" → Tasks 2/3. "the SettingKeys allowlist" → Task 4. "SettingsBridge snapshot reading through ConfigTargets.all(configDir) readers" → Task 5. "L11: cache readings by file modification time" → Task 5.
- "WS-D does no recording" (ChangeRecorder) → correctly out of scope; nothing in this plan calls `ChangeRecorder`.
