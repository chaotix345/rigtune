# Client game tests in CI (Linux + Xvfb), per Minecraft version

Research for v0.3.0 P0 item 2, 2026-09-26. Every claim below was checked live on that date: in Loom's source and jar, Fabric's docs and CI, SDL's source, the Minecraft jars, or in real GitHub Actions runs of the prototype (run timestamps are UTC, so they read 2026-09-25). Claims I could not check are marked UNVERIFIED, with the reason.

Prototype: branch `research/ci-gametest` (worktree `C:/Dev/Worktrees/rigtune-r-ci`), based on `feat/v0.3.0` at 748bfc2. Its workflow is `.github/workflows/gametest-proto.yml`. The Minecraft client was never started on this machine: every game run was on GitHub's runners.

## 1. Result and recommendation

**CI game tests are green on both versions.** All four test classes ran, with 52 screenshots per leg. Green runs of the final job shape:

- https://github.com/chaotix345/rigtune/actions/runs/36164710862 (71285b8, the branch head)
- https://github.com/chaotix345/rigtune/actions/runs/36164004195, 36162523914, 36162560514, 36163094639, 36163103970 (the same job shape)
- https://github.com/chaotix345/rigtune/actions/runs/36161585058 (dev and production tasks side by side, 5 legs)

**Approach:** add one job, `client-gametest`, to `build.yml`. It runs Loom's production run task (`ClientProductionRunTask`) on ubuntu-24.04 under Xvfb, as a matrix with three legs:

- 26.2 on OpenGL
- 26.3 on OpenGL
- 26.3 on Vulkan (lavapipe)

Three details make it work:

1. **Xvfb is automatic.** Loom wraps the game in `xvfb-run` by itself, because GitHub sets `CI` and the runner is Linux.
2. **26.3 needs one SDL environment variable.** `SDL_OPENGL_FORCE_SRGB_FRAMEBUFFER=skip` lets it start on OpenGL under Xvfb (§3.2).
3. **A step checks which backend actually ran.** Without that check, a leg can silently fall back to the other backend. Fabric API's own CI has exactly that blind spot.

Each leg takes about 4 minutes with a warm Gradle cache. The legs run in parallel, alongside the existing `java` job.

Why a job in `build.yml` and not a separate workflow: `update-rules.yml` already dispatches `build.yml` for the bot PRs it opens (see the comment at the top of build.yml). A rules change is exactly what the game tests' recommendation screens should see. Branch protection isn't configured on `main` (`GET /branches/main/protection` returns 404), so no required-check names need updating.

## 2. Exact files the final change needs

Only these 3 files change. Nothing in `src/` changes.

1. **`build.gradle`**: add the `runProductionClientGameTest` task. The block below is exactly as committed on the research branch (71285b8), placed after `runProductionSmoke`:

   ```groovy
   // CI (build.yml, client-gametest): the client game tests against the built jar in a production client, with
   // fabric-api, Sodium and ModMenu as real mod jars (the dev run has them on localRuntime). Its own run dir, wiped first.
   configurations {
   	productionGameTestMods {
   		canBeConsumed = false
   		transitive = false
   	}
   }

   dependencies {
   	productionGameTestMods "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"
   	productionGameTestMods fabricApi.module("fabric-client-gametest-api-v1", project.fabric_api_version)
   	productionGameTestMods "com.terraformersmc:modmenu:${project.modmenu_version}"
   	productionGameTestMods "maven.modrinth:sodium:${project.sodium_version}"
   }

   def productionGameTestRunDir = layout.buildDirectory.dir("run/productionClientGameTest")

   tasks.register("runProductionClientGameTest", net.fabricmc.loom.task.prod.ClientProductionRunTask) {
   	def fs = objects.newInstance(InjectedFs).fs
   	runDir = productionGameTestRunDir
   	mods.from(gametestJar)
   	mods.from(configurations.productionGameTestMods)
   	jvmArgs.add("-Dfabric.client.gametest")
   	doFirst {
   		fs.delete { delete productionGameTestRunDir }
   	}
   }
   ```

   Why it is built this way:

   - **It uses its own configuration, not Loom's `productionRuntimeMods`.** Every `ClientProductionRunTask` reads `productionRuntimeMods` by default (AbstractProductionRunTask's constructor). If these mods went there, `runProductionSmoke` would also get fabric-api, Sodium and ModMenu through `-Dfabric.addMods`, on top of the player's own copies in `run/mods`, and the game would find duplicate mods.
   - **`transitive = false`** keeps the fabric-api POM from pulling its modules in next to the fat jar.
   - **The client game test module has to be added separately**, because the fat jar doesn't nest it (the same note as `productionSmokeMods`).
   - **Sodium and ModMenu are required.** RigTuneClientGameTest, UndoGameTest and UiGameTest use them. In the dev run they come from `localRuntime`.
   - **The run dir is wiped first**, like `runClientGameTest`'s `deleteGameTestRunDir`. Loom's production task doesn't wipe it.
   - **The run dir is set explicitly**, because the task's default is `<project>/run`. For a smoke/e2e run, that directory is the player's instance.

