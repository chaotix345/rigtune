# 0.6.0 checklist: Minecraft 26.3 and 26.4, 26.2 dropped

One list for whoever ports RigTune to 0.6.0. Each item names where the detail lives, so you only open the source
when you need it. Line numbers are from main on 2026-11-23 and will drift; search for the quoted text if one has moved.
Items marked **Decide** are open questions the sources don't settle. Settle them with HQ before the port PR merges.

Sources: [docs/research/v0.6/mc-26.4.md](../research/v0.6/mc-26.4.md) (the breaks),
[docs/research/fabric-loader-0.20.md](../research/fabric-loader-0.20.md),
[tools/MC_VERSIONS.md](../../tools/MC_VERSIONS.md), [docs/DESIGN.md](../DESIGN.md) "Porting to new MC versions",
[docs/hq.md](../hq.md) "Releasing", [docs/STATUS.md](../STATUS.md) "Release plan" and "Approved by Charlie",
[docs/PROGRESS.md](../PROGRESS.md) "v0.6 backlog".

## 1. What to wait for

- [ ] Minecraft 26.4 is a `release` in Mojang's manifest. `add_mc_version.py` refuses anything else without
      `--prerelease-ok`, and a pre-release node isn't shipped (MC_VERSIONS.md "Version ranges"). Work can start on a
      pre-release in a scratch node; the committed `26.4` node waits for the release.
- [ ] Sodium and Iris both have 26.4 builds. Both are conditions of Charlie's 0.6.0 approval (STATUS.md "Approved by
      Charlie", 16 Nov).
- [ ] Fabric API has a stable 26.4 build, and Mod Menu has a build for 26.4 itself (MC_VERSIONS.md "`add_mc_version.py`"
      check 7: a release needs its own Mod Menu build).
- [ ] Read Fabric's "Fabric for Minecraft 26.4" post. If it asks for a newer Loom or Gradle, upgrade them first in a
      PR of their own (MC_VERSIONS.md "Adding a version" step 1). Loom is pinned at `gradle.properties:13`.
- [ ] 26.4's version JSON still asks for Java 25 (`add_mc_version.py` check 4).
- [ ] Check what main holds now: `git log v0.5.2..origin/main --oneline -- src/ versions/`. If the phase-1 compile
      port (research "Port size" step 1) already merged, section 4 is mostly done. Today the only 26.4 blocks are
      `BenchmarkWorld.java:330` and `BenchmarkGameTest.java:337`.

## 2. Remove the 26.2 node

Charlie decided 0.6.0 supports 26.3 and 26.4 only; 0.5.2 stays on Modrinth for 26.2 players (STATUS.md "Approved by
Charlie", 16 Nov). 26.2 is the committed version, so it moves with the drop (MC_VERSIONS.md "Dropping a version",
DESIGN.md line 344).

Build and Stonecutter:
- [ ] `settings.gradle:31` `versions '26.2', '26.3'`: remove `'26.2'`.
- [ ] Move the committed state to 26.3, all three together: `settings.gradle:32` `vcsVersion`, `stonecutter.gradle:6`
      `stonecutter.active`, and `.github/workflows/build.yml:142-145` (the step name and `VCS_MC`). Run
      `./gradlew "Set active project to 26.3"` before changing them, so `src/` is in the 26.3 state when you commit.
      Afterwards `./gradlew "Reset active project"` should leave `git diff` showing only your edits.
