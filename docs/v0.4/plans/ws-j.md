# WS-J: JVM and GC advice (SPEC 6 + amendments J-M1, J-M2, "Launcher steps"). Task plan

Branch `feat/jvm-advice`, worktree `C:/Dev/Worktrees/rigtune-jvm`. Research: docs/research/v0.4/jvm-gc.md (final),
docs/research/v0.4/launcher-steps.md. Contracts: docs/v0.4/design/ws-k.md (JvmService, JvmReport, JvmScreen, JvmGameTest).

**Goal:** RigTune reads the running JVM once per session, flags ignored, harmful or costly Java arguments by name (never
showing, logging or sharing the raw arguments), feeds `jvm-` facts to the rules, and shows the `jvm-*` advice with the
launcher's Java-arguments steps. It never recommends adding GC flags or switching collectors.

**Architecture:** pure `core/jvm` (JvmArgs → JvmFlagClassifier → JvmReport with JvmFindings and facts, from a
JvmSnapshot that holds a VmOptions lookup); `client/probe/JvmProbe` reads the snapshot from the MXBeans on
Probes.EXECUTOR (every call guarded) and classifies it right away (the raw arguments never leave the probe); one call in
HardwareProbe adds the facts to `HardwareProfile.flags`; ConditionEvaluator evaluates the `jvm-` prefix (UNKNOWN without
`jvm-probed`); LauncherAdvice/LauncherInfo/LauncherLines add the Java-arguments steps, the found-flags line and the
typed-`-Xmx` line; JvmScreen, ShareReport's Java line, JvmGameTest, the `-PgametestJvmArgs` hook.

## Decisions (from the SPEC and amendments)
- Facts: `jvm-probed` (only when the HotSpot diagnostic bean and the GC beans were read), `jvm-gc-{g1,zgc,shenandoah,parallel,serial,epsilon,other}`,
  `jvm-gc-typed`, `jvm-ignored-flags`, `jvm-young-gen-fixed`, `jvm-server-flags`, `jvm-explicit-gc-disabled`, `jvm-xmx-duplicate`.
  No `jvm-xms-large`/`jvm-pretouch` (J-M2). Rule vocabulary = `JvmFacts.RULE_FLAGS` (everything but `jvm-probed`).
- Evaluation (J-M1): any `jvm-` flag in a rule is UNKNOWN while `jvm-probed` is absent, and a `jvm-` name outside
  RULE_FLAGS (including `jvm-probed`) is always UNKNOWN; otherwise present = TRUE, absent = FALSE. `ConditionEvaluator.FLAGS`
  stays as it is (SchemaConsistencyTest compares it with the updater; WS-R mirrors RULE_FLAGS there).
- Findings: IGNORED (getVMOption: no such option), OVERRIDDEN (a boolean reads back differently), YOUNG_GEN_FIXED
  (`-Xmn`/`NewSize`/`MaxNewSize` under G1), STOP_THE_WORLD_GC (typed Serial/Parallel), NO_GC (Epsilon), SERVER_SET (Aikar
  markers or ≥ 4 of its 7 distinctive flags), EXPLICIT_GC_DISABLED (without the server set), XMX_DUPLICATE (≥ 2 max-heap settings).
  Display names only from `-XX:` names (sign kept, value dropped), `-Xmn`/`-Xmx` and RigTune's own `-D` marker table.
- Launcher-injected arguments are skipped: `-XX:HeapDumpPath`, `-XX:MetaspaceSize`, `-D*`, `-javaagent:`, `--add-opens`,
  `--enable-native-access`, `--sun-misc-unsafe-memory-access`, `-Xss` (version JSON).
- Java-arguments steps for 5 launchers now (Modrinth App, Prism, ATLauncher, CurseForge, official); MultiMC/GDLauncher and
  the GDLauncher typed-`-Xmx` case after WS-A's 2g lands (coordinator decision; LangCheckTest forbids unused keys).
- The typed-`-Xmx` line: under `ram-*` advice for the Modrinth App (and GDLauncher later) when `jvm-xmx-duplicate` holds.
- Share report: `- Java: <version> (<vendor>), <collector>, N argument notes`, no argument text.

