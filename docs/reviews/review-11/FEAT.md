# r11-FEAT: profiles (WS-P), C16 (WS-P2), C02 (WS-F), awareness (WS-W), C18 (WS-W2), NoticePriority interplay

Reviewer area FEAT. Worktree C:/Dev/Worktrees/rigtune-review11 @ ce8b1a8b, range 987179e4..ce8b1a8b. Read-only; no
gradle, no game. Lens: player-facing correctness, X3 honest wording, privacy, a11y, X8 I/O rules, false "new player" /
false regression for returning 0.4 players. Paths below are relative to `src/`.

Counts: 0 HIGH, 1 MEDIUM, 5 LOW.

| id | sev | file:line | scenario (inputs/state -> wrong outcome) | fix |
|---|---|---|---|---|
| FEAT-1 | MEDIUM | client/java/io/github/chaotix345/rigtune/client/launcher/ModFilesService.java:79-84; client/java/io/github/chaotix345/rigtune/client/FirstRunService.java:56-58 (called last from V05Hooks.java:63) | A brand-new player (no history, no last-apply/pending) in a Modrinth App instance (or any LAUNCHER instance, e.g. Prism + packwiz). RigTune shows the FIRST_RUN guide, whose detail already carries "Modrinth App manages this instance's mods: RigTune changes settings only." The player presses Apply (or takes a battery / server-profile switch). `FirstRunService.applied()` sets the in-memory status to RETURNING, and `ModFilesService.news(status)` only checks `status == RETURNING && policy == LAUNCHER`. Back on RigTuneScreen (after FirstApplyScreen's Done) the notice line now shows MOD_FILES_NEWS, "RigTune now leaves this instance's mod files to the Modrinth App": a "what changed since 0.4" notice for a player who never used an older RigTune (X3: "now" claims a change they never saw; SPEC 4b: "new players learn it from the guide"; AC4b.6: "never for a NEW player"). At every later launch FirstRun reads RETURNING from disk, so it keeps coming back until the player dismisses it with the ×. The unit test (`ModFilesNewsTest`) and `LauncherManagedGameTest.modFilesNews` only force NEW or RETURNING; the NEW -> RETURNING transition is never tested. | Key the news on "returning at load", not the live status. For example, FirstRunService keeps the status `load()` answered (`loadedStatus()`), `ModFilesService.news` requires `loadedStatus() == RETURNING`, and `applied()`, when it moves NEW -> RETURNING, stores `ModFilesService.NEWS_KEY` in awareness.json's `dismissed` (`AwarenessStore.dismiss`, on Probes.EXECUTOR), so later launches don't show it either. Add a unit test: NEW, applied(), then news() == null, and after a new FirstRunService over the same files news() is still null. |
| FEAT-2 | LOW | client/java/io/github/chaotix345/rigtune/client/profile/ProfileService.java:136 (`resolve(id)`) vs :356-357 (`refreshBaseline()`); template base at :433 | PF-1's refresh (the coordinator's review L2) runs inside `switchTo`, after `switchProfile` has already resolved the Battery template, and `resolve()` builds a template over the stored "My settings" (`base = baseline.settings()`). Scenario: "My settings" was auto-saved weeks ago; since then the player applied RigTune recommendations (no profile in effect). They take the Battery offer. Battery's values for every managed key the Battery rules/template don't set come from the OLD My settings, so the switch quietly puts those keys back to the stale values. Only then is My settings refreshed to the current values. The Battery state and "the settings you had" disagree, which is what L2 tried to prevent. | In `switchProfile(id, answered)`, when `active() == null` and `id` is `BatteryPrompt.BATTERY`, call `refreshBaseline()` before `resolve(id)` (and drop the call in `switchTo`), or re-resolve the target after the refresh. |
| FEAT-3 | LOW (questions an accepted decision) | ProfileService.java:356-357, :464-473 | The PF-1 refresh (SPEC amendment WS-P review L2) overwrites "My settings" in profiles.json on any switch to Battery with no profile in effect. That includes a player who deliberately re-saved "My settings" with Save current, and a profile whose active marker ended in a fold (ws-p.md residual). profiles.json isn't journaled and the switch status says only "Switched to Battery.", so the player isn't told their saved profile changed, and it can't be undone as a profile. The DESIGN promise "'My settings' … never deleted, so the way back can't be lost" is weakened silently. (History can still revert the settings, so no setting value is lost, only the saved profile snapshot.) | Keep the decision but disclose it. When the refresh changed any value, append a status part such as "My settings was updated to your current settings (the way back when you plug in)". Or, as a stricter option, refresh only when My settings was never re-saved by the player (for example, when its `createdAt` equals its last save). |
| FEAT-4 | LOW | client/java/io/github/chaotix345/rigtune/client/ui/ServerProfilesScreen.java:123 (head block), :199-217 | X6 on a new screen: the status that answers Offer / Stop / Forget / Forget all is drawn in the `head` block but is neither a Tab stop nor narrated. For a Narrator user, "Offer Max FPS here" when 32 servers are already set ("Forget one first"), a read-only file or a failed write gives no audible result: the This-server line (a Tab stop) still says "no profile set", and after `rebuildWidgets()` focus moves to the list. (ProfilesScreen has the same 0.4 pattern, but X6 applies to new screens.) | Make the head block a `RowFocus.standalone` (first in Tab order) and call `triggerImmediateNarration(false)` or `narrator.saySystemNow(status)` after `remember/stop/forgetSelected/forgetAll` set a status. |
| FEAT-5 | LOW | client/java/io/github/chaotix345/rigtune/client/awareness/OutsideChanges.java:192-205, :239-248 | 4h: RigTune applied render distance 12; the player later set 20 in game; the Modrinth App sync then wrote 8. The notice says "A setting was changed outside the game since you last played (Render distance: 20 → 8)" with "Apply RigTune's values again". That action applies 12 (`change.rigtune()`), a value shown nowhere in the notice, its detail or the status toast. A player reading "20 → 8" expects the button to restore 20. The reapply recommendation's reason, "RigTune's value before it was changed outside the game", is also untrue here: the value before the outside change was 20. | When `before` differs from `rigtune`, show RigTune's value in the detail (for example "Render distance: 20 → 8; RigTune's value: 12"), and reword `rigtune.outside.reason` to "The value RigTune last applied." |
| FEAT-6 | LOW | client/java/io/github/chaotix345/rigtune/client/server/ServerProfileService.java:106-124 | X4.4: new per-tick work runs in its own END_CLIENT_TICK listener, "measured separately (its own ns-per-call and allocation keys in the footprint JSON; 0 bytes strict)". C16's toast-wait listener (registered on the first offer, one volatile read per tick afterwards, for the rest of the session) has no footprint key and no allocation gate. It is cheap by inspection, but it is the only v0.5 tick listener with no measurement at all (RW-11 got the WS-S M1 interim gate). | Add `serverProfileTickNsPerCall`/`serverProfileTickAllocBytes` at the post-Wave-B checkpoint, or a StutterGameTest-style strict sum-of-bytes == 0 check in ServerProfilesGameTest. At minimum, record the exception in ws-p2.md and the SPEC amendments. |