2. **`stonecutter.gradle`**: add `order 'runProductionClientGameTest'` to `stonecutter.tasks`. An unqualified local `./gradlew runProductionClientGameTest` then runs 26.2 and 26.3 one after the other (`-m` lists 26.2 then 26.3) instead of starting two clients. CI always passes the project path.

3. **`.github/workflows/build.yml`**: add the job below. It is the prototype's job minus `cache-read-only: false`. The prototype needed that line because setup-gradle only writes its cache from the default branch.

   ```yaml
     # The client game tests (src/gametest) against the built jar in a production client, under Xvfb with Mesa llvmpipe
     # (lavapipe for Vulkan). Loom's ClientProductionRunTask wraps the game in xvfb-run by itself when CI is set on Linux.
     client-gametest:
       name: client game tests (${{ matrix.mc }}, ${{ matrix.backend }})
       runs-on: ubuntu-24.04
       timeout-minutes: 30
       strategy:
         fail-fast: false
         matrix:
           include:
             - { mc: '26.2', backend: OpenGL }
             - { mc: '26.3', backend: OpenGL }
             - { mc: '26.3', backend: Vulkan }
       env:
         # 26.3 creates its window with SDL3, which asks GLX for an sRGB-capable visual that Xvfb doesn't have; "skip"
         # leaves that attribute out. Left empty (unset for SDL) on the Vulkan leg: OpenGL then fails and 26.3 falls back
         # to Vulkan on lavapipe. 26.2 (GLFW) ignores it.
         SDL_OPENGL_FORCE_SRGB_FRAMEBUFFER: ${{ matrix.backend == 'OpenGL' && 'skip' || '' }}
       steps:
         - uses: actions/checkout@v7
         - uses: actions/setup-java@v6
           with:
             distribution: microsoft
             java-version: '25'
         - uses: gradle/actions/setup-gradle@v6
         - name: Install the Mesa Vulkan driver (lavapipe)
           if: matrix.backend == 'Vulkan'
           run: sudo apt-get update && sudo apt-get install -y --no-install-recommends mesa-vulkan-drivers
         # If the game finds no graphics backend it opens a blocking error dialog, so a hang means a timeout here.
         - name: Client game tests
           timeout-minutes: 20
           run: ./gradlew :${{ matrix.mc }}:runProductionClientGameTest --stacktrace
         - name: Check the game used ${{ matrix.backend }}
           run: grep -F "Using graphics backend ${{ matrix.backend }}" versions/${{ matrix.mc }}/build/run/productionClientGameTest/logs/latest.log
         - uses: actions/upload-artifact@v7
           if: always()
           with:
             name: gametest-screenshots-${{ matrix.mc }}-${{ matrix.backend }}
             path: versions/${{ matrix.mc }}/build/run/productionClientGameTest/screenshots/
             if-no-files-found: ignore
         - uses: actions/upload-artifact@v7
           if: always()
           with:
             name: gametest-logs-${{ matrix.mc }}-${{ matrix.backend }}
             path: |
               versions/${{ matrix.mc }}/build/run/productionClientGameTest/logs/
               versions/${{ matrix.mc }}/build/run/productionClientGameTest/crash-reports/
               versions/${{ matrix.mc }}/build/run/productionClientGameTest/hs_err_pid*.log
             if-no-files-found: ignore
   ```

   The `setup-gradle` step is new to this repo. The existing `java` job doesn't use it. Adding it to `java` as well is optional.

Not part of the final change:

