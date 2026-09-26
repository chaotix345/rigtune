# WS-X: accessibility, reduced scope (SPEC 11, P2), plus 2p

Branch `feat/a11y`, worktree `rigtune-a11y`. P2: ships only if clean. BenchmarkResultScreen's and BenchmarkHistoryScreen's
painted tables/charts stay out (SPEC "Deferred": v0.5).

Verified APIs (vineflower on the 26.2 and 26.3 client jars; notes in docs/v0.4/design/ws-x.md):
- A row is a Tab stop only through a focusable child: `ContainerEventHandler.handleTabNavigation` asks each entry's
  `nextFocusPath`, which asks its children; arrows use `nextEntry(dir, e -> !e.children().isEmpty())`.
- `ContainerObjectSelectionList.Entry.updateNarration` (package-private) narrates the child `Screen.findNarratableWidget`
  picks (the focused one); the list's `updateWidgetNarration` narrates the hovered entry first on 26.2, on 26.3 only for a
  MOUSE/SYSTEM trigger (the test keeps the cursor off the list and passes KEYBOARD on 26.3).
- `KeyEvent.isSelection()` (Enter, Space, keypad Enter) exists on both; key codes differ (26.3 is SDL), so the test uses
  `InputConstants.KEY_*` and the code never compares raw key numbers.
- `Options.highContrast()` reloads resource packs when set; `highContrastBlockOutline()` is a plain boolean. The game
  test toggles the latter.

## Task 0 (2p, coordinator-assigned, own branch `fix/mouse-left-263`)
- [x] Game tests' synthetic clicks use `InputConstants.MOUSE_BUTTON_LEFT`; HistoryGameTest's row click is the red test
      on 26.3 (pushed alone to `fix/mouse-left-263-red`).
- [x] `event.button() == InputConstants.MOUSE_BUTTON_LEFT` in HistoryScreen, ProfilesScreen, RigTuneScreen,
      RigTuneClient.

## Task 1: RowFocus
- `client/ui/RowFocus extends AbstractWidget`: draws nothing; message = the row's text; `getRectangle()` is the row's
  (arrow geometry); never claims the mouse (`isMouseOver` false, so clicks behave as before); optional `Runnable`
  run on Enter/Space (`isSelection`); narration TITLE = message (+ USAGE when it can be activated).
- `RowFocus.outline(graphics, entry)`: a 1 px frame around an entry whose RowFocus is focused (the only visible change,
  and only while keyboard focus is on such a row), called from each list's `extractItem`.
- Unit test `RowFocusTest`: narration text via `ScreenNarrationCollector`, Enter/Space run the action, other keys don't,
  `isMouseOver` false.

## Task 2: rows
- RigTuneScreen: CategoryEntry, informational RecommendationEntry (appliable rows keep the 2m checkbox).
- HistoryScreen: EntryRow (Enter/Space selects; focus comes back to the row after the rebuild), ChangeRow.
- PreviewScreen Row, UndoScreen SectionEntry/ItemEntry, JvmScreen Row, ProfilesScreen ProfileRow (Enter/Space selects),
  StutterScreen TextRow/BarRow.

## Task 3: Palette
- `client/ui/Palette.of(int argb)`: unchanged when neither option is on; otherwise a high-contrast value for each of
  RigTune's own colours (lighter greys, brighter accents, darker header backing, stronger row highlights). Pure
  `Palette.of(int, boolean)` for the unit test (`PaletteTest`: identity when off, every table colour changes when on).
- Every RigTune-drawn colour in client/ui goes through it.

## Task 4: A11yGameTest (AC11.1-AC11.3)
- Per screen (RigTune stub, History, Preview, Undo, JVM, Profiles, Stutter): Tab N times; after the focus enters the
  list, every press moves to the next row's child (none skipped, to the last row); the narration collected for each
  focused row contains its text; one known fixture string per screen. Down arrow walks the Undo list too. Enter selects
  a History entry and keeps focus on it; Enter selects a profile.
- High contrast: the stub RigTune screen with `highContrastBlockOutline` off and on: the on screenshot has none of the
  normal label grey and has the high-contrast one; off matches the pre-change CI screenshots (pixel diff vs the latest
  feat/v0.4.0 run, recorded in the design doc).
- Screenshots at the 3 standard sizes; network off for the main case (X1).
- LangCheckTest green (AC11.3): new keys under `rigtune.a11y.*` after `rigtune.download.target_exists`.
