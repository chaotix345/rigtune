# WS-V: Minecraft-version tooling, implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (inline; one agent). Steps use checkbox (`- [ ]`) syntax.

**Goal:** make adding a Minecraft version one command (`tools/add_mc_version.py`), commit the trial's API-diff harness as `tools/mc_apidiff.py`, and replace DESIGN.md's porting steps with the research checklist.

**Architecture:** two standalone Python 3.11 stdlib scripts under `tools/`. `add_mc_version.py` gathers facts from the Mojang manifest, Fabric meta, the Fabric and Terraformers mavens, Modrinth and the Fabric blog feed through one injectable HTTP opener, builds an edit plan (settings.gradle + `versions/<mc>/gradle.properties`), then prints it (`--dry-run`) or writes it. `mc_apidiff.py` parses RigTune's compiled class files and the MC/Fabric/Mod Menu jars from the Gradle and Loom caches with a small class-file reader (no javap needed for resolution), resolves every external member reference on both versions, and uses `javap -p -s -constants` for the human-readable class diffs.

**Tech Stack:** Python 3.11 stdlib (`argparse`, `json`, `re`, `struct`, `zipfile`, `urllib`, `xml.etree`, `subprocess`), `unittest`; JDK 25 `javap`.

**Spec:** docs/v0.3/SPEC.md item 1 (D, add_mc_version.py, the Porting checklist; AC1.2, AC1.3, AC1.6) with amendments V-M1 (mc_apidiff.py P1; add_mc_version.py first to cut) and V-L1 (tools/MC_VERSIONS.md; WS-V owns tools/tests/test_add_mc_version.py). Research: docs/research/v0.3/mc-versions.md §4.3, §4.4, §5.2.

