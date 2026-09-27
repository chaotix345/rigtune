# v0.5 feature research: C02, the first-time Apply trust flow

Summary (research agent f-firstapply, 2026-09-27, branch feat/v0.5.0 at 3e97cbec; read-only apart from this file):

1. The pre-Apply guide is a notice in the RigTune screen's existing notice line, not a new panel. It is a new `FirstRunNoticeSource` with a new `NoticePriority.FIRST_RUN`, its message is "New to RigTune? History… can undo any Apply.", and it has the actions **How it works** and **Got it**. It adds no RigTuneScreen widgets, and it inherits the line's 640x480 "…" fallback, Tab stop, narration and Palette.
2. The post-Apply confirmation is a new `FirstApplyScreen`. It opens once, after the first press of the RigTune screen's Apply button, and it lists that journal entry's `HistoryModel` rows through `HistoryScreen.describe`/`failureText`, the same static functions History draws with. It shows two sections, "In effect now" and "At the next restart", and a restart note only when a row is waiting for a restart. Its buttons are **Undo this Apply**, **History…** and **Done**.
3. Whether a player is new comes from what's on disk, with no flags: history.json is missing or has no entries, and there is no last-apply.json and no pending.json. Anyone who applied with 0.1 to 0.4 is RETURNING and sees neither the guide nor the confirmation. **Got it** is stored as a notice dismissal in awareness.json, which 0.4.0 keeps. **settings.json does not change at all.**
4. New code: `core/history/FirstRun` (a pure check), `client/FirstRunService` (UNKNOWN/NEW/RETURNING, loaded once on `Probes.EXECUTOR`), `client/notice/FirstRunNoticeSource`, `client/ui/HowItWorksScreen` and `client/ui/FirstApplyScreen`. Hotspot edits are small: RigTuneController gets 3 default methods, RealController about 10 lines, RigTuneScreen.applySelected about 6 lines, plus en_us.json (about 25 keys) and the gametest fabric.mod.json (1 line).
5. P0.4 (launcher-managed mods): neither feature adds header widgets to RigTuneScreen. Both use the notice slot through their own NoticeSources. C02 reads one P0.4 signal to switch its launcher-mode wording (`modFilesManagedBy()`, UNVERIFIED: P0.4's design isn't written yet). P0.4 can read C02's `FirstRunService.status()` to show its "RigTune now leaves mod files to your launcher" notice to RETURNING players only.
6. Compatibility: C02 adds no file formats, rules, pending ops or settings fields. history.json is only read. The only write is one string in awareness.json's `dismissed` array. 0.4.0's AwarenessStore/StateStore are byte-identical to HEAD (`git diff v0.4.0 HEAD` is empty), and 0.4.0 keeps unknown keys there.
7. 26.2 vs 26.3: C02 uses only APIs javap shows identical on both versions. Pitfalls to avoid: the InputConstants codes (MOUSE_BUTTON_LEFT is 0 on 26.2 and 1 on 26.3; KEY_TAB is 258 on 26.2 and 43 on 26.3), `Screen.scheduleNarration`/`updateNarratorStatus` (26.3 only), and the `fill(RenderPipeline…)` overloads (the package moved). The feature needs no `//? if` block.
8. Effort: about 3 agent-days (the pitch said 2.5). The two extra pieces are live reload while downloads finish and the explainer screen.
9. Riskiest part: the end-to-end game test. It must be the first entrypoint so it sees a fresh state, and it depends on the report CI's llvmpipe produces. Second risk: the P0.4 interface isn't settled yet.
10. Cut order if time runs short: the explainer screen (HowItWorksScreen), then live reload for downloads, then the guide notice. The confirmation is never cut.

---

## 1. Open questions and the critic's note, answered against the code

### 1.1 Brainstorm open question (1): the StartupNotices API, and where the flags go

- `StartupNotices` has two parts: `takePrivacyNotice(settings, configDir, io)`, a one-shot boolean in settings.json saved on the given executor (`client/StartupNotices.java:13-22`), and the `PrivacyToast` state machine, which is title-screen only and hidden over RigTuneScreen (`StartupNotices.java:27-54`). Only `RigTuneClient.onTick` uses them, on the title screen (`client/RigTuneClient.java:180-197`). RigTuneScreen has no StartupNotices hook, and the confirmation belongs on a RigTune screen, not over the title screen, so neither C02 piece belongs in StartupNotices.
- Copying that pattern would bring in a known bug. `takePrivacyNotice` saves on `Probes.EXECUTOR` (`RigTuneClient.java:192`, `StartupNotices.java:20`), not through `SettingsSaver`, so a quit right after the first title screen can lose the write (v04-leftovers L6, being fixed separately).
- settings.json flags also don't survive a downgrade and re-upgrade. `ClientSettings.save` writes `GSON.toJson(this)`, which contains only the fields the running version knows (`client/ClientSettings.java:125-131`). A 0.4.0 that rewrites settings.json (any Settings change there) therefore drops `firstRunGuideShown`/`firstApplyDone`. Gson ignores unknown fields on read, so 0.4.0 does read the file fine; the flags are only lost on its write.
- **Answer:** no new settings.json keys.
  - "Has this player ever applied?" is already on disk: history.json (the Journal) gets an entry for every Apply, Undo, benchmark "Keep" and 0.1.x import (`core/history/Journal.java:221-254`, `client/undo/VanillaChanges.java:31`, `client/benchmark/KeepSettings.java:35`).
  - The only extra state is "the player pressed Got it without applying". That is a notice dismissal, and notice dismissals already live in awareness.json (`core/awareness/AwarenessStore.java:96-110`, reached through `NoticeCenter`/`AwarenessService.dismiss`, `client/awareness/AwarenessService.java:194-207`).
  - C02 is therefore independent of L6.

### 1.2 Open question (2): copy that never implies an unnecessary restart

- Every change row already carries the truth:
  - Vanilla settings are journaled `APPLIED` the moment they're set (`VanillaChanges.java:51`).
  - Config patches and mod changes are journaled `STAGED` by `Staging` (`RealController.java:603-612`).
  - Downloads finish later and are added to the same entry id (`RealController.java:420-421, 519-565`), and `RealController.downloading()` says while that's happening (`RealController.java:827-829`).
  - Today's status line already only mentions a restart when something is pending (`RealController.java:493-497`, `rigtune.status.restart`).
