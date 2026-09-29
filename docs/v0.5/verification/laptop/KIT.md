# RigTune 0.5 test on your laptop (about 15 minutes)

Thanks for doing this! It checks what our CI machines can't: your laptop's real battery, its Intel Arc graphics, and
its 120 Hz screen. You make a **new test instance** in the Modrinth App for it. Your usual instance isn't used or
changed at any point, so please leave it alone while you do this.

Skipping a step is fine. Just tell us which ones, and we'll mark them as not tested.

**You need:** the laptop with its charger, the Modrinth App, and the RigTune test file for Minecraft 26.2:
`{{RC_JAR_26_2}}` ({{RC_JAR_26_2_URL}}). The 26.3 file is only for the optional step 7: `{{RC_JAR_26_3}}`
({{RC_JAR_26_3_URL}}).

**Screenshots:** in the game, press **F2**. Each press saves a picture into the test instance, and you'll send them
all back at the end, so there's nothing to name or sort. Take one at every **[F2]** below.

## 1. Make the test instance (3 min)
1. In the Modrinth App, create a new instance: name it `rigtune-test-26.2`, loader **Fabric**, game version **26.2**.
2. Open it, go to **Content**, then **Browse content**, and install **Fabric API**.
3. Back in **Content**, choose **Upload files** and pick the RigTune test file from above.
4. That's all: no other mods, and nothing copied from your usual instance.
5. Keep the charger plugged in for now.

## 2. First start (3 min)
1. Press **Play**. On the title screen, click **RigTune** (next to Options…), or press **F8** at any time.
2. What you should see at the top of the RigTune screen:
   - your processor (Intel Core Ultra 5 225H or Ultra 7 255H) with its number of threads (14 or 16); there's no
     separate "CPU tier" line;
   - **Intel Arc** graphics with a "GPU tier" line;
   - an "Estimated tier" line. **[F2]**
3. A notice near the top says **"New? History… lets you undo each Apply."** Press **How it works**: a page called
   **How Apply works** opens. **[F2]** Go back and press **Got it**.
4. Scroll the list to the frame-rate limit row (Minecraft calls it Max Framerate). With the charger in, it should
   suggest **110**. **[F2]**

## 3. One Apply (1 min)
1. Leave the ticked items as they are and press **Apply**.
2. A screen called **Your first Apply** lists what changed ("In effect now" and "At the next restart"). **[F2]**
3. Close it. Mod suggestions show as steps for the Modrinth App instead of ticking boxes; that's expected.

## 4. One benchmark (3 min)
1. Go back to the title screen. Click **RigTune**, then **Tools…**, then **Benchmark…**.
2. Set **Scene** to the RigTune benchmark world and press **Measure before**.
3. It runs by itself in a fixed world for about a minute: please don't touch the mouse or keyboard (**Esc** cancels).
4. A **Benchmark results** screen shows a "Result: avg … FPS · 1% low … FPS" line and a few lines under it. **[F2]**

## 5. Battery (4 min)
1. Open the RigTune screen (**F8**) and **unplug the charger**.
2. Within about a minute, a **Power source changed** message pops up, and RigTune shows **"You're on battery power.
   Switch to the Battery profile to make it last longer?"** **[F2]**
3. Press **Switch to Battery**. RigTune says **"Switched to Battery…"**, and **Tools… → Profiles…** shows Battery as
   **Active**. **[F2]**
4. **Plug the charger back in.** Within about a minute: **"You're plugged in again. Switch back to My settings?"** **[F2]**
5. Press **Switch back**.
6. If the minute passes with nothing, write down roughly how long you waited. That's useful too.

## 6. Restart (1 min)
1. Quit the game (**Quit Game**) and press **Play** again. Open RigTune.
2. What you should see:
   - the "New?" notice from step 2 and the **Your first Apply** screen don't come back;
   - a short message about changes that waited for the restart is fine. **[F2]**
3. Quit the game.

## 7. Optional: Minecraft 26.3 with Vulkan (4 min)
Only if you have time. This records what your graphics driver reports under Vulkan.
1. Make a second test instance, `rigtune-test-26.3`, the same way as step 1: Fabric, 26.3, Fabric API and the 26.3
   test file.
2. Press **Play**.
3. In **Options… → Video Settings…**, turn on **Prefer Vulkan (Experimental)**.
4. Quit and press **Play** again. Open RigTune once. **[F2]** Quit.
5. If 26.3 has no such option, or crashes on start, skip this step and just tell us.

## 8. Send the files back (2 min)
For each test instance:
1. In the Modrinth App, open the instance and choose **Open folder** (on the instance page).
2. In the folder that opens, select **`logs`**, **`config`** and **`screenshots`** (and **`crash-reports`** if it's
   there).
3. Right-click the selection and choose **Compress to ZIP file**.
4. Name the ZIP `rigtune-test-26.2.zip` (or `-26.3`) and move it to your **Desktop**.
5. Send the ZIP over however is easiest (USB stick, OneDrive…). The logs show your Windows user name in file paths
   and your Minecraft name; nothing else personal.

After that you can delete the test instances in the Modrinth App.

## What to tell us
- Which steps you did and which you skipped.
- Anything that looked wrong, confusing or slow.
- A rough time for the battery messages in step 5, if you noticed it.
- Screenshots can simply be sent in chat.
