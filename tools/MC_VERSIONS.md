# Minecraft versions: adding one, hotfixes, dropping one

RigTune builds one jar per Minecraft version from one source tree with Stonecutter 0.9.8
(docs/DESIGN.md, "Porting to new MC versions"). A version is a **node**: an entry in the `versions`
call in `settings.gradle` plus `versions/<mc>/gradle.properties`. Everything else follows from
the node list:

- `./gradlew build` builds and unit-tests every node.
- CI's client game-test legs come from `versions/*/` (`tools/gametest_matrix.py`: an OpenGL leg per
  node, plus a Vulkan leg from 26.3 on), so a new node needs no workflow edit.
- `release.yml` publishes every `versions/*/` node to Modrinth (`versionType` alpha for a
  `-snapshot-`/`-pre-`/`-rc-` id).
- Sodium is on `localRuntime` only when the node sets `sodium_version`, so a node can build and
  run its tests before Sodium has a build for it.

Two tools do the mechanical parts, both Python 3.11 standard library only:

| Tool | What it does |
|---|---|
| `tools/add_mc_version.py <mc> [--prerelease-ok] [--dry-run]` | checks that `<mc>` can be a node and writes it |
| `tools/mc_apidiff.py <prev> <mc>` | checks that RigTune's compiled code still links against `<mc>` and lists what changed |

Background and the evidence behind the policy: docs/research/v0.3/mc-versions.md.

## Version ranges (the `minecraft_dependency` of a node)

- A release node uses `~<mc>` (for example `~26.3`): it accepts that version's hotfixes (26.3.1,
  26.3.2, 26.3.1-rc-1) and rejects the next drop (every 26.4 build). This was checked with Fabric
  Loader 0.19.5's own `VersionPredicate`.
- A pre-release node uses `~<base>-` (for example `~26.4-` for `26.4-snapshot-1`). Don't ship one:
  on a snapshot, Loader's normalized version (`26.4-alpha.1`) isn't a Modrinth game version.
- Never an open-ended `>=`: every drop so far has changed bytecode RigTune depends on.
- A hotfix that changes an API RigTune uses gets its own node (`26.3.1` with `~26.3.1`), and the old
  node is narrowed to `>=26.3 <26.3.1-` so the two jars never accept the same version.

## Adding a version

1. When Fabric announces the version, read its blog post. Upgrade Loom and Gradle on their own
   first if it asks (Loom 1.18 needs Gradle 9.7 or newer).
2. `python tools/add_mc_version.py <mc>`, then `./gradlew :<mc>:build` (with `JAVA_HOME` set to
   JDK 25).
3. Fix compile errors with `//? if >=<mc> {` blocks. Run `python tools/mc_apidiff.py <prev> <mc>`
   and review every changed class it lists, even when the build is green: reflection targets, the
   mixin target, `Options` keys and runtime defaults (a default can change without any API
   change: 26.4 makes Vulkan the default renderer).
4. `./gradlew :<mc>:runClientGameTest` and a production smoke test with that version's mods.
5. Review the version-specific rules (`vulkan-backend`, `ixeris`, `vulkanmod`, the
   `mixintrace-reborn` ignore note) and regenerate the rules for every supported version
   (`python tools/update_rules.py`, then `rules/REVIEW.md`).
6. Update the docs and the changelog (README, DESIGN, the Modrinth body, CHANGELOG; optionally
   `KnowledgeV2ScenarioTest`'s version list). Run `./gradlew "Reset active project"`, check
   `git diff`, and commit on a feature branch.
7. Tag a release. CI builds and publishes every node.
8. For each later hotfix: see "Hotfixes".

## `tools/add_mc_version.py`

```
python tools/add_mc_version.py 26.4 [--dry-run]
python tools/add_mc_version.py 26.4-snapshot-1 --prerelease-ok [--dry-run]
```

It refuses (exit 1, nothing written) unless every check passes, in this order:

1. `<mc>` isn't already a node (`settings.gradle`) and `versions/<mc>/` doesn't exist.
2. The id is a release (`26.4`, `26.3.1`) or a pre-release (`26.4-snapshot-1`, `26.4-pre-1`,
   `26.4-rc-1`) id.
3. It's in the Mojang version manifest, and its type is `release` (anything else needs
   `--prerelease-ok`).
4. Its version JSON asks for Java 25 (`javaVersion.majorVersion`). Anything else needs a toolchain
   change first.
