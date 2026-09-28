# v0.5.0 plan review: the SPEC (prev-spec)

Independent, adversarial review of docs/v0.5/SPEC.md (commit 5df187b7, 228 ACs) against the user's v0.5 requirements
(scratchpad/spec/user-v05-requirements.md), the research inputs, PROGRESS's v0.5 decisions, and the code on
`feat/v0.5.0` @ fb86278f. Read-only: no Gradle, no Minecraft, no git changes. API claims were checked with javap
(JDK 25.0.4.1) against Loom's cached `minecraft-client-only.jar`/`minecraft-common.jar` for 26.2 and 26.3, the
fabric-client-gametest-api 6.0.2/6.0.7 jars and fabric-loader 0.19.5; released behaviour with `git show v0.4.0:<path>`
and the pinned copies under src/test (v010/, v030/).

Severity: HIGH = would ship a bug, break compatibility, miss a user must-have, or make an AC unverifiable; MEDIUM =
likely rework or a real gap; LOW = wording/clarity.

## Summary

| id | sev | one line |
|---|---|---|
| SPEC-1 | HIGH | The user's "triage any open weekly rules PR or canary issue" has no item; the weekly bot will bump main's revision while the SPEC hard-codes "r17" |
| SPEC-2 | HIGH | Phase 5's production smokes (a copy of the user's mods on 26.2, a Modrinth set on 26.3) have no AC anywhere |
| SPEC-3 | HIGH | LanGuestGameTest needs multicast, but 1a runs every game test in a loopback-only netns with no multicast route |
| SPEC-4 | HIGH | The E2E jobs (part of every streak run) run `./gradlew … e2eClient` online; AC3a.6 ("no network use besides gh release download") is false |
| SPEC-5 | HIGH | The `tickHookOnVsReference` ratio gate (1d b) has ~23 % headroom by design; RW-11's per-tick check and C09's tick line grow the timed path, and nothing re-baselines it |
| SPEC-6 | HIGH | X4.3's "lazy holder resolved before the CLIENT_STARTED handler returned" check races the worker tasks C02 and C09 submit from that handler |
| SPEC-7 | HIGH | RW-6's DH world-gen restore target as a new field in benchmark-restore.json: 0.4.0 deletes or rewrites that file, so "0.5 restores it at its next start" is false |
| SPEC-8 | MEDIUM | L5 (Preview lists a download Apply refuses) is only a disclosure, not the fix the user listed, and isn't recorded as a departure |
| SPEC-9 | MEDIUM | No AC re-reads the real instance before release to compare it with the seeded E2E predictions (a standing P0.2 duty) |
| SPEC-10 | MEDIUM | "Locally on 26.2" shrinks to one seeded run (+ helper-kill); none of the upgrade, undo or downgrade scenarios run on Windows |
| SPEC-11 | MEDIUM | The v0.4 residual harvest misses documented known limits (ws-g3 L4-L8, ws-g2, ws-g4, ws-a) and several v0.4 UNVERIFIED items; they're neither fixed nor listed with reasons |
| SPEC-12 | MEDIUM | The release AC leaves out the user's `submit` and `gallery` steps and editing the release notes; the moderation follow-up is thin |
| SPEC-13 | MEDIUM | PENDING contradicts itself (4b's rows say "<launcher> manages…", AC8.14 forbids that), and in the code a late detection is applied only at the next rescan, not "the next rebuild" |
| SPEC-14 | MEDIUM | The ModFilesPolicy table names PolyMC, but RigTune detects PolyMC as MULTIMC, and MultiMC is listed under RIGTUNE; the `.index/` row's scope is ambiguous |
| SPEC-15 | MEDIUM | Held file groups make preLaunch's WARN and the title toast ("They'll be retried when you exit") false at every launch |
| SPEC-16 | MEDIUM | 4h reports in-game changes as "changed outside the game" after any crash or kill; it's an always-on monitor (the guardrail says opt-in); the snapshot source is unclear |
| SPEC-17 | MEDIUM | `IntegratedServer.publishServer` differs between 26.2 and 26.3, and the research's call doesn't exist on either; X9 misses it |
| SPEC-18 | MEDIUM | Rules ACs can't pass as written: AC2R.1 (advice id `shaders-entry-level`), and AC2S.3/AC5.1's "rules-v1 unchanged" against one combined r17 |
| SPEC-19 | MEDIUM | C6 makes FirstApplyGameTest the first entrypoint, but ProductionSmoke runs through the first class and RigTuneClientGameTest screenshots the first title screen; new classes aren't required to skip `rigtune.smoke` |
| SPEC-20 | MEDIUM | `client/undo/Staging.java` is edited by L7, RW-3, 4d and C09 but isn't in the C7 hotspot table |
| SPEC-21 | MEDIUM | AC2A.2 ("screenshots differ … only by the focus frame" after the table becomes RowList rows) is very likely unachievable |
| SPEC-22 | LOW | The release-tier trigger "a dispatch" is ambiguous against 1g's "none skipped"; compat040 is listed as a job but runs inside the java job |
| SPEC-23 | LOW | X9: `updateNarratorStatus` exists on 26.2 (one argument); the Attributor citation is off by one line |
| SPEC-24 | LOW | 3a doesn't say why 0.1.0 → new and the real seed don't run on 26.3 (0.1.0 was never built for 26.3) |
| SPEC-25 | LOW | C20: the rounding of the effective counts h/φ in the "exact" binomial test is unspecified; AC5.7 sits at p = 0.049 |
| SPEC-26 | LOW | AC6.13's sizes (960×540 scaled) don't match X12's (1280×720 at GUI scale 2 = 640×360) |
| SPEC-27 | LOW | 2L's "Minecraft's crash-report setup waited %s s for it" credits the whole `preload` time to the Perflib setting |
| SPEC-28 | LOW | Bounds are missing: 4h shows values read from options.txt (other apps write it); `Context.stagedAtStart` has no size cap |
| SPEC-29 | LOW | 1e's SIGQUIT "to the game JVM only (`comm` = java)" also matches Gradle's and FakeModrinth's JVMs |
| SPEC-30 | LOW | RW-3's preLaunch change adds per-op checks on the render thread before init (X4); it must not open jars |
| SPEC-31 | LOW | How held ops appear in last-apply.json isn't specified (they must not get a new `ApplyResult.Status`) |
| SPEC-32 | LOW | AC1g.2's first streak is "on the P0.1 SHA", but PROGRESS moved it to the SHA where ws-ci and WS-K have merged |
| SPEC-33 | LOW | Over-claims: "a CI that never goes red for reasons outside the code"; C02's "History… can undo any Apply" |
| SPEC-34 | LOW | Precision: `ButtonRow` doesn't exist (`BarRow` is private to StutterScreen); a 0.4.0 save of a profile permanently drops PF-5's > 512 radius |

Counts: 7 HIGH, 14 MEDIUM, 13 LOW.

---

## HIGH

### SPEC-1 (HIGH): the weekly rules PR and canary triage are missing, and "r17" is hard-coded
- **Where:** absent from item 2 and 3h; the compat promise ("…land in one revision, r17"), AC2S.4, AC5.1, AC5.2 and 2S/4i all name "r17".
- **Requirement:** P0.2: "any open weekly rules PR or canary issue (triage)". Phase 7: "merge main and confirm the rules revision … run the update-rules workflow once".
- **Evidence:**
  - `.github/workflows/update-rules.yml:5` fires every Monday 03:00 UTC (next: 2026-09-28, tomorrow). It runs the live updater (`tools/update_rules.py:26`, `MODRINTH_API`) and opens a PR against **main** (`:60`).
  - `update_rules.py:1256-1270`: any content change bumps every file to `max(old) + 1`. Rules are 16 today (`rules/rules-v2.json` revision 16).
  - So the first weekly PR that merges makes main r17, and v0.5's regeneration becomes r18. Every "r17" AC then names the wrong revision.
  - An unmerged bot PR conflicts with the release merge.
  - AC3h.2 itself runs update-rules once after the release, which can open yet another PR.
  - Nothing in the SPEC or PLAN says who triages these, or when.
  - The canary: AC3f.8 only records the first scheduled run. An issue it opens (26.4-snapshot breakage) has no owner.
- **Fix:**
  - Add a P0.2 item "2T Triage". Every `bot/rules-update-*` PR and canary issue opened during v0.5 is reviewed, then merged into main (and main merged into feat/v0.5.0 before the rules workstream regenerates) or closed with a reason. PROGRESS gets one line per PR/issue with its disposition. The post-release update-rules run's PR is triaged the same way.
  - Replace the literal "r17" with "the release revision R" (r16+1 unless a weekly update lands first). The count ACs compare R with the last revision on main before it.

### SPEC-2 (HIGH): the production smokes have no AC
- **Where:** absent (no "smoke" in SPEC; PLAN only mentions the lock).
- **Requirement:** Phase 5: "production smokes with a COPY of the user's mods on 26.2 and a Modrinth set on 26.3".
- **Evidence:**
  - The tooling exists: `build.gradle:197-303` (`runProductionSmoke -PextraModsDir=… [-PuserOptions] [-PuserConfigDir] [-PsmokeBenchmark]`) and `ProductionSmoke.java`.
  - AC4j.4 runs the RC "in a dev instance with the simulated brand", not with the user's mods.
  - P0.4 especially needs a run with the real mod set: the real instance is a Modrinth App instance with 7 app-managed mods.
- **Fix:** add 3i with ACs, evidence in docs/smoke/v0.5/:
  - (a) `:26.2:runProductionSmoke` with a read-only copy of the user's mods, options.txt and config: no RigTune ERROR or stack trace, the report is built, a settings Apply followed by Undo, one benchmark (`-PsmokeBenchmark`).
  - (b) The same with `-Dminecraft.launcher.brand=theseus`: no mod-file item can be applied, the `mods/` hash is unchanged, and the repair notice matches AC4g.1.
  - (c) `:26.3:runProductionSmoke` with a listed Modrinth set (Sodium, Iris, Mod Menu, … with versions and sha1s): the same checks.

### SPEC-3 (HIGH): LAN discovery can't work in the loopback-only namespace
- **Where:** 1a ("the game-test step runs inside `sudo unshare --net` with only loopback up") vs 3d/AC3d.1 (LanGuestGameTest "every leg", 3 legs).
- **Evidence:**
  - The namespace is set up with only `ip link set lo up` (ci-robustness.md:521).
  - Vanilla's LAN discovery is multicast: `LanServerPinger` sends to `224.0.2.60:4445`, and `LanServerDetection` does `MulticastSocket(4445).joinGroup` (vg §2.1).
  - In a fresh netns, `lo` has no MULTICAST flag and there's no route for 224.0.0.0/4. Sending then fails with "Network is unreachable" and the join with "No such device", so the LAN list stays empty.
  - vg's multicast proof (run 36288792339) ran on the runner's normal network (it received from `10.1.0.15`), not in the namespace. Nobody has run LAN discovery inside it.
- **Fix:**
  - Add `ip link set lo multicast on; ip route add 224.0.0.0/4 dev lo` to the namespace set-up.
  - Add a check to AC1a.2's proof run that a `224.0.2.60:4445` round trip works inside it.
  - Say that the detected address is `127.0.0.1:<port>`.
  - If it can't be made to work, LanGuestGameTest runs outside the namespace, and that is recorded.

### SPEC-4 (HIGH): the E2E jobs still use the live network, and AC3a.6 claims they don't
- **Where:** 1a (prefetch + `--offline` + unshare, for build.yml only), 1g (the streak requires "the E2E push jobs"), 3a/AC3a.6.
- **Evidence:**
  - The harness launches the game through Gradle online: `tools/e2e/self_update_e2e.py:167-170` runs `gradlew … :<mc>:e2eClient` with no `--offline` and no prefetch.
  - The game JVM is sandboxed (`e2e_env.py:29-73`: a jdk.net.hosts.file plus a truststore), but Gradle and Loom (libraries, assets, plugin resolution) are not.
  - Those are the outage classes behind 9 of the 14 red runs (ci §1).
  - AC3a.6 ("no network use besides the pinned, sha256-checked `gh release download`") is therefore false as written, and every streak run depends on Maven/Mojang availability through the E2E push jobs.
- **Fix:**
  - e2e.yml gets the same retrying prefetch step and runs every later Gradle `--offline` (pass it through the harness's Gradle arguments).
  - Extend AC1a.1's Python test to e2e.yml.
  - Reword AC3a.6: "after the prefetch, no network use besides the sha256-checked download".

### SPEC-5 (HIGH): the ratio gate can't absorb the tick work the SPEC itself adds
- **Where:** 1d(b), AC1d.2/AC1d.3 vs X4.4 ("New tick work (C09's `TryItService.tick`, RW-11's settings check) … stays inside the tick budgets (1d)"), 2S RW-11, C7 ("C09 (tick line…)").
- **Evidence:**
  - The gate is median(hook / fixed reference) ≤ 1.95. The 1× ratio is already 1.20-1.577 across CPU models, which leaves about 23 % margin (ci §5.3: "1x max 1.577 × 1.24 = 1.95").
  - The timed path is `RigTuneClient.onTick + StutterHooks.tick` with the monitor on (`tools/footprint-budgets.json` `tickHookNsPerCallOn`.what).
  - RW-11 adds a per-tick check of four settings (render and simulation distance, the shader state and pack, DH rendering) to exactly that path. C09 adds a tick line to `onTick`.
  - A +25-50 % legitimate cost pushes the slow CPU models over 1.95. The gate then reports a "2× regression" on some or all legs, which breaks the 5-run streak on the RC.
  - Nothing in the SPEC re-derives the reference or the 1× distribution after the timed path changes.
- **Fix:**
  - (1) Make RW-11 event-driven (Options setters/`Options.save`, the resource-reload listener, the Iris/DH state read only on those events), so the monitor-on tick path doesn't grow. C09's tick line early-returns on one static volatile flag.
  - (2) Add an AC: whenever the timed path changes, re-measure the 1× and 2× distributions (the ci §5.3 method, ≥ 3 CPU models) and re-size the reference so the 1× median is back near 1.2. Record it in the budgets file's `about` with the SHA, before the RC streak.

### SPEC-6 (HIGH): X4.3's footprint check is racy by construction
- **Where:** X4.3 ("records whether the lazy holder was resolved … before the CLIENT_STARTED handler returned; FootprintGameTest fails the leg if it was (a deterministic check)"), AC-X.2.
- **Evidence:**
  - X4.1 says the holder is resolved "on first use (… a worker task …)".
  - C02: `FirstRunService.load()` "runs once on `Probes.EXECUTOR` (submitted from CLIENT_STARTED)". C09: "a CLIENT_STARTED submit" (C7 table) and "the first derive is submitted to `Probes.EXECUTOR`" (AC6.12).
  - A task submitted inside the handler can run on a worker and resolve the holder while `RealController.start` is still running (`clientStartedWallMs` ≈ 100 ms). The flag then flips, depending on worker scheduling.
  - The result is a flaky red gate on every leg: exactly what P0.1 exists to remove.
- **Fix:**
  - Record the resolving thread instead. The gate fails only if the render thread resolved the holder before `initEnd` or inside the CLIENT_STARTED handler.
  - A worker resolution is allowed, and its CPU is already counted in `workerCpuMs5s`.

### SPEC-7 (HIGH): the new DH world-gen restore target is lost on a downgrade
- **Where:** state-file table row `benchmark-restore.json` ("+ a DH world-generation restore target only if RW-6's pause ships … 0.5 restores it at its next start"), 2B RW-6, AC2B.5.
- **Evidence:**
  - `git show v0.4.0:…/core/benchmark/RestoreMarker.java`: the marker is a Gson record `(dhRenderingEnabled, irisShadersEnabled, createdAt)`.
  - `restorePending` (:67-82) restores those two and then **deletes the file** (:76) once both are done, or rewrites it with only the known fields (:79-82). An unknown field is dropped either way.
  - Scenario: a crash during a 0.5 benchmark, then a downgrade before 0.5 starts again. 0.4.0 restores DH rendering and Iris and deletes the marker, so DH world generation stays off in DH's config for good.
  - The specified test ("ignores the unknown target without failing the other restores") would pass while the bug ships.
  - This also breaks the guardrail "new data in new files".
- **Fix:**
  - Keep the world-gen restore value in a new file (e.g. `benchmark-restore-dhgen.json`) that 0.4.0 never reads, written before the change and removed after the restore.
  - AC: 0.4.0's `restorePending` run on a directory holding both files leaves the new file byte-identical, and 0.5 then restores the value.

## MEDIUM

### SPEC-8 (MEDIUM): L5 stays unfixed without being recorded as a departure
- **Where:** 2H L5 ("decision: tested disclosure"), item table ("L5 as a tested disclosure").
- **Requirement:** P0.2 lists "Preview listing a download that Apply then refuses on a version range" as an issue to **fix**.
- **Evidence:**
  - lo marks L5 "NEEDS A DECISION".
  - The SPEC keeps the generic note (`en_us.json:278`, `PreviewScreen.java:251`), so Preview still lists a download that Apply refuses.
  - Open questions says the SPEC names its departures; this one isn't there.
- **Fix:** either
  - (a) a per-row marker: when any installed mod's fabric.mod.json declares a range on the item's mod id (known from the scan, no download), Preview says "may be refused when it arrives: <mod> needs <range>"; or
  - (b) record L5 as a departure from the user's list in Open questions with the reason, for the coordinator to confirm.

### SPEC-9 (MEDIUM): no pre-release re-check of the real instance
- **Where:** absent. rw covers only the 2026-09-27 session, and PROGRESS:25 says "re-check before Phase 5".
- **Requirement:** "anything the real-instance feedback shows … compare what really happened with the seeded E2E predictions and treat every divergence as a bug".
- **Fix:** add an AC. At Phase 5 and again before tagging, take a read-only copy of the real instance (latest.log, helper.log, history.json, pending.json, last-apply.json, stutter.json, benchmarks.json). Compare it with the `v010-dh`/`v010-dh-app-reinstalled` predictions and 4g's repair lists. Each divergence gets an RW-n id, a test, and a fix or a "Not fixed" reason.

### SPEC-10 (MEDIUM): the local 26.2 E2E is reduced to one scenario
- **Where:** 3a "Local Windows, once on the RC: seeded 0.1.0 → RC"; 3f helper-kill.
- **Requirement:** "run the self-update E2E (…) on BOTH 26.2 and 26.3, ideally in CI on Linux and locally on 26.2".
- **Evidence:**
  - Windows is the user's platform and where the real incident happened.
  - Only the seeded run and helper-kill run on Windows. 0.4.0 → RC, 0.1.0 → RC (legacy import), undo-after-restart and the downgrade RC → 0.4.0 never run there.
  - vg §1.7 measures about 2 min per run under one lock hold.
- **Fix:** on the RC, also run locally on Windows 26.2: 0.4.0 → RC, 0.1.0 → RC `--legacy-disable`, undo-after-restart `--profile-switch profile`, and downgrade RC → 0.4.0. Or record the reduction as a departure.

### SPEC-11 (MEDIUM): the v0.4 residual harvest is incomplete
- **Where:** item 2's sources (lo), "Not fixed / deferred", "Stays UNVERIFIED".
- **Requirement:** "every 'known limit'/'left as is' low in the v0.4 design docs judged worth fixing (list the rest with reasons)".
- **Evidence:** documented limits that appear in neither lo nor the SPEC:
  - `docs/v0.4/design/ws-g3.md:129-150`, "Known limits":
    - each retry pass redoes and rolls back earlier renames (L4);
    - a record matches by name, not file identity (L5);
    - a group stuck half-applied repeats "try 3 of 3" at every exit (L6);
    - a death between last-apply and pending shows done ops as staged (L7);
    - a rollback whose moved file vanished still says "the original name is taken" (L8);
    - the declined M2 (Undo/Discard of a half-done update).
  - `ws-g2.md:108-116`:
    - a refused "Disable X" isn't counted in the Apply status and Preview doesn't show it: another Preview/Apply divergence;
    - `waitingFile`;
    - `dropQueuedUpdates` unstaging a half-done group.
  - `ws-g4.md:88-99`: the "Imported from 0.1" mislabel on 0.2+ instances.
  - `ws-a.md:126-129`: a staged version's declarations over-blocking; the untested `checkUpdate` branch.
  - `ws-g1.md:81-83`.
  - lo's "Owned elsewhere" UNVERIFIED items that vg §6 also skipped:
    - `add_mc_version.py`'s write path;
    - `mc_apidiff.py` off Windows;
    - Stonecutter's `//? if >=26.4-snapshot-1`;
    - spark commands in game;
    - the `problem.yml` prefill;
    - Java 26/27 and non-generational-Shenandoah measurements;
    - P5B-F1 (local Windows render-thread CPU quantisation).
- **Fix:** extend "Not fixed / deferred" and "Stays UNVERIFIED" with each item and a one-line reason. Pick the cheap honest fixes (L6's repeated toast, L8's wrong message, ws-g2's Preview skip reason) into 2H with tests.

### SPEC-12 (MEDIUM): release steps missing
- **Where:** AC3h.1 ("`sync-body` … and `status` ran").
- **Requirement:** "run `submit`, `sync-body` (docs/modrinth/body-0.5.md) and `gallery` … edit the release notes … follow up on Modrinth moderation".
- **Evidence:**
  - `tools/modrinth_project.py` has `cmd_submit` (:387) and `cmd_gallery` (:288); neither is in any AC.
  - Nothing requires editing the GitHub release notes.
  - PROGRESS:22 says moderator messages can't be read with the token.
- **Fix:** AC3h.1 adds:
  - `gallery` with docs/modrinth/gallery.md updated for the v0.5 screens (screenshots from the CI artifacts);
  - `submit` if the status isn't approved;
  - the GitHub release notes edited from CHANGELOG;
  - "ask the user to forward any moderator message; act on it and record it in PROGRESS".

### SPEC-13 (MEDIUM): PENDING is self-contradictory, and "the next rebuild" doesn't happen in the code
- **Where:** 4a table ("PENDING … the next rebuild lifts it"), AC4a.3, 4b first bullet ("under LAUNCHER or PENDING every AddMod … gets '<launcher> manages this instance's mods…'" plus the steps line), 4b `guideLine` (null for PENDING), C02 "PENDING never claims anything about mod files", AC8.14.
- **Evidence:**
  - Under PENDING no launcher is known, yet 4b's row text claims one manages the mods. AC8.14 forbids exactly that.
  - In code, the report build waits up to 3 s for the detection (`RealController.java:265-272`).
  - A detection that finishes later "is still used by the next rescan" (`LauncherProbe.java` header comment), and a rescan only comes from Re-scan, a power edge or the network toggle. So on a slow disk an official-launcher or MultiMC player stays in advice mode for the whole session.
  - Nothing triggers "the next rebuild".
- **Fix:**
  - PENDING rows get a neutral note ("Checking which launcher manages this instance's mods; mod changes wait until then") and no steps line.
  - When a timed-out detection completes, `RealController` calls `launcherDetected` + `rebuild()` once.
  - AC4a.3 asserts that call.
  - Game tests that apply mod changes wait for `modFiles() != PENDING`.

### SPEC-14 (MEDIUM): the policy table's launcher names don't match detection
- **Where:** 4a table rows "`<mods>/.index/` … (any launcher: Prism, PolyMC, Unknown)" and "official launcher, MultiMC, Prism/PolyMC/Unknown without `.index/` → RIGTUNE"; AC4a.1 "8 launchers".
- **Evidence:**
  - `LauncherDetector.java:37-38`: `INST_ID`/`INST_NAME` → MULTIMC, which is how PolyMC is detected (lm:147).
  - A reader of the table can build a MULTIMC + `.index/` → RIGTUNE cell, which breaks a PolyMC packwiz instance. packwiz-installer is also commonly used with MultiMC itself.
- **Fix:**
  - The `.index/` row reads "any detected launcher, including MULTIMC (which is how PolyMC is detected) and OFFICIAL".
  - AC4a.1 lists the expected cell for every `Launcher` enum value × `.index/`.

### SPEC-15 (MEDIUM): held groups make the leftover messages false
- **Where:** 4d (held at exit "without counting an attempt"); RW-3 only filters stale groups.
- **Evidence:**
  - `RigTunePreLaunch.java:120-123` logs "N staged RigTune change(s) were not applied; they will be retried at the next exit".
  - `RigTuneClient.java:242-246` shows the title toast `rigtune.toast.leftover.body` = "They'll be retried when you exit…".
  - Under hold, both fire at every launch and are false: this is RW-3's complaint again, by design.
  - The toast is raised at the first title screen, possibly before the detection answers.
- **Fix:**
  - Count held groups apart from the leftovers. Under LAUNCHER/PENDING, drop them from the WARN and the toast; HELD_MOD_CHANGES is their message.
  - Delay the leftover toast until the policy is known.
  - AC: a seeded held group gives no leftover toast or "will be retried" WARN under LAUNCHER, and the same toast as today under RIGTUNE.

### SPEC-16 (MEDIUM): 4h false positives, the opt-in guardrail, and the snapshot source
- **Where:** 4h, AC4h.1 ("a change the player made in game → nothing (the snapshot is taken at exit)").
- **Evidence:**
  - The snapshot is written only at CLIENT_STOPPING. After a crash, a kill from the launcher, or a power loss, the previous snapshot is stale.
  - Vanilla saves options.txt when the player leaves the Options screen, so the player's own in-game changes then show up as "N settings were changed outside the game": a false claim in the most common modded failure case.
  - "Current values" are the in-memory Options. During a benchmark or a Try It run they're temporary values.
  - It runs in every instance at every start and stop. The guardrail says "new monitors … are opt-in".
- **Fix:**
  - Take the snapshot from what vanilla writes: hook `Options.save` (off-thread `StateStore.update`), or read options.txt at stop.
  - Mark the session open at start and skip the comparison when the previous session didn't close cleanly.
  - Skip the snapshot while a benchmark or Try It run is active.
  - Scope it to Modrinth App instances (the only sync known), or get the coordinator's explicit exemption from the opt-in guardrail.
  - AC4h.1 adds the crash case.

### SPEC-17 (MEDIUM): `publishServer` differs between the versions
- **Where:** 3d ("the host side: `publishServer` on a singleplayer world"); X9 "No item needs a new `//? if` block".
- **Evidence (javap):**
  - 26.2: `publishServer(MinecraftServer$MultiplayerScope, GameType, boolean, int)` and `publishServer(MultiplayerScope, int)`.
  - 26.3: `publishServer(MultiplayerScope, boolean, int)` and `publishServer(MultiplayerScope, int)`.
  - vg §2.2 step 7's `publishServer(GameType.SURVIVAL, false, port)` exists on neither.
- **Fix:** specify `publishServer(MultiplayerScope.LAN, port)`, the only overload both have, and add the difference to X9's list.

### SPEC-18 (MEDIUM): three rules ACs can't pass as written
- **Where:** AC2R.1, AC2S.3, AC5.1.
- **Evidence:**
  - AC2R.1 requires "no rules value contains 'entry-level'". knowledge.json has 7 such `reason`s **and** the advice id `shaders-entry-level` (`advice[15].id`). The same AC forbids changing anything but `reason`.
  - AC2S.3 and AC5.1 require rules-v1.json unchanged apart from `revision`/`generatedAt`. But L2, L4, C20 and 4i land in one regeneration, L4 rewrites 4 v1 reasons (`grep -c entry-level rules/rules-v1.json` = 4), and v1 carries live `availability`/`upstream` data that any regeneration refreshes.
- **Fix:**
  - AC2R.1: "no `reason`/`text`/`title` value contains 'entry-level'; ids unchanged".
  - AC2S.3/AC5.1: "regenerating with and without the L2 seed (C20's section) gives an identical rules-v1.json".

### SPEC-19 (MEDIUM): the entrypoint order breaks the production smoke
- **Where:** C6 ("**FirstApplyGameTest** (first: a fresh run dir)"), AC8.16.
- **Evidence:**
  - The production smoke runs through the first entrypoint's class: `RigTuneClientGameTest.runTest` dispatches `ProductionSmoke.run` when `rigtune.smoke` is set (:57-60).
  - All 17 existing classes return early under `rigtune.smoke` (grep); nothing requires the 8 new ones to.
  - With FirstApplyGameTest first, a production smoke applies and undoes changes on the smoke instance before the smoke runs.
  - RigTuneClientGameTest also screenshots the first title screen's toasts (`title-toasts`, :61-64), which would now come after FirstApplyGameTest.
- **Fix:**
  - C6 requires every new class to return under `rigtune.smoke`.
  - Either keep RigTuneClientGameTest first and give C02 a test seam (a FirstRunService reset over a config dir with the three files moved aside), or accept FirstApply first and move the title-toast screenshot into it.

### SPEC-20 (MEDIUM): a missing hotspot
- **Where:** C7.
- **Evidence:** `client/undo/Staging.java` is where `discard()` (L7), `dropQueuedUpdates` and the new stale-group unstaging (RW-3), the unstage-by-op-id used by "Cancel them" (4d), and C09's "Cancel try" / C20's Discard all live. It's absent from the table, so three workstreams edit it without an owner.
- **Fix:** add it to C7 with a serialisation order (RW-3 → L7 → 4d).

### SPEC-21 (MEDIUM): AC2A.2 is likely unachievable
- **Where:** AC2A.2 ("screenshots differ from the pre-change CI screenshots only by the focus frame").
- **Evidence:** turning a painted table into `RowList` rows brings RowList's row height, padding, scroll area and selection rendering. Pixel equality everywhere but the focus frame would force the new rows onto the old paint coordinates.
- **Fix:** "no content lost: every value on the old screenshot appears on the new one (owner-checked side by side), X12's layout check passes, and with high contrast Palette maps every colour".

## LOW

### SPEC-22 (LOW): trigger wording
- 3a's release tier runs on "a pull_request into main from feat/v*, **a dispatch**, and release.yml". If "a dispatch" meant build.yml's, every streak dispatch would run about 23 E2E jobs, and push runs would count as "skipped" under 1g.
- vg §1.7's YAML means e2e.yml's own dispatch. Say so.
- 1g lists "compat040" as a job, but 3b runs it inside the java job.

### SPEC-23 (LOW): 26.2/26.3 details
- X9: "`Screen.scheduleNarration()` and `updateNarratorStatus(…)` exist only on 26.3". In fact 26.2 has `updateNarratorStatus(boolean)` and 26.3 has `(boolean, NarrationTrigger)` (javap). The advice (use `triggerImmediateNarration(false)`) is right.
- The compat promise cites `Attributor.java:38-39` at v0.4.0. It's :37-38 (`CHUNKS_LOADING` :37, `TAGS` :38).

### SPEC-24 (LOW): the missing 26.3 legs
- 3a should state that 0.1.0 → new and the real seed are 26.2-only because 0.1.0 has no 26.3 build (vg §1.4) and no real 26.3 state exists (vg §1.5). That's a departure from "on BOTH 26.2 and 26.3" the user should see.

### SPEC-25 (LOW): the C20 test is ambiguous with dispersion
- "effective counts h/φ; a one-sided exact binomial test" doesn't say how non-integer counts enter the CDF: floor, round, or the regularised incomplete beta.
- AC5.7's golden cases with φ > 1 (C4, φ = 14) and the 10 → 20 case at p 0.049 depend on it. Specify it.

### SPEC-26 (LOW): screen sizes
- AC6.13 uses "320×240, 427×240 and 960×540 scaled". X12's 1280×720 at GUI scale 2 is 640×360 scaled. Align them (or add 960×540 to X12 on purpose).

### SPEC-27 (LOW): 2L's wording over-credits the counters
- `CrashReport.preload` also initialises OSHI and SystemReport. The replica went 6.3 s → 0.8 s, so about 0.8 s isn't the counters.
- "waited %s s for it" credits all of it to the Perflib setting. Say "Minecraft's crash-report setup took %s s at this launch (about 1 s is usual)".

### SPEC-28 (LOW): unbounded or unsanitised input
- 4h's notice shows "was → now" values read from options.txt, which other apps (the Modrinth App) write. Pass them through `SettingKeys.safeValue` with a length cap before display or journaling.
- `Context.stagedAtStart` (BH-2) is an unbounded id list in a capped file. Cap it (e.g. 64; over the cap, omit the field and use the fallback rule).

### SPEC-29 (LOW): the thread-dump target
- `/proc/<pid>/comm` = `java` matches Gradle's JVM and FakeModrinth's `java FakeModrinth.java` too. SIGQUIT is harmless to them, but "the game JVM only" is false. Match `KnotClient` in `/proc/<pid>/cmdline`.

### SPEC-30 (LOW): preLaunch budget
- RW-3's "preLaunch leaves such ops out of its leftover count" adds per-op checks before init, and `renderThreadInitCpuMs` is at 131.1 of 150.
- Say it uses only `Files.exists` plus FabricLoader's loaded-mod origins and never opens a jar. Otherwise the count moves to the title-screen toast (it's consumed there anyway, `RigTuneClient.java:242`).

### SPEC-31 (LOW): how held ops are recorded
- The table says last-apply.json's format is unchanged. State that held ops are left out of last-apply.json (or recorded with an existing status that counts neither as failed nor as dropped).
- A new `ApplyResult.Status` constant would reach 0.4.0 as null after a downgrade.

### SPEC-32 (LOW): the first streak's SHA
- AC1g.2 says "5 on the P0.1 SHA". PROGRESS:32 moved the first streak to "the SHA where ws-ci AND WS-K have merged". Fold that into Amendments.

### SPEC-33 (LOW): over-claims
- Goal: "a CI that never goes red for reasons outside the code". GitHub Actions, apt on a cache miss, and `gh release download` stay external. Say "no longer depends on Modrinth, Maven or Mojang during tests".
- C02's "History… can undo any Apply" is too strong (Undo can refuse, and a launcher-managed file change is skipped, 4c). Say "lets you undo each Apply".

### SPEC-34 (LOW): precision
- 5 "UI": `ButtonRow` doesn't exist anywhere in src (it's new), and `BarRow` is a private inner class of `StutterScreen.java:467`. Say "new `ButtonRow`".
- Compat table, profiles.json row: "0.4.0 drops a radius above 512 on read". Its save of that profile also removes it for good (`git show v0.4.0:…/ProfileStore.java:410-422` replaces managed keys with the in-memory set). Say so.

---

## Checked (claims verified against code, jars or docs)

Requirements coverage (the user's list vs SPEC):
- Covered:
  - P0.1: fake Modrinth, pins/caches, ns gates, the three named flakes, 5-run streak.
  - P0.2: R10-1, L2 fails closed via v0.4.0's `shares()` UNKNOWN on unmeasured, the BenchmarkResultScreen rebuild, the tier texts, StartupNotices, Discard.
  - P0.3: both nodes, the downgrade to 0.4.0, LAN, simulated battery, driver strings, byte-identical publish.
  - P0.4; P1's 5 picks with cut order; P2's controller polish and a11y sweep; the guardrails (no new op type, the `requires`/section rules, share-code key rule, helper classpath).
- Weakened or missing: SPEC-1, 2, 8, 9, 10, 11, 12.

Code and doc citations (✓ = as the SPEC says; ✗ = finding):
1. X4.1 `RigTuneClient.java:73` (RealController built in onInitializeClient), `RealController.java:170-181` (services and NoticeCenter in the constructor) ✓
2. C3 `NoticePriority.java:3` declaration order ✓; priority used in memory only (`NoticeBoard.java:49`, no ordinal persisted) ✓
3. `NoticeCenter.java:77-84`: a throwing source is logged and skipped ✓
4. OQ1 `ServerLimitNoticeSource.java:43-47,66-67`: a notice on every non-singleplayer connection ✓
5. C8 `ProfileService.java:377-388` `refusal()` (benchmark, downloading, not_ready) ✓
6. 1b/1f `HttpModrinthClient.java:45` (property), `:71-77` (no validation today) ✓; `RulesSources.java:41-56` (https or http-loopback) ✓
7. 1b FakeModrinth has `version_files`, `version_files/update`, `projects`, `project/{id}[/version]` and `--bind`; `/v2/versions?ids=` absent (to add) ✓
8. AC1b.3 `PreviewGameTest.java:206` 6000 ticks ✓ (`ProductionSmoke.java:238` has its own 6000; local only)
9. AC1d.1 budgets file: ceilings 20/200/400/2000 consistent with the (a) limits 20/157/400/276/393/653 ✓
10. AC1e.1 `SettingsSaver.flush(long)`, `save(ClientSettings, Path)` ✓; `Probes` 2 EXECUTOR + 2 NETWORK threads ✓
11. 1e `ThreadSamplerTest.java:343` (`stopMs < 100`) ✓
12. 1g build.yml: push, pull_request and workflow_dispatch; concurrency cancel-in-progress; `ubuntu-latest` at build.yml:22,168,180,195, release.yml:13, snapshot-canary.yml:46, update-rules.yml:18 (AC1c.3 must cover all) ✓
13. AC1c.2 Loom 1.17.21 present in the Gradle cache ✓
14. AC2S.1 `StutterService.java:193-194`, `StutterHooks.java:129-135` ✓
15. AC2S.2 v0.4.0 `Attributor.TAGS` includes `chunksLoading` ✓ (line cite off by one, SPEC-23); v0.4.0 `ConditionEvaluator.shares` checks the vocabulary and unmeasured → UNKNOWN ✓; `update_rules.py:84` lacks the tag ✓
16. AC2S.7 `StutterService.java:196` ✓; AC2S.8 `:328-331` ✓
17. AC2S.12 `StutterAnalyzer.java:94`, `StutterScreen.java:262-267`, `StutterSummary.java:57` ✓
18. AC2P.1 `ProfileService.java:334,367-368`, `BatteryPrompt.java:38` ✓
19. AC2P.3 `ProfileService.java:284-288,485-486` ("?") ✓
20. AC2P.4 `ShareCode.java:77-80` ✓; `refreshRateCap(60)=60`, `(144)=140`, INT10 wire 5 = 60 ✓
21. AC2P.5 key 22 range 32..512 (`ShareKeys.java:137`) ✓; v0.4.0 `ProfileStore.settings` drops out-of-range values ✓ (and its save drops them, SPEC-34)
22. AC2P.6 `ShareCode.java:174-176` (OUT_OF_RANGE rejects the whole code) ✓
23. AC2W.1 `AwarenessService.java:86-97` ✓; AC2W.3 `HardwareProbe.java:93-109`, `ChangeDetector.java:42-45,126-128` ✓
24. AC2B.3 `RenderDistancePlanner.java:63,122-130`, `RenderDistancePlannerTest.java:173-192` ✓; AC2B.9 `BenchmarkController.java:502,520` ✓
25. AC2H.1 `DryRunPlanner.java:37-41` (never downloads) ✓; `rigtune.preview.note.downloads` exists ✓
26. AC2H.2 `RealController.java:666-683` (plain "Discarded N") ✓
27. AC2H.7 `LegacyImport.java:127-133`, `HistoryModel.java:191` ✓
28. AC2R.1 ✗ (SPEC-18); AC2R.2 `RigTuneClient.java:192`, `StartupNotices.java:13-22` ✓; AC2R.3 v0.4 SPEC:288 and DESIGN.md Non-goals ✓; DESIGN.md:243 budget sentence ✓
29. AC2D.1 `DriverVersionParser.java:23` ✓; AC3g.3 the Intel "- Build" pattern exists (:26) ✓
30. 2L JNA platform 5.17.0 and OSHI 6.9.0 ship with 26.2 and 26.3 (mojang_minecraft_info.json) ✓; `CrashReport.preload()` public static on both ✓
31. 3a `RELEASED` (self_update_e2e.py:49-53) lacks 0.4.0 and 26.3 jars (the harness commits add them) ✓; `compat030.py:86-87` ✓; the harness runs Gradle online ✗ (SPEC-4)
32. 3d javap on both versions: `LanServerPinger(String,String)` ✓, `RealmsConnect(Screen)` + `connect(RealmsServer, ServerAddress)` ✓, `RealmsServer()` + `name` ✓, `JoinMultiplayerScreen(Screen)` ✓, `NetworkServerEntry.join()` ✓, `ServerData.isLan/isRealm` ✓, gametest API `createServer(Properties)` in 6.0.2 and 6.0.7 ✓, `publishServer` ✗ (SPEC-17)
33. 3e `ToastManager.getToast(Class,Object)` ✓ and `SystemToastId(long)` ✓ on both
34. 3f `Options.highContrast()` on both ✓
35. X9 `InputConstants` MOUSE_BUTTON_LEFT 0/1, KEY_TAB 258/43, KEY_RETURN 257/40, KEY_ESCAPE 256/41 ✓; `GuiGraphicsExtractor.fill(int×5)` on both, pipeline package moved ✓; `ScreenNarrationCollector.update(…, NarrationTrigger)` 26.3 only ✓; `UiGameTest.java:479-483` ✓; `updateNarratorStatus` ✗ (SPEC-23)
36. 4a `LauncherProbe.java:56-58` (timeout → UNKNOWN; later detection used at the next rescan) ✓; `LauncherDetector.java:37-38` (INST_* → MULTIMC) ✗ table (SPEC-14); `Launcher` enum has 8 values ✓
37. 4b `RealController.java:340-350` (ModrinthOffAdvice in rebuild) ✓; `ServerCap.apply` at `RealController.java:335` ✓
38. 4c `UndoPlanner.java:817` (`file + DISABLED_SUFFIX` fallback) ✓
39. 4d `HelperLauncher` copies jars into config/rigtune/helper/, classpath ApplyHelper + Gson ✓; `PendingActions.Type` 5 constants ✓; `RigTunePreLaunch.java:120-123` and `RigTuneClient.java:242-246` ✗ under hold (SPEC-15)
40. 4e `RigTuneScreen.java:308-312` (single offline/Modrinth-off line) ✓
41. 4f `ApplyResult.java:33-35` (`failedOps` counts FAILED + ABANDONED) ✓
42. 4i: pinned `v030 ConditionEvaluator.modVersion` uses Fabric `VersionPredicate`; Fabric 0.19.5 `SemanticVersionImpl.DOT_SEPARATED_ID` = `|[-0-9A-Za-z]+(\.[-0-9A-Za-z]+)*` allows the empty pre-release, so `<0.5.0-` parses and is FALSE on 0.5.0-dev ✓; v010 `Condition.always` and advice kind `warning` known ✓; `RulesLoader.MAX_RULES_BYTES` 2 MiB in 0.1.0 and now, rules-v2 83 KB ✓
43. Compat: v0.4.0 `RestoreMarker.restorePending` deletes or rewrites the marker ✗ (SPEC-7); v0.4.0 `StutterScreen.java:269-271` known tags only ✓, `StutterSummary.name` falls back to the key ✓, `StutterReport` null-safe tags ✓; v0.4.0 `AwarenessStore.MAX_DISMISSED` 256 ✓; `JsonStateFile` rules match X7 ✓
44. C16 `ServerLimitsTracker.kind/address` are static package-private (a visibility-only change) ✓; `ServerLimitsStore.key` package-private static ✓
45. C09 `BenchmarkRequest(mode, scene, pairId)` ✓, `BenchmarkHistory.openBefore(String,String)` ✓, `BenchmarkController.unavailable(Minecraft, Scene)` ✓, `BenchmarkMath.gainPercent` ✓, `UndoScreen(parent, controller, entryId)` ✓, `PreviewScreen.Confirm` ✓, `SettingKeys.VANILLA_ALLOWED`/`changeable` ✓, `ShareKeys.MANAGED` ✓, `EffectiveSettings` ✓
46. C02 `HistoryScreen.describe` (static, package-private, :351), `failureText` (public static, :367), `changeRowText()` ✓
47. C18 `BenchmarkTrend.median/mad/noiseFloorPercent(null, …)` gives ≥ 10 % ✓; `StartupTimesStore.MEDIAN_OF = 10` ✓
48. C20 `BarRow` is private in StutterScreen; `ButtonRow` absent (SPEC-34)
49. C6 all 17 existing game-test classes honour `rigtune.smoke`; the RigTuneClientGameTest smoke dispatch and title-toasts screenshot (SPEC-19); the current fabric.mod.json order (16 entries) ✓
50. update-rules.yml weekly PR on main + `update_rules.py:1256-1270` revision bump (SPEC-1); `runProductionSmoke` (build.gradle:197-303) (SPEC-2); `modrinth_project.py` `submit`/`gallery` (SPEC-12)
51. The existing tests the SPEC names are present: HelperLauncherTest, RulesV1DifferentialTest, SchemaConsistencyTest, FrameHookBudgetTest, HelperCompat030Test, ApplyGroupsTest, StartupTimesFixtureTest, NoticeBoardTest, PaletteTest, PseudoLocaleTest, FootprintBudgetsTest, StutterAdvisorTest, GcKindTest, ChangeWindowTest, TrendTextTest, V010Fixtures, FakeModrinthTest, HttpModrinthClientTest ✓; StutterServiceTest is new (AC2S.1 needs a copy seam, since `end()` takes a `Minecraft`)

ACs checked for testability (beyond the citations above): AC-X.1, AC-X.2 ✗, AC1a.1, AC1a.2, AC1b.1-3, AC1c.1-3, AC1d.1-4 (AC1d.2 ✗ via SPEC-5), AC1e.1-4, AC1f.1-2, AC1g.1-2, AC2S.1-4, AC2S.12, AC2P.1-6, AC2W.1-3, AC2A.2 ✗, AC2H.1-2, AC2H.7, AC2R.1 ✗, AC2D.1-2 (38 = 29 + 4 + 5 ✓), AC2L.1-5, AC3a.2, AC3a.6 ✗, AC3d.1 ✗, AC3e.1-3, AC3g.3, AC3g.5, AC3h.1 ✗, AC4a.1 ✗, AC4a.3 ✗, AC4b.5, AC4d.1, AC4h.1 ✗, AC4i.1, AC5.1 ✗, AC5.7, AC6.13 ✗, AC7.13, AC8.14 ✗ (vs 4b), AC8.16 ✗, AC9.1.