## Global Constraints
- Python 3.11 stdlib only; UTF-8 writes with `newline="\n"`; no string literals with Windows backslashes.
- The repo's User-Agent form: `chaotix345/rigtune-add-mc-version/1.0 (github.com/chaotix345/rigtune)` on every request; Modrinth throttled to 1 request/s.
- Unit tests are offline (fixtures under `tools/tests/fixtures/add_mc_version/`), run by `python -m unittest discover -s tools/tests` on CI (ubuntu, Python 3.11, no JDK assumed).
- WS-V never touches `tools/update_rules.py`, `tools/README.md`, build.gradle, stonecutter.gradle, settings.gradle, `.github/workflows/*`. `settings.gradle` is edited only by the tool, never committed with a new node on this branch.
- `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before every `./gradlew` or javap; never `./gradlew --stop`; committed Stonecutter version 26.2; no force push; commit trailers per PLAN.
- Only WS-V edits docs/DESIGN.md (its "Porting to new MC versions" section).

## File structure
| File | Responsibility |
|---|---|
| `tools/add_mc_version.py` (new) | CLI; `Http` (UA, retries, Modrinth throttle); `gather()` → `Facts`; `plan_edits()` → `Edits`; `apply_edits()`; checklist printer |
| `tools/mc_apidiff.py` (new) | CLI; `parse_class()`; `collect_refs()`; `ClassIndex` (jars → classes, JDK via javap); `resolve()`; `Classpath` discovery from the caches; javap dumps diff; string checks; optional bytecode compare; report |
| `tools/tests/test_add_mc_version.py` (new) | AC1.2 tests + ordering/throttle/UA tests |
| `tools/tests/test_mc_apidiff.py` (new) | parser/resolution/report tests on synthesized class files |
| `tools/tests/fixtures/add_mc_version/*` (new) | trimmed copies of the live responses (2026-09-26) plus synthetic ones (Java 26 version, a future release) |
| `tools/MC_VERSIONS.md` (new) | how to add/drop a version and handle a hotfix; both tools' usage and output |
| `docs/DESIGN.md` | "Porting to new MC versions": steps 1-4 replaced by the §4.4 checklist |
| `docs/v0.3/verification/mc-tooling/` (new) | AC1.3 dry-run output; AC1.6 apidiff output (summary, report.json, class diffs) |
| `docs/v0.3/design/ws-v.md` (new) | design notes and deviations |

---

### Task 1: add_mc_version.py pure core (ids, ordering, settings.gradle, properties)

**Files:** Create `tools/add_mc_version.py`, `tools/tests/test_add_mc_version.py`.

**Interfaces (produces):**
- `parse_mc_id(mc) -> McId(base: str, kind: str, number: int)`; kind ∈ `snapshot`, `pre`, `rc`, `release`; raises `Refusal` for anything else (e.g. `25w14a`).
- `version_key(mc) -> tuple` (26.4-snapshot-1 < 26.4-pre-1 < 26.4-rc-1 < 26.4 < 26.4.1).
- `read_settings_versions(text) -> list[str]`; `insert_settings_version(text, mc) -> str` (inserts in version order, keeps quoting and the rest of the file; `Refusal` if the `versions` call isn't found exactly once).
- `minecraft_dependency(mc) -> str`: release `~<mc>`, pre-release `~<base>-`.
- `render_properties(values, template_text, prev) -> (text, copied_keys)`: known keys in the fixed order `minecraft_dependency, fabric_api_version, modmenu_version, sodium_version (only if set), # Iris comment, iris_version`; any other key in the newest existing file is copied unchanged (with its comment lines) and returned in `copied_keys`.

- [ ] Step 1: failing tests `test_parse_mc_id_*`, `test_version_key_orders_prereleases_before_release`, `test_insert_settings_version_{appends,in_order,refuses_without_versions_call}`, `test_minecraft_dependency_{release,snapshot}`, `test_render_properties_{full,no_sodium_iris_fallback,copies_unknown_keys}`:
```python
def test_insert_settings_version_in_order(self):
    text = "\t\tversions '26.2', '26.3'\n"
    self.assertEqual(amv.insert_settings_version(text, "26.2.1"), "\t\tversions '26.2', '26.2.1', '26.3'\n")
```
- [ ] Step 2: run `python -m unittest tools.tests.test_add_mc_version` → ImportError/fail.
- [ ] Step 3: implement the functions.
- [ ] Step 4: tests pass.
- [ ] Step 5: commit `feat(tools): add_mc_version core: ids, ordering, settings.gradle and properties`.

### Task 2: add_mc_version.py network, checks and CLI (AC1.2)

**Files:** Modify `tools/add_mc_version.py`, `tools/tests/test_add_mc_version.py`; create fixtures `tools/tests/fixtures/add_mc_version/{manifest.json, version-26.3.json, version-26.4-snapshot-1.json, version-26.5.json, fabric-loader-26.3.json, fabric-loader-26.4-snapshot-1.json, fabric-api-maven-metadata.xml, modmenu-maven-metadata.xml, modrinth-<project>-<mc>.json, feed.xml, post-263.html}`.

**Interfaces:**
- `Http(opener=default_opener, sleeper=time.sleep, clock=time.monotonic)`: `.json(url)`, `.text(url)`, `.modrinth(path, params)`; UA header on every request; `MODRINTH_MIN_INTERVAL = 1.0`; retries 429/5xx/`OSError` (3 attempts, Retry-After honoured, capped at 30 s); returns `None` for 404/400 where "none" is a valid answer (Fabric meta unknown version).
- `gather(mc, http, repo) -> Facts` (manifest entry, version JSON java major, loader list, Fabric API pick, Mod Menu/Sodium/Iris picks, blog lines).
- `pick_modrinth(versions) -> dict | None` (release, then beta, then alpha; newest `date_published` within a type).
- `main(argv, *, root=ROOT, http=None, out=sys.stdout, err=sys.stderr) -> int`: 0 ok, 1 refused.

Checks in order (each a `Refusal` with a message, nothing written): not already a node (settings.gradle or `versions/<mc>/` exists); in the Mojang manifest; `type == release` unless `--prerelease-ok`; Java major 25; Fabric meta lists a loader for it (warn if the repo's `loader_version` isn't listed); a Fabric API build on Modrinth for `<mc>` that is also in the Fabric maven metadata; a Mod Menu build on Modrinth that is also on the Terraformers maven.

- [ ] Step 1: capture the live responses (2026-09-26) into the fixtures, trimmed to the fields used; write the AC1.2 tests with a `FakeOpener(url → (status, body))` that fails on unscripted URLs:
  - `test_release_adds_node_and_writes_properties`: a repo with only 26.2 → `add 26.3` writes settings `'26.2', '26.3'` and a properties file equal to the committed `versions/26.3/gradle.properties`.
  - `test_snapshot_refused_without_prerelease_ok` (exit 1, nothing written).
  - `test_snapshot_without_sodium_has_no_sodium_version` (`--prerelease-ok`; iris falls back to 26.3's id with a comment; `~26.4-`).
  - `test_existing_node_refused`, `test_dry_run_writes_nothing` (files byte-identical, no `versions/<mc>/`; the planned edits are printed), `test_java_not_25_refused`, `test_unknown_version_refused`, `test_no_fabric_api_refused`, `test_no_modmenu_refused`.
  - `test_every_request_sends_user_agent`, `test_modrinth_calls_are_one_second_apart` (fake clock + recording sleeper).
  - `test_checklist_printed` (mentions `./gradlew :<mc>:build`, `mc_apidiff.py <prev> <mc>`, `Reset active project`).
- [ ] Step 2: run → fail.
- [ ] Step 3: implement `Http`, `gather`, checks, `main`, the edit printer (unified diff of settings.gradle + the new file), the Fabric blog lookup (Atom feed link titled `Fabric for Minecraft <base>`, Loom/Gradle sentences) and the checklist (hotfix note to narrow the old node's range; release.yml note if it still names `:<prev>:modrinth`; Sodium note if build.gradle doesn't guard `sodium_version`).
- [ ] Step 4: `python -m unittest discover -s tools/tests` green.
- [ ] Step 5: commit `feat(tools): add_mc_version.py fetches, checks and writes a new Stonecutter node (AC1.2)`.

### Task 3: live dry run (AC1.3)
- [ ] Run `python tools/add_mc_version.py 26.4-snapshot-1 --prerelease-ok --dry-run > docs/v0.3/verification/mc-tooling/add_mc_version-26.4-snapshot-1-dry-run.txt 2>&1`; check it shows `'26.2', '26.3', '26.4-snapshot-1'`, `minecraft_dependency=~26.4-`, `fabric_api_version=0.161.1+26.4`, `modmenu_version=22.0.0-alpha.1`, no `sodium_version`, the 26.3 Iris id with a fallback comment, and `git status` clean apart from the output file.
- [ ] Commit `docs(v0.3): AC1.3 live dry run of add_mc_version.py for 26.4-snapshot-1`.

### Task 4: mc_apidiff.py class reader, references and resolution

**Files:** Create `tools/mc_apidiff.py`, `tools/tests/test_mc_apidiff.py`.

**Interfaces:**
- `parse_class(data: bytes) -> ClassInfo(name, super, interfaces, access, fields: dict[(name, desc)] -> access, methods: dict[(name, desc)] -> access, refs: set[Ref(kind, owner, name, desc)], class_refs: set[str], strings: set[str], annotation_strings: set[str], utf8: set[str])`.
- `collect_rigtune(class_dirs) -> RigTuneRefs(refs, types, strings, files)`: only owners under `net/minecraft/`, `com/mojang/`, `net/fabricmc/`, `com/terraformersmc/`, `org/lwjgl/`; types also from descriptors, annotation types and dotted class-name strings (reflection).
- `ClassIndex(jars, jdk_lookup)`: `.get(name) -> ClassInfo | None`; `java/*` types come from `jdk_lookup` (javap-backed in the CLI, a dict in tests).
- `resolve(index, ref) -> Resolution(declaring_class, access) | None` per JVMS 5.4.3.2/5.4.3.3 (fields: class, superinterfaces, superclass; methods: class chain, then superinterfaces).
- `compare_refs(refs, old_index, new_index) -> list[RefResult]`, status `OK`, `MISSING` (resolves on old only), `CHANGED` (static or access narrowed), `UNRESOLVED` (neither side; a tooling gap, reported).

- [ ] Step 1: a test-side `ClassWriter` producing minimal class files; failing tests: `test_parse_reads_members_and_refs`, `test_collect_keeps_only_external_owners`, `test_resolve_through_superclass_and_interface`, `test_missing_member_on_new_is_breaking`, `test_static_change_is_breaking`, `test_jdk_inherited_member_resolves`, `test_reflection_strings_become_types`.
- [ ] Step 2: fail. Step 3: implement. Step 4: pass.
- [ ] Step 5: commit `feat(tools): mc_apidiff.py class reader and reference resolution`.

### Task 5: mc_apidiff.py classpaths, javap diffs, strings, report, CLI

**Interfaces:**
- `find_classpath(mc, props, gradle_home, loom_cache) -> list[Path]` (Loom `minecraft-{clientonly,common}-deobf/<mc>`, the version's Mojang libraries from `fabric-loom/<mc>/mojang_minecraft_info.json` or the manifest-free fallback, the Fabric API modules listed in the cached umbrella pom, Mod Menu, Fabric Loader); raises `SetupError` naming each missing jar and how to get it.
- `node_props(root, mc, overrides) -> dict` (`versions/<mc>/gradle.properties`, else `--fabric-api`/`--modmenu`).
- `javap_dumps(names, classpath)`, `diff_dumps(old, new) -> {name: SAME|DIFF|MISSING}` + unified diffs.
- string check: for each referenced class on both sides, String constants that RigTune also uses and that the new version lost; reflection/annotation names (e.g. `serverRenderDistance`, `logFrameDuration`) present on old but missing on new.
- optional `--bytecode`: when `versions/<mc>/build/classes` exists, `javap -c -p -constants` of both builds with constant-pool indices stripped.
- exit 0 no breaking change, 1 breaking change, 2 setup error. `--out <dir>` writes `summary.txt`, `report.json`, `diff-<class>.txt`.

- [ ] Step 1: failing tests `test_report_counts_and_exit_code`, `test_setup_error_names_missing_jars`, `test_string_removed_is_flagged`, `test_node_props_override`.
- [ ] Steps 2-4: fail, implement, pass.
- [ ] Step 5: commit `feat(tools): mc_apidiff.py compares two versions' APIs from the Gradle caches`.

### Task 6: AC1.6 live run
- [ ] `./gradlew :26.3:classes :26.3:clientClasses :26.3:gametestClasses :26.3:compileE2eUndoJava :26.3:compileE2eJava -Pe2e.oldJar=<v0.1.0 release jar>`.
- [ ] `python tools/mc_apidiff.py 26.3 26.4-snapshot-1 --fabric-api 0.161.1+26.4 --modmenu 22.0.0-alpha.1 --out docs/v0.3/verification/mc-tooling/apidiff-26.3-26.4-snapshot-1`; expected: every reference resolves, 0 missing classes, the 13 changed classes of research §5.3 (plus any from e2e), exit 0. Compare against research §5.2 numbers and explain differences.
- [ ] Commit `docs(v0.3): AC1.6 mc_apidiff 26.3 -> 26.4-snapshot-1 (no breaking change)`.

### Task 7: docs
- [ ] `tools/MC_VERSIONS.md`: the checklist (research §4.4), both tools (usage, what they check, output, exit codes, where the jars come from and how to get the snapshot's jars without adding a node), hotfix steps (§3), dropping a version.
- [ ] `docs/DESIGN.md` Porting: steps 1-4 replaced by the checklist, pointing at tools/MC_VERSIONS.md.
- [ ] Commit `docs: porting checklist and tools/MC_VERSIONS.md`.

### Task 8: review, design doc, CI
- [ ] Dispatch a code-reviewer subagent on `git diff feat/v0.3.0...HEAD`; carry on; fix high/medium findings when forwarded.
- [ ] `docs/v0.3/design/ws-v.md` (decisions, deviations, UNVERIFIED).
- [ ] `python -m unittest discover -s tools/tests` green; push; `gh run watch <id> --exit-status` green on every job.
- [ ] When WS-0 has merged: merge `origin/feat/v0.3.0`, re-run the tests, push, CI green.

## Self-review
- AC1.2 → Task 2 (release, snapshot refused, no Sodium, existing node, dry run, Java ≠ 25). AC1.3 → Task 3. AC1.6 → Tasks 4-6. D (committed harness) → Tasks 4-5. Porting → Task 7. V-L1 → Task 7 (MC_VERSIONS.md), Task 2 (test file).
- Not in scope: update_rules.py targets (WS-D, change C), release.yml loop (WS-0, change B), optional Sodium (WS-0, change A); the tool only warns when B or A are missing.
