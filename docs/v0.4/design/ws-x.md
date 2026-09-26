# WS-X: accessibility, reduced scope (SPEC 11, P2), plus 2p

Branch `feat/a11y`. Plan: docs/v0.4/plans/ws-x.md. Research: docs/research/v0.4/bench-history-a11y.md Part B.

## What landed

- **2p (coordinator-assigned, merged early as `fix/mouse-left-263`, 208c0fa).** 26.3's input is SDL, so a left click is
  button 1 there (`AbstractWidget.isValidClickButton` checks `== 1` on 26.3 and `== 0` on 26.2). HistoryScreen,
  ProfilesScreen, RigTuneScreen and RigTuneClient checked `event.button() == 0`, so on 26.3 a real left click never
  selected a History entry or a profile. They now compare with `InputConstants.MOUSE_BUTTON_LEFT`, a compile-time
  constant per version (0 / 1), so no version block is needed. The game tests' synthetic clicks use it as well. The red
  test ran first on its own branch: HistoryGameTest.java:182 timed out on both 26.3 legs, 26.2 passed (run 36231584362).
  The fix branch was green (run 36231792320).
- **`client/ui/RowFocus`** (an AbstractWidget):
  - It draws nothing; its message is the row's text.
  - `getRectangle()` is the row's, so arrow navigation out of the list uses the row's position.
  - `isMouseOver` is false, so clicks, hover and drag reach the row exactly as before.
  - With an action, Enter/Space (`KeyEvent.isSelection()`, the same on both versions) runs it, and the narration adds
    "Press Enter or Space to select" (`rigtune.a11y.row.select`).
  - `RowFocus.join(...)` builds a row's narration from its parts with vanilla's separator
    (`CommonComponents.joinForNarration`), leaving out missing and empty parts.
