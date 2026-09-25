package io.github.chaotix345.rigtune.core.benchmark;

import java.util.Optional;

// When a step's terrain is ready to measure (docs/v0.3/SPEC.md 3f with amendment E-M1). The server sends a circle of
// chunks, so the area is every chunk with dx² + dz² ≤ radius² around the camera's chunk (radius = render distance − 1).
// A step settles once the protocol's minimum time has passed and, for READY_TICKS ticks in a row, no chunk of the area
// was missing on the client and the chunk sections were built; otherwise it ends at the protocol's timeout. The chunks
// matter as much as the sections: Sodium reports the terrain complete while chunks the server never sent are missing.
// A timed-out step with more than MAX_MISSING_FRACTION of the area missing isn't complete: it can't count as a pass.
public final class SettleCheck {
	public static final int READY_TICKS = 10;
	public static final double MAX_MISSING_FRACTION = 0.02;

	public interface Chunks {
		boolean present(int chunkX, int chunkZ);
	}

	public record Count(int inRange, int missing) {
	}

	public record Result(int radius, int inRange, int missing, double seconds, boolean timedOut) {
		/** Measuring on this terrain is fair: the step settled, or at most 2% of the area was missing at the timeout. */
		public boolean complete() {
			return !timedOut || missing <= MAX_MISSING_FRACTION * inRange;
		}
	}

	private final double minSeconds;
	private final double timeoutSeconds;
	private final int radius;
	private int readyTicks;

	public SettleCheck(Protocol protocol, int radius) {
		this.minSeconds = protocol.settleMinSeconds();
		this.timeoutSeconds = protocol.settleTimeoutSeconds();
		this.radius = Math.max(0, radius);
	}

	public int radius() {
		return radius;
	}

	public static Count count(int radius, int centerX, int centerZ, Chunks chunks) {
		int r = Math.max(0, radius);
		int inRange = 0;
		int missing = 0;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (dx * dx + dz * dz <= r * r) {
					inRange++;
					if (!chunks.present(centerX + dx, centerZ + dz)) {
						missing++;
					}
				}
			}
		}
		return new Count(inRange, missing);
	}

	/** One tick of the settle phase; the result once the step settled or timed out, else empty. */
	public Optional<Result> tick(double elapsedSeconds, Count chunks, boolean sectionsReady) {
		readyTicks = sectionsReady && chunks.missing() == 0 ? readyTicks + 1 : 0;
		if (elapsedSeconds >= minSeconds && readyTicks >= READY_TICKS) {
			return Optional.of(new Result(radius, chunks.inRange(), chunks.missing(), elapsedSeconds, false));
		}
		if (elapsedSeconds >= timeoutSeconds) {
			return Optional.of(new Result(radius, chunks.inRange(), chunks.missing(), elapsedSeconds, true));
		}
		return Optional.empty();
	}
}
