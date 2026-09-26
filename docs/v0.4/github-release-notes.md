RigTune 0.4.0 for Minecraft Java 26.2 and 26.3 (Fabric). Download the jar for your Minecraft version: `rigtune-0.4.0+mc26.2.jar` or `rigtune-0.4.0+mc26.3.jar` (the `-sources` jars are the source code). It needs Fabric Loader 0.19.5 or newer and Fabric API.

## Highlights

- **Tools…** on the RigTune screen opens the new tools. All of them work with the network off and send nothing anywhere.
- **Performance Profiles and share codes**: switch your video, Sodium, Distant Horizons and Iris settings between Max FPS, Balanced, Quality, Battery, Recording, your own settings and saved or imported profiles in one click. A switch is an ordinary Apply, so History shows it and Undo works. A share code (about 100 characters) holds only setting values, and importing one always shows a Preview first.
- **Stutter Doctor**: an opt-in session monitor (off by default) that shows where the time went when the game hitches (garbage collection, chunk loading and building, game ticks, or "not explained") and what may help.
- **JVM & memory**: the Java your game runs on, and notes on Java arguments that Java ignores or that only cost memory, with your launcher's steps. It never suggests adding garbage-collector flags; your Java arguments are never shown, logged or shared.
- **Benchmark history and regression alerts**: runs are compared only under the same conditions; a drop in 1% lows beyond the run-to-run noise is flagged, with the changes since then listed as "may be related".
- **Notices** for a server's view-distance limit, a GPU or driver change, new recommendations after a rules update, and a benchmark that needs a rerun.
- **Estimated tier** instead of "limited by"; **VSync off** is optional and says what it does; MultiMC and GDLauncher are recognised.
- **Keyboard and Narrator** support for every list row, and high-contrast colours.
- **Fixes that also affect 0.3.0**: clicking a row in History on 26.3; a second "Undo last" skipping a staged setting; updates now respect other mods' version requirements; a helper killed mid-update is finished or rolled back at the next run; undo keeps a mod and its library together; "Undo all" still reaches your original settings after a long history.

No file format changed: every file 0.1.0 to 0.3.0 wrote keeps working, and 0.3.0 reads what 0.4.0 writes.

**Full changelog**: [CHANGELOG.md](https://github.com/chaotix345/rigtune/blob/main/CHANGELOG.md#040---2026-09-27) · [compare v0.3.0...v0.4.0](https://github.com/chaotix345/rigtune/compare/v0.3.0...v0.4.0)