- **Answer:**
  - The confirmation shows "Restart Minecraft to finish…" only if at least one row of this entry is `STAGED`.
  - It shows "All of it is in effect now. No restart needed." only if no row is `STAGED` and `downloading()` is false.
  - While downloads run, it shows a downloading note and reloads the entry when the controller's status changes.
  - The Apply status line itself stays exactly as it is.

### 1.3 Open question (3): one feature or two

- The two halves share no code except `FirstRunService`, which tells both whether this player is new.
  - The guide is one NoticeSource plus a NoticePriority constant (and optionally the explainer screen).
  - The confirmation is one screen plus a hook of about 6 lines in `RigTuneScreen.applySelected`.
- They can ship as separate PRs. The confirmation is the higher-value half, as the brainstorm says, and §9 cuts the guide before it.

### 1.4 The critic's note (§9.5): new UI adds accessibility surface

- The guide adds no new surface. The notice line's text is already a Tab stop that narrates message and detail (`RowFocus.standalone`, `client/ui/RigTuneScreen.java:452-456`), its actions are ordinary buttons, it collapses to message plus "…" below 400 scaled px (`RigTuneScreen.java:393-416`), and NoticeScreen lists the actions. Its colour goes through `Palette.of(COLOR_NOTICE)` (`RigTuneScreen.java:480`).
- The two new screens are RowList/RowFocus lists, like History (`client/ui/RowList.java`, `client/ui/RowFocus.java`). Every row is a Tab stop that narrates its text, focus survives a rebuild through `RowList.initialFocus`, and the lists get the 26.2 narration fix for free (`RowList.java:32-45`).
- They use only colour literals already in Palette's table (`client/ui/Palette.java`). A new literal fails `PaletteTest` (docs/v0.4/design/ws-x.md "Deviations" 3).
- A11yGameTest gets a Tab walk for both screens (§5).

### 1.5 Corrections to the C02 pitch found in the code

1. **"replaces the normal toast"**: Apply shows no toast. Its result is the status line under the list (`RigTuneScreen.java:569`, drawn at `:663-665`). The "RigTune applied N change(s)" toast comes at the *next launch*, after the helper ran (`RigTuneClient.java:212-235`). The confirmation is an added screen; the status line and the next-launch toast stay.
2. **"History → Undo reverts any one of them"**: Undo this works on an entry (one Apply), not one change row (`client/ui/HistoryScreen.java:144-151`, `selectedEntry().id()`). The wording must say "one Apply, the last one, or everything".
3. **"checked right after the first apply() call… that journal entry's rows"**: `apply(selected)` makes its entry id internally and returns only a Component (`RealController.java:415-418`), so the screen can't name "that entry". The two-argument `apply(selected, entryId)` is already public on RealController (`:422`) but isn't in `RigTuneController`. Downloads also join that entry later (`:519-565`), so rows read right after `apply()` can be incomplete.
4. **Screen space**: at 640x480 GUI scale 2 (320x240 scaled) the RigTune list is already only about 76-86 px tall. The arithmetic:
   - The column is 288 px (`RigTuneScreen.java:684-686`), and the 8 or 9 footer buttons at `MIN_BUTTON` 88 go 3 per row, so 3 rows put the footer top at y 168 and the status line at y 156 (`:186-193`).
   - 3 or 4 header lines put the list top at y 66-76 (`:144-154`).
   - A two-line panel would cut the list by about 25 px on top of the notice line.
   - The notice line costs 16 px (`NOTICE_ROW`), and that cost was already accepted for 0.4 (PROGRESS.md: "notice line + '…' at 640x480").
   - Using that slot costs nothing when another notice already shows.

---

## 2. Design

### 2.1 Who counts as new

`core/history/FirstRun` (pure, core, unit-tested):

```java
// A player RigTune has never changed anything for: no journal entry (none of Apply, Undo, Benchmark result, Imported
// from 0.1, baseline) and no sign of an earlier helper run (last-apply.json) or staged plan (pending.json).
public static boolean isNew(Journal.State state, List<JournalEntry> entries, boolean lastApplyExists, boolean pendingExists) {
    return (state == Journal.State.MISSING || state == Journal.State.OK && entries.isEmpty()) && !lastApplyExists && !pendingExists;
}
```

- CORRUPT, NEWER and UNREADABLE history count as returning (the conservative choice): such a player has used RigTune before.
- The last-apply.json and pending.json checks cover a 0.1.x player whose preLaunch couldn't take the apply lock. In that case `HistoryStartup.run` returns early and the legacy import is deferred to the first record (`client/undo/HistoryStartup.java` `run`, `legacyEntry`), so history.json would still be missing.
- These are the same two files `LegacyImport` imports from.

`client/FirstRunService` holds the result in memory:
- `enum Status { UNKNOWN, NEW, RETURNING }` in an `AtomicReference`.
- `load()` runs once on `Probes.EXECUTOR`, from `RealController.start` (`RealController.java:189-193`). It reads `ClientJournal.get().state()`/`entries()` and checks whether `ApplyResult.defaultPath(configDir)` and `PendingActions.defaultPath(configDir)` exist. Then it does `compareAndSet(UNKNOWN, NEW or RETURNING)`, so a load that finishes after an Apply can't turn a player back into NEW.
- `applied()` sets RETURNING. `RealController.apply(selected, entryId)` calls it at the end of every Apply: from the RigTune screen, a profile switch (`ProfileService.java:341`), or a direct controller call in a test.
- `status()` is a volatile read with no I/O, safe on the render thread.
- It persists nothing. The next launch finds the Apply's entry in history.json.

What UNKNOWN means for each piece:

| Status | Guide notice | Confirmation after Apply |
|---|---|---|
| UNKNOWN (load not finished; takes milliseconds, and the report takes seconds) | not shown | not shown |
| NEW | shown unless dismissed | shown on the first Apply press |
| RETURNING | not shown | not shown |

Known edge cases (documented, not fixed):
- A benchmark "Keep" records an entry directly through `ChangeRecorder` (`KeepSettings.java:35`) without passing through `apply`. After it, the guide stays up until the next launch, and the next Apply still gets the confirmation. Both are harmless.
- A player who deletes history.json is new again.

### 2.2 The pre-Apply guide: a notice

`client/notice/FirstRunNoticeSource implements NoticeSource`:

- `KEY = "firstrun.guide"` (stable, no version suffix), priority `NoticePriority.FIRST_RUN`. It is declared first in the enum: declaration order is priority (`core/notice/NoticePriority.java:3`).
- `current()` returns the notice only when all of these hold:
  - `firstRun.status() == NEW`;
  - `controller.report()` is non-null and has at least one `appliable()` recommendation, since a guide about Apply is pointless with nothing to apply;
  - (not dismissed: NoticeCenter already filters dismissed keys, `client/notice/NoticeCenter.java:56`).
