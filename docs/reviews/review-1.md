# RigTune review 1 (branch feat/rigtune-mvp, 3f59baa). Read-only.
Paths are relative to C:/Dev/Minecraft Setting Optimisation Mod. core = src/main/java/io/github/chaotix345/rigtune/core, client = src/client/java/io/github/chaotix345/rigtune/client.

1. CRITICAL: a RigTune self-update always leaves two rigtune jars enabled on Windows
   core/apply/HelperLauncher.java:25; client/RealController.java:367-368; core/apply/ApplyExecutor.java:33-35; core/modrinth/OnlineDataFetcher.java:53-58
   The helper JVM runs with codeSourceOf(ApplyHelper.class) = mods/rigtune-X.jar on its classpath, so it holds that jar open. Nothing excludes rigtune from update detection, so once RigTune is on Modrinth "Update RigTune" is offered and pre-selected. The plan is [DISABLE rigtune-X.jar, ENABLE rigtune-Y.jar]. The DISABLE fails on Windows (file in use, 10 x 300 ms retries) and the ENABLE still runs. Result: two jars with id rigtune, so Fabric refuses to start.
   Fix: launch the helper from copies (config/rigtune/helper/*.jar or a temp dir), and fix #2.

2. HIGH: an update's disable and enable are not applied as a unit, so a failed rename leaves two copies or none
   core/apply/ApplyExecutor.java:31-41,94-97; client/RealController.java:367-368; core/apply/ApplyHelper.java:37-60
   Each op runs independently. (a) DISABLE old.jar fails (another instance sharing mods/, AV or indexer lock, a quick relaunch inside the 1 s settle plus 3 s of retries) and ENABLE new.jar succeeds: the mod loads twice and Fabric crashes. (b) DISABLE succeeds and ENABLE fails (pending jar missing, target exists): the mod is gone. For Fabric API every dependent mod then fails, CLIENT_STOPPING never fires, and the retry never runs. There is no lock between the helper and the next game launch.
   Fix: group ops (update = group). Only ENABLE when the group's DISABLE is OK or SKIPPED_ALREADY_DONE, and undo the DISABLE when its ENABLE fails. The helper holds config/rigtune/apply.lock (FileChannel.tryLock), and preLaunch waits on it or reports it.

3. HIGH: Modrinth filename is used unsanitised, so path traversal lets a file be written and renamed anywhere
   client/RealController.java:345,368,400; core/modrinth/ModrinthFile.java:11; core/modrinth/HttpModrinthClient.java:126-144; core/apply/ApplyExecutor.java:86-97
   modsDir.resolve(filename) accepts "../", "..\", "C:\..." and "\x". download() writes the temp file and <name>.rigtune-pending there, and the helper then renames it to the bare name (e.g. a .bat or .jar in the Startup folder). The SHA-512 comes from the same response, so it doesn't help. Dependency filenames come from third-party metadata. Reserved names (NUL.jar) and non-.jar names are accepted, and ApplyExecutor never checks that paths stay inside modsDir or configDir.
   Fix: require the name to equal Path.of(name).getFileName(), with no / \ : or control characters, no leading dot, no trailing dot or space, no reserved DOS name, and a .jar ending. Assert that the target's normalized parent equals modsDir, and re-check containment in ApplyExecutor for every op.

4. HIGH: plan merge dedupes only identical ops, not mod ids, so carried-over or repeated staging enables two versions
   client/RealController.java:405-420 (Op.equals), 327-330,353 (loadedIds ignores staged ENABLEs), 88 (carried-over ops don't feed the staged set)
   Session 1 stages Sodium 0.7.0 -> 0.7.1 but the helper never runs (killed, or timed out). In session 2, 0.7.0 is still loaded and the update is offered again, now to 0.7.2. The merged plan is [DISABLE 0.7.0, ENABLE 0.7.1, ENABLE 0.7.2], which gives two Sodium jars. A shared dependency staged by two Apply clicks goes the same way if its latest version changes in between.
   Fix: record the fabric.mod.json id per ENABLE. When merging, replace any existing ENABLE for the same id (and delete its pending jar). Seed loadedIds with staged ids.

5. MEDIUM: pending.json has no lock, so staged ops are lost or orphaned
   core/apply/ApplyExecutor.java:37-41; client/RealController.java:405-431; core/apply/ApplyHelper.java:37-41
   Instance A's helper loads the plan, applies it, then deleteIfExists(pending.json), or rewrites it with only its own failed ops. Ops that instance B stage()d in the meantime are erased. B still says "restart", and its .rigtune-pending jars are orphaned. The helper waits only for its own pid.
   Fix: take one file lock for stage() and the helper. The helper re-reads under the lock and removes only the ops it executed.

6. MEDIUM: a stalled download blocks forever and wedges Apply; there's no size cap, 429 is ignored and errors are swallowed
   core/modrinth/HttpModrinthClient.java:38,128-138,184; client/RealController.java:293-298; client/probe/Probes.java:7; core/modrinth/OnlineDataFetcher.java:78-79
   The request timeout only covers the wait for headers. With ofInputStream, transferTo() blocks forever on a stalled connection. The downloading flag then stays true (every Apply returns "busy") and one of the 2 worker threads is lost. There's no check against file.size() or any cap, and JSON bodies are unbounded. rateLimited() and Retry-After are unused, and fetchAll turns every exception into offline() without logging it.
   Fix: add a byte cap (size + slack) and a stall deadline. Put orTimeout on sendAsync for the whole exchange. Cap JSON bodies. Log errors in fetchAll and back off on 429.

7. MEDIUM: the benchmark saves each test render distance to options.txt; quitting, crashing or throwing mid-run leaves it there
   client/benchmark/BenchmarkController.java:121-126,135-142,144-192; client/probe/SettingsBridge.java:136; client/RigTuneClient.java:69
   beginStep() -> applyVanilla() -> options.save() on every step. There's no CLIENT_STOPPING cancel and no try/finally in onTick, so Alt+F4, window close, a crash or a tick exception leaves the test RD (up to 32) in options.txt. On a disconnect cancel, player is null and flying isn't restored. The Esc, screen-open, disconnect and success paths otherwise restore correctly.
   Fix: set values in memory with option.set() and no save during the run. Add CLIENT_STOPPING -> cancel(), and catch exceptions in onTick -> finish(true). The FPS-cap/vsync fix must follow the same pattern.

8. MEDIUM (known issue, confirmed): the target can't be reached, and the result screen then offers RD 4
   client/benchmark/BenchmarkController.java:64-65; core/benchmark/RenderDistancePlanner.java:57; client/ui/BenchmarkResultScreen.java:49-55; src/main/resources/rigtune/rules-v1.json:1070-1071
   The target is min(refresh, 240), compared against 1% lows from logFrameDuration, which is measured under the player's cap and vsync. RigTune's own rule sets maxFps = $refreshRateCap (144 Hz -> 140), so after applying its recommendations every RD fails by construction. With no passes, bestRd = MIN_RD (4), targetMet is false, and the primary "Use 4" button is still shown.
   Fix: disable "Use N" when targetMet is false (or offer the best measured RD instead). Loosen the target, e.g. 0.9 x refresh for the 1% low.

9. MEDIUM: rules can set any options.txt key, and String fields accept CR/LF, which corrupts options.txt
   client/probe/SettingsBridge.java:167-185,290; core/recommend/Recommender.java:257
   The only gate is that the key exists in the snapshot, which covers every processOptions field (lang, lastServer, key_* binds, pack lists). String fields are unvalidated (the identity parser at SettingsBridge:290), and save() println's key:value raw. So a value containing a newline followed by "key_key.attack:..." injects extra lines. These recommendations default to selected.
   Fix: allowlist the video/performance OptionInstance keys and reject CR and LF.

10. MEDIUM: Sodium patching ignores the existing JSON type, which can break sodium-options.json
    core/apply/SodiumConfigPatcher.java:51-69
    Only a lowercase "true" or "false" becomes a boolean (the Recommender compares case-insensitively). "1" or "0" becomes a number where the field is boolean, and a non-numeric string overwrites a number. Sodium then fails to bind the file and falls back to defaults (read-only), which wipes the player's Sodium settings. There's no range check either.
    Fix: coerce to the existing primitive type and fail the op when the value can't be converted.

11. MEDIUM: update-rules.yml lacks actions: write, so gh workflow run fails every run
    .github/workflows/update-rules.yml:8-10,70
    The explicit permissions block grants only contents and pull-requests. With set -e the job ends red after opening the PR, and the bot PR gets no CI. The repo setting "Allow GitHub Actions to create and approve pull requests" is also needed. There's no shell injection: the branch is date-only and the body is passed via --body-file.
    Fix: add actions: write.

12. MEDIUM: availability comes from project-level unions, giving false positives (Java and Python)
    core/modrinth/ModrinthProject.java:26-28; tools/update_rules.py:250
    Project loaders and game_versions are unions across all versions. A mod with Fabric only for 26.1 and NeoForge for 26.2 counts as available for Fabric 26.2, so it's recommended and pre-selected, and then the Apply fails in DependencyResolver.
    Fix: check versions filtered by loader and game version, not the project fields.

13. LOW: remote rules are unbounded in size and regex cost
    core/rules/RemoteRulesFetcher.java:47; core/rules/RulesDocument.java:86; core/hardware/GpuClassifier.java:48; core/hardware/CpuClassifier.java:30
    ofString is unbounded, and OOM isn't caught by catch (Exception). A catastrophic gpuTiers or cpuTiers pattern (nested quantifiers) runs on every rebuild; two rebuilds pin both workers, and all async work stops (the render thread is unaffected).
    Fix: cap the body (~2 MB), cap pattern length, and use a deadline-checking CharSequence for matching.

14. LOW: compareVersions overflow kills the whole report
    core/recommend/Recommender.java:90-91,288
    A 19+ digit segment in a remote minModVersion makes Long.parseLong throw NumberFormatException, and every client shows "scan failed".
    Fix: compare with BigInteger, and isolate per-section failures.

15. LOW: the benchmark's SWEEP_DOWN phase uses pitch -25, which looks up
    client/benchmark/BenchmarkController.java:171
    Minecraft pitch is positive downward, so half the samples face the sky and the 1% lows come out inflated.
    Fix: use +25, or rename the phase if looking up is intended.

16. LOW: a ClientState lost update re-shows the "applied" toast
    client/RealController.java:86,131; client/RigTuneClient.java:168-171; client/ClientState.java:43
    RealController saves its stale init-time copy on a goal change, which reverts the lastShownApply that showNotices saved. The write isn't atomic either.
    Fix: use one shared instance and write atomically.

17. LOW: DISABLE and UPDATE can rename jars outside mods/
    client/probe/ModScanner.java:43; client/RealController.java:228,367
    Any PATH-origin jar is eligible, including -Dfabric.addMods or launcher-shared folders, so the change affects other instances.
    Fix: offer file actions only when the jar's parent is modsDir.

18. LOW: update_rules.py robustness
    tools/update_rules.py:123 (float(Retry-After) raises ValueError on an HTTP-date), 193 (TOMLDecodeError isn't caught), 340/354 (raw Modrinth titles in the PR-body markdown).
    Fix: parse both Retry-After forms, skip bad TOML with a warning, and escape pipe, newline and @.

## Checked, no defect
- Unverified jar enabled: no. The SHA-512 is verified before the move to .rigtune-pending, and a missing hash is refused. The helper doesn't re-verify.
- Deleting or overwriting mods: no. ENABLE never overwrites, DISABLE uses a unique .disabled[.N], and the only delete (RealController:355) is the just-downloaded pending duplicate.
- pickNewest pinning: not permanent. RemoteRulesFetcher.java:54-56 overwrites the cache on every fetch, so only offline users stay on a bad high revision. If the revision reaches Integer.MAX_VALUE, the updater's +1 breaks Gson parsing.
- Threading: MC state is touched only on the render thread, results come back via minecraft.execute, and shared fields are volatile. Async errors are logged, except in OnlineDataFetcher (#6).
- Conditions: unknown RAM, VRAM and refresh give false, an empty anyOf is false, and Gson's nesting limit bounds recursion.
