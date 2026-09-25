# Supporting newer Minecraft versions (v0.3.0, P0 item 1)

Research date: 2026-09-26. Every version, API and tool claim below was checked live on that date, or against the real jars in the Gradle cache, unless it is marked UNVERIFIED. All URLs were accessed on 2026-09-26.

## 1. Headline

- **There is no newer stable Minecraft to add.** 26.3 (2026-09-15) is the latest release, and there are no 26.2.x or 26.3.x hotfixes. The only newer build is `26.4-snapshot-1` (2026-09-22). 26.4 has no announced release date. Past cadence points to about December 2026 (§6).
- **The current `fabric.mod.json` ranges are right. Keep them.** `~26.2` and `~26.3` accept future hotfixes (26.3.1, 26.3.2, 26.3.1-rc-1) and reject every 26.4 build. I checked this with Fabric Loader 0.19.5's own predicate and version-normalization classes (§3).
- **Trial against `26.4-snapshot-1`: the code needs no changes.** Adding it as a third Stonecutter node took 2 files plus a 1-line `build.gradle` tweak. It compiled all source sets and passed 848/848 unit tests. The 26.3 and 26.4-snapshot-1 builds of all 470 RigTune class files are byte-identical after normalization. All 308 external member references resolve on the snapshot, and the option keys RigTune reads by reflection are unchanged (§5).
- **What would break is behaviour and rules, not the API.**
  1. Vulkan is the default renderer in 26.4-snapshot-1, so the `vulkan-backend` advice ("experimental Vulkan renderer…set it back to Default") becomes wrong.
  2. Sodium and Iris have no 26.4 build yet. The 26.3 Sodium on `localRuntime` makes Fabric Loader refuse to start the test JVM.
  3. On a snapshot, RigTune asks Modrinth about `26.4-alpha.1` instead of `26.4-snapshot-1`. This affects snapshots only (§5.4).
- **Adding a version is currently scattered across files.** `settings.gradle`, `versions/<mc>/gradle.properties` and one hard-coded step in `release.yml` must change, and several docs need hand edits. With two small one-time changes (§4.2), adding a version comes down to one script run, `tools/add_mc_version.py <mc>`, plus the steps only a human can do.

## 2. Verified versions (2026-09-26)