## Tasks
- [ ] 1. `core/jvm/JvmArgs` + `JvmArgsTest` (AC6.1): units and signs, last-wins duplicates, `-D` markers (keys only),
      junk tokens, empty/null list, max-heap count (`-Xmx` and `-XX:MaxHeapSize`).
- [ ] 2. `JvmSnapshot`, `VmOptions` (FOUND value+origin / MISSING / ERROR), `JvmFinding`, `JvmFacts`, `JvmReport`
      (extended; `UNAVAILABLE` kept), `JvmFlagClassifier` + `JvmFlagClassifierTest` (AC6.2): the user's Modrinth line, the
      official launcher's pre-26.1 G1 default line and its 26.1+ ZGC 4 GB default, ATLauncher's defaults, Prism's line → no
      findings; Aikar's set → SERVER_SET; ZGenerational missing → IGNORED; UseNUMA false → OVERRIDDEN; `-Xmn1G` →
      YOUNG_GEN_FIXED; ParallelGC from ENVIRON_VAR → STOP_THE_WORLD_GC; ergonomic G1 → nothing; Epsilon → NO_GC;
      DisableExplicitGC alone/with Aikar; two `-Xmx`; no HotSpot bean → no facts, no exception; nothing contains a path or `=`.
- [ ] 3. ConditionEvaluator `jvm-` prefix (one private method + one dispatch line) + `JvmFlagEvaluationTest` (J-M1: not/anyOf
      over a profile without JVM facts → UNKNOWN; unknown `jvm-` name → UNKNOWN; `jvm-probed` → UNKNOWN).
- [ ] 4. LauncherAdvice `JVM_ADVICE_PREFIX`, `isJvmAdvice`, `jvmStepsKey`; LauncherInfo `jvmStepsKey()`, `typedXmxWins()`;
      JvmReport `flagsFor(adviceId)`; lang keys; `JvmAdviceLinesTest`.
- [ ] 5. ShareReport Java line (new overload; old ones delegate) + `ShareReportJvmTest`; RealController.shareReport passes
      `jvmService.report()` (one line).
- [ ] 6. `client/probe/JvmProbe` (once per session, timeout, test seam `inject`/`reset`, `withFacts`) + `JvmProbeTest`
      (the real test JVM: available, facts include `jvm-probed`, never throws; the timeout copy); HardwareProbe one call;
      JvmService.report().
- [ ] 7. LauncherLines.adviceLine with the JvmReport (found-flags line + Java-arguments steps under `jvm-*`; typed-`-Xmx` line under
      `ram-*`); RigTuneScreen passes `controller.jvmReport()` at its two call sites.
- [ ] 8. JvmScreen (Java version/vendor, collector typed or Java's default, max/initial heap, findings, fired `jvm-`/`ram-`
      advice with their lines; unavailable and "checking" states; scrolling list; fits 640×480@2).
- [ ] 9. `-PgametestJvmArgs` hook (runClientGameTest + runProductionClientGameTest; local only) and JvmGameTest (AC6.4):
      network off; the CI JVM → G1, no `jvm-` advice; injected snapshot + brand theseus → findings line and Modrinth App
      steps (stub report now; the real bundled-rules report once WS-R lands); screenshots at 3 sizes.
- [ ] 10. Fixture-rules scenario test (`src/test/resources/jvm/`) now; after WS-R: merge origin/feat/v0.4.0, `JvmScenarioTest`
      over the bundled rules (AC6.3: each advice fires with the launcher line, defaults fire nothing, the pinned 0.2/0.3 skip
      via `requires`, RulesV1DifferentialTest unchanged).
- [ ] 11. README "JVM & memory advice" (incl. flags that stop Java from starting); design doc `docs/v0.4/design/ws-j.md` with
      the AC6.5 number checklist.
- [ ] 12. Self-review (code-reviewer subagent), fixes; merge origin/feat/v0.4.0 (WS-A: multimc/gdlauncher steps), build,
      push, CI green, screenshots checked.
