package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.report.MarkdownSafe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// The "Copy summary" text (docs/v0.4/SPEC.md 5): a short Markdown summary of one capture for a Discord message or an
// issue, like the share report. Only numbers, cause names and the advice titles: no paths, no world or player names.
// Honest wording (X4): causes are "likely", correlations "not measured", and the unexplained share is always there.
public final class StutterSummary {
	public static final int LIMIT = 2000;
	private static final Map<String, String> NAMES = Map.ofEntries(
			Map.entry(Attributor.GC, "garbage collection"),
			Map.entry(Attributor.CHUNK_LOAD, "chunk loading"),
			Map.entry(Attributor.CHUNK_BUILD, "chunk building"),
			Map.entry(Attributor.TICK, "game ticks"),
			Map.entry(Attributor.RENDER, "rendering"),
			Map.entry(Attributor.WORLD_SAVE, "world saves"),
			Map.entry(Attributor.DH, "Distant Horizons background work"),
			Map.entry(Attributor.CPU_CONTENTION, "a busy CPU"),
			Map.entry(Attributor.AFTER_TELEPORT, "the 10 s after a teleport"),
			Map.entry(Attributor.CHUNKS_LOADING, "chunks loading"),
			Map.entry(Attributor.MOVING_FAST, "fast movement"));

	private StutterSummary() {
	}

	public static String name(String causeOrTag) {
		return NAMES.getOrDefault(causeOrTag, causeOrTag);
	}

	public static String text(StutterReport r, List<StutterAdvisor.Fired> advice) {
		StringBuilder out = new StringBuilder();
		out.append("**RigTune Stutter Doctor** · ").append(StutterReport.BENCHMARK.equals(r.source()) ? "benchmark" : "session");
		if (r.mc() != null) {
			out.append(" · Minecraft ").append(r.mc());
		}
		if (r.collector() != null) {
			out.append(" · ").append(r.collector());
		}
		out.append(", ").append(r.heapMaxMb()).append(" MB heap\n");
		out.append(String.format(Locale.ROOT, "%s (%s of gameplay) · %,d frames · avg %.0f FPS · 1%% low %.0f FPS%n", clock(r.sessionSeconds()),
				clock(r.gameplaySeconds()), r.frames(), r.avgFps(), r.onePercentLowFps()));
		StutterReport.Spikes s = r.spikes();
		out.append(String.format(Locale.ROOT, "%s (%d minor, %d major, %d severe, %s) in %s · %.1f s lost%n", count(s.total(), "spike", "spikes"), s.minor(),
				s.major(), s.severe(), count(s.freeze(), "freeze", "freezes"), count(r.hitches(), "hitch", "hitches"), r.lostMs() / 1000));
		if (!r.enoughData()) {
			out.append("Not enough data yet (at least 3 spikes and 2 minutes of gameplay)\n");
		}
		if (s.total() > 0) {
			List<String> causes = new ArrayList<>();
			for (String cause : Attributor.CAUSES) {
				Double share = r.causes().get(cause);
				if (share != null && share > 0 && !cause.equals(Attributor.UNKNOWN)) {
					causes.add(String.format(Locale.ROOT, "%s %.0f %%", name(cause), share * 100));
				}
			}
			double unexplained = r.causes().getOrDefault(Attributor.UNKNOWN, causes.isEmpty() ? 1.0 : 0.0);
			out.append("Likely causes (share of the lost time): ").append(causes.isEmpty() ? "none measured" : String.join(", ", causes))
					.append(String.format(Locale.ROOT, "; not explained %.0f %%%n", unexplained * 100));
			for (String tag : Attributor.TAGS) {
				Integer n = r.tags().get(tag);
				if (n != null && n > 0) {
					String when = Attributor.CHUNKS_LOADING.equals(tag) ? "while chunks were loading" : "during " + name(tag);
					out.append(s.total() == 1 ? String.format(Locale.ROOT, "The spike happened %s (not measured)%n", when)
							: String.format(Locale.ROOT, "%d of %d spikes happened %s (not measured)%n", n, s.total(), when));
				}
			}
			List<String> worst = new ArrayList<>();
			for (StutterReport.Worst w : r.worst().subList(0, Math.min(3, r.worst().size()))) {
				worst.add(String.format(Locale.ROOT, "%.0f ms at %s%s", w.ms(), clock(w.t()), w.causes().isEmpty() ? "" : " (" + notes(w.causes()) + ")"));
			}
			out.append("Worst: ").append(String.join("; ", worst)).append('\n');
		}
		if (!r.phaseTiming()) {
			out.append("Phase timing unavailable\n");
		}
		if (r.facts().gcOffsetMs() == null) {
			out.append("GC timing not calibrated yet\n");
		}
		if (!advice.isEmpty()) {
			out.append("Advice: ").append(String.join("; ", advice.stream().map(a -> MarkdownSafe.field(a.title())).toList())).append('\n');
		}
		String text = out.toString();
		return text.length() <= LIMIT ? text : text.substring(0, LIMIT - 1) + "…";
	}

	// "1 spike", "2 spikes".
	static String count(long n, String one, String many) {
		return n + " " + (n == 1 ? one : many);
	}

	// "gc:high:FULL" -> "garbage collection (high), full GC".
	static String notes(List<String> notes) {
		List<String> out = new ArrayList<>();
		for (String text : notes) {
			Attributor.Note note = Attributor.Note.parse(text);
			StringBuilder b = new StringBuilder(name(note.name()));
			if (note.confidence() != null) {
				b.append(" (").append(note.confidence().id()).append(')');
			}
			if (note.full()) {
				b.append(", full GC");
			}
			if (note.explicit()) {
				b.append(", System.gc()");
			}
			if (note.stall()) {
				b.append(", allocation stall");
			}
			if (note.group() != null) {
				b.append(", ").append(note.group());
			}
			out.add(b.toString());
		}
		return String.join(", ", out);
	}

	public static String clock(double seconds) {
		long s = Math.max(0, Math.round(seconds));
		return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
	}
}
