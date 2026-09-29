# Launchers that manage your mods

[← Back to the README](../../README.md)

Some launchers keep their own list of an instance's mods: the Modrinth App, the CurseForge app, ATLauncher and GDLauncher, and Prism Launcher or PolyMC once they've installed mods for that instance (packwiz metadata in `mods/.index/`). When another program renames or adds mod jars, that list goes out of date: in the Modrinth App, **Update** and **Update all** can then fail with "The updated filename belongs to another content item"; in Prism, its own update can delete files RigTune kept for Undo.

Since 0.5, RigTune leaves mod files to these launchers:
- mod installs, updates and disables (and RigTune's own update) are advice, with the launcher's own click steps under each; settings still apply in one click;
- Undo leaves mod files an older RigTune changed to the launcher, with its steps;
- mod changes an earlier Apply staged wait for your choice: **Cancel them**, or **Let RigTune apply them** at the next exit;
- **Settings → Mod files → Let RigTune change them anyway** brings the old behaviour back for that instance.

The official Minecraft Launcher, MultiMC, and Prism or PolyMC without `mods/.index/` work as before: RigTune changes the mod files itself.

**If an older RigTune (0.1-0.4) already changed your mods** and the Modrinth App's Update all fails with "Couldn't prepare instance" ("Restore or repair mods/<old>.jar before updating this instance…"; older app versions: "The updated filename belongs to another content item"), then with the game closed, for each old copy:
- If it's listed in the instance's **Content**, switched off (the **State** filter's **Disabled** shows them), delete it there. RigTune's newer version stays.
- If it isn't listed (a mod the app installed since 0.21 drops out of Content once renamed), it still blocks Update all: delete RigTune's newer copy of that mod in **Content**, rename `<old>.jar.disabled` back to `<old>.jar` in the instance's `mods` folder, press **Refresh** in Content, then **Update** it.

Checked in the Modrinth App 0.21.6. Mods that update themselves (such as Distant Horizons' own updater) can put the app's list out of date the same way.

RigTune 0.5 lists the old copies for you in a notice ("Fix <launcher>'s mod list", with **Copy steps**, which copies the steps and the file names), worked out from RigTune's own records only, with your launcher's steps: in Prism, **Edit...** → **Mods** → select the old disabled copy → **Remove**, before any **Check for Updates**; in GDLauncher, **Mods** → the old disabled copy → **Delete**; in the CurseForge app, remove those mods from the profile and install them again there. ATLauncher doesn't list the mods RigTune disabled; rename one from `.jar.disabled` to `.jar` to switch it back on. RigTune never renames, moves or deletes the old copies itself. RigTune 0.1-0.4 show a warning with these steps too (from rules revision 17): untick RigTune's Install, Update and Disable items and make those changes in the launcher.

**The Modrinth App's game-settings sync** can overwrite `options.txt` before a launch and copy it to your other synced instances. RigTune notices when settings it applied were changed outside the game since you last played, and offers to apply its values again (one History entry you can undo) or keep them; it never reverts anything by itself. With the sync on, RigTune's changes are copied to your other synced instances too. To stop the sync: App settings → Synced settings → Sync game options, or the instance → Instance settings → Sync overrides → Unsync game settings.

The launchers' button names come from their source code and locale files (CurseForge's from its help pages), not from the running apps; the Modrinth App's steps above were checked in the running app (0.21.6).
