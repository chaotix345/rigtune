# WS-V design notes: Minecraft-version tooling

Scope: SPEC item 1 (D, `add_mc_version.py`, the Porting docs; AC1.2, AC1.3, AC1.6) with amendments V-M1 and
V-L1. Plan: docs/v0.3/plans/ws-v.md. User docs: tools/MC_VERSIONS.md and docs/DESIGN.md "Porting to new MC
versions".

## Files
- `tools/add_mc_version.py`, `tools/tests/test_add_mc_version.py`, `tools/tests/fixtures/add_mc_version/*`
- `tools/mc_apidiff.py`, `tools/tests/test_mc_apidiff.py`
- `tools/MC_VERSIONS.md` (new), `docs/DESIGN.md` (Porting section only)
- `docs/v0.3/verification/mc-tooling/` (AC1.3 and AC1.6 outputs, the 26.2 -> 26.3 control)

## add_mc_version.py
- **All checks before any write.** The order is: not already a node (settings.gradle or `versions/<mc>/`) and a
  known id form, both before any request; then the Mojang manifest (type, then Java 25 from the version JSON),
  Fabric meta's loader list, Fabric API, Mod Menu. Any refusal, network error, unreadable JSON/XML or unexpected
  API shape exits 1 with one line on stderr and nothing written.
- **Writes.** `versions/<mc>/gradle.properties` first, `settings.gradle` last, each through a temp file and
  `os.replace`; if either write fails, the new `versions/<mc>/` is removed and it exits 1 (review L8).
- **Picks.** Modrinth versions filtered by `game_versions=[<mc>]` and `loaders=["fabric"]`, ranked release >
  beta > alpha and newest first within a type (26.3 gets Sodium `mc26.3-0.9.2-fabric`, not the newer
  `0.9.3-alpha.1`, which matches the committed file). Fabric API and Mod Menu must also be in their maven's
  `maven-metadata.xml`, since Gradle resolves them there, not from Modrinth. Iris without a build keeps the
  previous node's id with a comment (compile-only, API only, as in the trial).
- **Properties file.** The known keys are rendered in the existing order and comment style; any other key of the
  previous node's file is copied unchanged and flagged, so a key added later (by another workstream) isn't
  silently dropped. With the fixtures, adding 26.3 to a 26.2-only repo reproduces the committed
  `versions/26.3/gradle.properties` byte for byte.
