# WS-J: JVM and GC advice (SPEC 6 + amendments J-M1, J-M2, "Launcher steps")

Branch `feat/jvm-advice`. Plan: docs/v0.4/plans/ws-j.md. Research: docs/research/v0.4/jvm-gc.md (final), docs/research/v0.4/launcher-steps.md.

## What landed

- **Pure core (`core/jvm/`)**
  - `JvmArgs`: parses `getInputArguments()`. `-XX` flags by name, last one wins. Heap sizes in bytes (k/m/g/t). `-D` property *names* only. A max-heap count (`-Xmx` + `-XX:MaxHeapSize`). Junk counting. **No argument value is ever stored**, so nothing downstream can leak a path.
  - `VmOptions`: getVMOption as a lookup (FOUND value + origin / MISSING / FAILED).
  - `JvmSnapshot`: its `toString` leaves the arguments out.
  - `JvmCollector`: family, `Use*GC` option, bean names, fact.
  - `JvmFinding`: kind + display name. The constructor refuses anything but `-XX:[+-]Name`, `-Xmn`/`-Xmx` and lower-case `-D` marker names.
  - `JvmFacts`: `PROBED`, `RULE_FLAGS`.
  - `JvmFlagClassifier`: snapshot → `JvmReport`.
  - `JvmReport`: skeleton reshaped. `UNAVAILABLE` kept. New: collector enum, typed, heap, findings, facts, `flagsFor(adviceId)`, `xmxDuplicate()`.
- **Probe**: `client/probe/JvmProbe`.
  - Runs once per session on `Probes.EXECUTOR`. Every MXBean call is in its own `catch (Throwable)`, and the HotSpot-only code is isolated in a nested class, so OpenJ9 fails only inside the guard.
  - Each caller waits at most 3 s (a timeout copy, as in LauncherProbe). A late result is used by the next rescan.
  - Test seam: `inject(JvmSnapshot)` / `reset()`. A replaced probe never overwrites its successor's `current()`.
  - `withFacts(HardwareProfile, JvmReport)`.
- **Hotspot edits** (each one line or one method):
  - `HardwareProbe.probe`: `slowPart().thenCombine(JvmProbe.probeAsync(), (s, jvm) -> JvmProbe.withFacts(combine(fast, s), jvm))`.
  - `ConditionEvaluator`: `knownFlag` accepts `RULE_FLAGS`, plus one dispatch line in `flags()` and `jvmFlag()`.
  - `RealController.shareReport`: passes `jvmService.report()`.
  - `RigTuneScreen`: two call sites pass `controller.jvmReport()` to `LauncherLines.adviceLine`.
  - `ShareReport`: two overloads (the old ones delegate) and a `java()` line.
  - `LauncherScenarioTest.everyLauncherKeyIsTranslated`: 3 lines.
  - `build.gradle`: the `-PgametestJvmArgs` block.
- **Launcher**
  - `LauncherAdvice.JVM_ADVICE_PREFIX = "advice:jvm-"`, `isJvmAdvice`, `jvmStepsKey`, `typedXmxWins`.
  - `LauncherInfo.jvmStepsKey()`: Prism, Modrinth App, ATLauncher, CurseForge, official.
  - `LauncherInfo.typedXmxWins()`: Modrinth App.
  - `LauncherLines.adviceLine(rec, launcher, jvm)`:
    - under `jvm-*`: "Found in your Java arguments: <flags>." then "In <launcher>: <Java-arguments steps>". Either part may be missing.
    - under `ram-*`: v0.3's memory line, plus "Your Java arguments also contain -Xmx, which %s uses instead of the memory slider: change or remove it there." when `jvm-xmx-duplicate` holds and the launcher lets a typed `-Xmx` win. The memory line stays the root component, so v0.3's LauncherGameTest assertions hold.
- **UI and docs**
  - `JvmScreen` (hub "JVM & memory"), a scrolling list:
    - This Java: version (vendor), collector + "chosen by Java" / "set in your Java arguments", heap up to / starting at.
    - Your Java arguments: one row per finding with its reason, "Nothing to note…", or "RigTune can't check…" (OpenJ9).
    - Advice: every fired `jvm-*` and `ram-*` recommendation with its reason and its launcher line.
    - "Checking Java…" until the probe finishes. `tick()` rebuilds when the JVM report or the controller's report changes.
  - `JvmService.report()` = `JvmProbe.current()`; it starts a probe if none has run.
  - README "JVM & memory advice": includes "The game won't start after I pasted Java arguments", with the exact JDK 25 error strings from research `flag-verify.txt`.

## Decisions and deviations

