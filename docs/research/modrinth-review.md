# Modrinth review: what to fix on 10 Dec

Research for Charlie, 26 Nov 2026. Project `oBN6pcGa` (slug `rigtune`) has been "Under review" since about 25 Sep, with no moderator messages as of 11 Nov. The public API returns 404 until approval, so this works from the repo (docs/modrinth/, tools/modrinth_project.py, release.yml, the source) and Modrinth's public rules. Nothing here was changed on Modrinth.

## The 15-minute list

Do these in order while logged in. Each is explained below.

1. **Settings → Disclosures** (exact page name unconfirmed): turn on **External system interactions**.
2. Same page: turn on **Contains AI-generated content**, ticking AI-generated code and AI-generated text.
3. Same page: turn on **Contains telemetry**, consent model **opt-out**. Then change the body's "It doesn't send telemetry." line (item 3).
4. **Versions**: open 0.5.0, 0.5.1 and 0.5.2 (both MC versions each) and check that each shows client-side required, server-side unsupported. If not, fix it (item 4).
5. **Description**: paste the edits under "Listing copy (player view)": the helper, the network timing, consent for downloads, and the launcher note moved up.
6. **Summary**: replace it with the line in item 6.
7. **Gallery**: make "RigTune report (0.4)" the featured image and edit three alt texts (Listing copy, Gallery).
8. **Icon**: if you don't know how the icon was made, say so in the AI disclosure or replace it (item 8).
9. Don't delete any version, now or when 0.6.0 drops 26.2.
10. Optionally, post the note at the end in the Moderation tab, once, after the changes.

## Why it's taking this long

Modrinth's [Project Review Times](https://support.modrinth.com/en/articles/8793355-modrinth-project-review-and-approval-times) article (7 Oct 2026) aims for "24 to 48 hours" but says waits average "two to four weeks, sometimes longer", while new moderators are trained. Two months is long but within what it describes. Two lines from it matter here:

- "Our malware detector might have flagged your project", which "means it needs to have its code manually reviewed by our technical moderator. This may extend your project's review time significantly." RigTune does what a scanner looks for. It starts a second Java process (`HelperLauncher.java:81`, `new ProcessBuilder(...)`), downloads jars over HTTP (`HttpModrinthClient.java`) and renames jars in `mods/`. A technical review is the likeliest reason for the extra wait. We can't confirm it without a moderator saying so.
- "If a project displays as 'Under review', it will be reviewed", and "No messages in a project's Moderation tab simply means that there's no update." Editing doesn't cost queue position, but "Deleting old versions then uploading new versions might send the project to the end of the queue."

Nothing in the repo shows anything blocking the review itself. The items below are what most likely gets the project **rejected or sent back** once a moderator opens it. A rejection costs more than the wait so far, because the same article says a reviewed project goes "to the end of the queue upon re-submission".

## Ranked fix list