- The notice:
  - message: `rigtune.firstrun.notice`;
  - detail: `rigtune.firstrun.notice.detail`, or `…detail.launcher` in launcher-managed mode;
  - actions: `how` "How it works" and `got_it` "Got it";
  - `dismissible = false`. "Got it" is the dismissal, as in `RegressionNoticeSource` (`client/notice/RegressionNoticeSource.java:31-49`), because it reads better to a new player than "×".
- `act("how")` opens `HowItWorksScreen(minecraft.gui.screen(), controller)`. It works from the RigTune screen and from NoticeScreen (the 640x480 path), and Done returns to whichever screen opened it, as `RegressionNoticeSource.act` does.
- `act("got_it")` calls `controller.awarenessService().dismiss(KEY)`. The key has neither the hardware nor the what's-new prefix, so `AwarenessService.dismiss` stores it in awareness.json (`AwarenessService.java:194-207`). The inline and NoticeScreen handlers then rebuild (`RigTuneScreen.java:420-425`, `NoticeScreen.java:56-60`).
- It is registered in RealController's `NoticeCenter` list (`RealController.java:179-181`).
- When it shows and how it goes away:
  - It appears on every RigTune screen until Got it, or until the first Apply by any path (status turns RETURNING).
  - Other notices are still reachable through "+N more" (`RigTuneScreen.java:435-443`).
  - It never shows on the title screen, in Settings or in Tools.

**Fit.** Widths are estimated with vanilla glyph advances (letters about 6 px, `i` 2, `l` 3, `t` 4, space 4); UNVERIFIED as exact, and the game test measures `font.width`.

| Size (GUI scale 2) | Scaled width | Layout | Room for the message | English message (~233 px) |
|---|---|---|---|---|
| 1280x720 | 640 | inline buttons | ~360 px | fits |
| 854x480 | 427 (≥ 400) | inline: How it works (~71 px) + Got it (~36 px) + gaps | ~280 px | fits |
| 640x480 | 320 (< 400) | message + "…" (~20 px) | ~268 px | fits; both actions in NoticeScreen |

- A longer translation is clipped, and its full text is in the tooltip, the narration and NoticeScreen (existing behaviour, `RigTuneScreen.java:472-486`).

### 2.3 The explainer: HowItWorksScreen (cut first if short of time)

A static, read-only page with no I/O, built like NoticeScreen/ToolsScreen:
- The title "How Apply works" and one RowList. Each row is one wrapped paragraph with a `RowFocus` narrating it: ticked items; settings now; config at restart; mods (RigTune mode) or launcher (launcher mode); Preview; History and Undo.
- One button: Done (`gui.done`) → the parent.
- Colours: `COLOR_LABEL 0xFFA8A8A8`, body white, only existing literals.
- Fit at 320x240: title at y 8, list y 24 to 212, footer 1 row. The 6 paragraphs wrap to about 18 lines at 288 px and scroll.
- In launcher-managed mode the mods row names the launcher with `Component.translatable(launcher.nameKey())`, keys already in LauncherInfo's table (`core/launcher/LauncherInfo.java:24-35`) and in LangCheckTest's (b) set.

### 2.4 The post-Apply confirmation: FirstApplyScreen

**Hook.** In `RigTuneScreen.applySelected` (`RigTuneScreen.java:561-571`) and nowhere else:

```java
List<Recommendation> chosen = ticked();
if (chosen.isEmpty()) return;
boolean first = controller.firstApplyPending();
String entryId = ChangeRecorder.newEntryId();
status = controller.apply(chosen, entryId);
updateApplyButton();
if (first) {
    minecraft.gui.setScreen(new FirstApplyScreen(this, controller, entryId, status));
}
```

Because the confirmation belongs to the button:
- Profile switches (ProfilesScreen, with its own Preview-first flow) never open it.
- Direct `controller.apply` calls in game tests (for example `RigTuneClientGameTest.java:250`) never open it.
- It still retires the NEW status through `applied()`.

**Controller contract** (`client/ui/RigTuneController.java`, three defaults so every stub and wrapper compiles unchanged):

```java
// v0.5 (C02): this player has never applied anything and hasn't yet this session (FirstRunService). Render thread, no I/O.
default boolean firstApplyPending() { return false; }
// v0.5 (C02): Apply journaled under this entry id (RealController already has it for profile switches).
default Component apply(List<Recommendation> selected, String entryId) { return apply(selected); }
// v0.5 (C02): downloads from an Apply are still running (their changes join the same entry when they finish).
default boolean downloading() { return false; }
```

- RealController's existing `apply(List, String)` (`:422`) and `downloading()` (`:827`) become the overrides; add `@Override`.
- A game-test wrapper that forwards `apply(selected)` to the real controller must also forward the two-argument form if it wants the entry found. Otherwise it shows the "nothing recorded" message, which is correct for a stub.

**Loading.** Same pattern as HistoryScreen (`HistoryScreen.java:125-138, 189-206`):
- `CompletableFuture.supplyAsync(controller::history, Probes.EXECUTOR)` → `minecraft.execute(...)`, then it finds `entries().stream().filter(e -> e.id().equals(entryId))`.
- It reloads when `init()` runs with `stale` set: back from UndoScreen or HistoryScreen, where rows then honestly read Undone or Cancelled.
- It also reloads when `controller.status()` changes identity in `tick()` (downloads finished), the same check RigTuneScreen uses (`RigTuneScreen.java:600-610`).

**Content.** Every row is a RowFocus row in one RowList (one column, like History):

| Row | Text | When |
|---|---|---|
| status | the Component `apply()` returned, then `controller.status()` once it changes | always; shows "N change(s) failed; see the log" and download errors too |
| section heading | `rigtune.firstrun.applied.section.now` "In effect now" | ≥1 APPLIED row |
| change rows | `HistoryScreen.describe(change)` + `Component.translatable(change.statusKey())` + `HistoryScreen.failureText(change)` | APPLIED rows, in journal order |
| section heading | `…section.restart` "At the next restart" | ≥1 STAGED row |
| change rows | as above | STAGED rows |
| section heading | `…section.undone` "Undone or cancelled" | ≥1 REVERTED/DISCARDED/ABANDONED row (only after Undo from here) |
| note | `…restart` | ≥1 STAGED row |
| note | `…no_restart` | no STAGED row and `!downloading()` |
| note | `…downloading` | `downloading()` |
| note | `…launcher` | launcher-managed mode (P0.4) |
| note | `…undo_hint` | always, last |
| message instead of rows | `rigtune.history.loading` / `.error` / `.corrupt` / `.newer` / `rigtune.firstrun.applied.nothing` | while loading; controller.history() null/threw; state CORRUPT/NEWER/UNREADABLE; entry not found with state OK |

