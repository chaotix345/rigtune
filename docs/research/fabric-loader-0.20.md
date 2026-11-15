# Fabric Loader 0.20.x

Research for the Backlog item in docs/STATUS.md: RigTune builds and tests on Loader 0.19.5 (`gradle.properties` `loader_version`) and declares `"fabricloader": ">=0.19.5"`, while players on current launchers get 0.20.1. Checked 15 Nov 2026 against the FabricMC/fabric-loader releases and commits, FabricMC/Mixin, FabricMC/fabric-api and meta.fabricmc.net.

## Short answer

Nothing RigTune uses changes in 0.20.x. 0.19.5 to 0.20.1 is 9 commits, and none touches the public API (`net.fabricmc.loader.api`), entrypoints, mod metadata or anything RigTune calls. The one behaviour change that could matter, Mixin 0.17.5's stricter selector parsing, is gated by a compatibility level that Loader picks from the mod's declared minimum loader, so it doesn't apply to RigTune while it declares `>=0.19.5` (and RigTune's selectors would pass it anyway).

Recommendation: move the dev loader to 0.20.1 in the next code PR that touches the build (or the 0.6.0 port), keep `"fabricloader": ">=0.19.5"`, and don't add a separate 0.20.x CI leg. Details and costs below.

## What players run