- [ ] Delete `versions/26.2/`.
- [ ] Delete the conditional branches only 26.2 used (Stonecutter doesn't prune them). `RowList.java:32`
      (`//? if <26.3`) goes. Every `//? if >=26.3` block becomes plain code: `RigTuneClient.java:82`,
      `HardwareProbe.java:7` and `:109`, `A11yGameTest.java:361` and `:497`, `FirstApplyGameTest.java:562`,
      `UiGameTest.java:492`, `RowFocusTest.java:63`. Update DESIGN.md line 330, which lists them.
- [ ] `build.gradle:388` (a comment) and `:630` (an error message) name `:26.2` and the 26.2 jar.

CI and the E2E harness. These steps name `:26.2` or 26.2 jars directly, so they break or lose coverage once the
Gradle project is gone:
- [ ] `build.yml:167-175` compile the E2E drivers on `:26.2` against the 0.1.0 to 0.4.0 `+mc26.2` jars. Remove the
      `:26.2` lines; the `:26.3` lines stay, and add `:26.4` ones if the drivers should compile there too.
- [ ] `build.yml:185-186` run `compat040.py` and `compat030.py` on the `+mc26.2` jars. `compat030.py:94` and
      `compat040.py:30` expect those exact versions. These run no client, so the 26.2 jars may keep working with no
      node. **Decide**: keep them, or move to the `+mc26.3` jars (their sha256s are already pinned at `build.yml:137`
      and `:139`) and update the two scripts and their tests.
- [ ] `tools/e2e/e2e_matrix.py:31` `DEFAULT_NODE = "26.2"`. The matrix builds rows only for nodes in `versions/`
      (`gametest_matrix.nodes`), so these release-tier rows disappear without an error: `upgrade-from-0.1.0`, the
      `v010-dh` seed, both stale seeds, and `brand-theseus` (`:93`). **Decide**: move the seed and brand rows to 26.3
      (the seeds name `+mc26.2` mod jars, e.g. `tools/e2e/seeds/v010-dh/last-apply.json:7`), or accept the loss. The
      0.1.0 upgrade can't move: 0.1.0 was built for 26.2 only.
- [ ] `tools/e2e/self_update_e2e.py:56-60` (`RELEASED` keeps the 26.2 jars; fine if unused) and `:1538` (`--mc`
      defaults to `26.2`).
- [ ] `tools/ci_streak.py:29` `REQUIRED_LEGS` names `"26.2, OpenGL"`. Replace it with the 26.4 legs.
- [ ] Python tests that name 26.2: `test_add_mc_version`, `test_check_rules_v1`, `test_ci_streak`,
      `test_gametest_matrix`, `test_modrinth_project`, `test_projection`, `test_rules_v05`, `test_update_rules`
      (`tools/tests/`). Most use fixtures and should pass unchanged; the gate shows which don't.
- [ ] Java tests under `src/test/` with `"26.2` strings (about 20 files, e.g. `BenchmarkWorldTest`, `FabricPinsTest`).
      Most are fixture data; fix the ones `./gradlew build` fails.

Rules (live on merge; CLAUDE.md "Hard rules"):
- [ ] **Decide before the port merges.** `tools/update_rules.py` takes its target versions from `settings.gradle`
      (tools/README.md "What it does" step 2). Once 26.2 is gone from main, the next run, by hand or by the Monday bot,
      writes rules with no 26.2 `availability`. Installed 0.5.2 on 26.2 still reads those rules. Offline, a missing
      version makes every mod count as available with "(availability not confirmed)" (`Recommender.java:517-529`,
      RULES_SCHEMA.md line 120); online it asks Modrinth and isn't affected. To keep 26.2 covered, `update_rules.py`
      needs a list of retired versions it still targets. `--mc-versions` in `update-rules.yml` would also work, but it
      turns off hotfix discovery. Or accept the offline gap for 26.2.
- [ ] `rules/source/knowledge.json:375` (vulkanmod: "has no Minecraft 26.2 release") and `:1049` (mixintrace-reborn:
      "no Fabric build for 26.2 or 26.3"): reword for 26.3 and 26.4 when you regenerate (section 8).

## 3. Add the 26.4 node

- [ ] `python tools/add_mc_version.py 26.4 --dry-run`, then without `--dry-run`. It writes the `settings.gradle` entry
      and `versions/26.4/gradle.properties` (`~26.4`, Fabric API, Mod Menu, `sodium_version`, `iris_version`) and
      prints its own checklist (MC_VERSIONS.md "`add_mc_version.py`").
- [ ] `./gradlew :26.4:build` with `JAVA_HOME` on JDK 25.

## 4. Fix the compile breaks

Details and fix shapes: research doc "The breaks"; every error line: its appendix. The lines there are 26.4-snapshot-7's.
The canary found the same break categories on snapshot-8 and snapshot-10 (STATUS.md "Where it stands", Recent changes 19
Nov), so expect the same list on the release and recheck the lines.

- [ ] Rewrite the existing `//? if >=26.4-alpha` blocks as `>=26.4` (MC_VERSIONS.md "Version ranges"):
      `BenchmarkWorld.java:330`, `BenchmarkGameTest.java:337`. With 26.2 gone, every `else` branch means 26.3.
- [ ] Break 1, the extract pass lost `mouseX, mouseY, partialTick` (88 of 99 errors): research §1. Shared helpers
      instead of 45 conditionals: `RigTuneScreenBase` for the 19 screens, `RowEntry` for the 22 entries, one block each
      in `RowList.extractItem` and `RowFocus.extractWidgetRenderState`, and `Widgets.extract` for the three direct calls.
- [ ] Break 2, `AbstractWidget.visible` is protected: research §2. `RigTuneClient:295`, `:318`, via `Widgets.visible`.
- [ ] Break 3, `FormattedText.getString(int)`: research §3. `BenchmarkResultScreen:402`, `StutterScreen:713`, `:716`,
      via `Texts.plain`.
- [ ] Break 4, the `HudElement` lambda lost `DeltaTracker`: research §4. `RigTuneClient:100`.
- [ ] Break 5, `CycleButton` split: research §5. No source change; the recompile fixes it.
- [ ] Break 6, OSHI 7.7.2 dropped `oshi.SystemInfo`: research §6. `HardwareProbe:188`, `PowerWatcher:127`, through
      one `Oshi.hardware()` helper. Minecraft still bundles OSHI, so add no dependency.
- [ ] `python tools/mc_apidiff.py 26.3 26.4 --out build/apidiff` and review every changed class (MC_VERSIONS.md
      "`tools/mc_apidiff.py`"). Known items to check (research "Other changes to review"): the `Options` keys
      (`key.otherPlayers`, the new `preferDXGIPresent`), Vulkan as the default renderer, `isHighlighted()`,
      `GuiPipeline`, and `KeyEvent`'s `repeat`.
- [ ] Compile the E2E drivers on `:26.4` (`compileE2eJava`, `compileE2eUndoJava`, `compileE2eDowngradeJava`). The
      survey didn't check them (research "Other changes to review", last bullet).

## 5. Loader and Fabric API

- [ ] `gradle.properties:10` `loader_version=0.19.5` becomes `0.20.1`. Keep `"fabricloader": ">=0.19.5"` in
      `src/main/resources/fabric.mod.json:36` (fabric-loader-0.20.md "Options and costs", option 1). Add no extra
      CI leg.
- [ ] Check Fabric API for 26.4's own `fabricloader` requirement. If it needs more than 0.19.5, revisit option 3
      (fabric-loader-0.20.md).
- [ ] `versions/26.3/gradle.properties` pins Fabric API `0.161.0+26.3`; move it to the newest 26.3 build
      (0.166.2+26.3 on 16 Nov; STATUS.md "Where it stands", Ecosystem). Consider Sodium, Iris and Mod Menu there too.

## 6. The v0.6 backlog

STATUS.md "Release plan" item 2 folds these into 0.6.0, but they aren't conditions of the release approval. HQ picks
which ones ship. Source: PROGRESS.md "v0.6 backlog" (lines 42-47), plus STATUS.md "Backlog".

- [x] The preLaunch history reconcile off the render thread: done in PR #88 (on main since 16 Nov).
- [ ] The review-12/13 lows: R12FEAT-2..9, R12X-4/5, R12APPLY-4, R12REL-3/5/6, R12STUTTER-4/5/7, R13-2
      (docs/reviews/review-12.md, review-13.md). Triage which still apply on main first.
- [ ] STUTTER-9, STUTTER-10, STUTTER-11 (docs/reviews/review-11/STUTTER.md; status in docs/v0.5/design/ws-s2.md
      lines 477-484) and PERF-7 (docs/reviews/review-11/PERF.md; docs/v0.5/design/ws-ci.md line 317).
- [ ] The AC5.14 re-run under the fresh-baseline C20 flow (docs/v0.5/SPEC.md line 643). It was specified on 26.2,
      so re-run it on 26.3 or 26.4.
- [ ] PR #63's review nits in `NetChanges.java:44` and `PendingNet.java:35` (STATUS.md "Backlog"). Another worker had
      these on 23 Nov; check for its PR.
- [ ] `FrameHookBudgetTest` failing on a low-memory PC, without loosening the ceilings (STATUS.md "Backlog").

## 7. Tests and game tests

All Gradle runs go through the gate queue as background commands (docs/hq.md "The gate").

- [ ] The gate: `./gradlew build` and `python -m unittest discover -s tools/tests`, both unpiped. If
      `FrameHookBudgetTest` fails on a low-memory PC, rerun it alone and say so.
- [ ] `./gradlew :26.3:runClientGameTest`, then `:26.4:runClientGameTest`, one at a time under
      `C:/Dev/Worktrees/.gametest-lock` (CLAUDE.md "Hard rules"). Watch for hover and tooltip changes from the extract
      pass (research "Port size" step 2).
- [ ] CI's game-test legs come from `versions/` (`tools/gametest_matrix.py`), so they become 26.3 and 26.4, each on
      OpenGL and Vulkan, with no workflow edit. Check that the 26.4 OpenGL leg really runs OpenGL. 26.4 defaults to
      Vulkan, and a step fails any leg whose `latest.log` names the wrong backend (DESIGN.md "Client game tests in CI";
      the `SDL_OPENGL_FORCE_SRGB_FRAMEBUFFER` comment at `build.yml:249-252`).
- [ ] Footprint budgets on the new legs (`tools/footprint-budgets.json`). Ceilings, CPU-ms, bytes, 0-allocation and
      leak checks stay strict (STATUS.md "Approved by Charlie", 27 Sep).
- [ ] OSHI runtime checks on a real 26.4 client (research §6, "Runtime checks"): on Windows and Linux, GPU names, VRAM,
      CPU, memory and the battery filter read the same as on 26.3; a test asserts the card, CPU and RAM come from OSHI;
      one smoke test each in Prism, MultiMC, ATLauncher and GDLauncher.
- [ ] Production smoke test with Sodium, Iris and Distant Horizons on 26.4 (docs/smoke/README.md), and a benchmark
      with Sodium.
- [ ] The E2E release tier runs in the dry run (section 10); check its rows after the section 2 decisions.

## 8. Rules for 26.4

DESIGN.md "Porting" step 5; docs/hq.md "Rules updates". A change to what a rule recommends is a risky PR: a Fable
review plus the Codex second opinion.

- [ ] Review `vulkan-backend`, `ixeris`, `vulkanmod` and the `mixintrace-reborn` ignore note for 26.4, where Vulkan is
      the default. Decide whether a rule should mention DXGI present (research "Other changes to review").
- [ ] `python tools/update_rules.py`, then triage `rules/REVIEW.md`. Rerun `./gradlew test` afterwards; scenario
      tests read the bundled rules.
- [ ] `KnowledgeV2ScenarioTest`'s version list (MC_VERSIONS.md "Adding a version" step 6).
- [ ] Merging puts the rules live for every installed RigTune, 0.1.x to 0.5.2 included. Settle the 26.2
      availability decision (section 2) first, then verify the raw files (docs/hq.md "Merging").

## 9. Docs to update

- [ ] README.md:10 (badge), :35, :36.
- [ ] CONTRIBUTING.md:7, :12, :23 (the committed version), :51, and the CI legs at :17.
- [ ] DESIGN.md "Porting to new MC versions" (the tree at line 321, the conditionals at 330) and "Client game tests in
      CI".
- [ ] tools/README.md:28, :34, :140. tools/MC_VERSIONS.md: add a results line for `26.3 -> 26.4` under "Results so
      far" if useful.
- [ ] docs/guide: faq.md:7, jvm-memory.md:22. Leave verification.md and footprint.md: they describe what 0.4 and 0.5
      were measured on.
- [ ] docs/modrinth/body.md:3, :33. Pasting the new body into Modrinth is a listing edit, which needs Charlie's OK
      (STATUS.md "Approved by Charlie", 16 Nov).
- [ ] CLAUDE.md:3; docs/hq.md:23, :29 (the `FrameHookBudgetTest` rerun on `:26.2`), :70 (Releasing step 4 names
      `+mc26.2`), :92 (the worker brief context). docs/STATUS.md is HQ's.
- [ ] CHANGELOG.md: rename `## [Unreleased]` to `## [0.6.0] - <date>` (the Modrinth changelog is matched on that
      heading; `tools/e2e/modrinth_publish.py:51`). Add 26.4 support, the 26.2 drop and the loader move to the draft
      already there, and recheck `git log v0.5.2..origin/main` for anything merged since.
- [ ] A laptop check for Charlie, if HQ wants one (docs/release/0.5.2-laptop-check.md is the template).

## 10. Release

Charlie's pre-approval (STATUS.md "Approved by Charlie", 16 Nov) lets HQ release 0.6.0 without asking again, trip
included, when all of these hold:
- [ ] MC 26.4 is released, with Sodium and Iris builds.
- [ ] The full review is done: Fable, plus Codex for the risky parts.
- [ ] The gate passes, main is green, and the dry run passes.
- [ ] Charlie gets a heads-up before the tag and the result after. During the trip (to 9 Dec), time it for his daytime
      (STATUS.md "Approved by Charlie", 11 Nov), and copy it to the coordinating thread (docs/hq.md "Coordinating
      thread").
- [ ] Modrinth listing edits beyond the release itself, such as the body, still need his OK.

Steps (docs/hq.md "Releasing"; HQ only from step 2, since the release guard blocks workers):
1. [ ] Release PR: `mod_version=0.6.0` at `gradle.properties:19`, the CHANGELOG heading, `docs/modrinth/body.md`.
   Gate, merge, wait for main's build on the merge commit.
2. [ ] Dry run: `gh workflow run release.yml --ref main`. It must pass the build, the E2E release tier, the Modrinth
   preflight and the printed upload.
3. [ ] Heads-up to Charlie, then `git tag v0.6.0 <merge sha> && git push origin v0.6.0` from the HQ worktree. Watch
   the run.
4. [ ] Verify: `gh release view v0.6.0` has the main and sources jars for 26.3 and 26.4; Modrinth has `0.6.0+mc26.3`
   and `0.6.0+mc26.4` with matching sha512s (release.yml's verify step). Record the run and version ids in STATUS.md.
5. [ ] Send Charlie the result. Close #49 if a green canary run hasn't already. 0.5.2+mc26.2 stays listed.
6. [ ] If it goes wrong: docs/hq.md "Releasing" step 5. Never delete a published tag or version without Charlie.
