package io.github.chaotix345.rigtune.client.stutter;

import io.github.chaotix345.rigtune.core.stutter.FrameRing;
import io.github.chaotix345.rigtune.core.stutter.StutterReport;
import io.github.chaotix345.rigtune.core.stutter.StutterRings;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

// The render thread's side of the Stutter Doctor (docs/v0.4/SPEC.md 5; research §2): the one call in
// DebugScreenOverlayMixin (onFrame), the optional phase timers (MinecraftFrameMixin), the chunk-load counter and the
// two captures (the session monitor and the benchmark's sweeps). With both captures off, every hook is one volatile read.
// Deliberately free of Minecraft types, so the hot path can be measured in plain JUnit (FrameHookBudgetTest).
// Threads: everything here is called on the render thread, except event() (any thread; the rings lock) and the reads
// of retainedBytes()/active().
public final class StutterMonitor {
	// Phase-timer bits (plan review S-M1): each handler sets its bit on its first call while capturing.
	static final int PACKETS_START = 1;
	static final int PACKETS_END = 1 << 1;
	static final int TICK_START = 1 << 2;
	static final int TICK_END = 1 << 3;
	static final int RENDER_START = 1 << 4;
	static final int LIMITER_START = 1 << 5;
	static final int LIMITER_END = 1 << 6;
	static final int REQUIRED = PACKETS_START | PACKETS_END | TICK_START | TICK_END | RENDER_START;
	static final int LIMITER = LIMITER_START | LIMITER_END;
	public static final long LOADING_NANOS = 10_000_000_000L;

	// One capture: its frame ring and when it started. Only the render thread writes it.
	public static final class Capture {
		final FrameRing ring;
		final long startNanos;
		final Instant startedAt;
		final String source;
		volatile boolean paused;
		private boolean skipNext = true;

		Capture(FrameRing ring, long startNanos, Instant startedAt, String source) {
			this.ring = ring;
			this.startNanos = startNanos;
			this.startedAt = startedAt;
			this.source = source;
		}

		void frame(long now, long duration, boolean excluded, long packets, long ticks, long render, int chunkLoads) {
			if (paused) {
				skipNext = true;
				return;
			}
			// The first frame after a start or a pause spans the gap: never gameplay.
			ring.frame(now, duration, excluded || skipNext, packets, ticks, render, chunkLoads);
			skipNext = false;
		}

		public FrameRing.Snapshot snapshot() {
			return ring.snapshot();
		}

		public long startNanos() {
			return startNanos;
		}

		public Instant startedAt() {
			return startedAt;
		}

		public String source() {
			return source;
		}

		public boolean paused() {
			return paused;
		}

		public long retainedBytes() {
			return ring.retainedBytes();
		}
	}

	private static volatile boolean active;
	private static volatile @Nullable Capture session;
	private static volatile @Nullable Capture benchmark;
	private static volatile @Nullable StutterRings rings;

	// Render thread only.
	private static boolean excludedNow;
	private static long loadingUntil;
	private static long packetsStart;
	private static long packets;
	private static long tickStart;
	private static long ticks;
	private static long renderStart;
	private static long limiterStart;
	private static long limiter;
	private static int chunkLoads;
	private static int phaseSeen;

	private StutterMonitor() {
	}

	// DebugScreenOverlay.logFrameDuration(J)V, HEAD: `duration` is the game's own frame-to-frame time.
	public static void onFrame(long duration) {
		if (!active) {
			return;
		}
		long now = System.nanoTime();
		long render = renderStart == 0 ? 0 : Math.max(0, now - renderStart - limiter);
		boolean excluded = excludedNow || now - loadingUntil < 0;
		Capture s = session;
		if (s != null) {
			s.frame(now, duration, excluded, packets, ticks, render, chunkLoads);
		}
		Capture b = benchmark;
		if (b != null) {
			b.frame(now, duration, excluded, packets, ticks, render, chunkLoads);
		}
		packets = 0;
		ticks = 0;
		limiter = 0;
		renderStart = 0;
		chunkLoads = 0;
	}

	// Phase timers (MinecraftFrameMixin; each injection is optional, S-M1).
	public static void packetsStart() {
		if (active) {
			packetsStart = System.nanoTime();
			phaseSeen |= PACKETS_START;
		}
	}