5. Fabric meta lists a Loader for it (`/v2/versions/loader/<mc>`). If the repo's `loader_version`
   isn't among them, it says so but carries on.
6. Fabric API has a build for it on Modrinth that is also in the Fabric maven's
   `maven-metadata.xml` (Gradle resolves it from there).
7. Mod Menu has a build for it on Modrinth that is also on `maven.terraformersmc.com`.

Then it picks, from Modrinth (`loaders=["fabric"]`, releases before betas before alphas, the newest
within each): Fabric API and Mod Menu (`version_number`), Sodium (`version_number`, the
`maven.modrinth` coordinate; left out when there is no build), and Iris (the Modrinth version **id**,
as in the existing files; when there is none, the previous node's id is kept with a comment, since
Iris is compile-only and only its API is used).

It writes, as UTF-8 with LF line endings:

- `settings.gradle`: `<mc>` inserted into the `versions` call in version order (the order
  `tools/gametest_matrix.py` uses: snapshot < pre < rc < release < hotfix);
- `versions/<mc>/gradle.properties`: `minecraft_dependency` (see "Version ranges"),
  `fabric_api_version`, `modmenu_version`, `sodium_version` (if any), the Iris comment and
  `iris_version`. A key it doesn't know in the previous node's file is copied unchanged and
  flagged.

It prints what it found, the `settings.gradle` diff, the new file, notes (no Sodium build, a
pre-release node, a hotfix node and the range to narrow, a `build.gradle` without the optional
Sodium, a `release.yml` with per-version steps, a Loader it doesn't list) and the checklist above.
The Loom and Gradle lines of Fabric's "Fabric for Minecraft <version>" post are shown when the post
exists. `--dry-run` prints all of it and writes nothing.

Network: every request sends `User-Agent: chaotix345/rigtune-add-mc-version/1.0
(github.com/chaotix345/rigtune)`; Modrinth requests are at least 1 s apart; a connection error or a
429, 500, 502, 503 or 504 response is retried twice (honouring `Retry-After`, capped at 30 s).

Tests: `tools/tests/test_add_mc_version.py`, offline, with trimmed copies of the live responses of
2026-09-26 under `tools/tests/fixtures/add_mc_version/`. The live dry run for `26.4-snapshot-1` is in
`docs/v0.3/verification/mc-tooling/add_mc_version-26.4-snapshot-1-dry-run.txt`.

## `tools/mc_apidiff.py`

```
export JAVA_HOME=<JDK 25>
./gradlew :<prev>:classes :<prev>:clientClasses :<prev>:gametestClasses \
    :<prev>:compileE2eUndoJava :<prev>:compileE2eJava -Pe2e.oldJar=<rigtune-0.1.0.jar from the v0.1.0 release>
python tools/mc_apidiff.py <prev> <mc> [--fabric-api V --modmenu V] [--out DIR] [--dumps]
```

`<prev>` is a node whose classes are compiled (`versions/<prev>/build/classes/java/`); `<mc>` is the
version to check. The Fabric API and Mod Menu versions come from each node's `gradle.properties`;
for a version that isn't a node, pass `--fabric-api` and `--modmenu` (the ones
`add_mc_version.py --dry-run` picks). `--sets` changes the source sets it reads (default
`main,client,gametest,e2e,e2eUndo`; a set that isn't compiled is listed as not checked).

What it checks:

1. **Member references.** Every field and method reference in RigTune's class files whose owner is
   on the version's classpath (Minecraft, its Mojang libraries, Fabric Loader, Fabric API, Mod
   Menu) is resolved on both versions the way the JVM does it (the class, its superclasses, then
   its superinterfaces; members inherited from the JDK are read from the JDK's own
   `lib/modules` with `jimage`). A reference is `MISSING` when it doesn't resolve on `<mc>`,
   `CHANGED` when it became static or non-static, lost visibility, or its owner switched between
   class and interface, and `UNRESOLVED` when it doesn't resolve on `<prev>` (a classpath gap).
   References to compile-only mod APIs (Iris, Distant Horizons) aren't on that classpath and are
   counted as not checked.
2. **Referenced classes.** Every class RigTune references, including types that only appear in
   descriptors, annotation values (the mixin target) and class names in strings (reflection), is
   dumped with `javap -p -s -constants` on both versions: `SAME`, `DIFF` (a diff is written) or
   `MISSING` on `<mc>`.
