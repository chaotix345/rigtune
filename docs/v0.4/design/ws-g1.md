# WS-G1 design notes: resolver hardening (SPEC 2o: H2, H3, H1-A, M4, M7)

Branch `fix/resolver-audit` (WP-1 of docs/v0.4/audit-verification.md). Plan: docs/v0.4/plans/ws-g1.md. Every fix has a test
that failed first (a compile red for a new API; behavioural reds for H2, H1-A, M7 and the review's M-1, confirmed by
mutating the fix out and rerunning DownloadPlannerTest). Unit tests only: nothing on a screen changes except the status line
of a refused download (localised keys below), so no game test was added; CI's game-test legs stay green. pending.json is
unchanged (no field, no op type): V040WrittenWsaTest regenerates WS-A's written set through the real planner and still
matches, so WS-H's compat030 harness wasn't re-run.

## H3: a library nested in another mod never counts as present for the drop decision
- `DownloadPlanner.topLevelIds(scan)`: the ids of scanned mods with `sha1 != null || file != null` (InstalledMod's contract:
  sha1 is null only for built-in and nested mods; a mods/ jar whose hash failed still has its file). RealController
  `download()` and `preview()` call it instead of their four-line loop over every scanned id.
- Test: a DH-like scan (distanthorizons top-level, fabric-api 0.149 nested with no file or hash): an addition requiring Fabric
  API now stages a top-level Fabric API in its own group; the old ids (every scanned id) dropped it.
- Residual (coordinator: use the sha1 signal): a top-level mod with neither a hash nor a mods/ file (loaded from a directory
  or a multi-path origin, dev only; or a jar outside mods/ whose hash failed) counts as nested, so a Modrinth copy of it could
  be staged next to it.

## H2: fabric.mod.json version ranges, over the whole batch, both directions
- First version (88a9bff) checked each downloaded jar against the installed mods' pins as it was planned. The code review
  found the regression that design causes (confirmed with the coordinator): Iris 1.12 (sodium "0.10.x") and Sodium 0.10
  ticked together; the installed Iris 1.11.4's "0.9.x" refused Sodium, and Iris 1.12 went in alone. 0.3 staged both.
