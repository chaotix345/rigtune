# The Modrinth App check (AC4j.5)

- `CHECK.md`: the user's steps on a throwaway instance (approved by the coordinator, AC3g.1's review), put to the user
  at the RC; their answers and files are recorded here when they come back.
- **UNVERIFIED in the running app, from source only** (docs/research/v0.5/launcher-managed-mods.md §2, §7): the app's
  synced game options (App settings → Synced settings → Sync game options) and RigTune's "changed outside the game"
  notice after the app rewrites options.txt. It's an app-wide setting that could copy options into the user's real
  instance, so the check never asks for it.

## Result: the user's laptop, 2026-09-29 (RigTune 0.4.0 → 0.5.0 release jars)
Modrinth App 0.21.6 on Windows 11 (build 26200). Throwaway instance on Fabric 26.2: Fabric API 0.161.0, Mod Menu 20.0.0,
Sodium mc26.2-0.9.0 and Text Placeholder API (a dependency). Screenshots are kept locally, not in the repo, because the
app's window shows the player's account name.

**Worked:**
- RigTune 0.4.0 offered "Update Mod Menu" (20.0.3) and "Update Sodium" (0.9.2). After the exit its helper left
  `modmenu-20.0.0.jar.disabled` and `sodium-fabric-0.9.0+mc26.2.jar.disabled` next to the new jars.
- 0.4.0 already showed rules r17's launcher warning.
- Updating RigTune 0.4.0 → 0.5.0 from the app's own row worked.
- At 0.5's first start, the repair notice named exactly those two files, and Copy list copied them. The upgrade notice
  ("RigTune now leaves this instance's mod files to the Modrinth App…") showed too.
- Mod suggestions are advice with the app's steps. Apply held only settings.
- 0.5 left `mods/` alone: the file list and timestamps were identical before and after a session.
- After a working repair (below), the repair notice retired at the next start.

**Diverged:** the app's current UI and error behaviour differ from what the research read in the source.
- **The repair steps can't be followed.**
  - Content has no Disabled filter; its filters are Author, Environment, Update available and Open source.
  - The `.disabled` copies aren't listed in Content at all.
  - The Files tab greys out Delete for them ("Manage your installed content via the Content tab").
- **The notice's other route fails too.**
  - Deleting RigTune's new copy in Content leaves no row for that mod, so there's no old copy to update and switch on.
  - "If an old copy isn't listed … nothing to do" is wrong: the leftovers still block updates.
- **What the app did:**
  - Update all fails with "Couldn't prepare instance" / "Invalid input: Restore or repair mods/<old>.jar before updating
    this instance; its current content cannot be backed up safely".
  - One leftover blocks every bulk update of the instance, even for other mods.
  - Switching Sodium's version to 0.9.0 gives "Invalid input: The updated filename belongs to another content item",
    the original 0.1.0-era error.
  - The app's log repeats "Ignoring content at the inactive form of a managed path … mods/<old>.jar.disabled".
- **The repair that worked (per mod):** delete RigTune's new copy in Content, rename `<old>.jar.disabled` to `<old>.jar`
  in File Explorer, press Refresh in Content, then Update all. Both mods updated with no error.
- Issues filed for each divergence; see the "field check 2026-09-29" issues on GitHub.