## Checked, fine
- **NoticePriority**: the 14 slots match C3; RealController's lazy list is in the same order; `NoticeBoard.select` sorts by
  ordinal. FIRST_RUN can't coexist with HELD_MOD_CHANGES or LAUNCHER_REPAIR (both need RigTune's records, which make
  RETURNING); FIRST_RUN and MOD_FILES_NEWS never show at the same moment (they follow each other in time: FEAT-1).
  SERVER_PROFILE sits above SERVER_LIMIT.
- **Session-only ×**: `server-profile:` keys go to the in-memory session set only (`AwarenessService.dismiss`,
  SESSION_ONLY_PREFIXES); the key is per join, so × hides that connection's offer only.
- **X8, notice sources**: ServerProfile (reads nothing while `offers.pending()` is null), FirstRun (awareness.json is read
  only when the guide would show), StartupRegression (`StartupTimes.computed()`, in memory), OutsideChanges and
  ModFilesNews (in memory). The v0.5 sources are built by the lazy supplier.
- **C16 privacy**: no address in any log (`lookup` logs the profile id; `Connection.toString` omits the address;
  ServerProfileStore warnings carry no address), none on screen or in narration (rows are kind · profile · date), none
  in reports or share codes (no reference outside the C16 classes). HMAC-SHA256 over the normalised address with the
  file's own 16-byte salt, lower-case hex checked; `joined` writes nothing for a server that isn't remembered. The privacy
  line claims nothing stronger than "not stored in readable form".
