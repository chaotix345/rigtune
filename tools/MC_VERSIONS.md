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
  on a snapshot, Loader's normalized version (`26.4-alpha.1`) isn't a Modrinth game version. When
  the release is out, remove the pre-release node (its `settings.gradle` entry and `versions/<id>/`)
  and add the release with `add_mc_version.py <base>`; a `//? if >=<id>` block written for the
  pre-release still matches the release, but rewrite it as `>=<base>`.
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

It refuses (exit 1, one line on stderr, nothing written) unless every check passes, in this order:

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

It writes, as UTF-8 with LF line endings, each file through a temp file and a rename (if a write
fails, the new `versions/<mc>/` is removed again and it exits 1):

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
429, 500, 502, 503 or 504 response is retried twice (honouring `Retry-After`, capped at 30 s); an
unreadable JSON or XML answer is an error. The blog post link from Fabric's feed is only followed
when it stays on `https://fabricmc.net/`.

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

1. **Member references.** Every field and method reference in RigTune's class files into the
   version's classpath (Minecraft, its Mojang libraries, Fabric Loader, Fabric API, Mod Menu) is
   resolved on both versions the way the JVM does it (a field: the class, its superinterfaces, then
   its superclass; a method: the class and its superclasses, then its superinterfaces; members
   inherited from the JDK are read from the JDK's own `lib/modules` with `jimage`). That includes a
   call to an inherited member through a RigTune subclass (`this.addRenderableWidget(...)` in a
   `Screen` subclass names the subclass as the owner) and the interface method each lambda or method
   reference implements (`ClientTickEvents.EndTick.onEndTick`). A reference is `MISSING` when it
   doesn't resolve on `<mc>`, `CHANGED` when it became static or non-static, lost visibility, or its
   owner switched between class and interface, and `UNRESOLVED` when it doesn't resolve on `<prev>`
   (a gap in the check; an owner under `net/minecraft`, `com/mojang`, `net/fabricmc`,
   `com/terraformersmc` or `org/lwjgl` that isn't on the classpath is one too). References to
   compile-only mod APIs (Iris, Distant Horizons) aren't on that classpath and are counted as not
   checked.
2. **Overrides.** Every RigTune method that overrides or implements a method of those classes
   (`Screen.init`, `onInitializeClient`, a Gson `TypeAdapter.read`) must still override one on
   `<mc>`, and that method mustn't have become final or static; otherwise the game silently stops
   calling RigTune's code. A concrete RigTune class that would leave a new abstract method of a
   Minecraft or Fabric supertype unimplemented is flagged too.
3. **Referenced classes.** Every class RigTune references, including types that only appear in
   descriptors, annotation values (the mixin target) and class names in strings (reflection), is
   dumped with `javap -p -s -constants` on both versions: `SAME`, `DIFF` (a diff is written),
   `MISSING` on `<mc>`, or `NO DUMP` when javap printed nothing (a gap).
4. **Names in strings.** A string RigTune uses next to a reference to a class (for example
   `getDeclaredField("serverRenderDistance")` on `Options`, or `@Inject(method = "logFrameDuration")`
   on `DebugScreenOverlay`) that names a member of that class must still name one on `<mc>`, with the
   same descriptors.
5. **String constants.** Strings a referenced class lost or gained, method bodies included (javap
   `-p` doesn't show them): option keys, translation keys, log messages. A lost key-like string that
   RigTune also uses is flagged.
6. **RigTune's own bytecode**, only when `<mc>` is a built node too: `javap -c -p -constants` of both
   builds with constant-pool indices stripped. This catches what still compiles but changed, like
   javac-inlined constants (F8 is 297 on 26.2 and 65 on 26.3).

Checks 4 and 5 are heuristics. A match counts (exit 1) when the string is an annotation value or
the RigTune class that has it uses reflection (`Class.getDeclaredField`, `forName`, ...); any other
match may be a coincidence (`BenchmarkController`'s `"minimized"` reason string and `Window.minimized`)
and is only listed under `REVIEW`. Classes named in strings that aren't on the classpath (Sodium,
Iris) are listed to check by hand.

Output: a summary on stdout; with `--out DIR`, also `summary.txt`, `report.json` (every reference,
override, class and string result) and `diff-<class>.txt` per changed class (`--dumps` adds both
versions' full javap dumps, about 800 KB each). Exit code 0: no breaking change (the changed classes
and the `REVIEW` list still need a human); 1: something is `MISSING`/`CHANGED`/`UNRESOLVED`/
`NO DUMP`, an override or a name/string check failed, or RigTune's own bytecode differs; 2: a setup
error (no compiled classes, a jar missing from the caches, no JDK) or an unexpected error, as one
line on stderr.

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

- `26.3 -> 26.4-snapshot-1`: no breaking change. 479 references resolve (378 into Minecraft,
  Mojang, Fabric and Mod Menu, 101 into other Minecraft libraries such as Gson; 56 of them are
  inherited members called through a RigTune subclass, 111 are interface methods, lambdas included);
  42 overrides still override; 13 of 171 referenced classes changed, none in a way RigTune uses; the
  reflection and mixin names are still there. (The research trial counted only the constant-pool
  references to those classes: 308 with its source sets, `main,client,gametest,test`.)
- `26.2 -> 26.3` (a positive control): it finds the breaks the `//? if` blocks handle (`GpuDevice`
  and `DeviceInfo` moved, `InputConstants$Type.KEYSYM`, `getRefreshRate`) and the 4 classes whose
  bytecode differs between the two builds; it also flags `Window.minimized`, a coincidence (see
  above).

Tests: `tools/tests/test_mc_apidiff.py` (synthesized class files, a fake Gradle cache; no JDK needed).

## Snapshot canary

`.github/workflows/snapshot-canary.yml` builds and unit-tests RigTune against the newest Minecraft
snapshot every Wednesday (05:00 UTC), so an API break shows up while the version is still a
snapshot. It takes `latest.snapshot` from the Mojang manifest, adds it as a node with
`add_mc_version.py --prerelease-ok` in the runner's checkout only (never committed, so build.yml's
game-test legs, release.yml and update-rules.yml don't see it) and runs `./gradlew :<mc>:build`,
which also compiles the game tests. It skips with a notice, and a green run, when the newest
snapshot is the release or `add_mc_version.py` refuses it (no Fabric API build yet, another Java
version, ...).

Run it by hand: `gh workflow run snapshot-canary.yml`, or `-f mc=<id>` to test a given version.
The schedule only fires from the copy on `main`.

When a build fails it opens one issue, "Snapshot canary: RigTune fails to build against the newest
Minecraft snapshot" (label `snapshot-canary`), with the build reports attached to the run; later
failures comment on it and the next green run closes it. To fix it:

1. Reproduce locally: `python tools/add_mc_version.py <snapshot> --prerelease-ok` and
   `./gradlew :<snapshot>:build`. Don't commit the node.
2. Compile the previous node's classes and run `python tools/mc_apidiff.py <release> <snapshot>`
   (see above): it lists every member RigTune uses that is missing or changed.
3. Handle each break with a `//? if >=<snapshot> {` block until `:<snapshot>:build` passes. Remove
   the throwaway node, check that `./gradlew build` still passes, and commit only the source change.
   When the release ships, rewrite the blocks as `>=<release>` (see "Version ranges").

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