3. **Names in strings.** A string RigTune uses next to a reference to a class (for example
   `getDeclaredField("serverRenderDistance")` on `Options`, or `@Inject(method = "logFrameDuration")`
   on `DebugScreenOverlay`) that names a member of that class must still name one on `<mc>`.
4. **String constants.** Strings a referenced class lost or gained, method bodies included (javap
   `-p` doesn't show them): option keys, translation keys, log messages. A lost string that RigTune
   also uses is flagged.
5. **RigTune's own bytecode**, only when `<mc>` is a built node too: `javap -c -p -constants` of both
   builds with constant-pool indices stripped. This catches what still compiles but changed, like
   javac-inlined constants (F8 is 297 on 26.2 and 65 on 26.3).

Classes named in strings that aren't on the classpath (Sodium, Iris) are listed to check by hand.

Output: a summary on stdout; with `--out DIR`, also `summary.txt`, `report.json` (every reference,
class and string result) and `diff-<class>.txt` per changed class (`--dumps` adds both versions'
full javap dumps, about 800 KB each). Exit code 0: no breaking change (the changed classes still
need a human review); 1: something is `MISSING`/`CHANGED`/`UNRESOLVED`, a used name or string is
gone, or RigTune's own bytecode differs; 2: a setup error (no compiled classes, a jar missing from
the caches, no JDK).

Where the jars come from (per user, shared by every worktree): `~/.gradle/caches/fabric-loom/
minecraftMaven/net/minecraft/minecraft-{clientonly,common}-deobf/<mc>/`, the Mojang libraries listed
in `~/.gradle/caches/fabric-loom/<mc>/mojang_minecraft_info.json`, and the Fabric API modules of the
cached `fabric-api-<version>.pom`, Mod Menu and Fabric Loader under
`~/.gradle/caches/modules-2/files-2.1/`. They appear once Gradle has built that version. For a
version that isn't a node here, get them from a throwaway worktree (the tool prints this when a jar
is missing):

```
git worktree add ../rigtune-apidiff-tmp HEAD && cd ../rigtune-apidiff-tmp
python tools/add_mc_version.py <mc> --prerelease-ok
./gradlew :<mc>:compileJava
cd - && git worktree remove --force ../rigtune-apidiff-tmp
```

Results so far (`docs/v0.3/verification/mc-tooling/`):

- `26.3 -> 26.4-snapshot-1`: no breaking change. 404 references resolve (306 into Minecraft,
  Mojang, Fabric and Mod Menu; 98 into other Minecraft libraries such as Gson); 13 of 169
  referenced classes changed, none in a way RigTune uses; the reflection and mixin names are still
  there. With the research trial's source sets (`--sets main,client,gametest,test`) the core count
  is the trial's 308.
- `26.2 -> 26.3` (a positive control): it finds the breaks the `//? if` blocks handle (`GpuDevice`
  and `DeviceInfo` moved, `InputConstants$Type.KEYSYM`, `getRefreshRate`) and the 4 classes whose
  bytecode differs between the two builds.

Tests: `tools/tests/test_mc_apidiff.py` (synthesized class files, a fake Gradle cache; no JDK needed).

## Hotfixes (no new node)

When Mojang ships a hotfix (26.3.1) that RigTune's `~26.3` range already accepts:

1. Check that the jar still works on it: in a throwaway worktree, add the hotfix as a node
   (`add_mc_version.py 26.3.1`), `./gradlew :26.3:build :26.3.1:build`, then
   `python tools/mc_apidiff.py 26.3 26.3.1`: both nodes are built, so it also compares RigTune's
   bytecode. A clean result means the released `+mc26.3` jar works on 26.3.1; throw the worktree
   away. Otherwise the hotfix needs its own node (see "Version ranges").
2. Add the hotfix to the existing Modrinth versions' `game_versions`, since launchers filter by that
   list and a release uploads only the node's own version: Modrinth's `PATCH /v2/version/{id}` with
   `game_versions` (`tools/modrinth_project.py` has no command for it yet).
3. Make sure the rules cover the hotfix (`tools/update_rules.py`'s targets).

## Dropping a version

Remove it from `versions` in `settings.gradle`, delete `versions/<mc>/`, and delete the `//? if`
branches only it used (Stonecutter doesn't prune them). If it was the committed version
(`vcsVersion`), move `vcsVersion`, `stonecutter.active` and the version build.yml checks together.