- Now (488edf2) `DownloadPlanner.checkVersions` runs after the plan loop over the folder the batch leaves behind:
  - core `VersionPins.check(jars)`: (a) every loaded mod's `depends`/`breaks` on a jar of the batch; a declaration of a jar
    the batch replaces (the mod itself, or a mod nested in it) goes with it, and a jar only fine because of that replacement
    relies on it; (b) every new jar's own `depends`/`breaks` (`ModJars.rangesOf`) on what will be present: another jar of
    the batch (a jar that's only fine with it relies on it), else the loaded mod unless its top-level jar is replaced. A
    target that won't be present, or isn't known, is left to Fabric and H1-A.
  - A group with a refused jar is dropped with its downloads; every recommendation in it gets a localised line (never a
    silent drop); repeated until nothing is refused (what relied on a dropped group is judged against the loaded version
    next time). Reliance pairs are then joined into one all-or-nothing group.
  - A pair of ticked items that can't go in together is refused together, each naming the other (the 2e precedent: RigTune
    can't know which one the player wanted): Install Nvidium (sodium "0.9.2") + Update Sodium 0.9.3.
- Client `client/probe/FabricPins.loaded()`: pins from `FabricLoader.getAllMods()` (nested mods too; recommends/suggests/
  conflicts ignored, they don't stop Fabric), matched with `ModDependency.matches(Version.parse(v))`; loaded versions
  (provides aliases with the provider's version); a matcher for raw ranges with `VersionPredicate.parse`. The range shown for
  an installed pin is read lazily from the declaring mod's own fabric.mod.json (`findPath`, capped), else Fabric's form.
  A version Fabric can't parse is fine for no depends.
- Keys (rigtune.download.*): `pinned`, `pinned_breaks` (an installed mod's), `pinned_ticked`, `pinned_breaks_ticked` (a ticked
  jar's, on the other side of a pair), `needs_version`, `breaks_version` (the jar's own, on an installed mod),
  `needs_version_ticked`, `breaks_version_ticked` (on another ticked jar). Text from a downloaded jar (id, version, ranges)
  goes through `VersionPins.shown` = `ModJars.sanitizeName` (P-L1).
- Tests: VersionPinsTest, FabricPinsTest (stand-in containers, Fabric's real predicates: Iris "0.9.x", Nvidium "0.9.2", arrays,
  breaks, nested, provides alias, unparseable), DownloadPlannerTest: the coordinator's three cases (Sodium 0.10 + Iris 1.12 in
  either tick order: both staged, one group; Sodium 0.10 alone with Iris 1.11.4 installed: refused naming Iris; Install
  Nvidium + Update Sodium past its pin: both refused, each naming the other, and Nvidium alone against the installed Sodium),
  Iris 1.12 alone, a chain (Sodium refused on Nvidium's pin, so Iris 1.12 is refused too), an addition's own `breaks`.
- Residuals: the dry run (Preview) knows no jar's version, so it checks no range and can list a download Apply then refuses
  (the refusal is in Apply's status line); ranges on an id the new jar provides or nests aren't matched; a target a new jar
  requires that won't be present at all is left to Fabric (H1-A covers Modrinth-level requirements); mods staged for
  disabling in pending.json, or disabled in the same Apply, still count as present (over-blocking only).

## H1-A: an update whose new version requires a project that isn't installed
- `DependencyResolver.missingRequirements(update, installedProjects)`: required projects of the new version that aren't
  installed and that the version it replaces didn't already require (the game started without those being known here: nested
  in another mod, or a jar Modrinth doesn't know). Version-only dependencies don't count.
- DownloadPlanner: a project an earlier item of this batch staged is joined; otherwise the update waits once: the ticked
  additions whose own project it needs are planned next (review M-1: the other additions still see the update, A-H1), then
  the update joins the group that staged it; a project only an addition's dependency brings makes it wait for all
  additions. Else refused before the download: `missing_dependency` ("its new version needs %s, which isn't installed"), or
  `missing_dependency_staged` when only an earlier Apply staged it (another all-or-nothing group: update after restarting).
- ids and errors keep the planner's order (updates first) whatever the processing order, since PreviewDownloads matches
  errors by position (PreviewDownloadsTest checks a waiting update and a failed addition keep their own reasons).
- Residuals: minimal fix (refuse, don't resolve the update's dependencies); a requirement the old version didn't have that is
  present in a form Modrinth's hashes don't show is refused (over-blocking); an update that waits for an addition's
  dependency is invisible to the additions planned while it waits (an addition relying on it is refused, safe).

## M7: an update's jar is the same mod
- `updateMod` refuses a downloaded jar whose fabric.mod.json id differs from the installed mod's unless it provides it
  (`JarInfo`: `provides` or a nested mod): `not_same_mod`; the download is deleted.

## M4: Apply before the Modrinth lookup finished (or after it failed)
- `DownloadPlanner.lookedUp(lookups, online)`: with Modrinth lookups on and the installed mods not looked up, `plan()` refuses
  every recommendation with `not_loaded` ("... wait a moment or press Rescan, then try again": a Rescan makes the lookup due
  again, OnlineLookupGate), fetching and asking nothing. With lookups off nothing waits (the existing "Modrinth is off" path).
- RealController passes `settings.modrinthAllowed()` and `data.data().online()`; Preview gets the same through the new
  `DownloadInputs.lookedUp` (old constructors = true) and `DryRunPlanner.plan(..., lookups, online)`.
- WS-H's e2e drivers apply once a report exists (not necessarily an online one); every recorded dev run under docs/smoke
  had `report ready (online=true)` by then, so they aren't expected to hit the refusal (UNVERIFIED until the next e2e run).

## Other review fixes
- L-2: a failed recommendation's downloads that nothing else stages are deleted (they used to be left as
  `*.jar.rigtune-pending` when a later dependency failed).

## RealController (hotspot) edits
- `download()`: `DownloadPlanner.topLevelIds(mods)`; `FabricPins.loaded()` as the planner's pins; `.lookedUp(settings.modrinthAllowed(),
  data.data().online())`. `preview()`: `topLevelIds(mods)`; `data.data().online()` as DownloadInputs' last argument.