	public static void packetsEnd() {
		if (active) {
			if (packetsStart != 0) {
				packets += System.nanoTime() - packetsStart;
				packetsStart = 0;
			}
			phaseSeen |= PACKETS_END;
		}
	}

	public static void tickStart() {
		if (active) {
			tickStart = System.nanoTime();
			phaseSeen |= TICK_START;
		}
	}

	public static void tickEnd() {
		if (active) {
			if (tickStart != 0) {
				ticks += System.nanoTime() - tickStart;
				tickStart = 0;
			}
			phaseSeen |= TICK_END;
		}
	}

	public static void renderStart() {
		if (active) {
			renderStart = System.nanoTime();
			phaseSeen |= RENDER_START;
		}
	}

	public static void limiterStart() {
		if (active) {
			limiterStart = System.nanoTime();
			phaseSeen |= LIMITER_START;
		}
	}

	public static void limiterEnd() {
		if (active) {
			if (limiterStart != 0) {
				limiter += System.nanoTime() - limiterStart;
				limiterStart = 0;
			}
			phaseSeen |= LIMITER_END;
		}
	}

	// Every required phase timer fired, and the (optional, only while the frame rate is capped) limiter pair fired both
	// halves or neither. Otherwise attribution uses the no-phase rules and the report says "phase timing unavailable".
	public static boolean phaseTiming() {
		int seen = phaseSeen;
		return (seen & REQUIRED) == REQUIRED && ((seen & LIMITER) == 0 || (seen & LIMITER) == LIMITER);
	}

	static int phaseSeen() {
		return phaseSeen;
	}

	// ClientChunkEvents.CHUNK_LOAD (render thread, while packets are handled).
	public static void chunkLoaded() {
		if (active) {
			chunkLoads++;
		}
	}

	// END_CLIENT_TICK: a screen is open or the window isn't focused.
	public static void setExcluded(boolean excluded) {
		excludedNow = excluded;
	}

	// ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE: the next 10 s are world loading (excluded), recorded as an event.
	public static void levelChanged(long now) {
		loadingUntil = now + LOADING_NANOS;
		event(StutterRings.LEVEL_CHANGE, now, 0);
	}

	public static void event(int kind, long nanos, long value) {
		StutterRings r = rings;
		if (r != null) {
			r.event(kind, nanos, value);
		}
	}

	public static boolean active() {
		return active;
	}

	public static @Nullable Capture session() {
		return session;
	}

	public static @Nullable Capture benchmark() {
		return benchmark;
	}

	public static @Nullable StutterRings rings() {
		return rings;
	}

	// The captures' own memory (plan review F-M1): the frame rings plus the shared rings; 0 when off.
	public static long retainedBytes() {
		Capture s = session;
		Capture b = benchmark;
		StutterRings r = rings;
		return (s == null ? 0 : s.retainedBytes()) + (b == null ? 0 : b.retainedBytes()) + (r == null ? 0 : r.retainedBytes());
	}

	// Starting and stopping (render thread, through StutterCapture, which owns the shared rings' GC listener and sampler).
	// The shared rings exist while any capture does.
	static synchronized Capture startSession(StutterRings shared, long now, Instant startedAt) {
		rings = shared;
		Capture c = new Capture(new FrameRing(FrameRing.SESSION_FRAMES, FrameRing.SESSION_CANDIDATES), now, startedAt, StutterReport.MONITOR);
		session = c;
		active = true;
		return c;
	}

	static synchronized Capture startBenchmark(StutterRings shared, long now, Instant startedAt) {
		rings = shared;
		Capture c = new Capture(new FrameRing(FrameRing.BENCHMARK_FRAMES, FrameRing.BENCHMARK_CANDIDATES), now, startedAt, StutterReport.BENCHMARK);
		c.paused = true;
		benchmark = c;
		active = true;
		return c;
	}

	// Detaches the capture; the shared rings go too when nothing else uses them (returns true then).
	static synchronized boolean stop(Capture c) {
		if (session == c) {
			session = null;
		}
		if (benchmark == c) {
			benchmark = null;
		}
		boolean last = session == null && benchmark == null;
		if (last) {
			active = false;
			rings = null;
		}
		return last;
	}
}