| What | Value | Source |
|---|---|---|
| Latest release / snapshot | `26.3` (2026-09-15T11:23Z) / `26.4-snapshot-1` (2026-09-22T13:38Z) | [Mojang manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json) `latest` |
| 26.x releases | 26.1 (03-24), 26.1.1 (04-01), 26.1.2 (04-09), 26.2 (06-16), 26.3 (09-15) | same manifest. **No 26.2.x or 26.3.x exists.** 26.1 got two hotfixes within 16 days, so a 26.3.1 is plausible. |
| Fabric game versions | `26.4-snapshot-1` stable:false, `26.3` stable:true | [meta /v2/versions/game](https://meta.fabricmc.net/v2/versions/game) |
| Fabric Loader | 0.19.5 (stable), listed for 26.4-snapshot-1; intermediary `0.0.0` (unobfuscated) | [meta /v2/versions/loader](https://meta.fabricmc.net/v2/versions/loader), [/loader/26.4-snapshot-1](https://meta.fabricmc.net/v2/versions/loader/26.4-snapshot-1) |
| Fabric API | newest `0.161.0+26.2`, `0.161.0+26.3`, `0.161.1+26.4` (beta on Modrinth, 2026-09-22, game_versions `["26.4-snapshot-1"]`) | [maven-metadata.xml](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml) (lastUpdated 2026-09-22), [Modrinth](https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%2226.4-snapshot-1%22%5D) |
| Fabric API's own `minecraft` range | `~26.3-` (0.161.0+26.3), `~26.4-` (0.161.1+26.4) | `fabric.mod.json` inside the jars in the Gradle cache |
| Loom | 1.17.21 (what `1.17-SNAPSHOT` resolves to) builds 26.4-snapshot-1. Latest is 1.18.2. | [plugin metadata](https://maven.fabricmc.net/net/fabricmc/fabric-loom/net.fabricmc.fabric-loom.gradle.plugin/maven-metadata.xml), trial build log |
| Gradle | wrapper 9.5.1 (enough for Loom 1.17); current is 9.8.0 | [services.gradle.org/versions/current](https://services.gradle.org/versions/current) |
| Mod Menu | 21.0.0 (26.3), `22.0.0-alpha.1` (26.4-snapshot-1, 2026-09-24, `minecraft: >=26.4-`), also on the Terraformers maven | [Modrinth](https://api.modrinth.com/v2/project/modmenu/version?game_versions=%5B%2226.4-snapshot-1%22%5D), [maven](https://maven.terraformersmc.com/releases/com/terraformersmc/modmenu/maven-metadata.xml) |
| Sodium, Iris, DH API | **none** for 26.4-snapshot-1. Sodium's 26.3 jar declares `minecraft: 26.3.x`. | Modrinth `/project/{sodium,iris,distanthorizonsapi}/version?game_versions=["26.4-snapshot-1"]` → `[]` |
| Fabric blog | nothing on 26.4 yet; newest post is "Fabric for Minecraft 26.3" | [fabricmc.net/blog](https://fabricmc.net/blog/) |

## 3. `minecraft` ranges and policy

What's declared today: `src/main/resources/fabric.mod.json:37` has `"minecraft": "${minecraft_dependency}"`, which `build.gradle:113-120` fills in through `processResources` (Stonecutter doesn't process `.json`). The values are `versions/26.2/gradle.properties:1` → `~26.2` and `versions/26.3/gradle.properties:1` → `~26.3`.

**Semantics, verified.** The [fabric.mod.json spec](https://wiki.fabricmc.net/documentation:fabric_mod_json_spec) says that `~` means "same version and up within the same minor version", that space-separated ranges are AND and that array elements are OR. I ran the real `VersionPredicate.parse`/`test` and `McVersionLookup.normalizeVersion` from `fabric-loader-0.19.5.jar`. Minecraft ids are normalized first, as Loader does at runtime:

| Mojang id | Loader-normalized | `~26.2` | `~26.3` = `[26.3,26.4-)` | `26.3.x` = `[26.3-,26.4-)` | `>=26.3-` | `~26.4-` = `[26.4-,26.5-)` |
|---|---|---|---|---|---|---|
| 26.2 / 26.2.1 | same | Y / Y | n | n | n | n |
| 26.3-rc-3 | 26.3-rc.3 | n | n | Y | Y | n |
| 26.3 | 26.3 | n | **Y** | Y | Y | n |
| 26.3.1-rc-1 | 26.3.1-rc.1 | n | **Y** | Y | Y | n |
| 26.3.1 / 26.3.2 | same | n | **Y / Y** | Y | Y | n |
| 26.4-snapshot-1 | **26.4-alpha.1** | n | n | n | Y | Y |
| 26.4-pre-1 / 26.4-rc-1 / 26.4 | 26.4-pre.1 / 26.4-rc.1 / 26.4 | n | n | n | Y | Y |

**A 26.3.1 hotfix would be accepted** by the shipped `+mc26.3` jar, and 26.4 would be rejected. That's the right outcome, so no range change is needed.

**Recommended policy:**
- Release nodes use `~<mc>` (for example `~26.3`). This accepts hotfixes and blocks the next drop, and it matches Fabric API (`~26.3-`) and Sodium (`26.3.x`). The trailing `-` only adds that version's own pre-releases, which don't matter once it's released.
- Pre-release nodes, if one is ever shipped, use `~<base>-` (for example `~26.4-`). Switch to `~26.4` when it goes stable.
- **Never use an open-ended `>=`.** Every drop so far has broken bytecode: 26.3 inlined `KEY_F8` differently and moved `GpuDevice`. `>=26.3-` would load the 26.3 jar on 26.4.
- If a hotfix does change an API RigTune uses, add a node for it (`26.3.1`, with `~26.3.1`). Narrow the old node to `>=26.3 <26.3.1-` so the two jars never both accept one version. The exact interval isn't tested, but it follows the verified semantics.

**Hotfix checklist (no new node):**
1. Build RigTune against the hotfix and bytecode-compare it with the build for the previous version (§5.2 method). Identical output means the release jar works on the hotfix.
2. Add the hotfix to the existing Modrinth versions' `game_versions`. Launchers filter by that list, and `build.gradle:181` uploads `gameVersions = [mcVersion]` only. Modrinth documents [`PATCH /version/{id}` with `game_versions`](https://docs.modrinth.com/api/operations/modifyversion/). `tools/modrinth_project.py` has no command for this yet.
3. Make sure `update_rules.py` targets the hotfix (§4.1, row "rules").

## 4. The one-step procedure to add a Minecraft version

### 4.1 Every file that changes today

| File | Change | Where the value comes from |
|---|---|---|
| `settings.gradle:21` | append `'<mc>'` to `versions`. `vcsVersion` stays `'26.2'`. | the Mojang id |
| `versions/<mc>/gradle.properties` (new) | `minecraft_dependency` | `~<mc>` (release) or `~<base>-` (pre-release) |
| 〃 | `fabric_api_version` | newest `<x>+<base>` in the Fabric maven `maven-metadata.xml`, cross-checked with Modrinth `/project/fabric-api/version?game_versions=["<mc>"]` (prefer `release`) |
| 〃 | `modmenu_version` | Modrinth `/project/modmenu/version?game_versions=["<mc>"]&loaders=["fabric"]` → `version_number`. It must also exist on `maven.terraformersmc.com`, which `build.gradle:23-26` resolves from. |
| 〃 | `sodium_version` | Modrinth `/project/sodium/version?...` → `version_number` (the `maven.modrinth` coordinate, e.g. `mc26.3-0.9.2-fabric`). **Omit it if none exists** (needs one-time change A). |
| 〃 | `iris_version` | Modrinth `/project/iris/version?...` → version **id** (convention in the existing files). If none exists, reuse the newest one: it's `compileOnly`, `transitive = false`, API only. |
| `gradle.properties:7` (+ `gradle/wrapper/gradle-wrapper.properties`) | only if Fabric's announcement asks for a newer Loom. Loom 1.18 needs Gradle ≥ 9.7 (multi-version.md §2). | the Fabric blog post for that MC version |
| `.github/workflows/release.yml:67-73` | add a `Publish to Modrinth (<mc>)` step. **This is the only hard-coded per-version CI list.** | — (removed by one-time change B) |
| `.github/workflows/build.yml` | **nothing.** `./gradlew build` builds every node, and `VCS_MC` stays `26.2`. | — |
| `src/**` | `//? if >=<mc> {` blocks for each compile error or semantic change | the API diff (§5.2) |
| `rules/source/knowledge.json` | review the rules whose text or conditions depend on the version: `vulkan-backend` (§5.4), `ixeris` ("26.3's new SDL"), `vulkanmod` ("no Minecraft 26.2 release"), `mixintrace-reborn` ignore note ("26.2 or 26.3"). Use `mcVersionRange` (a Fabric predicate, `ConditionEvaluator.java:288`) for version-specific rules. | a human, from the changelog and the diff |
| rules generation (`tools/update_rules.py:514-522`) | **nothing today.** Targets are the newest 3 Modrinth `release` tags, currently `[26.3, 26.2, 26.1.2]`. But after 26.4 plus a 26.4.1 hotfix that list becomes `[26.4.1, 26.4, 26.3]`, which **drops 26.2** while RigTune still ships a 26.2 jar. Offline availability for 26.2 would then be `UNKNOWN` (`Recommender.java:413-425`). Also, the client looks up `availability` by the exact runtime version string. | recommendation: derive the targets from the supported nodes plus their hotfix tags (one-time change C) |
| `src/test/.../KnowledgeV2ScenarioTest.java:346` | optional: add `<mc>` to `List.of("26.2", "26.3")`. It's the only test that enumerates supported versions. Other tests only use `"26.2"`/`"26.3"` as fixtures and need no change. | — |
| `README.md:7,44-45,91-105`, `docs/DESIGN.md:3,153,164-168`, `docs/modrinth/body-0.2.md:25` (or its successor), `CHANGELOG.md`, `tools/README.md:24,101` | prose that lists the supported versions, jar names and example commands | hand edit |

### 4.2 One-time changes that make it one step

- **A. Optional Sodium in `build.gradle:57`.** Use `if (project.hasProperty("sodium_version")) { localRuntime "maven.modrinth:sodium:${project.sodium_version}" }`. Without this, a node whose Sodium isn't out can't run its unit tests: `fabric-loader-junit` refuses to start (§5.1). I proved this in the trial.
- **B. Loop the Modrinth publish in `release.yml`.** The existing "Verify Modrinth files match" step already loops over `versions/*/`, so this makes adding a version a zero-touch change here. It keeps SPEC's independent-per-version property with `|| status=1` and `continue`:
  ```bash
  status=0
  for dir in versions/*/; do mc=$(basename "$dir"); ./gradlew ":$mc:modrinth" || { echo "::error::Modrinth publish failed for $mc"; status=1; }; done
  exit "$status"
  ```
  Also set `versionType` from the node: `alpha` when `mcVersion` contains `-snapshot-`/`-pre-`/`-rc-`. Today it's hard-coded `'release'` (`build.gradle:179`); the Minotaur dry-run for the trial node printed `"versionType": "release"`.
- **C. Drive `update_rules.py` targets from `settings.gradle`.** The targets should be every supported node, plus every Modrinth release tag that starts with `<node>.`, which covers hotfixes. `--mc-versions` still works as an override. Until then, add `--mc-versions` to `update-rules.yml`.
- **D. Commit the API-diff harness** (§5.2) as `tools/mc_apidiff.py`, so step 3 of the checklist is one command.

### 4.3 `tools/add_mc_version.py <mc>` — design (not written into the repo)

**Usage:** `python tools/add_mc_version.py <mc> [--prerelease-ok] [--dry-run] [--from <prev-mc>]`

**Checks (fails with a message):**
1. `<mc>` is in the Mojang manifest.
2. It isn't already in `settings.gradle`.
3. Its type is `release`, unless `--prerelease-ok` is given.
4. `javaVersion.majorVersion` in its version JSON is 25; otherwise it prints "toolchain change needed".
5. Fabric meta lists `loader_version` for it.
6. Fabric API has a build for it.

**Fetches:**
- Mojang manifest and the version JSON: type, date, client jar URL and sha1, Java version.
- Fabric meta: `/v2/versions/game` and `/v2/versions/loader/<mc>`.
- The Fabric API `maven-metadata.xml`, plus the Modrinth fabric-api versions for `<mc>`.
- Modrinth `modmenu`, `sodium` and `iris` versions for `<mc>` with `loaders=["fabric"]`. It prefers `release`, then `beta`, then `alpha`. For Mod Menu it also checks the Terraformers `maven-metadata.xml`.
- The Fabric blog index, where it looks for a "Fabric for Minecraft <mc>" post and prints its Loom and Gradle lines for the human.
- It sends the repo's User-Agent and follows the same 1 req/s Modrinth throttle as `update_rules.py`.

**Edits** (UTF-8, `newline="\n"`):
- `settings.gradle`: inserts `'<mc>'` into the `versions` call, in version order.
- `versions/<mc>/gradle.properties`: same keys and comments as the newest existing file. It leaves out `sodium_version` when there's no build (needs change A) and falls back to the previous Iris id with a comment.
- Only if change B isn't done yet: `release.yml`, adding a publish step next to the existing ones.
- Optionally, the `KnowledgeV2ScenarioTest` version list.

**Prints** a checklist of the human steps:
1. `./gradlew :<mc>:build`. Fix compile errors with `//? if >=<mc> {`, then run `./gradlew "Reset active project"` and `git diff` (the CI guard).
2. `python tools/mc_apidiff.py <prev> <mc>` (change D). Review every non-identical class it lists, even when the build is green: inlined constants, reflection strings, runtime defaults.
3. `./gradlew :<mc>:runClientGameTest` (opens a game window), and a production smoke test with that version's mods.
4. Review the rules text for this version. Run `python tools/update_rules.py --mc-versions <all supported>` and check `rules/REVIEW.md`.
5. Update the README, DESIGN, Modrinth body and CHANGELOG lines listed in §4.1.
6. Release. Modrinth `gameVersions` comes from the node name.

**Draft (core only):**

```python
import json, re, sys, urllib.parse, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UA = {"User-Agent": "chaotix345/rigtune/add-mc-version (github.com/chaotix345/rigtune)"}

def get_json(url):
    return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30))

def get_text(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30).read().decode()

def modrinth_versions(project, mc):
    q = urllib.parse.urlencode({"game_versions": json.dumps([mc]), "loaders": json.dumps(["fabric"])})
    versions = get_json(f"https://api.modrinth.com/v2/project/{project}/version?{q}")
    rank = {"release": 0, "beta": 1, "alpha": 2}
    newest_first = sorted(versions, key=lambda v: v["date_published"], reverse=True)
    return sorted(newest_first, key=lambda v: rank[v["version_type"]])  # stable sort: releases first, newest within each

def main(mc, prerelease_ok=False):
    manifest = get_json("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
    entry = next((v for v in manifest["versions"] if v["id"] == mc), None) or sys.exit(f"{mc}: not in the Mojang manifest")
    if entry["type"] != "release" and not prerelease_ok:
        sys.exit(f"{mc} is a {entry['type']}; pass --prerelease-ok")
    if get_json(entry["url"])["javaVersion"]["majorVersion"] != 25:
        sys.exit("needs a Java toolchain change first")
    base = re.match(r"\d+\.\d+", mc).group(0)
    fapi = re.findall(rf"<version>([^<]+\+{re.escape(base)})</version>",
                      get_text("https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml"))
    fapi or sys.exit(f"no Fabric API +{base} yet")
    modmenu, sodium, iris = (modrinth_versions(p, mc) for p in ("modmenu", "sodium", "iris"))
    modmenu or sys.exit("no Mod Menu build")          # compileOnly API; could also fall back to the previous one
    props = [f"minecraft_dependency=~{mc}" if entry["type"] == "release" else f"minecraft_dependency=~{base}-",
             f"fabric_api_version={fapi[-1]}", f"modmenu_version={modmenu[0]['version_number']}"]
    if sodium:
        props.append(f"sodium_version={sodium[0]['version_number']}")
    props.append(f"# Iris {iris[0]['version_number'] if iris else '(none yet, previous kept)'} (Modrinth version id), compileOnly")
    props.append(f"iris_version={iris[0]['id'] if iris else previous_iris_id()}")
    (ROOT / "versions" / mc).mkdir(parents=True)
    (ROOT / "versions" / mc / "gradle.properties").write_text("\n".join(props) + "\n", encoding="utf-8", newline="\n")
    add_to_settings_versions(mc)   # regex on "versions '26.2', '26.3'" -> insert in version order
    print_human_checklist(mc)
```

### 4.4 Checklist (for `docs/DESIGN.md` §Porting, replacing steps 1-4)

1. When Fabric announces the version, read its blog post. Upgrade Loom and Gradle on their own first if it asks.
2. Run `python tools/add_mc_version.py <mc>`, then `./gradlew :<mc>:build`.
3. Fix compile errors with `//? if >=<mc> {`. Run `tools/mc_apidiff.py <prev> <mc>` and review every changed class, including reflection targets, the mixin target, `Options` keys and runtime defaults.
4. Run `:<mc>:runClientGameTest` and a production smoke test.
5. Review version-specific rules. Regenerate the rules for all supported versions.
6. Update docs and the changelog. Run `./gradlew "Reset active project"` and commit on a feature branch.
7. Tag a release. CI builds and publishes every node.
8. For each later hotfix: bytecode-compare, then `PATCH` `game_versions` on Modrinth (§3).

## 5. Trial: 26.4-snapshot-1

### 5.1 What was done

- Created worktree `C:/Dev/Worktrees/rigtune-r-mc` (branch `research/mc-trial` from `feat/v0.3.0` @ 748bfc2), with `JAVA_HOME` = JDK 25.0.4.1, Loom 1.17.21 and Gradle 9.5.1.
- Added `'26.4-snapshot-1'` to `settings.gradle` `versions`.
- Created `versions/26.4-snapshot-1/gradle.properties`: `minecraft_dependency=~26.4-`, `fabric_api_version=0.161.1+26.4`, `modmenu_version=22.0.0-alpha.1`, `iris_version=bAdKrpw8` (26.3 id, compileOnly placeholder), no `sodium_version`.
- Applied one-time change A to `build.gradle`.
- The Stonecutter node name is the Mojang id, so tasks are `:26.4-snapshot-1:<task>`. Stonecutter evaluates `>=26.3` as true for it and picks the 26.3 branches in `HardwareProbe`/`RigTuneClient` (checked in the generated sources).

| Step | Result |
|---|---|
| `./gradlew :26.4-snapshot-1:compileJava :26.4-snapshot-1:compileClientJava` | **BUILD SUCCESSFUL**, 36 s. Loom downloaded and set up 26.4-snapshot-1 without any change. |
| `./gradlew :26.4-snapshot-1:build` with the 26.3 Sodium placeholder on `localRuntime` | compile, jar and `compileGametestJava` OK. `test` **FAILED** before running any test: `FabricLoaderLauncherSessionListener could not be instantiated`, with an `AssertionError` at `ModSolver.computeFix`. Cause: `sodium-mc26.3-0.9.2-fabric` declares `minecraft: 26.3.x`. |
| same, Sodium omitted (change A) | **BUILD SUCCESSFUL**, 1 m 30 s: **848 tests / 78 classes, 0 failures, 0 errors**. Gametest compiles. The jar is `rigtune-0.2.0+mc26.4-snapshot-1.jar`, with `"version": "0.2.0+mc26.4-snapshot-1"` and `"minecraft": "~26.4-"`. Its bytecode reads `InputConstants$Type.KEYBOARD` and `bipush 65` (F8). |
| `./gradlew "Reset active project"` + `git status` | clean apart from the trial edits: the VCS guard works with 3 nodes |
| `./gradlew :26.4-snapshot-1:modrinth -PmodrinthDryRun` | OK. Payload: `versionNumber` `0.2.0+mc26.4-snapshot-1`, `versionType` `release` (should be alpha for a snapshot, see change B), Fabric API dependency required |
| Client launch / `runClientGameTest` | **not run** (task rule: no Minecraft client) |

Cleanup: `git clean -fdq` → `worktree remove` → `branch -D research/mc-trial`. All three succeeded, and nothing was pushed.

### 5.2 API-diff method

Harness: `scratchpad/r-mc/apidiff.py`, outputs in `scratchpad/r-mc/apidiff/`. javap is from JDK 25.0.4.1.

1. **Whole-bytecode comparison.** I compiled `main`, `client`, `gametest` and `test` for both the `:26.3` and `:26.4-snapshot-1` nodes from the same source; both use the `>=26.3` branches. Then I diffed `javap -c -p -constants` for every class file, with constant-pool indices stripped. This catches every compile-visible change that affects RigTune, including javac-inlined constants (the 26.2→26.3 `KEY_F8` trap) and overload re-resolution. **Result: 470 = 470 class files, 0 differ.**
2. **Member references.** I parsed every `Methodref`, `InterfaceMethodref` and `Fieldref` in the 26.3 build's constant pools whose owner is `net.minecraft`, `com.mojang`, `net.fabricmc`, `com.terraformersmc` or `org.lwjgl`. That's 308 refs: 225 MC, 57 Fabric, 25 Mojang, 1 Mod Menu. Each was resolved by name and descriptor through the superclass and interface chain on each version. **Result: 308/308 resolve on 26.4-snapshot-1 with the same descriptor.**
3. **Class dumps.** I dumped `javap -p -s -constants` for every referenced external type, 144 in all. That includes types that only appear in descriptors, plus the reflection target `Options$FieldAccess` and the mixin target `DebugScreenOverlay`. Jars on each side:
   - Loom's `minecraft-{clientonly,common}-deobf`
   - Fabric API modules extracted from `fabric-api-0.161.0+26.3` / `0.161.1+26.4`
   - `fabric-client-gametest-api-v1` 6.0.7 / 6.0.8
   - Mod Menu 21.0.0 / 22.0.0-alpha.1
   - Loader 0.19.5 on both sides

   **Result: 131 byte-identical, 13 changed, 0 missing.** Every Fabric API class RigTune uses (21 of them) and the Mod Menu API are identical.
4. **Strings and runtime defaults a compile can't see:**
   - `Options.processOptions` option keys: **74 = 74, identical.**
   - The mixin target `DebugScreenOverlay.logFrameDuration(J)V`: class identical.
   - The reflected `Options.serverRenderDistance`: still present.
   - Backend name strings `OpenGL`/`Vulkan`: unchanged.
   - `CloudStatus` and `InactivityFpsLimit` constants: unchanged.

Not covered: the `e2e`/`e2eUndo` source sets, which aren't part of `build` and compile against the 0.1.0 jar or the client output; Sodium, Iris and DH (no 26.4 builds); anything only visible at runtime.

### 5.3 The 13 changed classes: none breaks RigTune

| Class | Change on 26.4-snapshot-1 | RigTune impact |
|---|---|---|
| `com.mojang.blaze3d.systems.RenderSystem` | `getCompiledPipeline*(RenderPipeline)` now takes `com.mojang.blaze3d.pipeline.RenderPipeline` (moved back from `renderpearl.api.pipeline`); `getPipelineBuilder()` added | none. RigTune only calls `tryGetDevice()`, which is unchanged. **Sodium and Iris will need ports.** |
| `com.mojang.renderpearl.api.device.GpuDevice` | `compilePipeline` signature changed, `createSpvModule` added | none. RigTune calls `getDeviceInfo()` only. |
| `net.minecraft.client.Options` | private `GRAPHICS_API_TOOLTIP_VULKAN` removed | **behaviour: Vulkan is now the default renderer** (§5.4) |
| `GuiGraphicsExtractor` | `fill`/`blit` overloads taking `RenderPipeline` switched package (as above) | none. RigTune uses the `fill(int,int,int,int,int)`/`text`/`centeredText` overloads, which are unchanged. |
| `ClientLevel`, `ServerLevel` | constructor params; `getUncachedNoiseBiome` → `getUncachedBiome`; `BiomeResolver` → `BiomeManager` | none (not used) |
| `LocalPlayer` | adds `getRidingSoundId()` | none |
| `LevelRenderer` | OIT constants: `OIT_WAVELET_RANK` removed, `OIT_NUMBER_OF_DEPTH_BINS = 8` added, `OIT_TRANSMITTANCE_TARGET_COUNT = 2` (matches the [wiki changelog](https://minecraft.wiki/w/Java_Edition_26.4_Snapshot_1)) | none (class ref only) |
| `MinecraftServer` | adds `enableLegacyStatus`, `statusContactDetails`, `acceptsConnection` | none |
| `Difficulty`, `GameType`, `Heightmap$Types` | `BY_ID` removed; `STREAM_CODEC` is now an `EnumStreamCodec` | none. The benchmark world uses the enum constants only. |
| `Heightmap` | adds `copyHeightmap(...)` | none |

### 5.4 What would actually need work for 26.4

1. **Vulkan is the default renderer. This is a rules change, not a code change.**
   - `PreferredGraphicsApi.getBackendsToTry()` returns `[Vulkan, GL]` for `DEFAULT` and `VULKAN`, and `[GL, Vulkan]` only for `OPENGL`, on 26.4-snapshot-1. On 26.3, only `VULKAN` put Vulkan first. The Vulkan option's label key also changed to `options.graphicsApi.vulkanNonExperimental`. I verified this by disassembling both jars, and it matches third-party coverage ([MC Toolbox](https://mctoolbox.net/blog/minecraft-26-4-snapshot-1-what-s-new-vulkan-sulfur-caves-more)).
   - RigTune's `vulkan-backend` advice (`knowledge.json:642-648`) says "experimental Vulkan renderer … set Video Settings > Graphics API back to Default". On 26.4 that would fire for most players, and "Default" is Vulkan.
   - Fix: add `"mcVersionRange": "<26.4-"` to it. If still useful, write a 26.4 variant that suggests *OpenGL* when there are problems.
   - The `backend-vulkan` flag in `HardwareProbe.java:119` keeps working: the `DeviceInfo.backendName()` strings are unchanged.
   - UNVERIFIED: whether Sodium's 26.4 port runs on the Vulkan backend. It decides whether RigTune's core "add Sodium" advice still holds, so re-check it when Sodium ships for 26.4.
2. **Sodium and Iris have no 26.4 builds.** Until they do, the node can't have Sodium on `localRuntime` (change A). The Iris and DH compile-only APIs can stay on the 26.3 builds. Reflection targets (`SodiumWorldRenderer.instanceNullable`/`isTerrainRenderComplete`, `Workarounds$Reference`) must be re-checked against the 26.4 Sodium when it ships.
3. **Snapshot version string. This matters only if a snapshot or pre-release jar is ever shipped.**
   - `HardwareProbe.minecraftVersion()` (`HardwareProbe.java:71-74`) and `ClientJournal` use `getMetadata().getVersion().getFriendlyString()`, which is Loader's *normalized* version: `26.4-alpha.1`, `26.4-rc.1`.
   - `OnlineDataFetcher`, `DependencyResolver` and the self-update send that value as Modrinth `game_versions`. Modrinth knows `26.4-snapshot-1`, not `26.4-alpha.1`, so every mod would look unavailable and no downloads would resolve.
   - Fix: use `FabricLoader.getRawGameVersion()` (present in 0.19.5, checked with javap) for Modrinth queries. Keep the normalized version for `mcVersionRange` predicates.
   - Releases and hotfixes are unaffected, because normalized equals raw there.
   - Runtime behaviour is UNVERIFIED (no client launched).
4. **Toolchain:** nothing needed so far. Loom 1.17.21, Gradle 9.5.1, Loader 0.19.5 and Java 25 all handled the snapshot. Fabric's 26.4 announcement may still ask for Loom 1.18, which needs Gradle ≥ 9.7.

**Recommendation:** don't ship a snapshot node. The trial shows the procedure works and that 26.4 will probably be a small port. Add `26.4` with `tools/add_mc_version.py` once it's stable and Sodium has a build. Do one-time changes A–D in v0.3.0, since they cost little and make that day a single step.

## 6. 26.4 timing and what the rules need

**No release date has been announced.**
- Mojang's snapshot post gives no timing: "no drop name, no timing", per [howtovideogame, 2026-09-22](https://howtovideogame.com/minecraft-26-4-release-date-snapshots/), which explicitly calls its own December 2026 estimate a projection.
- Minecraft LIVE is on 2026-09-26 ([howtovideogame](https://howtovideogame.com/minecraft-live-september-2026/)) and may announce more. That's UNVERIFIED as of writing. The minecraft.net article fetch timed out.
- Cadence from the Mojang manifest, snapshot-1 to release: 26.1 took 13.9 weeks, 26.2 took 10.0 and 26.3 took 11.9. Applied to 26.4-snapshot-1 (09-22), that gives **about 2026-12-01 to 12-29, most likely mid-December**. This is an estimate, not an announcement.

**Rules and mod availability for 26.4:**
- Today, 2 of the 28 rule mods that have a Modrinth `projectId` have a Fabric build for 26.4-snapshot-1: `c2me-fabric` and `modernfix-mvus`. 24 have one for 26.3. Checked at version level: Modrinth `/project/{id}/version?loaders=["fabric"]&game_versions=["26.4-snapshot-1"]`.
- Sodium, Lithium, FerriteCore, EntityCulling, ImmediatelyFast and others will follow the release. Nothing in `knowledge.json` needs changing for availability. `update_rules.py` computes it per version.
- Once 26.4 is a Modrinth `release` tag, the weekly `update-rules` run adds it on its own; the targets become `[26.4, 26.3, 26.2]`. After any 26.4.x hotfix, the top-3 rule drops 26.2 (§4.1), so do change C before then.
- `upstream.fabulouslyOptimized`/`additive` switch to 26.4 by themselves once those packs publish a 26.4 folder (`newest_available_version`).
- Rule text to revisit: `vulkan-backend` (§5.4), `ixeris` (the "26.3's new SDL" wording), `vulkanmod`'s "no 26.2 release" reason (re-check against 26.4, now that Vulkan is vanilla's default), and the `mixintrace-reborn` ignore note.

## 7. Risks and UNVERIFIED

- **The hotfix policy (`~<mc>`) assumes hotfixes keep the API.** I couldn't check 26.1 → 26.1.1/26.1.2 (jars not diffed); UNVERIFIED. If a hotfix breaks something, the `+mc26.3` jar would still load and fail at runtime. Mitigation: the hotfix checklist in §3, run the day a hotfix drops.
- **Runtime-only behaviour on 26.4 is untested:** SDL input, the Vulkan default, benchmark-world generation and DH/Iris integration. No client was launched.
- **Snapshots churn.** This diff is for `26.4-snapshot-1` only. Re-run it against the release.
- **The Minecraft LIVE (2026-09-26) announcements** are not captured here.
- **Side effect:** before removing the worktree I ran `./gradlew --stop`, which stops *every* Gradle 9.5.1 daemon for this user. If another agent's build failed around then with "daemon disappeared", that's why. Re-run it.
