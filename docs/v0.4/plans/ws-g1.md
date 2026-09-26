# WS-G1 plan: resolver hardening (SPEC 2o: H2, H3, H1-A, M4, M7)

Branch `fix/resolver-audit` from origin/feat/v0.4.0 @ 0132341 (WS-A's 2d/2e merged). Sources: docs/research/v0.4/audit-apply-pipeline.md
(H1-H3, M4, M7), docs/v0.4/audit-verification.md (WP-1), docs/v0.4/design/ws-a.md. Every task: a test that fails first, then the
fix, then `./gradlew build` (both versions), commit, push.

## Task 1: H3, nested libraries never count as installed for the drop/keep decision
- Test (red): `DownloadPlannerTest.aLibraryPresentOnlyNestedIsStagedTopLevel`: a DH-like scan (distanthorizons top-level with
  sha1, fabric-api nested: file and sha1 null) -> `DownloadPlanner.topLevelIds(scan)` = {distanthorizons}; an addition requiring
  fabric-api planned with those ids stages `[aV.jar, fabric-apiV.jar]` in one group (today the library is dropped).
- Fix: `DownloadPlanner.topLevelIds(List<InstalledMod>)` = ids of mods with `sha1 != null || file != null` (InstalledMod's
  contract: sha1 is null only for built-in and nested mods; file covers a mods/ jar whose hash failed). RealController
  `download()`/`preview()`: the four-line loadedIds loop becomes that call.

## Task 2: H2, an update honours installed mods' fabric.mod.json pins on it
- Core `core/modrinth/VersionPins` (pure): `Pin(by, byName, top, target, targetName, kind DEPENDS|BREAKS, declared supplier,
  satisfiedBy predicate)`, `problem(modId, version)` -> the refusal Text or null. Pins declared by the replaced jar itself
  (top == modId: the mod or anything nested in it) don't count.
- `ModJars.versionOf(jar)` (fabric.mod.json `version`, null like nameOf).
- DownloadPlanner: after the download's mod id is read, `pins.problem(jarModId, versionOf(pending))` refuses (the download is
  dropped) for updates and for additions (an installed mod's `breaks`, or a pin on a library present only nested).
- Client `client/probe/FabricPins.loaded()`: pins from `FabricLoader.getAllMods()`, matched with Fabric's own
  `ModDependency.matches(Version.parse(v))`; the declared range text read lazily from the declaring mod's fabric.mod.json.
- Tests: VersionPinsTest (Iris `sodium: 0.9.x` vs 0.9.3 / 0.10.0, Nvidium `0.9.2`, breaks, own/nested pins skipped, unknown
  version = no refusal), DownloadPlannerTest (update refused naming Iris; unpinned staged; addition broken by an installed mod),
  FabricPinsTest (fake containers, real Fabric predicates; nested pin's top; raw declared text).
- Lang: `rigtune.download.pinned`, `rigtune.download.pinned_breaks`.

## Task 3: H1-A, an update whose new version requires a project that isn't installed is refused
- Test (red): DownloadPlannerTest: update M whose new version `required("LIB")`, LIB not installed -> refused naming LIB, nothing
  fetched; LIB installed -> staged; the replaced version already required LIB (satisfied by a nested copy) -> staged; LIB added in
  the same batch -> the update joins the addition's group.
- Fix: `DependencyResolver.missingRequirements(update, installedProjects)`; DownloadPlanner defers such an update after the
  batch's additions once, then joins the group that staged the project or refuses (`rigtune.download.missing_dependency`).
  Errors stay in the planner's order (PreviewDownloads matches them by position).

## Task 4: M7, an update's jar is the same mod
- Test (red): update of mod m whose downloaded jar declares id "other" -> refused, download dropped; a jar that provides "m"
  (JarInfo: `provides` or a nested jar) -> staged.
- Fix: in `updateMod`, `jarModId.equals(update.modId())` or `JarInfo.read(pending).provides()` contains it.
  Lang: `rigtune.download.not_same_mod`.

## Task 5: M4, Apply before the Modrinth lookup finished (or after it failed)
- Test (red): `DownloadPlanner.installedLookedUp(false)` refuses every Add/Update with `rigtune.download.not_loaded`, fetching
  and asking Modrinth nothing; PreviewDownloadsTest: DownloadInputs with lookups on and `lookedUp = false` -> DOWNLOAD_FAILED with
  that reason.
- Fix: the planner flag; `DownloadInputs.lookedUp` (old constructors = true); RealController passes
  `data.data().online() || !settings.modrinthAllowed()` (one call each in download() and preview()).

## Finish
- Merge origin/feat/v0.4.0, `./gradlew build` (26.2 + 26.3), push, CI green, code-reviewer self-review,
  docs/v0.4/design/ws-g1.md. pending.json content is unchanged (no new field or op), so compat030 needs no re-run unless that
  changes.
