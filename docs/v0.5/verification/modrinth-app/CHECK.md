# RigTune 0.5 and the Modrinth App: a check in the real app (about 20 minutes)

RigTune 0.5 stops changing mod files in instances the Modrinth App manages, because the app keeps its own list of
mods (see docs/research/v0.5/launcher-managed-mods.md §7). We've only read the app's source code, so this check
confirms in the running app that:
- the problem is real;
- RigTune 0.5's repair steps fix it;
- 0.5 leaves the mods folder alone.

Everything happens in a **new throwaway instance** that you delete at the end. Please don't use or change your usual
instance. Skipping a step is fine; just tell us which.

**You need:**
- RigTune **0.4.0** for 26.2: `rigtune-0.4.0+mc26.2.jar` from https://github.com/chaotix345/rigtune/releases/tag/v0.4.0;
- the RigTune 0.5 test file `{{RC_JAR_26_2}}` ({{RC_JAR_26_2_URL}}).

**Screenshots:** in the game, press **F2**. In the app, press **Win+Shift+S** and save each picture to a folder on the
Desktop. Take one wherever you see **[shot]**.

## 1. A throwaway instance with two older mods
1. In the Modrinth App, create an instance named `rigtune-test`: loader **Fabric**, game version **26.2**.
2. In **Content → Browse content**, install **Fabric API**, **Mod Menu** and one more mod you like, for example Sodium.
3. For Mod Menu and the other mod, pick an **older version**, not the newest, so there's an update to find.

## 2. Let RigTune 0.4.0 update them
1. **Content → Upload files**, and pick `rigtune-0.4.0+mc26.2.jar`.
2. Press **Play**, then open RigTune (the **RigTune** button on the title screen).
3. Tick only the **Update …** rows for Mod Menu and your other mod, and press **Apply**.
4. Quit the game with **Quit Game**. RigTune 0.4 swaps the files after the game closes.

## 3. Update all in the app
1. Back in the app, open the instance's **Content** and press **Update all**.
2. What we expect: it fails, with a message like **"The updated filename belongs to another content item"**. **[shot]**
3. Please write down the exact message you see.

## 4. Switch to RigTune 0.5
1. Before you start the game, open the instance folder (**Open folder**, then `mods`) and take a picture of the file
   list. **[shot]**
2. In **Content**, delete RigTune 0.4.0 and use **Upload files** to add the 0.5 test file.
3. Press **Play** and open RigTune. What we expect:
   - a notice **"Mod changes from an older RigTune: help the Modrinth App catch up"**. Its details say to do this *in
     the app*: open this instance → Content → filter Disabled → select the old copy → Delete, with the file names.
     **[shot]** of the notice and its details;
   - no mod row you can tick and apply: mod suggestions read "…managed by the Modrinth App: update it there" (or
     install / turn it off there). **[shot]**
4. Quit the game.
5. Look at the `mods` folder again: the file list should match the picture from 4.1. **[shot]**

## 5. Follow the steps, then Update all again
1. In the app: **Content**, filter **Disabled**, select each old copy the notice named, and **Delete**.
2. Press **Update all** again. What we expect: it works now. **[shot]**

## 6. Optional: the app's "Sync game options"
**Careful:** this is an app-wide setting. While it's on, the app may copy game options between your instances,
including your usual one. Please skip this step unless you're happy with that.
1. **App settings → Synced settings → Sync game options**: turn it on.
2. **Play** `rigtune-test`, apply one game setting in RigTune (for example render distance), then quit.
3. Close the Modrinth App completely, open it again, and **Play** `rigtune-test`.
4. What we expect: RigTune says **"A setting was changed outside the game since you last played (…)"**, and its
   details mention the Modrinth App's sync. **[shot]**
5. Turn **Sync game options** off again.

## 7. Send the files, then delete the instance
1. In the instance folder, select **`logs`**, **`config`** and **`screenshots`**.
2. Right-click the selection and choose **Compress to ZIP file**. Name it `rigtune-modrinth-check.zip` and put it on
   your **Desktop**, next to your app screenshots.
3. Delete the `rigtune-test` instance in the Modrinth App.

## What to tell us
For each step: what you saw (the exact messages in steps 3 and 6), whether it matched "what we expect", and
anything that was confusing. Send the ZIP and the screenshots over however is easiest.
