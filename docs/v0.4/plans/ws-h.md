# WS-H: self-update E2E for 0.4 Implementation Plan

> **For agentic workers:** executed inline by WS-H, TDD per task, commit after each. Steps use checkbox (`- [ ]`) syntax.

**Goal:** The self-update harness (tools/e2e) proves the self-update path to the new version from every released version (0.3.0, 0.2.0, 0.1.0), the seeded real-state 0.1.0 variant, and undo-after-restart (Undo last + Undo this), with Python tests for every harness change and dry-run evidence on a 0.4.0-dev build; plus a documented hook for a profile-switch entry in undo-after-restart (Phase 5, after WS-P).

**Architecture:** Unchanged shape: one script (`self_update_e2e.py`), pure assertions (`e2e_checks.py`), templating (`fixtures.py`). The self-update driver (src/e2e) keeps compiling against the OLD released jar (`-Pe2e.oldJar`); it already compiles against 0.3.0 unchanged. What the old side journals is derived from its version (`0.1.x` → the new version's legacy import; `0.2+` → its own `apply` entry of the update), so the expectation isn't hard-wired to "0.2" any more. The profile hook is a third part of the undo scenario (`profile-*` phases) behind `--profile-switch`: `settings` (now) applies vanilla SetSetting changes through `controller.apply`, which is exactly what a profile switch is (docs/research/v0.4/profiles.md §0.1-2); `profile` (Phase 5) switches through WS-P's API and also checks the `profiles.json` label keyed by the entry id.

**Tech Stack:** Python 3.11 stdlib (unittest), Java 25 driver mods, Gradle `:26.2:e2eClient`, PowerShell 7 for process checks.

**Spec:** docs/v0.4/PLAN.md (WS-H, Global Constraints); docs/v0.4/SPEC.md item 3 (in progress in parallel).

## Findings before planning
- Released `rigtune-0.3.0+mc26.2.jar` (GitHub release v0.3.0): sha256 `5717f65cb90c71aaeda844b7bd56e3ce9255e83f44418af0cfc6a589050cd7e9`, equal to the release asset's digest (`gh release view v0.3.0 --json assets`).
- `./gradlew :26.2:compileE2eJava -Pe2e.oldJar=<rigtune-0.3.0+mc26.2.jar>` compiles the unchanged driver: no build.gradle change.
- 0.3.0's journal format is 0.2.0's (formatVersion 1, same records); its User-Agent is `chaotix345/rigtune/<version> (...)`, as the download check expects.
- `mod_version` is still 0.3.0 on this branch (WS-0 bumps it): the dry runs build the new jar with `-Pmod_version=0.4.0-dev`.

## Tasks
- [x] **T1 0.3.0 old side.** build.yml's "Compile the E2E drivers" step downloads and sha256-pins the 0.3.0 jar and compiles the driver against it. Harness: `RELEASED` table; `prepare()` refuses a jar whose version is a released one but whose bytes aren't the release's (`ReleasedJarTest`).
- [x] **T2 generalise.** `history_expectation(old_version)` and `--expect-history auto` (without the option there is no history check, as in v0.3); a mismatching explicit value is refused (`HistoryExpectationTest`). Version-neutral titles and check names; the 3e WARN pattern as a constant.
- [x] **T3 dry runs** on 0.4.0-dev: dev-v030-to-040, dev-v020-to-040, dev-v010-to-040, dev-v010-seeded-to-040, dev-undo-after-restart-040 → docs/smoke/self-update/dev-*-040/ (PLAN's "dev-*-v04"; named like v0.3's dev runs).
- [x] **T4 profile part** (reworked after plan review P-H1): two switches before one restart on an instance with Sodium; Undo last twice, and Undo all on a copy; `--profile-switch {settings,profile}`; the stand-in dry run found SPEC amendment 2n.
- [x] **T5 README "v0.4 runs"** with the exact Phase 5 commands (one lock hold per pair).
- [x] **T6** self-review (code-reviewer subagent, twice), merge origin/feat/v0.4.0, `./gradlew build`, push, CI green, `docs/v0.4/design/ws-h.md`.
- [x] **T7 released-jar compatibility harness** (AC3.3, plan review H-M1): `compat030.py` + `compat/Compat030.java` against the released 0.3.0 jar, Gson 2.14.0, fabric-loader, slf4j; in CI's driver step.
- [x] **T8 "written by 0.4" placeholder sets** in `src/test/resources/v040-written/placeholder/<set>/` (SPEC C1 shapes), composed by `written.py`.
- [x] **T9 downgrade-040-to-030** (AC3.2): `--scenario downgrade`, driver `src/e2eDowngrade` compiled against the released jar; dry run on the placeholder sets.