- **`gametest-proto.yml`** stays on the research branch only.
- **The dev task path (`runClientGameTest`) needs no change at all.** If the production task ever gets in the way, the fallback is to swap the task name in the job and the run dir to `clientGameTest`. That combination was green in run 36161585058.

When a Minecraft version is added to `settings.gradle`, add a matrix line. The matrix is static on purpose. It could be generated from `settings.gradle` in a setup job, but that's more machinery than one line per release.

## 3. Findings

### 3.1 `xvfb-run ./gradlew runClientGameTest` vs Loom's production run task

**Loom does the Xvfb wrapping for both tasks,** so `xvfb-run ./gradlew …` in the workflow isn't needed, and would even nest a second Xvfb. This repo's `loom_version=1.17-SNAPSHOT` resolves to **1.17.21**. That is the latest snapshot build (21, 2026-09-15, per `maven-metadata.xml`), and the local cache and the CI log (`Fabric Loom: 1.17.21`) agree. Its source (branch `dev/1.17`) and jar show the two mechanisms:

- **Production task:** `net.fabricmc.loom.task.prod.ClientProductionRunTask` has `Property<Boolean> getUseXVFB()` (Groovy: `useXVFB`).
  - Its convention is `true` when the `CI` env var is set and the OS is Linux.
  - It runs `xvfb-run -a <java> …` (`configureCommand`) and doesn't check that xvfb-run exists.
  - It also adds `--assetIndex`, `--assetsDir` (Loom's user-cache assets) and `--gameDir`, and depends on `downloadAssets`.
  - `mods` defaults to the `jar` output (26.x isn't remapped) plus `productionRuntimeMods`, passed as `-Dfabric.addMods`.
  - `runDir` defaults to `<project>/run`.
- **Dev run task:** the dev run tasks (`net.fabricmc.loom.task.AbstractRunTask`, which includes `runClientGameTest`) have their own `Property<Boolean> getUseXvfb()` (Groovy: `useXvfb`, lower case).
  - Its convention is `CI` set, Linux, a client run config, and `xvfb-run --help` exiting 0 (`XVFBExistsValueSource`).
  - It runs `xvfb-run --auto-servernum java …`.
- **Screen size:** neither task passes `-s`, so Xvfb gets xvfb-run's default. Debian's `xvfb-run` at 21.1.12-1 (the runner's `xvfb 2:21.1.12-1ubuntu1.6`) sets `XVFBARGS="-screen 0 1280x1024x24"`. That is a 24-bit screen large enough for the tests' 1280×720 window, and RigTune's header shows "Display 1280×1024" in CI.

Fabric's docs recommend the production task for CI. The "Run Game Tests on GitHub Actions" section says the snippet runs client game tests "using Loom's production run tasks". Their reference task is `tasks.register("runProductionClientGameTest", ClientProductionRunTask) { jvmArgs.add("-Dfabric.client.gametest"); useXVFB = true }`. Their docs-repo workflow runs `./gradlew runProductionClientGameTest` on `ubuntu-24.04` with Temurin 25.

One mistake in that example: it uploads `build/run/clientGameTest/screenshots`, which is the dev task's run dir. The production task's default is `run/`. Setting `runDir` explicitly, as RigTune's task does, avoids that. Fabric API's own `build.yml` (branch 26.3) runs the dev task instead (`./gradlew runClientGametest`, a Loom run config) on ubuntu-24.04, as an `opengl`/`vulkan` matrix.

**Both tasks pass for RigTune** (run 36161585058):

- Both produce the same 52 screenshots.
- The static screens are pixel-identical between them: the mean absolute difference is 0.0 on video-settings and ui-settings, and 0.13 on ui-main.
- The Gradle times are also the same, 2m52s–3m44s.

**The production task is the better gate for RigTune:**

- **It tests what ships.** The mod runs from the built `jar` exactly as release.yml uploads it: expanded `fabric.mod.json`, packaged resources, the main+client jar layout, and loading through `-Dfabric.addMods` rather than the dev classpath groups. Fabric API, Sodium and ModMenu are real mod jars. Dev-only launch pieces are absent: the dev launch injector and `fabric.development`.
- **It costs almost nothing extra.** It adds about 25 lines of Gradle (§2) and no job time.
- **It gives earlier warning for the Phase-5-style production smokes,** which until now were only run by hand.
- **The dev task's remaining advantage is small.** It needs zero build changes and is what developers run locally. Keeping it as the documented fallback covers that.

UNVERIFIED: `runProductionClientGameTest` was not run on the Windows dev machine. The rules don't allow launching a client locally. It is the same task type as the `runProductionSmoke` that works there, and `useXVFB` is false without `CI`.

### 3.2 What 26.2 and 26.3 need from the GPU stack, and what the runner gives

**What Minecraft asks for** (from the jars in Loom's cache):

- **26.2** creates its window with GLFW. `com.mojang.blaze3d.opengl.GlBackend` asks for an OpenGL **3.3 core**, forward-compatible context. The hints are 0x22002=3, 0x22003=3, 0x22008=0x32001 and 0x22006=1, and the jar also contains the message "Driver does not support OpenGL 3.3".
- **26.3** creates its window with **SDL3** ("Minecraft now uses SDL3 instead of GLFW", [Java Edition 26.3](https://minecraft.wiki/w/Java_Edition_26.3)). `com.mojang.renderpearl.backend.opengl.GlBackend` sets these SDL attributes:
  - `CONTEXT_MAJOR_VERSION`=3 and `CONTEXT_MINOR_VERSION`=3
  - `CONTEXT_PROFILE_MASK`=CORE and `CONTEXT_FLAGS`=FORWARD_COMPATIBLE
  - **`SDL_GL_FRAMEBUFFER_SRGB_CAPABLE`=1**

  The attribute numbers are checked against LWJGL 3.4.3's `SDLVideo` constants 17/18/20/19/22.
- **Vulkan backend:** both versions have one. It needs "Vulkan 1.2 with dynamic rendering and push descriptors" ([Java Edition 26.2](https://minecraft.wiki/w/Java_Edition_26.2)). In 26.2, "Default" is "currently the same as Prefer OpenGL". If one backend fails, the game tries the other.

**The runner** (ubuntu-24.04 image 20260920.314, Ubuntu 24.04.5, 4 vCPUs, 16 GB RAM) already has the software OpenGL stack, as the CI log shows:

- `xvfb 2:21.1.12-1ubuntu1.6`
- `libgl1-mesa-dri`, `libglx-mesa0` and `mesa-libgallium` 25.2.8
- `libvulkan1` 1.3.275, but **no Vulkan driver (ICD)**

Mesa's llvmpipe reports "4.5 (Core Profile) Mesa 25.2.8-0ubuntu0.24.04.2" with the renderer "llvmpipe (LLVM 20.1.2, 256 bits)". That meets 3.3 core, and Sodium runs on it. No environment variables such as `LIBGL_ALWAYS_SOFTWARE` or `MESA_GL_VERSION_OVERRIDE` are needed: llvmpipe is the only GL driver, and it already exposes 4.5.

**The 26.3 problem and its fix:**

- **What fails.** Under Xvfb, 26.3 logged `Failed to create backend OpenGL … Failed to create window for OpenGL context: Couldn't find matching GLX visual` (run 36158675026).
- **Why.** SDL 3.4's `SDL_x11opengl.c` adds `GLX_FRAMEBUFFER_SRGB_CAPABLE_ARB=True` to the visual request when the app sets that attribute, and it reports exactly this error when no visual matches.
- **The fix.** SDL's hint `SDL_OPENGL_FORCE_SRGB_FRAMEBUFFER="skip"` leaves the attribute out. SDL_hints.h (release-3.4.x) documents it: "Don't make any request for an sRGB-capable context (don't specify the attribute at all during context creation time)". It has existed since SDL 3.4.2, and 26.3 logs `SDL-3.4.14`. An empty value means unset.
- **The result.** With the variable set, 26.3 logs `Using graphics backend OpenGL, using drivers: 4.5 (Core Profile) Mesa 25.2.8`, and all tests pass.
- **The screenshots are unaffected.** Static frames are pixel-identical to the Vulkan leg's: the mean absolute difference is 0.0 on video-settings and ui-settings. That makes sense, because Fabric takes screenshots from Minecraft's own render target, not the window's default framebuffer (`ClientGameTestContextImpl.doTakeScreenshot`).
- **Why not EGL.** `SDL_VIDEO_FORCE_EGL` was the other idea, and I didn't use it. 26.3's `GlBackend.loadLibrary` passes LWJGL's GL library to `SDL_GL_LoadLibrary`, then fails with "glGetError mismatch" unless SDL's `glGetError` address equals LWJGL's. Switching SDL to EGL would very likely break that check. (UNVERIFIED, reasoned from bytecode, not run.)

**Fabric API's own CI has the same failure, and hides it.** Its `client_test (opengl)` job on 26.3 (run 35471896965, job 105974232602, 2026-09-19) logs `Couldn't find matching GLX visual`, then `Using graphics backend Vulkan … llvmpipe`. The job installs `mesa-vulkan-drivers`, so the "opengl" leg quietly tests Vulkan. That is why RigTune's job checks the backend line in `latest.log`.

**The Vulkan leg (lavapipe):**

- **How it's set up.** `apt-get install mesa-vulkan-drivers`, with the SDL variable left empty. OpenGL then fails on the sRGB visual and 26.3 falls back to `Vulkan, using drivers: 1.4.318 llvmpipe Mesa 25.2.8`. All tests pass (runs 36161585058, 36162523914, …).
- **Why keep it.** Vulkan is where Mojang is heading: "it is intended to switch the game from OpenGL to Vulkan". It also covers Sodium's Vulkan path and RigTune's backend detection.
- **The catch.** The leg depends on OpenGL failing. If a later SDL, Mesa or Minecraft change makes the sRGB visual available, the backend check fails loudly. The fix then is `preferredGraphicsBackend:"vulkan"` in the run dir's `options.txt`, which Fabric's CI writes together with `version:4896`. It would have to be written after the wipe in `doFirst`.
- **No 26.2 Vulkan leg.** 26.2 on GLFW gets OpenGL under Xvfb, so a Vulkan leg there would need that `options.txt` route. Not prototyped.

**A missing backend hangs the game.** Without the SDL variable and without lavapipe, both backends fail on 26.3 (`Vulkan is not supported: Installed Vulkan doesn't implement the VK_KHR_surface extension`). The game then hangs until cancelled; run 36158675026 sat for 22 minutes. The cause is in 26.3's `Minecraft.<init>`: it builds "No supported graphics backend was found." and calls `com.mojang.blaze3d.platform.MessageBox.error`, which is a modal dialog nobody can click under Xvfb. This is inferred from bytecode: the log simply stops after the Vulkan error. That's why the game-test step has `timeout-minutes: 20`. A normal run's Gradle step takes 3–4 minutes.

### 3.3 Audio (OpenAL) on a headless runner

No setup needed. Every leg logs `Error starting SoundSystem. Turning off sounds & music` with `IllegalStateException: Failed to open OpenAL device` (SoundEngine.loadLibrary), because the runner has no sound device. The game carries on without sound. `libasound2t64` is installed and PulseAudio isn't.

This also means the Windows-only flake on this machine, the native OpenAL crash at 26.3 sound startup, can't happen in CI, because the device never opens. The narrator also fails to load `libflite`, which is harmless.

### 3.4 Screenshots, logs and crash reports

- **Where screenshots go.** Fabric saves them to `FabricLoader.getGameDir()/screenshots/NNNN_<name>.png`, with a counter prefix unless disabled (`ClientGameTestContextImpl.saveScreenshot`, Fabric API branch 26.3). With the production task that is `versions/<mc>/build/run/productionClientGameTest/screenshots/`. With the dev task it is `versions/<mc>/build/run/clientGameTest/screenshots/`.
- **What gets uploaded.** `actions/upload-artifact@v7` with `if: always()` uploads the screenshots (about 6.9–13 MB per leg), plus `logs/`, `crash-reports/` and `hs_err_pid*.log`.
- **The failure path, proven with a deliberate failure.** A temporary commit (2b4a61d, reverted in 24d0ae4) threw an `AssertionError` at the start of UiGameTest. All three legs went red: `Client gametests failed with an exception`, then `Process 'command 'xvfb-run'' finished with non-zero exit value 1`, then `BUILD FAILED` (run 36163460374). The artifacts still uploaded: 35 screenshots, from every class that ran before the failure, plus `latest.log`.
- **Why a failure fails the job.** Fabric rethrows the test thread's exception out of `Minecraft.run` (`mixin/client/gametest/threading/MinecraftMixin`). Loader reports it as a `FormattedException` and the JVM exits with 1. No Minecraft crash report is written in that case.
- **Logs in production.** The production run writes `logs/latest.log` only, no `debug.log`. The log is also streamed into the job log.

### 3.5 Runtime, caching and matrix

Job durations (the whole job; the Gradle step is in brackets):

| Run | 26.2 OpenGL | 26.3 OpenGL | 26.3 Vulkan |
|---|---|---|---|
| 36158675026 (cold cache, first run) | 5m05s (4m40s) | hung, cancelled (sRGB visual) | n/a |
| 36162523914 | 4m10s (3m43s) | 4m00s (3m31s) | 3m48s (3m00s) |
| 36162560514 | 4m20s (3m41s) | 3m57s (3m27s) | 4m17s (3m28s) |
| 36163094639 | 4m03s (3m40s) | 3m43s (3m10s) | 3m54s (3m01s) |
| 36163103970 | 4m09s (3m45s) | 3m45s (3m19s) | 3m55s (3m13s) |
| 36164004195 | 4m21s | 3m52s | 4m11s |
| 36164710862 | 4m10s | 3m15s | 3m46s |

- **Where the time goes.** About 3 of the 4 minutes are the game itself: the first frame to UiGameTest's end took 16:35:05 → 16:38:06 on 26.2. llvmpipe on 4 cores renders the benchmark world at about 37 FPS average.
- **Parallelism.** The legs run in parallel, so the job adds about 4–5 minutes of wall time next to `java` (about 2.5–3 minutes today). The repo is public, so standard runners cost nothing. On a private repo it would be about 12 runner-minutes per push.
- **Caching.** `gradle/actions/setup-gradle@v6` (v6.3.0 is the latest release; v6.4.0-rc.1 exists) caches `~/.gradle/caches` and the wrapper. That covers Loom's `caches/fabric-loom/` too: the Minecraft jars and the ~480 MB of assets.
  - A cold first run took 4m32s–4m40s in Gradle, against 3m40s–3m45s warm on 26.2.
  - By default setup-gradle writes the cache only from the default branch, and other branches and PRs read it. That's the right setting for the final job. The prototype set `cache-read-only: false` only so a research branch could warm its own cache.
  - Its default "Enhanced Caching" provider is proprietary but "free for all public repositories". `cache-provider: basic` is the MIT alternative ([setup-gradle.md](https://github.com/gradle/actions/blob/v6.3.0/docs/setup-gradle.md)).
- **Matrix per version.** Use `include:` entries (mc × backend) with `fail-fast: false`, so one version's failure doesn't cancel the other.
- **Pin `ubuntu-24.04`.** `ubuntu-latest` moves to Ubuntu 26.04 in November 2026 ([runner-images announcement](https://github.com/actions/runner-images/issues/14748)), with a different Mesa and Xvfb.

### 3.6 Flakiness and retries

- **Record so far.** After the sRGB fix: 23 green legs out of 23, in 7 runs (5 pushes, plus 2 dispatches of one commit). That counts the 5-leg run 36161585058 and the 3-leg runs 36162523914, 36162560514, 36163094639, 36163103970, 36164004195 and 36164710862. Plus 3 legs of the deliberate failure, red as intended. 0 flakes.
- **Why it's stable.** Fabric's harness syncs ticks, llvmpipe is deterministic, and the flaky OpenAL path can't run in CI (§3.3).
- **Not needed.** Fabric's docs mention a network-synchronizer failure on GitHub Actions and suggest `-Dfabric.client.gametest.disableNetworkSynchronizer=true`. It didn't occur, so it isn't set.
- **Recommendation: no automatic retry.** A retry would hide real, intermittent regressions in apply/undo, which is what these tests guard. Instead:
  - the 20-minute step timeout turns a hang into a quick red;
  - the artifacts come back on every outcome;
  - GitHub's "Re-run failed jobs" handles one-off infrastructure failures.
- **If flakes appear later.** Retry the game-test step once in a shell loop, `for i in 1 2; do ./gradlew … && exit 0; echo "::warning::game tests failed, attempt $i"; done; exit 1`, so every retry shows up as a warning.

### 3.7 What CI doesn't cover (unchanged local checks)

- **A real GPU driver.** CI has only llvmpipe and lavapipe, so the hardware tier is always 0/5 ("No graphics driver in use"). Tier-dependent recommendations for real GPUs stay covered by the unit-test matrix.
- **Benchmark numbers.** They mean nothing on llvmpipe with tick sync; the tests don't assert FPS thresholds.
- **Windows-only behavior,** including the OpenAL crash.
- **Distant Horizons, Iris and Xaero.** They aren't loaded in the game tests (the harness deadlocks on world exit with DH or Xaero anyway).
- **The network.** The tests reach Modrinth (the UI shows "Online"). UNVERIFIED: whether the tests still pass while api.modrinth.com is down. RigTune falls back to offline data, but a Modrinth outage never happened during these runs.

## 4. Risks

- **Moving targets.**
  - `loom_version=1.17-SNAPSHOT` can change under CI. `useXVFB`/`useXvfb` are `@ApiStatus.Experimental` Loom API.
  - Fabric API, Sodium and ModMenu come from Maven at the pinned versions in `versions/<mc>/gradle.properties`, so Modrinth Maven or Terraformers Maven being down fails the job.
  - The runner image's Mesa updates, and `ubuntu-latest` becomes 26.04 in November (pinned to 24.04 here).
- **The SDL hint is a workaround for Xvfb's GLX visuals.** It is harmless on 26.2 (GLFW) and has no effect on the Vulkan leg (empty value). A future Minecraft version that stops requesting sRGB, or a Mesa/Xvfb that exposes sRGB visuals, makes it a no-op. The backend check catches any silent backend switch.
- **Hang mode.** Any future "no backend" situation shows the blocking MessageBox and hangs. The 20- and 30-minute timeouts bound it.
- **The Vulkan leg depends on the OpenGL fallback** (§3.2). If that changes, the backend check fails and `options.txt` has to select Vulkan explicitly.
- **Every new Minecraft version needs a matrix line** in build.yml (and `versions/<mc>/gradle.properties` as today).
- **No local proof on Windows** of `runProductionClientGameTest` (UNVERIFIED, §3.1).

## 5. Run log (chaotix345/rigtune, workflow gametest-proto)

| Run | Commit | Legs | Outcome |
|---|---|---|---|
| [36158675026](https://github.com/chaotix345/rigtune/actions/runs/36158675026) | cae849f | 26.2/26.3 × dev/prod | 26.2 both green (cold 4m32s/4m40s Gradle). 26.3 both hung on the GLX sRGB visual and the MessageBox, cancelled at about 22 minutes |
| [36161585058](https://github.com/chaotix345/rigtune/actions/runs/36161585058) | c4a495f | 26.2 dev/prod, 26.3 dev/prod on OpenGL, 26.3 dev on Vulkan | 5/5 green, 52 screenshots each, no dark or blank frames |
| [36162523914](https://github.com/chaotix345/rigtune/actions/runs/36162523914) | fac620e | final shape (3 legs) | green |
| [36162560514](https://github.com/chaotix345/rigtune/actions/runs/36162560514) | c32df2b | final shape | green |
| [36163094639](https://github.com/chaotix345/rigtune/actions/runs/36163094639) | c32df2b (dispatch) | final shape | green |
| [36163103970](https://github.com/chaotix345/rigtune/actions/runs/36163103970) | c32df2b (dispatch) | final shape | green |
| [36163460374](https://github.com/chaotix345/rigtune/actions/runs/36163460374) | 2b4a61d (deliberate failure) | final shape | 3/3 red as intended, artifacts uploaded |
| [36164004195](https://github.com/chaotix345/rigtune/actions/runs/36164004195) | 24d0ae4 (reverted) | final shape | green |
| [36164710862](https://github.com/chaotix345/rigtune/actions/runs/36164710862) | 71285b8 (branch head) | final shape | green |

The existing `build.yml` was green on every one of these commits.

**Screenshot checks.** The screenshots from runs 36161585058 and 36164004195 are in `C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/4bf644a6-501a-4093-a81e-9e29a6010059/scratchpad/r-ci/run2/` and `…/r-ci/final/`.

- **A pixel check** (PIL mean brightness and distinct-colour count over all 416 images) found no black or blank frames. The darkest frame, 26.2 `0039_ui-undo-last-from-button`, has mean 11.7: it's a legitimately dark scene behind the dialog, and I looked at it. On 26.3 the minimum distinct-colour count per frame is 565 (OpenGL) and 1355 (Vulkan).
- **I viewed these frames:** `0014_real-world` (the RigTune screen over the blurred world: "GPU llvmpipe (LLVM 20.1.2, 256 bits) · OpenGL · GPU tier 0", "Display 1280×1024"), `0021_bench-world-after` (the benchmark result, 37 FPS average), `0037_ui-main-1280x720-scale2`, `0019_bench-world-running` (sky while chunks load, as expected at that step) and both versions' `0039`. All rendered correctly.

## 6. Sources (accessed 2026-09-26)

- **Fabric docs:**
  - Automatic testing, "Run Game Tests on GitHub Actions": https://docs.fabricmc.net/develop/automatic-testing (source: https://github.com/FabricMC/fabric-docs/blob/main/develop/automatic-testing.md)
  - Reference task: https://github.com/FabricMC/fabric-docs/blob/main/reference/latest/build.gradle (region `automatic_testing_game_test_2`)
  - Docs CI job: https://github.com/FabricMC/fabric-docs/blob/main/.github/workflows/build.yaml (`client_game_test`)
  - Production run tasks: https://docs.fabricmc.net/develop/loom/production-run-tasks
- **Loom 1.17** (1.17.21 jar in the Gradle cache, plus source):
  - https://github.com/FabricMC/fabric-loom/blob/dev/1.17/src/main/java/net/fabricmc/loom/task/prod/ClientProductionRunTask.java
  - https://github.com/FabricMC/fabric-loom/blob/dev/1.17/src/main/java/net/fabricmc/loom/task/prod/AbstractProductionRunTask.java
  - https://github.com/FabricMC/fabric-loom/blob/dev/1.17/src/main/java/net/fabricmc/loom/task/AbstractRunTask.java
  - https://github.com/FabricMC/fabric-loom/blob/dev/1.17/src/main/java/net/fabricmc/loom/util/XVFBExistsValueSource.java
  - https://github.com/FabricMC/fabric-loom/blob/dev/1.17/src/main/java/net/fabricmc/loom/configuration/fabricapi/FabricApiTesting.java
  - Snapshot metadata: https://maven.fabricmc.net/net/fabricmc/fabric-loom/net.fabricmc.fabric-loom.gradle.plugin/1.17-SNAPSHOT/maven-metadata.xml
- **Fabric API (branch 26.3):**
  - https://github.com/FabricMC/fabric/blob/26.3/.github/workflows/build.yml
  - https://github.com/FabricMC/fabric/blob/26.3/build-logic/src/main/groovy/fabric-api.root-testing.gradle
  - `fabric-client-gametest-api-v1`: `ClientGameTestContextImpl`, `FabricClientGameTestRunner`, `threading/ThreadingImpl`, `mixin/client/gametest/threading/MinecraftMixin`
  - The run showing the silent Vulkan fallback: https://github.com/FabricMC/fabric/actions/runs/35471896965
- **SDL:**
  - https://github.com/libsdl-org/SDL/blob/release-3.4.x/src/video/x11/SDL_x11opengl.c (sRGB attribute, lines 587–599; the error, line 727)
  - https://github.com/libsdl-org/SDL/blob/release-3.4.x/include/SDL3/SDL_hints.h (`SDL_HINT_OPENGL_FORCE_SRGB_FRAMEBUFFER`)
- **Minecraft:**
  - https://minecraft.wiki/w/Java_Edition_26.2 (Graphics API option, Vulkan requirements)
  - https://minecraft.wiki/w/Java_Edition_26.3 (SDL3 replaces GLFW)
  - Jars: `~/.gradle/caches/fabric-loom/26.{2,3}/minecraft-client-only.jar` (GlBackend, GlDevice, Minecraft)
- **xvfb-run defaults:** https://salsa.debian.org/xorg-team/xserver/xorg-server/-/raw/xorg-server-2_21.1.12-1/debian/local/xvfb-run
- **Runner image:**
  - https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md (xvfb 2:21.1.12-1ubuntu1.6)
  - https://github.com/actions/runner-images/issues/14748 (ubuntu-latest becomes 26.04 in November 2026)
- **setup-gradle:** https://github.com/gradle/actions/blob/v6.3.0/docs/setup-gradle.md