1. **Rule vocabulary kept apart from `ConditionEvaluator.FLAGS`.** SchemaConsistencyTest asserts FLAGS equals the updater's v2 flag vocabulary. `JvmFacts.RULE_FLAGS` is its own set: `jvm-gc-{g1,zgc,shenandoah,parallel,serial,epsilon,other}`, `jvm-gc-typed`, `jvm-ignored-flags`, `jvm-young-gen-fixed`, `jvm-server-flags`, `jvm-explicit-gc-disabled`, `jvm-xmx-duplicate`. The coordinator forwarded it to WS-R for update_rules.py. `jvm-probed` isn't in it: it always evaluates UNKNOWN in a rule, and the updater refuses it.
2. **J-M1 semantics**:
   - any `jvm-` flag in a rule is UNKNOWN unless `jvm-probed` is in the profile;
   - a `jvm-` name outside RULE_FLAGS is UNKNOWN even when probed (a future fact can't be falsely FALSE under a `not`);
   - otherwise present = TRUE, absent = FALSE.
3. **`jvm-probed`** only when the options lookup exists (HotSpot bean) and the GC bean list is non-empty. Otherwise the report has no facts and no findings. The version, vendor and bean-derived collector are still shown.
4. **Collector and typed.** The `Use*GC` option that reads `true` decides. Typed = its origin is VM_CREATION, ENVIRON_VAR (`JAVA_TOOL_OPTIONS`) or CONFIG_FILE. If every lookup fails: the bean names decide, and typed comes from the command line. Neither → OTHER (`jvm-gc-other`). Verified on Temurin 25.0.4.1 (scratch `ws-j/probe/Beans.java`):
   - Epsilon's only bean is "Epsilon Heap";
   - `UseEpsilonGC` is MISSING unless experimental options are unlocked;
   - `-Xmn` shows up as `NewSize`/`MaxNewSize` with origin VM_CREATION;
   - `UseAdaptiveSizePolicy` typed under G1 is not reset (so it isn't flagged).
5. **Findings.**
   - IGNORED: MISSING from getVMOption. OVERRIDDEN: booleans only, compared as `true`/`false` (value flags get rounded, e.g. G1HeapRegionSize, and are never compared).
   - Never checked: `-XX:HeapDumpPath` (version JSON) and `-XX:MetaspaceSize` (ATLauncher). Every other launcher-injected token is a `-D`, module option or agent, which the classifier never reads.
   - A FAILED lookup concludes nothing about that flag.
6. **Aikar's server set**:
   - either marker (`-Dusing.aikars.flags`, `-Daikars.new.flags`), or at least 4 of the 7 distinctive flags **by name, any value**. That also covers Aikar's >12 GB variant (different percentages, same names). The launchers' default set shares only `G1NewSizePercent`.
   - Every marker and distinctive flag found is one SERVER_SET finding, so the found line lists them.
   - `DisableExplicitGC` is folded into the server set (EXPLICIT_GC_DISABLED only without it; research §5.2).
7. **`jvm-xmx-duplicate`** (launcher-steps amendment): two or more max-heap settings, counting `-Xmx` and `-XX:MaxHeapSize` together. One finding, "-Xmx". Equal values still count (the typed one still overrides the Modrinth slider).
8. **The found line for collector advice.** `flagsFor("jvm-zgc-small-heap")` (any `jvm-<collector>-…` id) adds the typed collector flag, "-XX:+UseZGC". A future `jvm-` id the client doesn't map gets no found line, only the steps.
9. **Launchers (coordinator decision)**:
   - `jvm_steps` for the 5 launchers that exist on feat/v0.4.0. MultiMC/GDLauncher (WS-A's 2g enum values) and the GDLauncher case of `typedXmxWins` come with the merge of WS-A.
   - LangCheckTest forbids unused keys, and JvmLauncherAdviceTest fails on an uncovered launcher, which forces the follow-up.
   - CurseForge's string adds "Advanced Settings" to the research's proposal (its summary table and screenshot 9236352588 show the Additional Arguments field under Advanced Settings in Profile Options).
10. **Official launcher 26.1+ default line** in the tests: `-Xmx4G -XX:+UseZGC`. Inferred from Mojang article 41950300066573 ("replace the phrase -XX:+UseZGC with -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC …", re-read 2026-09-26 through the JSON endpoint, scratch `ws-j/mojang-*.json`) and 39083573916941 (4 GB from 26.1-snapshot-2). No real 26.x launch was observed (UNVERIFIED).
11. **Share report**: `- Java: <version> (<vendor>), <collector>, N argument note(s)` right after the Launcher line.
    - Vendor through `field()` (scrubbed, escaped).
    - No line before the probe finished.
    - OpenJ9: version and vendor only.
12. **Logging**: one INFO line per probe with version, vendor, collector, typed and the finding names (AC6.6 evidence). Failures log only the exception's class name.
13. **JvmGameTest**:
    - network off (X1);
    - running JVM: CI → G1 chosen by Java, no notes, no `jvm-` advice. Locally with `-PgametestJvmArgs="-XX:+UseZGC -XX:+ZGenerational -Dusing.aikars.flags=x"` it asserts the notes come from the running JVM (AC6.6's detection part);
    - injected snapshot + brand theseus through the real controller → facts in the profile, JvmScreen rows, found line + Modrinth steps on the main list and JvmScreen, share line "ZGC, 2 argument notes" naming no flag;
    - until the bundled rules fire both `jvm-ignored-flags` and `jvm-server-flags` it shows a canned report (the real report plus two jvm entries); after WS-R, the real one;
    - screenshots: `jvm-running-*`, `jvm-findings-*` at 3 sizes, `jvm-findings-640x480-scale2-end`, `jvm-main-list-854x480-scale2` (scrolled to the advice).
14. **`-PgametestJvmArgs`**: whitespace-split. Appended to `runProductionClientGameTest.jvmArgs` and, as a `jvmArgumentProviders` entry, to `runClientGameTest`. CI never passes it.

## AC status (see the final report for run links)
- AC6.1: JvmArgsTest (7).
- AC6.2: JvmFlagClassifierTest (19): both official launcher lines, Modrinth, ATLauncher, Prism → no findings; the privacy checks.
- AC6.3:
  - JvmScenarioTest (3) over bundled + fixture advice (18 real command lines).
  - JvmFlagEvaluationTest (5, J-M1).
  - JvmLauncherAdviceTest (3), LauncherLinesJvmTest (3).
  - The pinned 0.2/0.3 skip is PinnedCopiesTest's (the v030 `Recommender` stub skips `jvm-flags`).
  - The bundled-rules scenario lands with WS-R's content.
- AC6.4: JvmGameTest green on 26.2 OpenGL, 26.3 OpenGL and 26.3 Vulkan.
- AC6.5: the checklist below.
- AC6.6: Phase 5.

## AC6.5: every number in `jvm-*` advice text traces to jvm-gc.md §4

The `jvm-*` rule texts are WS-R's content and haven't landed on feat/v0.4.0 yet. This checklist covers the numbers WS-J ships and the thresholds the advice conditions use. The rule texts get the same check after the merge (below).

**Numbers in text WS-J ships:**

| Where | Number | Source |
|---|---|---|
| README table, G1 defaults | 1931 FPS, 610 1% low, 27 pauses / 84 ms, 1.3 GB | §0 table, §4.2 row "G1 default, -Xmx4G" (1316 MB) |
| README table, launcher set | 1937, 616, 45 / 119 ms, 1.1 GB | §4.2 "Launcher default set" (1088 MB) |
| README table, Aikar | 1926, 622, 6 / 10 ms, 4.1 GB | §4.2 "Aikar's set" (4096 MB) |
| README table, ZGC | 1922, 627, 23 / under 1 ms, 3.9 GB | §4.2 "ZGC" (3852 MB; total 0 (0-1) ms) |
| README | 140 FPS cap, 1% low 126 vs 127 | §4.3 capped table |
| README | 33 clean runs, 80 s each, 4 GB heap, RD 16 | §4.1 ("33 clean runs", "80 s of recorded frames per run", `-Xmx4G`, render distance 16) |
| README / finding reason | Java 24 (ZGenerational removed) | §2.2 (JEP 490, `arguments.cpp:547`) |
| README | CMS removed in Java 14 | §2.2 (JEP 363) |
| game-test canned text | Java 24 | same |

**Thresholds in the advice conditions** (SPEC 6 as amended, used by the fixture and to be matched by WS-R):
- `jvm-zgc-small-heap`: heap ≤ 3 GB, RAM ≥ 8 GB. From §4.3, ZGC 2 GB vs 4 GB: 1% low 564 vs 627, 3 runs, every 2 GB run at or below the slowest 4 GB run. The amendment's wording: "slightly lower 1 % lows on the test PC".
- `jvm-zgc-small-pc`: RAM ≤ 8 GB, heap ≥ 4 GB. From §4.2/§3.2: ZGC committed 3852 of 4096 MB vs 1316 MB for G1 ("about a third", §5.2 draft).
- `jvm-server-flags`: RAM ≤ 16 GB. From §4.2: Aikar committed 4096 MB (whole heap) vs 1316 MB for G1, with the same frame rates.

**After WS-R's content merges:** each `jvm-*` text's numbers are listed here against §4 (or §2 for Java versions and documentation quotes). Anything without a §4 row is changed or reported.

## UNVERIFIED
- The official launcher's labels: from Mojang's help articles, not the closed-source UI. The 26.1+ default argument line is inferred from the articles (no observed 26.x launch).
- CurseForge: whether a pack's Additional Arguments replace or add to the Default Additional Arguments, and whether a typed `-Xmx` beats Custom RAM Allocation (so CurseForge gets no typed-`-Xmx` note).
- Shenandoah and Java 26/27 were not measured. The runtime ignored/overridden check needs no table. The curated rows (young gen, server set, explicit GC) need a re-check with each new Java the game ships with.
- MultiMC/GDLauncher Java-arguments steps: pending WS-A's merge.
