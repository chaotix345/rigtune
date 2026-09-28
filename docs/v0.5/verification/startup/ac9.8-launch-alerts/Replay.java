import io.github.chaotix345.rigtune.core.footprint.StartupTimesStore;
import io.github.chaotix345.rigtune.core.footprint.StartupTrend;

import java.nio.file.Path;
import java.util.List;

// AC9.8: what this branch's StartupTrend decides for each recorded launch of a startup-times.json (every prefix, as the
// launch itself would have assessed it). Usage: java -cp <main classes>;<gson>;<jspecify> Replay.java <config dir>
public class Replay {
	public static void main(String[] args) {
		List<StartupTimesStore.Run> runs = new StartupTimesStore(Path.of(args[0])).runs();
		System.out.println("launch\tat\tms\tpreloadMs\tmods\tkey\tassessment");
		for (int i = 1; i <= runs.size(); i++) {
			StartupTimesStore.Run r = runs.get(i - 1);
			StartupTrend.Assessment a = StartupTrend.assess(runs.subList(0, i));
			System.out.println(i + "\t" + r.at() + "\t" + r.ms() + "\t" + r.preloadMs() + "\t" + r.mods() + "\t" + (a.slower() ? StartupTrend.key(a) : "-") + "\t"
					+ StartupTrend.describe(a) + (a.slower() ? "\t" + StartupTrend.regression(a).english() + " / " + StartupTrend.cause(a).english() : ""));
		}
	}
}
