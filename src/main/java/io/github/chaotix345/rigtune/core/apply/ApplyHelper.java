package io.github.chaotix345.rigtune.core.apply;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ApplyHelper {
	static final long WAIT_TIMEOUT_MINUTES = 15;
	// The game process has exited, but something else (the Modrinth App re-scanning the instance, an AV scanner)
	// can still briefly hold a mod jar open right after exit; this settle delay gives that a moment before the
	// apply even starts trying, on top of ApplyExecutor's own retries for whatever contention is still there.
	static final long SETTLE_MILLIS = 2000;
	static final Duration LOCK_WAIT = Duration.ofSeconds(60);

	// Injectable so tests can drive the settle delay without sleeping for real.
	interface Sleeper {
		boolean sleep(long millis);
	}

	private ApplyHelper() {
	}

	public static void main(String[] args) {
		System.exit(run(args));
	}

	static int run(String[] args) {
		return run(args, ApplyHelper::realSleep);
	}

	static int run(String[] args, Sleeper sleeper) {
		return run(args, sleeper, new ApplyExecutor());
	}

	static int run(String[] args, Sleeper sleeper, ApplyExecutor executor) {
		if (args.length != 2) {
			log("Usage: ApplyHelper <gamePid> <pendingJsonPath>");
			return 2;
		}
		long pid;
		try {
			pid = Long.parseLong(args[0]);
		} catch (NumberFormatException e) {
			log("Invalid pid: " + args[0]);
			return 2;
		}
		Path pending = Path.of(args[1]);
		int waited = waitForGame(pid, pending, sleeper);
		if (waited != 0) {
			return waited;
		}

		// Taken only once the game has exited (a lingering JVM must not block staging in a relaunched game), and held
		// until the plan is rewritten, so the next launch and staging can't race the renames.
		try (ApplyLock lock = ApplyLock.acquire(ApplyLock.besidePlan(pending), LOCK_WAIT)) {
			if (lock == null) {
				log("Another RigTune apply still holds " + ApplyLock.besidePlan(pending) + "; leaving " + pending + " for the next exit");
				return 3;
			}
			return applyLocked(pending, executor);
		} catch (IOException e) {
			log("Could not take the apply lock: " + e);
			return 3;
		}
	}

	private static int waitForGame(long pid, Path pending, Sleeper sleeper) {
		Optional<ProcessHandle> game = ProcessHandle.of(pid);
		if (game.isPresent()) {
			log("Waiting for game process " + pid + " to exit");
			try {
				game.get().onExit().get(WAIT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
			} catch (TimeoutException e) {
				log("Game still running after " + WAIT_TIMEOUT_MINUTES + " minutes; leaving " + pending + " for the next launch");
				return 3;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return 3;
			} catch (ExecutionException e) {
				log("Could not wait for process " + pid + ": " + e.getCause());
			}
		} else {
			log("Process " + pid + " not found; proceeding");
		}

		return sleeper.sleep(SETTLE_MILLIS) ? 0 : 3;
	}

	private static boolean realSleep(long millis) {
		try {
			Thread.sleep(millis);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static int applyLocked(Path pending, ApplyExecutor executor) {
		if (!Files.exists(pending)) {
			log("Nothing to apply: " + pending + " does not exist");
			return 0;
		}
		try {
			PendingActions plan = PendingActions.load(pending);
			log("Applying " + plan.ops().size() + " operation(s) from " + pending);
			ApplyResult result = executor.run(plan, pending);
			for (ApplyResult.OpResult r : result.results()) {
				log(r.status() + " " + (r.op() == null ? "?" : r.op().type()) + ": " + r.message());
			}
			log(result.allSucceeded() ? "All operations done"
					: "Some operations were not applied; failed ones remain in " + pending + ", abandoned ones were dropped");
			return result.allSucceeded() ? 0 : 1;
		} catch (Throwable t) {
			// An Error too (review 4, security-1): logged, and pending.json stays for the next exit.
			log("Apply failed: " + t);
			return 1;
		}
	}

	static void log(String message) {
		System.out.println("[" + Instant.now() + "] [RigTune apply] " + message);
		System.out.flush();
	}
}