- Row texts come from the same package-private static functions HistoryScreen draws with, so they can't drift from History or from what Undo reverts. Only the grouping differs.
- HistoryModel keeps the journal's order: vanilla changes are recorded first, staged ones after, downloads last. Grouping is therefore a stable filter.
- The row statuses keep History's colours: `HistoryScreen.statusColor`, with the existing literals `0xFF7FE07F` (Applied), `0xFFFFD166` (Staged) and `0xFFFF7A6B` (failed), all in Palette.
- Title line: `rigtune.firstrun.applied.title` "Your first Apply". Subtitle: `HistoryScreen.summary(entry)` ("3 settings, 1 mod", existing keys), or empty until loaded.
- `getNarrationMessage()` (present on 26.2 and 26.3, javap) returns `RowFocus.join(title, summary, restart-or-no-restart note)`. `triggerImmediateNarration(false)` (both versions) runs once after the first load, so a Narrator user hears the outcome without tabbing.

**Buttons.** Laid out like History's footer (`HistoryScreen.java:165-186`):
- **Undo this Apply** → `new UndoScreen(this, controller, entryId)` (`UndoScreen.java:68`). UndoScreen shows exactly what `UndoPlanner` would put back or skip before anything happens, and while downloads run it already says "busy" (`RealController.undoPlanFor`, `:721-733`).
- **History…** (`rigtune.history.open`, existing) → `new HistoryScreen(this, controller)`, which selects the newest entry, this one (`HistoryScreen.java:198-199`).
- **Done** (`gui.done`) and Esc → the RigTune screen, whose status line still shows `apply()`'s message.
- Initial focus follows the house rule (`RowList.initialFocus`): nothing is focused after mouse use, and the first button after keyboard use.

**Fit at 640x480 scale 2.** 3 buttons at 88 px fit one row in the 288 px column: footer top 216, list y 32 to 212 (180 px, about 16 lines). A typical first Apply (5-8 settings, a Sodium patch or two, 4-5 notes) fits with little or no scrolling. At 1280x720 the column is 480 px.

**Why a screen and not a toast.**
- A SystemToast is 2 short lines. It disappears after 5-10 s (`SystemToast.SystemToastId(long)`), it isn't a Tab stop, and it can't list rows or hold an Undo button.
- The pitch's "byte-for-byte HistoryModel rows" needs a list.
- The screen appears once per player, costs one click (Done or Esc), and is never shown to returning players.

### 2.5 Launcher-managed mode (P0.4) and sharing RigTuneScreen

P0.4's design (docs/research/v0.5/launcher-managed-mods.md) did not exist at 12:25. r-launchers was still researching launcher internals (its scratch progress.log), and I messaged it the proposal below. **Everything named for P0.4 here is UNVERIFIED.**

- **One signal from P0.4.** `RigTuneController.modFilesManagedBy()` returns `@Nullable LauncherInfo`: null when RigTune still changes mod files (today) or while unknown, and the detected launcher when it manages them. C02 only reads it (render thread, no I/O).
  - It is read by: the guide's detail choice (`…detail` / `…detail.launcher`), the explainer's mods row, and the confirmation's launcher note.
  - If P0.4 names it differently, C02 renames its three call sites. If P0.4 slips, C02 ships the null branch, today's wording, which is true for today's behaviour.