- **Rows.** Every custom list row now returns its RowFocus from `children()`/`narratables()`, the only way in, since
  `Entry.updateNarration` is package-private:
  - RigTuneScreen: CategoryEntry (name + count) and informational RecommendationEntry (title + impact, reason, launcher
    line). An appliable row keeps the 2m checkbox as its only child, so there's one stop per row.
  - HistoryScreen: EntryRow (heading, summary, versions) and ChangeRow (description, status, failure).
  - PreviewScreen Row and JvmScreen Row: the row's text.
  - UndoScreen: SectionEntry (name + count) and ItemEntry (description + reason).
  - ProfilesScreen: ProfileRow (name, active/source, the template's description).
  - StutterScreen: TextRow (its text) and BarRow (label + value).
- **Selection by keyboard.**
  - Enter/Space on a History entry selects it the way a click does: `clicked` is set, and the entry is selected on the
    next tick.
  - `refocus` plus an override of `setInitialFocus()` put the focus back on that entry's row after the rebuild.
    Otherwise vanilla would move it to the first element, because the screen was used from the keyboard.
  - Enter/Space selects a profile. ProfilesScreen doesn't rebuild, so the focus stays where it is.
- **`client/ui/RowList`**, the base every RigTune list now extends (instead of `ContainerObjectSelectionList`):
  - **Focus frame.** RowFocus draws nothing, and ContainerObjectSelectionList draws no selection, so a sighted keyboard
    user would otherwise not see which row has the focus. `extractItem` draws a 1 px frame, white (yellow with high
    contrast), around a row whose RowFocus has the focus. Nothing changes while no RowFocus has the focus, which is
    always the case with a mouse.
  - **26.2 hover narration.** 26.2's `updateWidgetNarration` narrates the hovered entry before the focused one, so a
    keyboard user whose cursor rests on the list would hear the wrong row. RowList narrates the focused child plus its
    position instead. This is a `//? if <26.3` block, because 26.3 already narrates the focused row for keyboard
    navigation.
  - **Focus across rebuilds.** `focusedRow()` plus `initialFocus(screen, list, row, keyboard)` put the focus back on the
    same row, clamped, after a rebuild. That covers Stutter's live refresh, JVM's report arriving, Preview's load,
    Undo's confirm and Profiles' switch. With no row focused and after keyboard use, the focus goes to the first widget
    outside the list: vanilla's initial focus from before the rows were focusable. So a rebuild neither jumps to row 0
    nor scrolls a restored list back to the top.
  - Each list screen records the row in `rebuildWidgets` and restores it in `setInitialFocus`. History tries its
    Enter/Space entry id first.
- **Selected state.** History's open entry and the chosen profile add "Selected" (`rigtune.a11y.selected`) to their
  narration (RowFocus's optional `BooleanSupplier`).
- **`client/ui/Palette`**:
  - `of(int)` returns the colour unchanged unless `Options.highContrast()` or `highContrastBlockOutline()` is on. Then it
    returns a value from a table that covers every ARGB literal in client/ui except white.
  - In that table, greys and accents get lighter with the same hue, row highlights and divider lines get stronger, and
    the in-world header backing and the benchmark table's backing go from 0x70 or 0x60 black to 0xD0.
  - Screens keep their colour constants and draw through `Palette.of` at the draw site (or at style creation for header
    components, which are rebuilt on init).
  - Covered: every client/ui screen, TrendChart, and BenchmarkResultScreen's table and lines. `focus()` gives the
    frame's colour.

## Tests

- **Unit (both versions).**
  - `RowFocusTest` (5): narration via ScreenNarrationCollector; the usage hint only with an action; `join`;
    Enter/Space run the action and Tab doesn't; the rectangle; no mouse.
  - `PaletteTest` (4): the table's own coverage and behaviour.
    - Off: every client/ui colour (scanned from source) is unchanged.
    - On: every one except white changes.
    - Mapping twice gives the same result, and opaque colours stay opaque.
    - Text colours get lighter (WCAG luminance).
  - LangCheckTest green (AC11.3).
- **`A11yGameTest`**, CI on every leg, with the network off (X1). It uses canned data: StubController's report plus a
  canned history, undo plan, preview, profiles and stutter session (the StutterWrittenFixtureTest capture); the JVM
  report is the real one.
  - Tab walk on RigTune (16 rows), History (4), Preview (6), Undo (6), JVM (8), Profiles (3) and Stutter (32):
    - From nothing focused, Tab reaches row 0.
    - Each press lands on the next row's own child; after the last row, the next press leaves the list.
    - Each focused row's narration, collected over the list's `updateWidgetNarration` (KEYBOARD trigger on 26.3, cursor
      off the list on 26.2), contains the row's text.
    - Every fixture string is narrated.
  - Down walks the whole Undo list and Up moves back. With the cursor resting on row 0, the focused row still narrates
    its own text (the 26.2 case).
  - After keyboard use, History opens with the focus on a button, not a row.
  - Enter on History's older entry selects it with the focus back on its row, and its narration says "Selected"; Up,
    then Space, selects the newer one.
  - Enter selects a profile, which then says "Selected".
  - A live Stutter refresh (a new report) rebuilds the screen and keeps the focus on row 3.
  - High contrast: the stub RigTune screen with High Contrast Block Outline off and then on. Off, the label grey
    (0xA8A8A8) is drawn and its high-contrast value (0xE6E6E6) never is. On, the label-grey pixels that disappear
    account for the new ones (CI: off 2908 / 0, on 640 / 2268; the 640 are vanilla button-sprite pixels present in both).
  - Screenshots:
    - `a11y-rigtune-focus-*` and `a11y-hc-rigtune-*` at the 3 standard sizes.
    - One focused-row shot each for History (after Enter), Preview, Undo (arrows), Profiles and Stutter.
    - `a11y-hc-off/on`, and History focused with high contrast.
- **Off is unchanged (AC11.2).** I pixel-diffed every screenshot of the rows+palette run (36232799173) against the
  feat/v0.4.0 run it was merged onto (36232653309), on 3 legs, 537 files:
  - 341 are identical. That includes ui-main at every size, ui-narration-focused (2m's row), preview, history, JVM and
    undo-all.
  - Every other difference also shows up between two consecutive feat/v0.4.0 runs (36232653309 vs 36233202583): 256
    files differ there, in the same regions (timestamps, the startup time, live stutter/benchmark numbers, the panorama).
  - Four remain, all benchmark-history shots on 26.3, and I looked at them:
    - 0147 is a report built before vs after the network switch ("Apply (21)" vs "Apply (9)").
    - 0151 is the live stutter line.
    - Neither involves colour or layout. The first is an existing timing race in BenchmarkHistoryGameTest's
      screenshots, not a check.

## Deviations and decisions

1. **The focus frame is drawn by the list, not by RowFocus.** SPEC 11 says RowFocus draws nothing, and it doesn't.
   Without the frame, keyboard focus on a row would be invisible.
2. **Profiles: Enter/Space selects.** SPEC 11 names only History. ProfilesScreen's Switch/Rename/Delete act on the
   selected row, so without this the screen can't be used from the keyboard.
3. **Palette maps values, not named roles.** It's the smallest edit to screens owned by other workstreams: one wrap at
   each draw site, and every constant stays. PaletteTest keeps the table complete for future colours (a new literal in
   client/ui without a high-contrast value fails it).
4. **The game test toggles `highContrastBlockOutline`.** Setting `highContrast` reloads resource packs (Mojang's High
   Contrast pack), which would change vanilla's textures in the screenshot and cost a reload. Palette reads both, and a
   unit test covers the mapping.
5. **Not covered:**
   - The painted tables and charts of BenchmarkResultScreen and BenchmarkHistoryScreen: they aren't widgets (SPEC
     "Deferred": v0.5). Their colours do go through Palette.
   - Vanilla `ChatFormatting` colours inside RigTune components (GOLD, GRAY, WHITE).
   - Tooltip text colours (BenchmarkTrendLines' last-benchmark line).

## Hotspot edits

- RigTuneScreen:
  - CategoryEntry and RecommendationEntry: a focus field plus `children`/`narratables`.
  - The list's `extractItem`.
  - Palette wraps at the existing draw and style sites, including `impactColor`/`accentColor`.
  - No layout change.
- en_us.json: `rigtune.a11y.row.select` and `rigtune.a11y.selected` at WS-K's anchor, after
  `rigtune.download.target_exists`.
- HistoryScreen/UndoScreen/PreviewScreen: merged over the coordinator's race fix (init → layout() + async load last);
  the row changes sit in the list classes only.

## Self-review

A code-reviewer subagent reviewed the diff and found 0 high, 3 medium, 6 low and 3 nits. Every medium and the actionable
lows are fixed in add1ff6d:
- M1: a live Stutter refresh took the focus off the rows.
- M2: on 26.2, the hovered row was narrated instead of the focused one.
- M3: a rebuild moved the focus to row 0 and scrolled the list to the top; after Undo's confirm, Enter no longer closed
  the screen.
- L1: the tooltip colour skipped Palette.
- L2: Palette was applied twice through `accentColor`.
- L3: the JVM case was open to a live report replacing the fixture mid-walk.
- L4: the last input type leaked to later tests.
- L5: rows didn't say which one is selected. The symbols ●, → are still read as symbol names: optional, left.
- L6: this file.
Nits left as they are: `Palette.enabled()` reads two options per call (negligible on menu screens); a defensive
`requireNonNull`; holding Enter on a History entry queues one rebuild per tick.

## UNVERIFIED

- Speech from a real screen reader or the Narrator: the tests check the text vanilla's narration pipeline collects, not
  the TTS output (CI has no TTS; flite is missing on the runners).
- `Options.highContrast()` (the resource-pack toggle) switching the palette in a running game. Palette reads it, but only
  `highContrastBlockOutline` was toggled in a game test.
- Controlify or controller navigation (it drives vanilla focus, so it should use the same stops).
