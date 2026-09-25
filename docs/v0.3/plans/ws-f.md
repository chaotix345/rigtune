# WS-F: Report a problem + Quilt FAQ Implementation Plan

> **For agentic workers:** executed inline by the WS-F agent (superpowers:test-driven-development per task). Steps use checkbox (`- [ ]`) syntax.

**Goal:** A "Report a problem" button on the RigTune screen that copies the full share report to the clipboard and opens a pre-filled GitHub issue (issue form `problem.yml`) through vanilla's confirm-link screen; RigTune itself sends nothing. Plus README FAQ entries (Quilt; Report a problem).

**Architecture:** Pure `core/report/IssueLink` builds the URL (form encoding, line-boundary truncation to a fixed budget). The client adds one controller method (`reportVersions()`), one footer button line and two small helpers in `RigTuneScreen`, three `rigtune.report.*` lang keys, and `ReportGameTest`. `.github/ISSUE_TEMPLATE/problem.yml` is the issue form; the unit test reads it.

**Tech Stack:** Java 25, Fabric (Mojang names), Stonecutter (26.2 active), JUnit 5, Fabric client game tests.

**Spec:** docs/v0.3/SPEC.md items 10 and 11 plus the amendments F-M1, F-L1, X-M2 (they override item 10's 2,000-character design).

## Global Constraints
- Branch `feat/report-problem`, worktree `C:/Dev/Worktrees/rigtune-report`; never commit to main or feat/v0.3.0; no force push; merge (never rebase) `origin/feat/v0.3.0`.
- `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before every `./gradlew`; never `./gradlew --stop`; committed Stonecutter version 26.2.
- Don't edit build.gradle, stonecutter.gradle, settings.gradle, gradle.properties, .github/workflows/*.
- `core/` has no Minecraft imports. UI text only from en_us.json; new keys under `rigtune.report.*`, in alphabetical position, never appended at the end.
- RigTuneScreen footer: WS-B owns the layout (moves Undo last/Undo all into History); WS-F adds exactly one line (`buttons.add(reportButton());`) right after `buttons.add(copy);`; helpers live elsewhere in the file.
- RealController/RigTuneController: one new method (`reportVersions()`), under a `// v0.3 (WS-F)` comment in the interface; `shareReport()` is left untouched (WS-C changes it).
- The URL never carries `labels`/`assignees`/`projects`/`milestone` (a bad value 404s the link); labels come from problem.yml.
- Local game-test runs only under the lock protocol (PLAN Global Constraints); CI is the main game-test evidence once WS-0 is merged into this branch.
- Commit trailers: `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01BzjDikL2wqunaSiMjefimU`.

## Task 0: F-M1 spike (DONE before this plan)
Local `runClientGameTest` on 26.2 under the lock with a throwaway game test (only it registered, not committed) that opened `ConfirmLinkScreen.confirmLinkNow(screen, uri)` with prefixes (300..2,000 chars) of a real encoded report URL at 640x480@2, 854x480@2 and 1280x720@2, logging the message widget's rows and screenshotting 500/650/800/2,000.
- javap (26.2 and 26.3): the message is a `MultiLineTextWidget` with `setMaxWidth(width - 50)` and `setMaxRows(15)`; `confirmLinkNow(Screen, URI)` passes `trusted = true` (title "Do you want to open this link or copy it to your clipboard?", buttons Open in Browser / Copy to Clipboard / Cancel; no warning line). Cancel calls the callback with `false`, which only sets the parent screen back (no `openUri`). "Copy to Clipboard" replaces the clipboard with the URL.
- 640x480@2 (320x240 GUI, 270 px text width): 700 chars = 15 rows (the cap); 750+ clipped to 15 rows ending in "…"; buttons always at y=198..218 of 240. A 2,000-char URL never pushes the buttons off-screen, but only about the first 700 characters are readable.
- 854x480@2: fully readable up to about 1,000 chars; 1280x720@2: about 1,550.
- **Decision: `IssueLink.MAX_URL = 675`** = 15 rows × 45 glyphs: the widest character the form encoder can emit is 6 px, and 270 / 6 = 45, so any URL up to 675 chars is fully readable at the smallest reference size (640x480 GUI scale 2, which is also the minimum GUI width of 320). ReportGameTest asserts this in game on every MC version (a synthetic 675-char URL of the widest glyph, and the real link, fully shown at all three sizes). 675 < 800 (AC10.1). On this machine the whole versions + hardware block (459 encoded chars) plus the note fits (about 640 chars).

## Task 1: IssueLink (pure core) with AC10.1 encoding/truncation tests

**Files:**
- Create: `src/main/java/io/github/chaotix345/rigtune/core/report/IssueLink.java`
- Test: `src/test/java/io/github/chaotix345/rigtune/core/report/IssueLinkTest.java`

**Interfaces:**
- Consumes: `ShareReport.Versions(String rigtune, String minecraft, String loader)`, `ShareReport.format(Report, Versions, BenchmarkSummary)`.
- Produces: `IssueLink.NEW_ISSUE`, `TEMPLATE = "problem.yml"`, `TITLE_FIELD = "title"`, `REPORT_FIELD = "report"`, `MAX_URL = 675`, `SHORTENED = "(shortened; the full report is on your clipboard)"`, `static String title(Versions)`, `static URI uri(@Nullable Versions, String report)`, package-private `static String url(@Nullable Versions, String report, int maxChars)`, `static String encode(String)`.

- [ ] Step 1: failing tests in IssueLinkTest:
  - `encodesFormStyle`: `encode("a b+c%d&e#f\ng·×é😀")` == `"a+b%2Bc%25d%26e%23f%0Ag%C2%B7%C3%97%C3%A9%F0%9F%98%80"`.
  - `titleCarriesVersions`: `title(new Versions("0.3.0+mc26.2","26.2","0.19.5"))` == `"[RigTune 0.3.0+mc26.2] MC 26.2: "`; the URL contains `title=%5BRigTune+0.3.0%2Bmc26.2%5D+MC+26.2%3A+` and decodes back exactly.
  - `shortReportIsSentWhole`: a 3-line report → decoded `report` == the report, no note; URL starts with `NEW_ISSUE + "?template=problem.yml&title="`.
  - `longReportIsCutAtALineBoundary`: the userRig ShareReport with 50 recommendations → decoded report == first k lines + `"\n" + SHORTENED`, k ≥ 2 and maximal (k + 1 lines + note would exceed MAX_URL); URL ≤ MAX_URL ≤ 800 and `URI.create` parses it.
  - `worstCaseReportStaysWithinBudget`: 120-char CPU/GPU names with `+ % & # \n`-free but non-ASCII-heavy text, 50 recommendations with 200-char titles, a benchmark → URL ≤ MAX_URL, parses, decoded report ends with SHORTENED.
  - `crlfIsNormalised`, `firstLineTooLongLeavesOnlyTheNote`, `nullVersionsLeaveTheTitleOut`, `hugeTitleIsLeftOutRatherThanOverflowing`, `emptyReportSendsNoReportField`.
  - helper `params(String url)` splits the raw query on `&` and `URLDecoder.decode`s each value.
- [ ] Step 2: run `./gradlew :26.2:test --tests '*IssueLinkTest'` → compile failure (no IssueLink).
- [ ] Step 3: implement IssueLink (`URLEncoder.encode(s, UTF_8)`; lines from `report.replace("\r\n","\n").replace('\r','\n')` with trailing newlines stripped; full text if it fits, else the longest line prefix + `"\n" + SHORTENED`, else SHORTENED alone, else no report field; title left out when base + title alone exceeds the budget).
- [ ] Step 4: tests pass.
- [ ] Step 5: commit `feat(report): IssueLink builds the pre-filled issue URL (SPEC 10, F-M1 budget 675)`.

## Task 2: problem.yml and the template/no-network tests (AC10.1 rest)

**Files:**
- Create: `.github/ISSUE_TEMPLATE/problem.yml`
- Modify: `src/test/java/io/github/chaotix345/rigtune/core/report/IssueLinkTest.java`

- [ ] Step 1: failing tests:
  - `fieldNamesMatchTheIssueForm`: reads `RepoFiles.resolve(".github/ISSUE_TEMPLATE/" + IssueLink.TEMPLATE)`; a line parser maps each `id:` to the `type:` of its `- type:` item; asserts `report` is a `textarea`, `what-happened` is a required `textarea`, `expected` is a `textarea`; every query parameter of a built URL other than `template`/`title` is an id in the form; the form has a `labels:` key; the URL has no `labels=`, `assignees=`, `projects=`, `milestone=`.
  - `noNetworkClient`: IssueLink.java's imports are within {`java.net.URI`, `java.net.URLEncoder`, `java.nio.charset.StandardCharsets`, `java.util.*`, `org.jspecify.annotations.Nullable`} and the source mentions none of `openConnection`, `openStream`, `HttpClient`, `HttpURLConnection`, `Socket`, `new URL(`.
- [ ] Step 2: run → FAIL (no problem.yml).
- [ ] Step 3: write problem.yml: `name: Report a problem`, `description`, `labels: ["bug"]` (exists in the repo), a markdown intro (RigTune filled in a short report and copied the full one), textareas `what-happened` (required), `expected`, `report` (label "RigTune report", description: paste the full report from the clipboard over the short one; Copy report on the RigTune screen copies it again; without RigTune, give the RigTune/Minecraft/Fabric Loader versions).
- [ ] Step 4: tests pass; `python -c "import yaml"`-validate the file if PyYAML is present.
- [ ] Step 5: commit `feat(report): problem.yml issue form; AC10.1 template and no-network checks`.

## Task 3: the button (controller method, RigTuneScreen, lang)

**Files:**
- Modify: `src/client/java/io/github/chaotix345/rigtune/client/ui/RigTuneController.java` (append a `// v0.3 (WS-F)` default method)
- Modify: `src/client/java/io/github/chaotix345/rigtune/client/RealController.java` (a new override after `shareReport()`; `shareReport()` untouched)
- Modify: `src/client/java/io/github/chaotix345/rigtune/client/ui/RigTuneScreen.java` (one line after `buttons.add(copy);`, helpers after `copyReport()`, imports)
- Modify: `src/main/resources/assets/rigtune/lang/en_us.json` (3 keys before `rigtune.screen.benchmark_menu`)

**Interfaces:**
- Produces: `default ShareReport.@Nullable Versions reportVersions()` on RigTuneController (null by default and while there's no report).

- [ ] Step 1: the behaviour is covered by ReportGameTest (Task 4); here the check is compile + unit tests on both versions.
- [ ] Step 2: RigTuneController:
  ```java
  	// v0.3 (WS-F)

  	/** The versions for the "Report a problem" issue title (item 10); null while the report is being built. */
  	default ShareReport.@Nullable Versions reportVersions() {
  		return null;
  	}
  ```
  RealController (after shareReport):
  ```java
  	@Override
  	public ShareReport.@Nullable Versions reportVersions() {
  		Report shown = report;
  		if (shown == null) {
  			return null;
  		}
  		String loaderVersion = FabricLoader.getInstance().getModContainer("fabricloader")
  				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
  		return new ShareReport.Versions(modVersion, shown.hardware().mcVersion(), loaderVersion);
  	}
  ```
  RigTuneScreen: `buttons.add(reportButton());` after `buttons.add(copy);` and
  ```java
  	private Button reportButton() {
  		Button button = Button.builder(Component.translatable("rigtune.report.button"), b -> reportProblem())
  				.tooltip(Tooltip.create(Component.translatable("rigtune.report.button.tooltip"))).build();
  		button.active = shown != null;
  		return button;
  	}

  	// The full report goes to the clipboard; the issue link carries the title and a short report (IssueLink). Vanilla's
  	// confirm screen shows the link; nothing is opened unless the player chooses Open in Browser.
  	private void reportProblem() {
  		String text = controller.shareReport();
  		if (text.isEmpty()) {
  			status = Component.translatable("rigtune.share.unavailable");
  			return;
  		}
  		minecraft.keyboardHandler.setClipboard(text);
  		status = Component.translatable("rigtune.report.copied", text.length());
  		ConfirmLinkScreen.confirmLinkNow(this, IssueLink.uri(controller.reportVersions(), text));
  	}
  ```
  en_us.json:
  ```json
  	"rigtune.report.button": "Report a problem",
  	"rigtune.report.button.tooltip": "Opens a new GitHub issue in your browser, after you confirm the link, with your versions and hardware filled in. The full report is copied to your clipboard to paste into it. Nothing is sent until you submit the issue on GitHub.",
  	"rigtune.report.copied": "Copied the full report (%s characters): paste it into the GitHub issue.",
  ```
- [ ] Step 3: `./gradlew build` (both versions) green.
- [ ] Step 4: commit `feat(report): Report a problem button (clipboard + confirm-link screen)`.

## Task 4: ReportGameTest (AC10.2)

**Files:**
- Create: `src/gametest/java/io/github/chaotix345/rigtune/gametest/ReportGameTest.java`
- Modify: `src/gametest/resources/fabric.mod.json` (one entrypoint line after UiGameTest)

- [ ] Step 1: the test (skips in `rigtune.smoke` mode):
  1. Title screen, real report ready; clear toasts; open RigTune; remember the clipboard (restored in `finally`).
  2. At 640x480@2: the "Report a problem" button is present, active, inside the screen, and its label fits (`font.width ≤ width - 4`); screenshot `report-button-640x480-scale2`.
  3. Worst case: from the RigTune screen, `confirmLinkNow` with `NEW_ISSUE?template=problem.yml&report=` + the widest [A-Za-z0-9] glyph repeated to `MAX_URL` (after checking no other encoder output char is wider); at 640x480@2 the message is fully shown (`font.split(message, width - 50).size()` == widget height / line height ≤ 15); Cancel.
  4. Press the button in one client task and read back {clipboard, `shareReport()`, `IssueLink.uri(reportVersions(), report)`}: clipboard (CRLF normalised) == report; the screen is a `ConfirmLinkScreen`; its message text == the expected URI string; it starts with the template/title prefix, is ≤ `MAX_URL`, has no `labels=`.
  5. At 640x480@2, 854x480@2, 1280x720@2: the message fully shown; every widget inside the screen, none overlapping; screenshot `report-confirm-<w>x<h>-scale<s>`.
  6. Press Cancel (`gui.cancel`): back on the same RigTuneScreen (Open in Browser never pressed; the Cancel callback only sets the parent screen, javap-verified); screenshot `report-cancelled` (status line).
- [ ] Step 2: `./gradlew :26.2:compileGametestJava :26.3:compileGametestJava` (via build).
- [ ] Step 3: register in fabric.mod.json; commit `test(report): ReportGameTest (AC10.2)`; push.
- [ ] Step 4: when WS-0 is merged into feat/v0.3.0: `git merge origin/feat/v0.3.0`, push, `gh run watch <id> --exit-status`, `gh run download <id>`, look at the report-* screenshots of every leg. Before that, optionally one local run under the lock (only if free; don't wait).

## Task 5: README FAQ (AC11.1)

**Files:** Modify `README.md` (a new `## FAQ` section before "Build from source"; one sentence in Privacy after the Copy report paragraph).
- Quilt: not supported; QSL/QFAPI retired at MC 26.1 (QuiltMC blog, 2026-02-03); RigTune needs the real Fabric API; unverified whether Quilt Loader loads Fabric API on 26.x; untested; use Fabric Loader.
- Report a problem: what it does and what is sent (nothing until you submit on GitHub; the link's title and short report; the full report on the clipboard; the confirm screen's Copy to Clipboard button replaces the report with the link).
- Commit `docs(readme): FAQ (Quilt, Report a problem)`.

## Task 6: finish
- [ ] docs/v0.3/design/ws-f.md: the spike table and decision, the design, deviations (budget 675 not 800; `rigtune.share.unavailable` reused; `shareReport()` untouched → a two-line loader-version lookup duplicated in `reportVersions()`), UNVERIFIED items (the live GitHub prefill only works once problem.yml is on main: check the link after the release PR merges, before the tag).
- [ ] Dispatch a code-reviewer subagent on `git diff feat/v0.3.0...HEAD`; keep working; fix high/medium findings.
- [ ] Merge `origin/feat/v0.3.0`; `./gradlew build` green on both versions; push; CI green on every job; screenshots reviewed.