Sources: [Content Rules](https://modrinth.com/legal/rules) (last modified 13 Aug 2026), [Content Disclosures](https://support.modrinth.com/en/articles/16567675-content-disclosures) (22 Oct 2026), [Disclosure and Usage of AI](https://support.modrinth.com/en/articles/16551575-disclosure-and-usage-of-ai) (24 Aug 2026), and Modrinth's moderator checklist in its public source, [modrinth/code `packages/moderation`](https://github.com/modrinth/code/tree/main/packages/moderation) (`src/data/issues/disclosures.ts`, `rules.ts`, `tech-review.ts`, `description.ts`).

The disclosure rules postdate our 25 Sep submission (AI rules 13 Aug, disclosures article 22 Oct), and tools/modrinth_project.py never sets disclosures. Unless Charlie set them by hand, none are set.

### 1. External system interactions disclosure (likely rejection)

- **Rule.** Content Disclosures: "If a project interacts with the user's system outside of the context of the game, especially in unpredictable or unanticipated ways, this must be disclosed." Rule 5.9: all applicable disclosures are filled out and kept current. In the moderator checklist (`disclosures.ts`), a missing system-interactions disclosure is marked as a suggested **rejection**, not just a flag.
- **What RigTune does.** When Minecraft closes with changes staged, it starts a separate Java process from a copy of its own jar in `config/rigtune/helper/` (`RigTuneClient.java:170-192`, on `CLIENT_STOPPING`). That process waits up to 15 minutes for the game to exit (`ApplyHelper.java:14`), then renames, downloads and disables mod jars in `mods/` and writes Sodium, Distant Horizons and Iris config files. RigTune also reads launcher files outside the game folder's own config (Prism's `instance.cfg`, CurseForge's `minecraftinstance.json`, `mods/.index/`; docs/guide/privacy.md).
- **Change.** Turn the disclosure on. If it has a free-text field: "When Minecraft closes after you press Apply, RigTune starts a separate Java process (a copy of its own jar, in config/rigtune/helper/) that waits for the game to exit, then applies the mod changes you ticked: downloads from Modrinth's CDN (SHA-512 checked), renames to .jar.disabled, and Sodium/Distant Horizons/Iris config edits. It then exits; its log is config/rigtune/helper.log. Nothing is deleted, and History undoes every change."

### 2. AI-generated content disclosure (flag; affects public listing)

- **Rule.** Content Rules 6.1: the "Contains AI-generated content" disclosure is required when "a substantial portion of the project's code is a product of AI output", and when "any element of the project's page such as description or publishing relies on generative AI". 6.2: "Projects may not be published publicly if the contents are primarily or entirely a product of AI output." The AI article defines that as "created entirely or almost entirely through the use of generative AI with little-to-no human input". Such projects "can still be made unlisted, but will be prohibited from appearing in public search results."
- **What the repo shows.** The public repo records that AI agents wrote the code and the description, in the checked-in CLAUDE.md and `.claude/`, docs/PROGRESS.md's agent logs, and `Co-Authored-By: Claude` trailers on earlier commits (for example the icon commit e4235c03, 24 Sep). The description rewrite (PR #65) was a worker's. A moderator who opens the source link sees this in seconds. Leaving the disclosure off would also break rule 5.1, which wants metadata "consistent with information found elsewhere".
- **Change.** Turn on "Contains AI-generated content" with **AI-generated code** and **AI-generated text** (description, translations). Don't tick "AI functionality": RigTune doesn't call any AI service.
- **The real risk, said plainly.** 6.2 can keep RigTune out of public search even after approval. Whether it applies turns on "little-to-no human input". Charlie set the scope, made the product calls, tested on his own hardware (the 0.5.2 desktop check, the Modrinth App field checks behind #17-#25) and approves every release. That is human input, but how much counts is the moderators' call. If the disclosure has a note field, add one sentence saying so. The worst case is an unlisted project, which people can still open through a direct link. Whether launchers' update checks still find an unlisted project's versions is unverified.

### 3. Telemetry disclosure, and the "doesn't send telemetry" line (likely rejection if missing)

- **Rule.** Content Disclosures: "If a project collects usage data, or sends data to third parties, this behavior must be disclosed", with consent models opt-in, opt-out or always. Content Rules 1.11 forbids being "designed to upload any data to a remote server" the user doesn't choose to connect to "without clear disclosure". The checklist marks a missing telemetry disclosure as a suggested rejection.
- **What RigTune sends.** With the network switches on, which is the default (`ClientSettings.java:24-28`), RigTune contacts two servers in the background at every game start (`RealController.start` reloads the rules, rescans the mods, then runs the Modrinth lookup). It sends the SHA-1 hashes of your mod jars, the ids of mods it might suggest, your Minecraft version and `fabric` to `api.modrinth.com`, and makes a plain download from `raw.githubusercontent.com`. None of it is analytics, but it is data about your installed mods, sent by default. A moderator could read that as telemetry, and the disclosure costs nothing.
- **Change.** Turn on "Contains telemetry", consent **opt-out**, note: "No usage statistics or analytics. With the network switches on (default), RigTune sends your mod jars' SHA-1 hashes and Minecraft version to the Modrinth API to find updates, and downloads its rules file from GitHub. Each can be turned off in RigTune → Settings."
- **And in the description** (rule 5.1, consistency), replace "It doesn't send telemetry." under "What it doesn't do" with: "It collects no usage statistics or analytics. Its only network requests are the ones listed under Network use."

### 4. Version environment on 0.5.0-0.5.2 (possible rejection; check first)

- **Why.** tools/modrinth_project.py says a version uploaded through the v2 API after the project was created has no v3 environment, and "review refuses a missing environment" (`cmd_submit`). `cmd_sides` exists to fix that and has to run after every release with the setup token, which release.yml's token can't do (release.yml comments). PROGRESS.md records `submit` (which re-applies sides) for 0.2.0-0.4.0, and "no re-submit" for 0.5.0. Nothing records `sides` after 0.5.0, 0.5.1 or 0.5.2, and the setup token is no longer on this PC (STATUS.md "Secrets").
- **Rule.** 5.1: environment information is "filled out correctly". The checklist has a metadata stage.
- **Change.** Open each of the six 0.5.x versions. If any lacks client/server info, set it to **client required, server unsupported** (the project values; `fabric.mod.json` has `"environment": "client"`). If the version page has no such field, re-save the project's own environment setting. Whether the web form does what `sides` does is unverified.

### 5. Downloads and updates: say that each needs consent (possible rejection in tech review)

- **Rule.** The tech-review stage's "Unsafe functionality" issue (a rejection) lists as a required change: "Ensure that all file downloads, including project updates, require informed consent from the user before being initiated." "Unclear or misleading functionality" is also a rejection there, and rule 2 requires a "clear and honest" description.
- **What RigTune does.** It complies. Nothing downloads until the player ticks an item and presses Apply, every download is SHA-512 checked, and RigTune's own update is an ordinary recommendation (privacy.md says update lookups include "RigTune's own"). The description doesn't say that RigTune can update itself, or what the "small helper" is.
- **Change.** The description edits under "Listing copy" (first paragraph, the Applies bullet, the updates bullet). They spell out consent, self-update and the helper in the description, where the technical reviewer reads first.

### 6. Summary (minor)

- **Rule.** 5.3: summaries are short, unformatted, and don't repeat the title. Ours doesn't repeat it, so it passes. It says RigTune "applies recommended settings and mod suggestions" with no mention of consent, which fits item 5 poorly.
- **Current** (from `DEFAULT_SUMMARY`, unless edited by hand since): "Hardware-aware performance tuning for Fabric. Detects your CPU/GPU/RAM and applies recommended settings and mod suggestions."
- **New:** "Reads your PC's hardware and mods, then recommends performance mods and settings with reasons. Changes only what you tick and apply, and can undo every change."

### 7. Gallery (minor)

- **Rule.** 5.5: gallery images "do not give a false impression of the project's contents". The featured image is the 0.1-era `report.png`, whose footer predates the 0.3 buttons (docs/modrinth/gallery.md says so itself). It's outdated rather than false, but it's the first image people see. Wording is under "Listing copy, Gallery".

### 8. Icon (check)

- **Rule.** 6.2: no icon or gallery image "may be created or derived from generative AI output", and the AI article counts logos as assets to disclose.
- **What we know.** The icon (a gauge with three bars, 256×256) was committed in e4235c03 with a Claude co-author trailer. The repo doesn't say how it was drawn (code or an image model). The gallery images are in-game screenshots, which are fine.
- **Change.** If Charlie knows it was drawn by code, leave it and tick "AI-generated assets" only if the disclosure asks about AI-assisted logos too (the article says it covers "AI-generated or AI-assisted assets such as textures, models, or logos", so ticking it is the safe reading). If nobody knows, replacing it with a simple icon Charlie makes himself removes the question.

### 9. Don't delete versions

Deleting old versions mid-review "might send the project to the end of the queue" (Review Times article). When 0.6.0 drops 26.2, keep 0.5.2+mc26.2 up. STATUS.md already plans this.

## What we can't know without logging in

- Whether any disclosure, the summary or the description was edited by hand since the tools last ran.
- Whether the malware scanner flagged the project; Modrinth doesn't show it ("be completely unnoticed by you").
- Whether the 0.5.x versions show an environment (item 4).
- The exact name of the disclosures page in project settings. The moderator code calls the panel "Disclosures", and the support article doesn't say where authors set it.
- Whether anything in the Moderation tab changed after 11 Nov.

## Listing copy (player view)

HQ passed on a Gemini Neon read of docs/modrinth/body.md and gallery.md as a player. Each point was checked against the code. The ones kept are below with exact wording, in docs/modrinth/body.md's line order. Charlie pastes them in on Modrinth. docs/modrinth/ itself is unchanged here; a follow-up PR can copy the final text into body.md so `sync-body` doesn't revert it.

### First paragraph (body.md:1)

Neon's proposed line drops the fact that Apply can download mods, which is the part item 5 needs, so this version keeps it:

> RigTune reads your PC's hardware and your installed mods, then recommends performance mods, mod settings and video settings that suit them, each with a plain-English reason. Nothing changes until you tick it and press **Apply**. Apply can download, update or disable mods as well as change settings, and History can undo every change. An in-game benchmark then tunes your render distance to your monitor.

### Launcher note, moved up (after body.md:3)

Accurate. In launcher-managed instances, mod installs, updates and disables are advice (body.md:42; `HelperLauncher.holds`). It's currently buried under Compatibility. Add as a new paragraph after "Client-side only…":

> In the Modrinth App, the CurseForge app and other launchers that keep their own mod list, RigTune leaves mod files to the launcher and shows mod installs, updates and disables as steps to do there. Settings still apply in one click.

### Hardware bullet (body.md:7)

> - **Reads your hardware**: CPU, RAM and the memory given to Minecraft, GPU and driver, OpenGL or Vulkan, your monitor's resolution and refresh rate, and whether a laptop is on battery. It uses these to estimate a hardware tier from 1 to 5 and shows what the estimate rests on.

### Updates bullet (body.md:12, a sub-bullet, so keep its two-space indent)

```
  - updates for mods you have, RigTune included;
```

### Applies bullet, with the helper explained (body.md:15)

Neon's point is accurate, since "a small helper" is unexplained. In the code, the helper is a separate Java process, started only when Minecraft closes with changes staged (`RigTuneClient.java:95`, `:170-192`), from a copy of RigTune's jar in `config/rigtune/helper/`. It waits for the game to exit (`ApplyHelper.java:73-90`), applies the staged changes, and exits.

> - **Applies only what you tick**: **Preview** shows exactly what Apply would change. Video settings change at once. Windows keeps a running game's mod files locked, so mod downloads, updates and disables, and Sodium, Distant Horizons or Iris config changes, wait until you close Minecraft. RigTune then starts a separate Java process (a copy of its own jar, in `config/rigtune/helper/`) that waits for the game to exit, applies those changes, and exits; its log is `config/rigtune/helper.log`. Nothing is deleted (a disabled mod becomes `.jar.disabled`), and every download is checked against Modrinth's SHA-512 hash.

### "Stays current" bullet (body.md:22)

Neon says the remote rules "drive mod downloads". That's partly true. The rules decide what's recommended, but nothing downloads without a tick and Apply. One added sentence makes that clear.

> - **Stays current without updating the mod**: the recommendations come from a rules file in the GitHub repository, rebuilt weekly from Modrinth data and the Fabulously Optimized and Additive mod lists, and reviewed before it goes live. A rules update changes only what RigTune suggests; nothing is installed until you press Apply.

### FPS bullet (body.md:27)

> - It doesn't promise FPS. Its settings are table estimates for your hardware tier; the benchmark, or Try it, measures what a change does on your PC.

### Telemetry bullet (body.md:29)

See item 3:

> - It collects no usage statistics or analytics. Its only network requests are the ones listed under Network use.

### Older-RigTune note (body.md:43)

Neon says this may erode trust. It also helps the 0.1-0.4 users it's for, and the full steps are in docs/guide/launchers.md:15-17, so shorten it to a pointer rather than cutting it:

> - **Coming from RigTune 0.1-0.4 in the Modrinth App**: if Update all fails, see the [launchers guide](https://github.com/chaotix345/rigtune/blob/main/docs/guide/launchers.md) for the fix.

### Network use (body.md:45-50)

Accurate. The section doesn't say the switches are on by default or when requests run. All three switches default to on (`ClientSettings.java:24-28`), and at each game start the rules download and the Modrinth lookup run in the background (`RealController.java:224-262`). Replace the lead line (body.md:47) with:

> RigTune makes two kinds of request. Both are on by default and run in the background when the game starts, and again when you change RigTune's settings. Each has its own switch in **RigTune → Settings**, under a master **Network access** switch:

Replace the Modrinth bullet (body.md:50) with:

> - **Modrinth**: sends the SHA-1 hashes of your mod jars (to find updates), the ids of mods it might suggest, and your Minecraft version, through the official Modrinth API. When you press Preview or Apply it also looks up the mods you ticked. It downloads only the mods you choose to install or update, from Modrinth's CDN.

### Gallery

All accurate against docs/modrinth/gallery.md. Make these in Modrinth's gallery editor:

- **Featured**: unfeature "RigTune report" (`report.png`, 0.1-era, gallery.md:9-12) and feature "RigTune report (0.4)".
- "RigTune report (0.4)" description (gallery.md:46), cutting "on the test PC": "The RigTune screen in 0.4: the hardware header with the estimated tier and its lowest estimated components, recommendations with plain-English reasons and impact ratings, and the footer with Apply, Preview, History, Tools, Rescan, Copy report and Report a problem."
- "Stutter Doctor" description (gallery.md:61): "The Stutter Doctor report from a deliberately stressed test session: session length, frames, average FPS and 1% low, the spike count by severity, and a frame-time histogram, with Stop, Pause, Clear and Copy summary buttons."
- "JVM and memory advice" description (gallery.md:66), cutting "for a test launch": "The JVM and memory screen: the Java version, the garbage collector set in the Java arguments and the heap size, notes on two Java arguments (one Java ignores, one from Paper's server flags), and advice to remove the ignored flag."

## Should Charlie send a message?

Not about timing. The 11 Nov decision stands, and the Review Times article says status questions "slow us down". One note is worth posting **after** items 1-5, because it isn't a status request. It sits in the Moderation tab for whoever opens the project and answers what a technical reviewer would otherwise have to ask, saving a "Source code requested" or "Unclear functionality" round trip. A round trip sends the project to the back of the queue. It won't notify anyone and won't speed anything up. If Charlie would rather post nothing, the disclosures and description changes do the same job.

Draft (100 words):

> Hi, a note for whoever reviews RigTune. Since submitting I've added the AI-generated content, external system interactions and telemetry disclosures, and expanded the description. In short: RigTune only changes mods after the player ticks them and presses Apply. Mod file changes are applied after Minecraft closes by a separate Java process started from a copy of RigTune's own jar (HelperLauncher.java and ApplyHelper.java in the source), because Windows locks loaded jars. Downloads come only from Modrinth's CDN and are SHA-512 checked. Nothing is deleted, and every change can be undone. The jar matches the tagged GitHub source. Thanks for your time.