- **One signal from C02.** `RealController.firstRunService().status()`. P0.4's "What's new: RigTune leaves mod files to your launcher" notice should show only to RETURNING players, since new players learn it from the guide's detail and the explainer.
- **One slot, several sources.** Neither feature adds header lines, panels or banners to RigTuneScreen.
  - Each owns its own `NoticeSource` file and its own `NoticePriority` constant(s).
  - Proposed order: `LAUNCHER_REPAIR` (P0.4: this instance is in a state its launcher can't update), then `FIRST_RUN`, then the existing constants, then `LAUNCHER_WHATS_NEW` near `WHATS_NEW`.
  - They rarely meet: FIRST_RUN needs an empty history, and the repair notice needs an earlier RigTune change.
- **Rows are P0.4's.** In launcher mode, mod recommendations become advice with the launcher's steps (like `LauncherLines.adviceLine`, `RigTuneScreen.java:785-786`). C02 doesn't touch recommendation rows. Advice isn't appliable, so it never reaches Apply or the confirmation.
- **Hotspot contact:**
  - `RigTuneScreen` gets C02's 6-line `applySelected` hook and whatever P0.4 needs in rows or the header; the changes are in different places.
  - `NoticePriority` gets one enum line from each feature.
  - `RealController`'s NoticeCenter list gets one element from each.
  - `RigTuneController` gets separate default methods.
  - Whichever lands second rebases, and the conflicts stay local to a line.

### 2.6 Wording: draft en_us.json keys (area `rigtune.firstrun`)

Honesty rules applied: nothing claims a restart that isn't needed; "undo" means per Apply; no "always"; no "bottleneck/limited by" (WordingTest); launcher text doesn't name a launcher inside core `Text` (LangCheckTest (e) needs literal keys in `Text.of`, and a launcher name key is dynamic). `%s` only (LangCheckTest (d)).

| Key | English |
|---|---|
| `rigtune.firstrun.notice` | New to RigTune? History… can undo any Apply. |
| `rigtune.firstrun.notice.detail` | Only the ticked items change, with any mods they need. Game settings change when you press Apply; Sodium, Distant Horizons and Iris settings and mod changes take effect at the next restart. Preview shows each change first. |
| `rigtune.firstrun.notice.detail.launcher` | Only the ticked settings change. Your launcher manages this instance's mods: RigTune leaves mod files alone and lists mod suggestions as steps for your launcher. Game settings change when you press Apply; Sodium, Distant Horizons and Iris settings at the next restart. Preview shows each change first. |
| `rigtune.firstrun.action.how` | How it works |
| `rigtune.firstrun.action.got_it` | Got it |
| `rigtune.firstrun.how.title` | How Apply works |
| `rigtune.firstrun.how.ticked` | Nothing changes until you press Apply, and only the ticked items change, with any mods they need. Untick anything you don't want. |
| `rigtune.firstrun.how.ticked.launcher` | Nothing changes until you press Apply, and only the ticked settings change. Untick anything you don't want. |
| `rigtune.firstrun.how.now` | Minecraft's own settings change as soon as you press Apply. |
| `rigtune.firstrun.how.restart` | Sodium, Distant Horizons and Iris settings are written after you close Minecraft, and take effect the next time it starts. |
| `rigtune.firstrun.how.mods` | Mods are downloaded when you press Apply and added at the next restart. A mod RigTune turns off is renamed to .jar.disabled, never deleted. |
| `rigtune.firstrun.how.mods.launcher` | %s manages this instance's mods, so RigTune doesn't add, update or turn off mod files here. Mod suggestions come with the steps to follow in it. |
| `rigtune.firstrun.how.preview` | Preview shows every change, file by file, before anything happens. |
| `rigtune.firstrun.how.undo` | History… lists everything RigTune changed. Undo this reverts one Apply, Undo last the most recent one, and Undo all everything; each shows what it will put back first. |
| `rigtune.firstrun.applied.title` | Your first Apply |
| `rigtune.firstrun.applied.section.now` | In effect now |
| `rigtune.firstrun.applied.section.restart` | At the next restart |
| `rigtune.firstrun.applied.section.undone` | Undone or cancelled |
| `rigtune.firstrun.applied.no_restart` | All of it is in effect now. No restart needed. |
| `rigtune.firstrun.applied.restart` | Restart Minecraft to finish: RigTune writes these files after you close the game. |
| `rigtune.firstrun.applied.downloading` | Mod downloads are still running; they're added to this list when they finish. |
| `rigtune.firstrun.applied.launcher` | Your launcher manages this instance's mods, so RigTune didn't change any mod files. |
| `rigtune.firstrun.applied.nothing` | Nothing was recorded for this Apply. |
| `rigtune.firstrun.applied.undo_hint` | Changed your mind? Undo this Apply shows what it would put back before it changes anything. History… keeps this list for later. |
| `rigtune.firstrun.applied.undo` | Undo this Apply |
| `rigtune.firstrun.applied.undo.tooltip` | See exactly what would be put back, then confirm. |

- Reused keys with no new copy: `rigtune.history.open`, `gui.done`, `rigtune.history.change.*`, `rigtune.history.status.*`, `rigtune.history.failed`/`not_applied`, `rigtune.history.summary.*`, `rigtune.history.loading`/`error`/`corrupt`/`newer`, and `rigtune.launcher.name.*`.
- Every key is written out literally: `Text.of(key, english)` in the notice source, whose English must equal en_us.json (LangCheckTest (e)), and `Component.translatable(key)` in the screens. No new dynamic key family, so LangCheckTest needs no new family entry.
- README's key-area list (README.md:280) gains `firstrun`.

### 2.7 Threading

| Work | Thread | New I/O? |
|---|---|---|
| `FirstRunService.load` (history.json with at most 50 entries, 2 `Files.exists`) | `Probes.EXECUTOR`, once after CLIENT_STARTED | yes, off-thread, a few ms; inside `workerCpuMs5s` (300 ms) |
| `FirstRunNoticeSource.current()` | render thread (screen init/rebuild, never per frame) | no (volatile reads) |
| Got it → `AwarenessService.dismiss` | render thread | the existing awareness.json write every dismissal already does (`AwarenessStore.java:16`); nothing new |
| Apply | render thread, unchanged | unchanged |
| `FirstApplyScreen` history read | `Probes.EXECUTOR`, results back through `minecraft.execute` | same as HistoryScreen |
| `FirstApplyScreen.tick()` | render thread, only while the screen is open | no (`status()` identity check) |

- `RigTuneClient.onTick` is untouched, so the `tickHook*` budgets are untouched.
- No settings.json write, so `SettingsSaver` and L6 aren't involved.

### 2.8 Error handling

- `load()` catches `RuntimeException`/`IOException`, logs one WARN and sets RETURNING. A failure never shows the guide or confirmation by mistake.
- A Journal record that failed (apply lock busy past 2 s, or a history.json from a newer RigTune, `Journal.java:221-233`) leaves no entry. The confirmation says "Nothing was recorded for this Apply." or the History state message, and the status row still shows what Apply did. History is still empty then, so the next launch treats the player as new again, which is correct.
- `controller.history()` returning null or throwing gives `rigtune.history.error` (same as HistoryScreen, `:291-304`).
- A NoticeSource that throws is skipped by NoticeCenter (`NoticeCenter.java:77-83`).
- The screen closing mid-load: the callback only rebuilds `if (minecraft.gui.screen() == this)`, as History does (`HistoryScreen.java:201-204`).

---

## 3. Compatibility (0.1.x to 0.4.x, and a downgrade to 0.4.0)

- **settings.json:** unchanged, with no new field. 0.1.x to 0.4.x keep reading and writing it exactly as before. The `ClientSettingsTest` partial-file and default tests are untouched.
- **history.json:** read-only for C02 (`Journal.state()`/`entries()`). The first Apply writes the same entry it writes today, and there is no format change.
- **awareness.json:** one string, `"firstrun.guide"`, may be appended to `dismissed`.
  - 0.4.0 reads `dismissed` as a set of strings (`AwarenessStore.java:84-94`) and keeps unknown ones on its own writes: `StateStore.update` deep-copies the whole object (`core/store/StateStore.java:40-55`), and `dismiss` only appends or evicts beyond 256 (`AwarenessStore.java:96-110`).
  - Those files and `AwarenessService`/`ClientSettings`/`RealController`/`RigTuneScreen` are unchanged since v0.4.0 (`git diff --stat v0.4.0 HEAD` on them prints nothing), so 0.4.0 is this code.
  - 0.3.x and older don't read awareness.json.
- **Upgraders:**

| Instance | Result |
|---|---|
| 0.1.x with an apply | preLaunch's legacy import makes a `legacy-import` entry, or last-apply.json/pending.json exist → RETURNING, nothing shown |
| 0.2 to 0.4 with any Apply, Undo, profile switch or benchmark Keep | entries present → RETURNING |
| installed before but never applied | NEW: guide and confirmation shown; correct, they've never applied |

- **Downgrade to 0.4.0 and back:** 0.4.0 knows neither the notice nor the screen. A Got it survives the round trip in awareness.json, and history.json keeps any Apply. Nothing re-shows except for a player who never applied and never pressed Got it, which is correct.
- **Rules, pending.json, last-apply.json, helper:** untouched. No new op type, so the C22-style Gson enum trap can't happen.
- **Tests that pin the compatibility:**
  - `FirstRunTest` runs `isNew` on `V010Fixtures` (0.1.0 state) and on `src/test/resources/v040-written/` (0.4.0-written files).
  - An `AwarenessStore` test writes `firstrun.guide`, then dismisses 10 more keys through the same code path 0.4.0 uses, and the key is still there.

---

## 4. 26.2 vs 26.3

Checked with javap (JDK `C:/Dev/Tools/jdk/jdk-25.0.4.1+1`) on `~/.gradle/caches/fabric-loom/{26.2,26.3}/minecraft-client-only.jar`.

**Identical on both:**
- `Screen`: `init()`, `rebuildWidgets()`, `setInitialFocus()`, `changeFocus(ComponentPath)`, `clearFocus()`, `onClose()`, `shouldCloseOnEsc()`, `addRenderableWidget`, `getNarrationMessage()`, `triggerImmediateNarration(boolean)`, `extractRenderState(GuiGraphicsExtractor,int,int,float)`.
- `Button$Builder`: `size`, `bounds`, `tooltip`, `build`.
- `SystemToast.add`/`forceHide`/`SystemToastId(long)`.
- `GuiGraphicsExtractor`: `text(Font, Component|FormattedCharSequence|String, int, int, int[, boolean])`, `centeredText(...)`, `fill(int,int,int,int,int)`.
- `InputWithModifiers.isSelection()`/`isEscape()`/`isCycleFocus()` (KeyEvent's).
- `Minecraft.getLastInputType()`.
- `Options.highContrast()`/`highContrastBlockOutline()`/`narrator()`.

**Different, and how C02 avoids each difference:**
- `InputConstants`: `MOUSE_BUTTON_LEFT` is 0 on 26.2 and 1 on 26.3; `KEY_TAB` 258/43; `KEY_RETURN` 257/40; `KEY_ESCAPE` 256/41. Screens compare only with the constants (`RowFocus.keyPressed` uses `isSelection()`), and game tests press `InputConstants.KEY_*`, never literals (the 0.3.0 left-click bug, DESIGN.md "Accessibility (0.4)").
- `Screen.scheduleNarration()` and `updateNarratorStatus(boolean, NarrationTrigger)` exist only on 26.3. C02 doesn't call them; it uses `triggerImmediateNarration(false)`, which exists on both.
- `GuiGraphicsExtractor.fill(RenderPipeline, …)`: the pipeline type moved from `com.mojang.blaze3d.pipeline` (26.2) to `com.mojang.renderpearl.api.pipeline` (26.3). C02 uses only the 5-int `fill`.
- List narration: 26.2 narrates the hovered row first. The new lists extend `RowList`, whose `//? if <26.3` override already fixes that (`RowList.java:32-45`).
- In game tests, `ScreenNarrationCollector.update` takes a `NarrationTrigger` only on 26.3. The existing `//? if >=26.3` idiom (`UiGameTest.java:479-483`) applies if FirstApplyGameTest collects narration itself; otherwise reuse A11yGameTest's helpers.

**Result:** C02's main code needs no Stonecutter conditional.

---

## 5. Test plan

### 5.1 Unit tests (JUnit, run for both version nodes)

- `core/history/FirstRunTest`:
  - Returns true for MISSING, and for OK with no entries.
  - Returns false for each entry kind (apply, undo, benchmark, legacy-import, a `baseline-` entry), for CORRUPT, NEWER and UNREADABLE history, when last-apply.json exists, and when pending.json exists.
  - Run on the `V010Fixtures` instance and the `v040-written` files.
- `client/FirstRunServiceTest`:
  - UNKNOWN until `load()`.
  - `load()` on an empty temp config gives NEW.
  - `applied()` gives RETURNING.
  - A `load()` that finishes *after* `applied()` stays RETURNING (the compareAndSet race).
  - A `load()` that throws gives RETURNING.
  - `load()` runs on the given executor, not the caller (a queued-executor check like `StartupNoticesTest.java:25-34`).
- `client/notice/FirstRunNoticeSourceTest` (NoticeCenter with a temp `AwarenessStore`):
  - Null unless NEW, a report exists, and ≥1 appliable recommendation.
  - Key and priority are right.
  - Two actions, with the english of each `Text` equal to en_us.json.
  - The launcher detail is used when `modFilesManagedBy()` is non-null.
  - `got_it` stores `firstrun.guide` in awareness.json, and a new NoticeCenter over the same file hides it.
  - After `applied()`, `current()` is null.
- `client/ui/FirstApplyScreenTest` (static text functions, English via the `HistoryScreenTest` helper):
  - An all-APPLIED entry gives "In effect now" rows and the no-restart note, with no restart note.
  - A mixed entry gives both sections and the restart note.
  - `downloading=true` gives the downloading note and no no-restart note.
  - REVERTED/DISCARDED rows go to "Undone or cancelled".
  - A missing entry with OK state gives the nothing message. CORRUPT, NEWER and UNREADABLE give the History messages.
  - Rows equal `HistoryScreen.describe` texts in journal order within each section.
- **Existing tests that must stay green without edits:** `LangCheckTest` (new keys used and written out), `WordingTest`, `PaletteTest` (no new colour literal), `PseudoLocaleTest`, `ClientSettingsTest`, `StartupNoticesTest`, `HistoryScreenTest`, `RealControllerTest`.

### 5.2 Client game tests (Linux CI legs from `tools/gametest_matrix.py`: 26.2 OpenGL, 26.3 OpenGL, 26.3 Vulkan under Xvfb)

**New `FirstApplyGameTest`, listed first** in `src/gametest/resources/fabric.mod.json`, so it sees the fresh run dir. `runProductionClientGameTest` deletes its run dir first (build.gradle:334-336). It returns at once under `-Drigtune.smoke=true`. Main case, with the network off:

1. Wait for the title screen.
2. Set `ClientSettings.shared(configDir).networkEnabled=false` and call `settingsChanged()`, as `BenchmarkHistoryGameTest.java:89-101` does. Wait for the report.
3. Assert `firstApplyPending()`. Assert history.json has no entries, and that last-apply.json and pending.json don't exist.
   - On a non-fresh dev run dir (`runClientGameTest`), log a WARN and skip the fresh-path steps. Under `CI`, fail instead.
4. Open the real RigTune screen through its button.
   - Assert `shownNotice().key()=="firstrun.guide"`.
   - Assert `font.width(message) <= noticeTextRight - left` at 1280x720, 854x480 and 640x480 (scale 2). This needs one small game-test getter on RigTuneScreen, or compute it from `shownNotice()`.
   - Take screenshots at all three sizes. At 640x480 the "…" button is there.
   - Tab reaches the notice text, and its narration contains message and detail (A11yGameTest's `tabUntilNarrates`).
5. Press How it works → HowItWorksScreen. Tab walk its rows. Done → back to the RigTune screen, with the notice still there.
6. Press Got it → the notice is gone, and awareness.json's `dismissed` contains `firstrun.guide`.
7. Press Apply (the real button) with the default ticks. The CI report has at least `set-vanilla.renderDistance`, as `UiGameTest.java:477` relies on. With the network off, installs are advice (`UiGameTest.java:508`), so nothing downloads.
   - Wait for `FirstApplyScreen` to finish loading.
   - Assert its entry id is the newest history entry's id.
   - Open `HistoryScreen` at the same size and compare `changeRowText()` (`HistoryScreen.java:95-109`) with the confirmation's change rows: same texts and statuses.
   - Assert the restart note appears iff a STAGED row exists, and the no-restart note iff none.
   - Screenshots at 854x480 and 640x480.
8. Undo this Apply → UndoScreen with a non-null plan for that entry. Cancel. Done → RigTune screen: no notice, and the status line equals `apply()`'s message.
9. Press Apply again on another ticked item (or the same set). No confirmation opens.
10. Clean up: `controller.undo(controller.undoPlanFor(entryId))` puts the settings back and cancels the staged ops, so later tests start from the usual state (history now non-empty, as after RigTuneClientGameTest today).

**Knock-on effects on the existing game tests:**
- RigTuneClientGameTest's `title-toasts` screenshot now comes after the privacy toast was taken. No test asserts that toast's pixels; only `UiGameTest.java:71` checks `privacyNoticeShown`, which is still true.
- Every later test sees RETURNING, so no notice line changes in AwarenessGameTest, A11yGameTest or UiGameTest.

**`A11yGameTest`** (hotspot): two blocks.
- `FirstApplyScreen(new TitleScreen(), controller, "e2", Component.empty())` over the A11yController's history fixture (`A11yGameTest.java:550-578`): walk the rows, and expect "Render distance" and "Lithium" to be narrated.
- `HowItWorksScreen`: walk its rows.
- A high-contrast screenshot of FirstApplyScreen.

**`FootprintGameTest`** needs no change and the budgets stay as they are: nothing is added per frame or per tick, one small static FirstRunService, and the screen/Tools cycles run with RETURNING. It is the check that the guide doesn't leak in the 20 open/close cycles.

### 5.3 One real run on the dev PC

The user's real Modrinth App instance stays untouched.

1. **Fresh instance.** Use a new Prism or official-launcher instance with the 26.2 build, or a scratch game dir with `config/rigtune/` absent.
   - At 1280x720, 854x480 and 640x480 (GUI scale 2), confirm the guide, How it works, Got it, the first Apply and the confirmation.
   - Restart, and confirm the next-launch "applied" toast and that nothing re-shows.
   - Do one pass with Narrator on (Ctrl+B) to hear the guide and the confirmation's open narration.
2. **26.3 check.** Repeat step 1's Apply half once on 26.3.
3. **Upgrade check.** Copy the real instance's `config/rigtune` (read-only copy, history from 0.4.0) into a scratch instance. No guide, no confirmation.
4. **Launcher mode (only once P0.4 lands).** A throwaway Modrinth App instance shows the launcher wording.

Record results in `docs/smoke/` like earlier releases.

---

## 6. Draft acceptance criteria

"5" stands for the SPEC item number to be assigned.

- **AC5.1** On an instance with no history entries, no last-apply.json and no pending.json, the first RigTune screen whose report has ≥1 appliable recommendation shows the `firstrun.guide` notice as its notice line's top notice, with the actions "How it works" and "Got it".
- **AC5.2** With ≥1 history entry of any kind, or a last-apply.json or pending.json, or a corrupt, newer or unreadable history.json, neither the guide nor the confirmation appears. FirstRunTest covers this, including the 0.1.0 and 0.4.0-written fixtures.
- **AC5.3** The English guide message isn't clipped at 1280x720, 854x480 or 640x480 (GUI scale 2). Below 400 scaled px the line is message plus "…", and NoticeScreen offers both actions. There are screenshots for each size.
- **AC5.4** Got it hides the guide for good. `firstrun.guide` is in awareness.json `dismissed`, it isn't shown after a restart, and it stays dismissed after 0.4.0's AwarenessStore writes other dismissals.
- **AC5.5** After the first Apply by any path, the guide is gone in the same session without Got it, and after a restart.
- **AC5.6** Pressing the RigTune screen's Apply while `firstApplyPending()` opens FirstApplyScreen for the entry id Apply journaled under. Its change rows have the same text, statuses and failure lines as the History screen's rows for that entry at the same size.
- **AC5.7** The restart note appears iff ≥1 row is "Waiting for restart". "No restart needed" appears iff no row waits and no download is running. While downloads run, the downloading note shows, and the list reloads with the downloaded changes when they finish (unit-tested with a stub controller whose status changes).
- **AC5.8** In the confirmation:
  - Undo this Apply opens UndoScreen for that entry.
  - History… opens History with that entry selected.
  - Done and Esc return to the RigTune screen, whose status line shows `apply()`'s message.
- **AC5.9** The confirmation opens at most once per session and never for a RETURNING player. A profile switch or a direct `controller.apply` call never opens it but still retires the guide.
- **AC5.10** C02 writes no settings.json field: `ClientSettings.java` and `StartupNotices.java` are unchanged, and it creates no new file under `config/rigtune/`.
- **AC5.11** With the entry missing or history unreadable, the confirmation shows Apply's status line and the matching message (`rigtune.firstrun.applied.nothing`, `rigtune.history.error`/`corrupt`/`newer`), never an unexplained empty list.
- **AC5.12** Accessibility:
  - Every FirstApplyScreen and HowItWorksScreen row is a Tab stop that narrates its text (A11yGameTest walk), and the Tab after the last row leaves the list.
  - The guide's text narrates message plus detail.
  - FirstApplyScreen's open narration includes its title, summary and restart outcome.
  - PaletteTest passes, with no new colour literal.
- **AC5.13** Every new string is an en_us.json key under `rigtune.firstrun.*`, and `LangCheckTest`, `WordingTest` and `PseudoLocaleTest` pass.
- **AC5.14** With `modFilesManagedBy()` non-null, the guide detail, the explainer's mods row and the confirmation's launcher note use the launcher variants; with it null, today's wording. This depends on P0.4; unit-tested with a stub either way.
- **AC5.15** FootprintGameTest passes with `tools/footprint-budgets.json` unchanged. `RigTuneClient.onTick` is unchanged.
- **AC5.16** FirstApplyGameTest's main case passes with the network off on 26.2 OpenGL, 26.3 OpenGL and 26.3 Vulkan.
- **AC5.17** No new render-thread file I/O: `FirstRunService.load` and FirstApplyScreen's history read run on `Probes.EXECUTOR` (asserted with a queued executor in unit tests).

---

## 7. File ownership

HOTSPOT marks files other v0.5 features also edit.

**New:**
- `src/main/java/io/github/chaotix345/rigtune/core/history/FirstRun.java`
- `src/client/java/io/github/chaotix345/rigtune/client/FirstRunService.java`
- `src/client/java/io/github/chaotix345/rigtune/client/notice/FirstRunNoticeSource.java`
- `src/client/java/io/github/chaotix345/rigtune/client/ui/FirstApplyScreen.java`
- `src/client/java/io/github/chaotix345/rigtune/client/ui/HowItWorksScreen.java` (cut first)
- Tests: `src/test/java/.../core/history/FirstRunTest.java`, `.../client/FirstRunServiceTest.java`, `.../client/notice/FirstRunNoticeSourceTest.java`, `.../client/ui/FirstApplyScreenTest.java`
- `src/gametest/java/.../gametest/FirstApplyGameTest.java`

**Changed:**

| File | Change | Note |
|---|---|---|
| `client/ui/RigTuneController.java` | +3 default methods: `firstApplyPending`, `apply(selected, entryId)`, `downloading` | **HOTSPOT**; P0.4 adds `modFilesManagedBy` |
| `client/RealController.java` | `@Override` on `apply(List,String)` and `downloading()`; `firstRun.applied()` at the end of `apply(List,String)`; `firstApplyPending()`; `firstRunService()` getter; `load()` scheduled in `start()`; one more NoticeCenter source | **HOTSPOT**, about 10 lines; C20/C09/C16 are consumers of `apply` and don't change its body |
| `client/ui/RigTuneScreen.java` | `applySelected` only, about 6 lines, plus the `ChangeRecorder` import | **HOTSPOT** |
| `core/notice/NoticePriority.java` | `FIRST_RUN` first | shared with P0.4 (one line each) |
| `src/main/resources/assets/rigtune/lang/en_us.json` | about 25 keys, `rigtune.firstrun.*` | **HOTSPOT**; alphabetical block, append-only |
| `src/gametest/resources/fabric.mod.json` | FirstApplyGameTest as the first entrypoint | **HOTSPOT** |
| `src/gametest/java/.../A11yGameTest.java` | 2 walk blocks and 1 screenshot | **HOTSPOT** (C09/C16 screens too) |
| `docs/DESIGN.md` | new "First-time Apply (0.5)" section; Data flow step 4 gets one sentence | |
| `README.md` | Usage paragraph (README.md:67) gets one sentence; key-area list (:280) gets `firstrun` | |
| `CHANGELOG.md` | entry | |

- **Deliberately not touched:** `ClientSettings` and `StartupNotices` (hotspots the brainstorm named; not needed), `HistoryScreen` (its static text functions are reused as they are, same package), `HistoryModel`, `Journal`, `UndoScreen`, `NoticeScreen`, `RigTuneClient`, `tools/footprint-budgets.json`.
- `ToolsScreen` gets a "How RigTune works" entry only if HowItWorksScreen is kept *and* the coordinator wants it reachable after Got it (optional; skip by default).

---

## 8. Effort and the riskiest part

| Piece | Agent-days |
|---|---|
| `FirstRun` + `FirstRunService` + notice source + NoticePriority + wiring, with unit tests | 0.5 |
| `FirstApplyScreen` + controller contract + RigTuneScreen hook + download reload, with unit tests | 1.0 |
| `HowItWorksScreen` | 0.4 |
| en_us.json, DESIGN/README/CHANGELOG | 0.2 |
| `FirstApplyGameTest` + A11yGameTest blocks, green on 3 CI legs | 0.7 |
| Real run on the dev PC (26.2 fresh, 26.3 Apply, upgrade copy) | 0.2 |
| **Total** | **≈ 3.0** (the pitch said 2.5) |

**Riskiest part: the end-to-end game test.**
- It must run first to see a fresh state, and CI's llvmpipe report and the Sodium presence per leg decide whether the restart branch or the no-restart branch runs.
- The assertion is written to hold either way (the note matches the rows), and the unit tests pin both branches.
- If the Apply button can't be driven by `clickScreenButton("rigtune.screen.apply.count")` (UNVERIFIED for a translatable with arguments), press it through `screen.children()` on the client thread.

**Second risk:** P0.4's interface. Its doc doesn't exist yet. Mitigation: C02's only dependency on it is one nullable getter with a working null branch.

---

## 9. What to cut first

1. **`HowItWorksScreen`:** drop the "How it works" action. The notice detail (tooltip, narration, NoticeScreen) carries the same content. Saves about 0.4 day.
2. **Live reload while downloads finish:** show the downloading note plus "History… shows them when they're done" instead of reloading on status change. Saves about 0.2 day. This never happens in launcher-managed or network-off instances.
3. **The launcher-mode variants:** moot anyway if P0.4 slips.
4. **The guide notice as a whole:** ship only the confirmation, the higher-value half per the brainstorm.

Never cut: the confirmation's rows coming from HistoryModel/HistoryScreen functions, the restart logic, and the a11y walk.

---

## UNVERIFIED

- P0.4's signal (`modFilesManagedBy()`), its NoticePriority names, and whether its mod rows are non-appliable advice. docs/research/v0.5/launcher-managed-mods.md didn't exist when this was written. I sent the proposal in §2.5 to r-launchers and got no answer yet.
- The exact pixel widths of the English guide message and buttons. They are estimates from vanilla glyph advances, and AC5.3 measures `font.width` in the game test.
- `ClientGameTestContext.clickScreenButton` matching a button whose message is `rigtune.screen.apply.count` with an argument (fallback in §8).
- That CI's network-off report always has ≥1 appliable recommendation on all three legs. `UiGameTest.java:477` relies on `set-vanilla.renderDistance` today, which is strong evidence but not a guarantee for Vulkan. If there is none, the test fails with a clear message rather than skipping.