- [meta.fabricmc.net](https://meta.fabricmc.net/v2/versions/loader/26.3) lists 0.20.1 as the only `stable` loader for both 26.2 and 26.3 (0.20.0 and 0.19.5 are listed, not stable). An installer or launcher that takes meta's stable loader installs 0.20.1, so new installs since 19 Oct run RigTune on a loader CI never starts.
- Fabric API doesn't force the move: its newest 26.3 build, [0.166.2+26.3](https://github.com/FabricMC/fabric-api/blob/0.166.2%2B26.3/src/main/resources/fabric.mod.json) (11 Nov), still needs `fabricloader >=0.19.3`, and the last 26.2 build ([0.161.0+26.2](https://github.com/FabricMC/fabric-api/blob/0.161.0%2B26.2/src/main/resources/fabric.mod.json), 18 Sep) needs `>=0.18.4`.

## What changed in 0.20.0 and 0.20.1

[0.20.0](https://github.com/FabricMC/fabric-loader/releases/tag/0.20.0) (15 Oct) and [0.20.1](https://github.com/FabricMC/fabric-loader/releases/tag/0.20.1) (19 Oct); full diff: [0.19.5...0.20.1](https://github.com/FabricMC/fabric-loader/compare/0.19.5...0.20.1).

| Change | Commit | Affects RigTune? |
|---|---|---|
| Mixin 0.17.4 to 0.17.5, with a new compatibility level `COMPATIBILITY_0_17_5` for mods that declare `fabricloader >=0.20.0` | [35bb7e56](https://github.com/FabricMC/fabric-loader/commit/35bb7e56), [e2f5045d](https://github.com/FabricMC/fabric-loader/commit/e2f5045d) (#1181) | No (next section) |
| Mod jar file systems opened read-only (Java 25+ only), fixing [#1167](https://github.com/FabricMC/fabric-loader/issues/1167): a mod container's root path was writable | [9372c0d4](https://github.com/FabricMC/fabric-loader/commit/9372c0d4) (#1168) | No. RigTune only reads through it (`ModContainer.findPath("fabric.mod.json")` in `FabricPins`). It opens no zip file systems of its own and never writes into a mod jar; undo and self-update move whole files in `mods/`, which this doesn't touch |
| ASM 9.11, then reverted to 9.10.1 in 0.20.1 (PR body gives no reason) | [63778ede](https://github.com/FabricMC/fabric-loader/commit/63778ede), [cb9523bc](https://github.com/FabricMC/fabric-loader/commit/cb9523bc) (#1184) | No. 0.20.1 ships the same ASM as 0.19.5 |
| tiny-remapper update | [e8ebce84](https://github.com/FabricMC/fabric-loader/commit/e8ebce84) | No. Runtime remapping of mod jars; RigTune's build output is unchanged |
| Error GUI: a note about dependency overrides; the clipboard copy no longer includes tree decorations | [435c9f3d](https://github.com/FabricMC/fabric-loader/commit/435c9f3d) (#1169), [fb354921](https://github.com/FabricMC/fabric-loader/commit/fb354921) (#1165) | No. Loader's own crash/incompatibility window |

Files changed across the whole range: `FabricLoaderImpl`, `ModContainerImpl`, `RuntimeModRemapper`, the GUI classes, `FabricMixinVersions`, `KnotClassDelegate`, `FileSystemUtil`, `ManifestUtil`, the installer JSONs and `Messages.properties`. All are `impl`; nothing under `net/fabricmc/loader/api`. The issues opened since 0.20.0 ([#1182](https://github.com/FabricMC/fabric-loader/issues/1182), [#1185](https://github.com/FabricMC/fabric-loader/issues/1185)) are a user's mismatched mods and an Ornithe installer problem on 1.7-1.13 servers; neither is a 0.20 regression that touches RigTune.

## The APIs RigTune calls

Loader API use in `src/client` and `src/main`:

- `FabricLoader`: `getInstance`, `getConfigDir`, `getGameDir`, `getAllMods`, `isModLoaded`, `getModContainer` (also for the "fabricloader" version in the share report, `RealController.java:757`), `getRawGameVersion`.
- `ModContainer`: `getMetadata`, `getOrigin` (`ModOrigin.Kind`, `getPaths`), `getContainingMod`, `findPath`.
- `ModMetadata`: `getId`, `getName`, `getVersion().getFriendlyString()`, `getProvides`, `getDependencies` (`ModDependency.matches`).
- `Version.parse`, `SemanticVersion`, `VersionPredicate.parse` / `getTerms` (`ConditionEvaluator`, `FabricPins`).
- Entrypoints: `preLaunch` (`PreLaunchEntrypoint`), `client`, `modmenu`; the game-test mod uses `fabric-client-gametest`.
- Tests: `fabric-loader-junit` (same version as `loader_version`).

None of these classes changed between 0.19.5 and 0.20.1. The client game-test API (`FabricClientGameTest`, `ClientGameTestContext`, `TestSingleplayerContext`) belongs to Fabric API, not Loader, and moves only with `fabric_api_version`. Both `fabric-loader` and `fabric-loader-junit` 0.20.1 are on maven.fabricmc.net.

## Mixin 0.17.5 and the compatibility level

Loader gives each mod's mixin configs a compatibility level taken from the lowest `fabricloader` version the mod accepts ([`FabricMixinBootstrap.getMixinCompat`](https://github.com/FabricMC/fabric-loader/blob/0.20.1/src/main/java/net/fabricmc/loader/impl/launch/FabricMixinBootstrap.java), table in [`FabricMixinVersions`](https://github.com/FabricMC/fabric-loader/blob/0.20.1/src/main/java/net/fabricmc/loader/impl/launch/FabricMixinVersions.java)). RigTune's `>=0.19.5` maps to `COMPATIBILITY_0_17_4` on every loader, so on 0.20.x its mixins keep 0.19.5's behaviour.

What 0.17.5 changes ([0.17.4...0.17.5](https://github.com/FabricMC/Mixin/compare/0.17.4+mixin.0.8.7...0.17.5+mixin.0.8.7), 12 commits):

- [ae50870e](https://github.com/FabricMC/Mixin/commit/ae50870e): selectors may only have whitespace between components, and `->` is disallowed for now. Gated: only at `COMPATIBILITY_0_17_5`. RigTune's five mixins (`rigtune.client.mixins.json`) use plain names and descriptors such as `"runTick"` and `"Lnet/minecraft/client/Minecraft;tick()V"`, with no whitespace, `->` or `@Desc`, so they'd pass even if RigTune declared `>=0.20.0`.
- New lambda selectors (unused), `@Desc` `next` disabled (unused), and a warning dropped when a config has no side mixins.

## Build on 0.20.1

Run on 15 Nov with `loader_version=0.20.1` set locally (not committed), through the gate queue: `./gradlew build` passed in 4 min 4 s for both 26.2 and 26.3. The unit tests ran on `fabric-loader-junit` 0.20.1: 2,916 per version, 0 failures, 3 skipped. The built jars still declare `"fabricloader": ">=0.19.5"`, which is hand-written in `fabric.mod.json` and doesn't follow `loader_version`.

Not run: the client game tests. They are the only check that starts Minecraft on the new loader, and they need CI or the game-test lock. A PR that moves `loader_version` gets them from CI automatically.

Also checked: with `gradle.properties` back at 0.19.5, `./gradlew -Ploader_version=0.20.1 :26.3:dependencies --configuration compileClasspath` resolves `net.fabricmc:fabric-loader:0.20.1`, and the same command without `-P` resolves 0.19.5. A CI leg could switch the loader without editing the file.

## Options and costs

**1. Move the dev loader to 0.20.1, keep `>=0.19.5` (recommended).** One line in `gradle.properties`. Every CI run (unit tests, all six client game-test jobs, E2E, compat030/compat040, which read `loader_version` from that file) then tests what new installs run. Costs: no extra CI minutes; no change for players, since the declared minimum stays. What it gives up: CI no longer starts 0.19.5. That trade is fine, because the 0.19.5 to 0.20.1 diff has no API change and RigTune's mixins keep the same compatibility level on either loader. Do it in a code PR that runs the full CI anyway (the 0.6.0 port is the natural one); there's no user-facing reason to cut a 0.5.x release for it.

**2. Add a 0.20.x CI leg, keep the dev loader at 0.19.5.** A leg could run `-Ploader_version=0.20.1` (checked above). The cheapest useful leg is one client game-test leg (one MC version, one backend, both parts): about 17 job-minutes per CI run, measured from main's run 43125625281 (12 Nov: 26.3 OpenGL took 6.4 + 10.8 minutes; the whole run was 60 job-minutes). Wall time stays about the same because legs run in parallel, minutes are free on a public repo, and the extra screenshot/log artifacts add storage at 7-day retention. It also means another matrix dimension in `tools/gametest_matrix.py` and `build.yml`. Testing both loaders on every push buys little given the diff above. Worth it only if Loader starts shipping behaviour changes that matter to RigTune.

**3. Raise the minimum to `>=0.20.0`.** Not recommended now. It gains nothing, since RigTune needs no 0.20 API. It costs two things. Anyone still on Loader 0.19.x gets Loader's "incompatible mods" screen until they update the loader. RigTune's mixins also move to `COMPATIBILITY_0_17_5` (harmless for its current selectors, but a behaviour change for no reason). Revisit when RigTune needs a Loader feature, or at the 26.4 port if Fabric API for 26.4 requires a newer loader anyway.

With option 1, the Loader line in docs/STATUS.md ("players on 0.20.x run it on a loader CI doesn't test") can be closed once the PR lands.