- **Ordering** reuses `tools/gametest_matrix._sort_key` (WS-0's; the coordinator asked for reuse), behind
  `version_key()`, which first refuses ids this tool doesn't add. A test checks that a node the tool adds gets
  its CI legs. It imports a `_`-prefixed function; if WS-0 renames it, the tests fail loudly.
- **Notes it prints** instead of editing files outside its two: no Sodium build; `build.gradle` without the
  optional Sodium (change A); `release.yml` with per-version steps (change B); a Loader not listed for `<mc>`; a
  pre-release node (don't ship; remove it and add the release once it's out); a hotfix node (narrow the old range
  to `>=<old> <<mc>-`); keys copied unchanged. Since WS-0 merged, the A and B notes no longer fire on this repo
  (compare the two AC1.3 runs in git history).
- **Network.** One `Http` class: the repo-style User-Agent `chaotix345/rigtune-add-mc-version/1.0
  (github.com/chaotix345/rigtune)`, Modrinth requests at least 1 s apart (SPEC; `update_rules.py` uses 0.25 s),
  3 attempts on connection errors and 429/500/502/503/504 with `Retry-After` honoured (capped at 30 s), a 400/404
  from Fabric meta meaning "no loader". The Fabric blog is read through its Atom feed (`/feed.xml`, entry title
  "Fabric for Minecraft <base>"); the post link is resolved against the feed and followed only on
  `https://fabricmc.net/`; its Loom/Gradle sentences are printed. Blog failures never fail the run.
- **Output** is ASCII: a redirected Windows console isn't UTF-8 (the first AC1.3 capture had a mangled "§").

## mc_apidiff.py
- **A class-file reader instead of parsing `javap -v`.** The trial parsed javap text; the committed tool reads
  class files itself (constant pool including method handles, method types and invokedynamic; the
  `BootstrapMethods` attribute; members with access flags; class, field and method annotation string values).
  That makes resolution exact and fast (all 11,383 MC 26.3 classes parse in about 4 s) and testable without a
  JDK. javap is still used for what a human reads: the `-p -s -constants` class dumps (one javap run per jar, so
  the command line stays short) and, when both nodes are built, `-c` bytecode of RigTune's own classes.
- **Resolution** follows the JVM: fields per JVMS 5.4.3.2 (the class, its superinterfaces, then its superclass),
  methods in the class and its superclasses, then every superinterface. RigTune's own classes are in both
  indexes, so a call to an inherited member through a RigTune subclass (the code-review finding H1: 56 such
  references, e.g. `Screen.addRenderableWidget`, `font`, `width`) resolves from the subclass on both versions.
  JDK supertypes (`Enum.ordinal`, `Object.hashCode`) come from the running JDK's `lib/modules`, extracted once
  per run with `jimage` into a temp directory (about 4 s). `CHANGED` covers what breaks linkage without a
  missing member: static/instance flips, narrowed access, an owner switching between class and interface.
- **What the constant pool doesn't show** (finding H2): every non-private, non-static RigTune method that
  overrides or implements a foreign method must still override one, not final or static (41 on 26.3, e.g.
  `Screen.init`, `onInitializeClient`, Gson `TypeAdapter.read`/`write`); a concrete RigTune class mustn't gain an
  unimplemented abstract method from a foreign supertype; and each `LambdaMetafactory` call site adds its
  interface method as a reference (`ScreenEvents$AfterInit.afterInit`, `ClientTickEvents$EndTick.onEndTick`).
- **Scope.** SPEC says MC/Fabric/Mod Menu; the classpath also has Minecraft's own libraries (Gson, Guava, ...),
  which change with Minecraft, so references into them are checked too and counted separately. An owner or type
  under `net/minecraft`, `com/mojang`, `net/fabricmc`, `com/terraformersmc` or `org/lwjgl` that isn't on the
  old classpath is `UNRESOLVED`, a gap that fails the run (finding M3). Compile-only mod APIs (Iris, Distant
  Horizons) aren't on it and are counted as not checked; Sodium/Iris class names in reflection strings are
  listed to check by hand.
- **Default source sets** `main, client, gametest, e2e, e2eUndo` (SPEC: src/main, client, gametest, e2e*). The
  trial used `main, client, gametest, test` and counted only constant-pool references whose owner is a
  Minecraft/Fabric/Mod Menu class (308). The first version of this tool reproduced exactly that 308 with the
  trial's sets; the current one counts more (inherited members through subclasses, lambda interface methods),
  so the number isn't comparable any more. e2e compiles against the released 0.1.0 jar (`-Pe2e.oldJar`).
- **Referenced classes** are all types in RigTune's constant pools, descriptors, annotation values (the mixin
  target), class-name strings (reflection) and the declaring classes of inherited members, when they're on the
  old classpath: 169 for 26.3 (the trial's 144 had no library classes). A class javap prints nothing for is
  `NO DUMP` (a gap, exit 1), never `SAME`.
- **Heuristics.**
  - "Names in strings": a string in a RigTune class that names a member of a class referenced by the same
    RigTune class, minus names every class has (`equals`, `values`, ...). The member must still exist with the
    same descriptors (finding M4). It finds `Options.serverRenderDistance`, `Options.processOptions`,
    `DebugScreenOverlay.logFrameDuration`.
  - "String constants": strings a referenced class lost, flagged when RigTune also uses them and they look like a
    key (3+ identifier characters; not `null`/`true`/`false`), so `""` from string concatenation isn't flagged.
  - Both fail the run only when the string is an annotation value or its RigTune class uses reflection
    (`Class.getDeclaredField`, `forName`, `MethodHandles.Lookup.find*`, ...). Other matches go to a `REVIEW` list
    that doesn't change the exit code (finding L7). Known false positive that still fails: on 26.2 -> 26.3 it
    flags `Window.minimized` because `BenchmarkController`, which does use reflection, has an unrelated
    `"minimized"` string next to a `Window` reference.
- **Exit codes**: 0 no breaking change (changed classes and `REVIEW` still need a human), 1 breaking or a gap,
  2 setup error or any unexpected error, as one line on stderr (finding L6). A missing jar prints where it was
  expected and how to fetch it from a throwaway worktree.

## Code review (dispatched on 11c5e57; 2 high, 2 medium, 7 low)
Fixed as decided by the coordinator: H1, H2, M3, M4, L5, L6, L7, L8, L9 (urljoin + fabricmc.net only; unreadable
JSON/XML → network error), L10 (removing a pre-release node, in tools/MC_VERSIONS.md), L11 (tests: method-level
`@Inject` annotations, int/float/double/method-handle/method-type/invokedynamic pool entries, a write failure, a
connection that never comes back). Each fix has a test; the live runs were redone after them.

## Evidence
- AC1.2: `tools/tests/test_add_mc_version.py` (45 tests): release adds the node and writes the committed 26.3
  file; snapshot refused without `--prerelease-ok`; no Sodium build → no `sodium_version`; existing node (and an
  existing `versions/<mc>/`) refused before any request; `--dry-run` leaves every file byte-identical; Java 26
  refused; plus unknown/malformed ids, no Loader/Fabric API/Mod Menu, Fabric API not on the maven, UA on every
  request, Modrinth calls ≥ 1 s apart, a 503 retried with `Retry-After`, retries exhausted, unreadable answers,
  a failed write rolled back, the blog lines and link check, the hotfix note, the new node's CI legs.
- AC1.3: `docs/v0.3/verification/mc-tooling/add_mc_version-26.4-snapshot-1-dry-run.txt` (live, 2026-09-26, after
  WS-0): `'26.2', '26.3', '26.4-snapshot-1'`, `~26.4-`, Fabric API `0.161.1+26.4` (beta), Mod Menu
  `22.0.0-alpha.1`, no `sodium_version`, 26.3's Iris id kept, no Fabric blog post for 26.4 yet. The values match
  the research trial's hand-made node. `git status` was clean afterwards.
- AC1.6: `docs/v0.3/verification/mc-tooling/apidiff-26.3-26.4-snapshot-1/` (exit 0): 472/472 references OK (56
  through RigTune subclasses, 110 interface methods including lambdas), 41/41 overrides OK, no new abstract
  method, 0 missing classes, the same 13 changed classes as research §5.3, the reflection/mixin names still
  declared with the same descriptors; `Options` lost only the private `GRAPHICS_API_TOOLTIP_VULKAN` and its
  string, `Minecraft` lost the two "forcing preferred graphics API" log strings (the Vulkan-default change,
  research §5.4). The snapshot's jars were already in the Loom/Gradle caches from the trial, so no node was
  added. `...-trial-sets-summary.txt`: the same with the trial's source sets (482 references, 39 overrides).
- Positive control: `apidiff-26.2-26.3-summary.txt` (exit 1) finds the known v0.2 API changes the `//? if` blocks
  exist for (`GpuDevice`/`DeviceInfo` moved to another package, `InputConstants$Type.KEYSYM` gone (26.3 uses
  `KEYBOARD`), `Window`/`VideoMode.getRefreshRate` gone) and the 4 RigTune classes whose bytecode differs between
  the 26.2 and 26.3 builds (RigTuneClient, HardwareProbe and two game tests), which is the bytecode-compare path
  running for real. With the H1/H2 checks it still finds exactly these; no override or inherited member broke
  between 26.2 and 26.3.

## Deviations from research §4.3
- No `--from <prev-mc>`: the template and `<prev>` are the closest lower node (the lowest node when adding an
  older version).
- It doesn't edit `release.yml` or `KnowledgeV2ScenarioTest`: change B made the first unnecessary (it warns if
  a per-version step reappears), and the second is printed as a checklist step.

## UNVERIFIED
- The write path has only run offline (fixtures); no newer release exists to add for real, and the live run was a
  dry run by design.
- The Fabric blog parsing is checked against the 26.3 post only; a differently worded post prints "no Loom/Gradle
  line found".
- mc_apidiff.py has only run on Windows; the Linux/macOS paths (`javap`/`jimage` without `.exe`, `~/.gradle`) are
  untested.
- Whether Stonecutter accepts `//? if >=26.4-snapshot-1` (the checklist prints `>=<mc>`) wasn't tested; the trial
  only showed that `>=26.3` is true on the snapshot node.
- The override check treats a default method anywhere in the hierarchy as an implementation and ignores
  bridge-method subtleties; the abstract check compares against the old version, so a gap present on both
  sides isn't reported.
