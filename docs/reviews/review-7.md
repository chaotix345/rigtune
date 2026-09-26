# RigTune v0.4.0: review round 1

Scope: commit 9cf84f66 (v0.3.0..9cf84f66, feat/v0.4.0) against `main`. Run as a Workflow: six fresh reviewers (correctness, apply-safety, security, compat, rules-honesty, performance) against the v0.4 changes (Performance Profiles/RT1 share codes, Stutter Doctor, JVM/GC advice, benchmark history and regression alerts, server-aware advice, change awareness, footprint guard, apply-pipeline hardening); every finding adversarially verified against `docs/v0.4/SPEC.md` (including its Amendments section) and the documented residuals in `docs/v0.4/design/*.md`.

**Result:** 1 finding, confirmed at MEDIUM; 0 critical or high. The correctness, apply-safety, compat, rules-honesty and performance reviewers found nothing.

| id | dimension | final severity | verdict | title | file:line |
|---|---|---|---|---|---|
| S-1 | security | medium | CONFIRMED | Stutter Doctor's "Copy summary" embeds remote rules-feed advice titles unescaped into clipboard text, unlike the equivalent Share Report path | `src/main/java/io/github/chaotix345/rigtune/core/stutter/StutterSummary.java:80` |

## Details

### S-1 (CONFIRMED, medium): Stutter Doctor's "Copy summary" embeds remote rules-feed advice titles unescaped into clipboard text, unlike the equivalent Share Report path

**Scenario:** Stutter Doctor's advice titles come straight from `stutterAdvice` rules in `rules-v2.json`/`rules-v1.json`, a remote, network-fetched document (`RemoteRulesFetcher`/`RulesSources`, default `https://raw.githubusercontent.com/chaotix345/rigtune/main/rules/`, overridable to any host via `-Drigtune.rules.baseUrl`). `StutterAdvisor.fire()` sets each `Fired.title` directly from the rule's `title` field with no validation. `StutterSummary.text()` then joins those titles with `"; "` and appends them to the "Copy summary" text with no Markdown escaping and no `@`-mention neutralising — only an overall 2000-character truncation. The class exists specifically so a player can paste this text "for a Discord message or an issue." If a `stutterAdvice` rule's title were ever `@everyone`, a role mention (`<@&ROLE_ID>`), or a Markdown link/spoiler, every player who presses "Copy summary" and pastes into a support Discord (the documented use case) would paste live Discord markup/mentions verbatim.

This is the exact class of injection the same codebase already defends against one file over: `ShareReport.field()`/`escape()` backslash-escape Markdown control characters and insert a zero-width space after `@`, specifically (per its own comment) "so a mod called `*@everyone*` can't format the message or ping anyone." That protection is applied to the *identical* `rule.title` field when it reaches the main Recommendation list (`Recommender.java:460` → `Recommendation.of(..., Text.literal(rule.title != null ? rule.title : rule.id), ...)` → `ShareReport.items()` calling `field(r.title())`). `StutterSummary` and `StutterAdvisor` are both wholly new in v0.4 and did not reuse that existing sanitiser, so the new Copy-summary feature regressed behind the bar the rest of the codebase already set for exactly this kind of remote text.

**Evidence (all at 9cf84f66):**
- `src/main/java/io/github/chaotix345/rigtune/core/stutter/StutterSummary.java:80` — `out.append("Advice: ").append(String.join("; ", advice.stream().map(StutterAdvisor.Fired::title).toList()))...`, no escaping, only the class-wide truncation to `LIMIT` (2000 chars) at the return statement two lines below.
- `src/main/java/io/github/chaotix345/rigtune/core/stutter/StutterAdvisor.java:72` — `out.add(new Fired(rule.id, kind, impact, rule.title != null ? rule.title : rule.id, rule.text == null ? "" : rule.text));`, where `rule` is `RulesDocument.AdviceRule`, a plain Gson-deserialised field from `stutterAdvice` in the remote rules document, with no field-content validation anywhere in the rules-loading path.
- `src/main/java/io/github/chaotix345/rigtune/core/rules/RulesSources.java:18,39-41` — `DEFAULT_BASE_URL = https://raw.githubusercontent.com/chaotix345/rigtune/main/rules/`, overridable via the `rigtune.rules.baseUrl` system property to any host.
- `src/main/java/io/github/chaotix345/rigtune/core/report/ShareReport.java:275-295` (`field()`/`escape()`) — backslash-escapes Markdown control characters and inserts a zero-width space after `@`; comment: "so a mod called `*@everyone*` can't format the message or ping anyone."
- `src/main/java/io/github/chaotix345/rigtune/core/recommend/Recommender.java:460` — the identical `rule.title` field, for the main recommendation list, is wrapped as `Text.literal(rule.title != null ? rule.title : rule.id)` and reaches `ShareReport.items()` (`ShareReport.java:197`, `field(r.title())`), i.e. the analogous path *is* escaped.
- `git diff main...9cf84f66 --stat` confirms `StutterSummary.java` (+116) and `StutterAdvisor.java` (+83) are both wholly new in this branch, absent from `main`.
- Clipboard call chain, no other sanitising layer in between: `StutterScreen.java:126-131` (`copySummary()` → `minecraft.keyboardHandler.setClipboard(text)`) ← `RealController.java:952` (`stutterSummary()` → `stutterService.summary()`) ← `StutterService.java:147` (`StutterSummary.text(a.report(), a.advice())`).

**Verifier reason:** Traced the full path at 9cf84f66 and confirmed every line cited above matches file content exactly, including the exact line numbers. Confirmed `RulesDocument.AdviceRule` (`RulesDocument.java:219`) is a plain unvalidated Gson field and that no validation of rule-text content exists anywhere in `RulesLoader`. Confirmed the identical `rule.title` field is escaped when it reaches the main recommendation list via `ShareReport.items()`, so the analogous, already-shipped path is protected and this new one is not. Confirmed via `git diff main...9cf84f66 --stat` that both classes are wholly new in this branch. Confirmed the clipboard chain has no other sanitising layer between `StutterSummary.text()` and `setClipboard()`. Checked `docs/v0.4/design/*.md` (including WS-S's "UNVERIFIED / left for Phase 5" section), `SPEC.md` (including its Amendments), and the audit docs — none mention this as an accepted residual, so `documentedResidual=false` is correct. Severity: medium per the rubric — noticeable, player-triggerable wrong/unsafe behaviour (mass-ping or message-formatting griefing via a compromised or overridden rules feed pasted into Discord) but it does not break the game, corrupt files, or leak private data, so it does not reach high.

**Proposed fix:** Route `StutterSummary`'s advice titles (and any other rule-sourced text later added there) through the same escaping `ShareReport.field()`/`escape()` uses — e.g. extract that method to a shared utility (`core/report` or `core/text`) and call it for each advice title in `StutterSummary.text()`, the same way `ShareReport` already does for `rule.title`-derived Recommendation titles.

## Checked and OK (per dimension)
- **correctness**: no findings raised.
- **apply-safety**: no findings raised.
- **compat**: no findings raised.
- **rules-honesty**: no findings raised.
- **performance**: no findings raised.

## REFUTED findings
None — the one finding raised (S-1) was CONFIRMED.

## Fixes
Not yet applied; this is the round-1 report only (matches the task's read-only reporting scope — no fix branch created for this round).
