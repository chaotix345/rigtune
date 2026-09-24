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
	static final long SETTLE_MILLIS = 1000;
	static final Duration LOCK_WAIT = Duration.ofSeconds(60);

	private ApplyHelper() {
	}

	public static void main(String[] args) {
		System.exit(run(args));
	}

	static int run(String[] args) {
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

		// Held from before the game exits until the plan is rewritten, so the next launch and staging can't race us.
		try (ApplyLock lock = ApplyLock.acquire(ApplyLock.besidePlan(pending), LOCK_WAIT)) {
			if (lock == null) {
				log("Another RigTune apply still holds " + ApplyLock.besidePlan(pending) + "; leaving " + pending + " for the next exit");
				return 3;
			}
			return runLocked(pid, pending);
		} catch (IOException e) {
			log("Could not take the apply lock: " + e);
			return 3;
		}
	}

	private static int runLocked(long pid, Path pending) {
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

		try {
			Thread.sleep(SETTLE_MILLIS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return 3;
		}

		if (!Files.exists(pending)) {
			log("Nothing to apply: " + pending + " does not exist");
			return 0;
		}
		try {
			PendingActions plan = PendingActions.load(pending);
			log("Applying " + plan.ops().size() + " operation(s) from " + pending);
			ApplyResult result = new ApplyExecutor().run(plan, pending);
			for (ApplyResult.OpResult r : result.results()) {
				log(r.status() + " " + (r.op() == null ? "?" : r.op().type()) + ": " + r.message());
			}
			log(result.allSucceeded() ? "All operations done" : "Some operations failed; they remain in " + pending);
			return result.allSucceeded() ? 0 : 1;
		} catch (IOException | RuntimeException e) {
			log("Apply failed: " + e);
			return 1;
		}
	}

	private static void log(String message) {
		System.out.println("[" + Instant.now() + "] [RigTune apply] " + message);
		System.out.flush();
	}
}
