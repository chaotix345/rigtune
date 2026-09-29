# FAQ

[← Back to the README](../../README.md)

## Does RigTune work with Quilt?

Not officially, and it isn't tested there. Quilt Loader lists Minecraft 26.2 and 26.3, but Quilt retired the Quilt Standard Libraries and Quilted Fabric API at Minecraft 26.1 ([QuiltMC blog, February 2026](https://quiltmc.org/en/blog/2026-02-03-non-obfuscated-updates/)), and RigTune needs Fabric API. Whether Quilt Loader can load Fabric API itself on 26.x hasn't been confirmed, so RigTune doesn't claim Quilt support. Use [Fabric Loader](https://fabricmc.net/use/).

## What does "Report a problem" send?

RigTune itself sends nothing and makes no network request for it. The button on the RigTune screen:
1. copies the full report (the same text as **Copy report**) to your clipboard;
2. shows Minecraft's own "open this link?" screen with a link to a new issue on this repository. The link fills in the title (your RigTune and Minecraft versions) and as much of the report as fits (always the versions line, usually your hardware, the whole report when it's short), and it's short enough to read in full on that screen. **Cancel** closes it and nothing is opened.

If you choose **Open in Browser**, your browser opens that link, so GitHub receives what's in it (the title and the report text in the link), and shows the issue form with those fields filled in. Paste the full report from your clipboard, say what happened, and review everything: nothing is posted until you submit the issue, which needs a GitHub account. (Minecraft's **Copy to Clipboard** button on the link screen puts the link on your clipboard instead of the report: press **Copy report** again to get the report back.)