- **C16 flow**: a repeated JOIN with the same identity is the same connection; a lookup that finishes after DISCONNECT
  offers nothing; one toast per server per session (toast waits for the world, and a retired offer never toasts);
  Switch goes through `ProfileService.switchProfile` (Busy refusal, one journal entry); the offer retires once the
  profile is active, and fails closed on a deleted or unknown profile; `forgetProfile` on delete, while a rename keeps
  the mapping; SINGLEPLAYER covers the own world, the Open-to-LAN host and the benchmark world.
- **C02**: `FirstRun.isNew` reads history.json once (`holdsNoEntries`); CORRUPT, NEWER and UNREADABLE count as
  RETURNING; any last-apply.json or pending.json makes RETURNING; a failed read gives RETURNING; load's compareAndSet
  can't undo an Apply. A 0.1-0.4 player with any record is never "new". FirstApplyScreen's notes: restart iff STAGED;
  "no restart" also needs no failure, nothing undone and one row in effect; the downloading-only case isn't called
  "nothing recorded"; the no-mod-files note only when every row is a setting. The guide wording under PENDING claims
  nothing about mod files. HowItWorks, FirstApply and guide rows are RowFocus Tab stops with open narration.
- **C18**: MIN_RUNS 5, and a floor of at least 10 % through `BenchmarkTrend.noiseFloorPercent`. RW-19's subtraction is
  used only when the latest run and at least 5 runs of the window recorded preloadMs, and subtracted and raw values
  are never mixed. A returning 0.4 player (no preloadMs) is compared raw until 5 new runs carry it, so there is no false
  regression at the upgrade. The streak keeps its first launch's key (one Got it), and a slowdown after an in-line
  launch gets a new key. Every cause line hedges ("may be related", or it only states that RigTune's version changed);
  RigTune is left out of ModSetHash, so an update of RigTune reads as RIGTUNE_VERSION, not MOD_SET. The
  acknowledgements are read only for a SLOWER launch. Tools' usual median is the trend's window.
- **2L**: registry reads only (`registryValueExists`/`registryGetValue`), on the worker, NOT_READ off Windows or
  without JNA; the advice never says lodctr, never guesses who set the value, and credits only "part of" the preload
  time; the mixin is `require = 0` with two clock reads; the notice detail carries one 2L line.
- **AW-1/AW-2/Latent 2**: a committed notice survives a NONE rescan; its own Re-scan commits synchronously, then
  retires it; NoticeScreen reports each init's listing (no tick work); "unknown" maps to "" both in `of()` and in
  0.4-stored fingerprints, so a failed probe gives no false GPU/CPU change for a returning 0.4 player.
- **2D**: the month-bounded ADRENALIN pattern. A legacy "Context 22.20.x" driver read UNKNOWN on both sides compares by
  raw string (same string, no change), so an upgrade gives no false driver notice.
- **4h**: no snapshot, no comparison (a first 0.5 launch, a crash, a kill); `fullscreen` and keys RigTune never
  applied are ignored; a value back at RigTune's is not reported; the snapshot is consumed once (not re-read when the
  file is unwritable); 0.5 -> 0.4.0 -> 0.5 skips the comparison; values are cleaned and capped. The options.txt encoding
  matches SettingsBridge's (the same codec path).
- **WS-P**: PF-4 sends MATCH_DISPLAY only when the sender's cap isn't 60; PF-5 local range used by the 4 gates and
  `leftOut` counts only shareable keys; PF-3 retires a back-offer whose target is gone or already active; PF-2 lets
  only the unplug offer snooze, and the Settings row writes `battery.snoozed` (inactive for a newer file); L8's
  `foldedEntryIds` keep the newest 50 and only a baseline's count, `withProfiles` gives newest first, the "Includes"
  line wraps and the row grows, and its narration includes it.
