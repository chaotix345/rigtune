# RigTune v0.4.0: final focused re-check of fix/review-9

Range: `a91f6ef8..85cfb9d7`

Sources: `docs/reviews/review-9.md`, `docs/v0.4/design/fix-9.md`.

Method: a finder pass re-checked X3-1 and SE2-RESIDUAL against `85cfb9d7` source and tests and hunted for regressions the diff itself could have introduced (cross-thread capture start/stop, session-summary data loss, per-frame allocation, the footprint budget, test-strength drift, 26.2/26.3 divergence); an independent verifier re-derived every status and verdict from source, re-ran the ring-size arithmetic independently, and traced the new `TextsTest` assertion character by character. This report re-confirms both passes directly against `85cfb9d7` (every file:line below was read from `git show 85cfb9d7:<path>`, not carried over from the finder's citations) and uses the verifier's status/severity as final throughout.

## Summary

**Fix status:**

| ID | Status |
|---|---|
| X3-1 | FIXED |
| SE2-RESIDUAL | FIXED |

**New findings, by final severity (verifier status):**

| Severity | CONFIRMED | PLAUSIBLE |
|---|---|---|
| High | 0 | 0 |
| Medium | 0 | 0 |
| Low | 0 | 1 (R10-1) |

No high or medium severity problem remains open. One low-severity, largely theoretical exposure (R10-1) was found and is not blocking.

## Fix-status table

| ID | Status | Evidence (`85cfb9d7`) |
|---|---|---|
| X3-1 | FIXED | `StutterMonitor.startSession`/`startBenchmark` (`StutterMonitor.java:248-266`) are `static synchronized` and each throws `IllegalStateException` before any field is touched if the other capture is already running — one capture at a time, enforced at the source of truth. `BenchmarkController.begin()` (`BenchmarkController.java:337-338`) calls `StutterHooks.benchmarkStarted()` **before** `controller.prepare()` changes any setting; that call chain is `StutterHooks.benchmarkStarted()` → `StutterService.benchmarkStarted(minecraft)` (`StutterService.java:229-231`) → `endSessionForBenchmark(minecraft)` (`:248`), which ends the running session exactly as leaving the world does (so it is saved only with ≥2 min gameplay, per review-8 P5A-F3). Every exit path of a run — `finish()` (`BenchmarkController.java:588-610`, guarded by `if (active != this) return;` so it can only run once per controller), `cancel()` (`:439-445`), the `onTick()` catch (`:430-435`), and `begin()`'s own catch when `prepare()`/`nextStep()` throws — converges on `finish()`, which unconditionally calls `StutterHooks.benchmarkFinished(!run.cancelled())`; the `benchmarkRunning` flag/hook pairing can't leak. `StutterHooks.benchmarkStarted()`/`benchmarkFinished()` (`StutterHooks.java`) each wrap their `StutterService` call in a try/catch so a `RuntimeException` from the stutter side never propagates into `BenchmarkController`. `StutterMonitorTest.oneCaptureAtATime` (`StutterMonitorTest.java:106-138`) now asserts the exclusivity both directions (`startBenchmark` while a session runs throws, and vice versa) plus that each capture alone stays under the `monitorOnRetainedBytes` budget. `FrameRingAllocationTest` (`:131-138`) independently confirms, from source constants, that a session's rings alone or a benchmark's rings alone fit the 2,621,440-byte (2.5 MiB) budget but both together (2,982,176 bytes) would not — matching why the monitor must never hold both. `FootprintGameTest`'s new block (diff at `FootprintGameTest.java:455-489`) drives the real render-thread sequence via `computeOnClient`/`waitTicks` (not a stub): pausing a running session, `benchmarkStarted()` ending it, 20 ticks with none starting, a sweep starting the benchmark capture alone, `benchmarkFinished(false)` stopping it and starting a fresh paused session — and every `check()` in it passes by construction against the real code path. `DESIGN.md`'s stale "a running session pauses during a benchmark" line was updated to describe the end/restart behaviour (diff at `docs/DESIGN.md:212`). |
| SE2-RESIDUAL | FIXED | `BenchmarkResultScreen.reason(String)` (`BenchmarkResultScreen.java:384-389`) wraps its exception-derived string in `SafeLiteral.of(...)` instead of a bare `Component.literal(...)`. `RealController.finishDownloads()`'s download-failure status (`RealController.java:549`) routes `error.getMessage()` through `SafeText.clean(...)`, which is null-safe (returns `""` for a null message rather than NPE-ing or showing the literal string "null"). `TextsTest.outsideTextIsInert` (`TextsTest.java:57-58`) asserts `BenchmarkResultScreen.reason(SessionResult.NOT_MEASURED_FAILED + "No pack §cred" + rlo).getString() == "No pack red"`; traced character by character against `SafeText`'s section-code-pair stripping and Unicode-category (FORMAT, U+202E RLO) stripping and confirmed correct. A grep of every remaining `Component.literal(` call site under `src/client/java` found none holding raw, unsanitized outside/untrusted text — all remaining sites hold RigTune's own numbers, separators, or text already sanitized upstream. |

## New findings (verifier verdict/severity final)

### R10-1 — a benchmark run's forced session-end can silently drop that session's summary if the copy step throws (low, PLAUSIBLE)

- **Scenario:** A player has a Stutter Doctor session running (say, 20 minutes of real gameplay already logged) and starts a benchmark. `BenchmarkController.begin()` calls `StutterHooks.benchmarkStarted()` → `StutterService.benchmarkStarted()` → `endSessionForBenchmark()` → `end(session, minecraft, false)` (`StutterService.java:193-200`). `end()`'s very first line is `StutterCapture.Copy copy = StutterCapture.stop(session);` (`:194`), and only after that returns does it build and schedule the `Runnable save` that actually queues the session for a write to `stutter.json`. `StutterCapture.stop()` (`StutterCapture.java:38-49`) does `try { return copy(capture); } finally { StutterMonitor.stop(capture); ...; }` — so if `copy(capture)` (a plain array-copy of the frame/phase rings) ever throws a `RuntimeException`, the capture is still correctly detached and the GC listener/sampler are still correctly released (no resource leak), but the exception propagates straight out of `end()`, past `endSessionForBenchmark()` and `benchmarkStarted(Minecraft)`, and is only caught by `StutterHooks.benchmarkStarted()`'s generic wrapper (`StutterHooks.java:132-139`), which logs one warning: `"Stutter Doctor: could not end the session for the benchmark"`. The session's entire summary is never queued for save — nothing in the log says a running session's data was specifically discarded, only that ending it failed.
- **Evidence:** Confirmed by direct read of `StutterCapture.java:38-49` and `StutterService.java:193-231` at `85cfb9d7`, and this exact ordering (copy-before-save-is-constructed, no try/catch around the copy step inside `end()`) is unchanged from `a91f6ef8` — it is pre-existing for the world-leave and monitor-toggle-off callers of `end()` too, and `fix-9.md`'s own self-review (L2) already flags this ordering as a known, deliberately-unaddressed gap. What this diff changes is **reachability**: before it, starting a benchmark only ever paused a running session (never called `end()`/`StutterCapture.stop()` on it), so this exception window was never exercised by starting a benchmark; after this diff, every single benchmark run now calls `end()` on any running session as the very first side effect of `begin()`. No concrete trigger was found or forced — `copy()`'s underlying `FrameRing.snapshot()`/`StutterRings.snapshot()` calls are plain in-memory array reads on the render thread with no I/O, so this is a real increase in exposure rather than a demonstrated live defect.
- **Fix:** Either wrap the `StutterCapture.stop(session)` call inside `end()` (or inside `endSessionForBenchmark`/`benchmarkStarted`) so a `copy()` failure still attempts a best-effort save of whatever can be salvaged, or at minimum log specifically that a running session's summary was lost (not just that ending it for the benchmark failed), so the gap is visible in the logs instead of silent.

## Refuted

None additional beyond what review-9 already refuted (NEW-1); no candidate raised in this recheck's finder pass was rejected on independent review — R10-1 was downgraded to low/theoretical exposure rather than refuted outright, since the ordering gap and its new reachability are both real, only the trigger is unconfirmed.

## Other areas hunted, no issue found

- **Thread-safety of ending/starting captures:** `StutterMonitor.startSession`/`startBenchmark`/`stop` are all `static synchronized`, so the render thread (which owns every capture start/stop call in practice) cannot race itself, and `GcListener`/`ThreadSampler` are only attached/detached from inside these same synchronized transitions.
- **A session never restarted, or started twice:** `finish()`'s `if (active != this) return;` guard makes it idempotent per controller; every exit path (`finish()`, `cancel()`, the `onTick()` and `begin()` catch blocks) routes through it exactly once, so `benchmarkFinished()` — and the fresh-session start it triggers — cannot fire twice or zero times for a given run.
- **Allocation per frame / footprint budget:** `FrameRingAllocationTest` and `StutterMonitorTest.oneCaptureAtATime`'s budget assertions were re-derived from source constants independently in this recheck and matched the hardcoded expected values (session 1,900,688 B, benchmark 475,280 B, shared rings 606,208 B; both together = 2,982,176 B > the 2,621,440 B budget). `tools/footprint-budgets.json` itself was not touched by this diff.
- **Test-strength drift:** the `sessionAndBenchmarkShareTheRingsUntilTheLastStops` test was replaced, not weakened — `oneCaptureAtATime` now asserts exclusivity in both directions (session-then-benchmark and benchmark-then-session both throw) plus per-capture budget compliance, which is strictly more coverage than the old test gave. `TICK_ROUNDS` moving from 3 to 5 (`FootprintGameTest.java`) reduces test flakiness (per a cited real CI failure, run 36255335999) and does not loosen any assertion.
- **26.2 vs 26.3 divergence:** no Stonecutter version markers (`//? if`) appear anywhere in this diff's touched files; the one pre-existing marker in `BenchmarkGameTest.java` is outside the diff hunks and unrelated.

## Conclusion

Both X3-1 and SE2-RESIDUAL are FIXED at `85cfb9d7`; no high- or medium-severity problem remains open, and the one new finding (R10-1) is low severity and unconfirmed as a live trigger.
