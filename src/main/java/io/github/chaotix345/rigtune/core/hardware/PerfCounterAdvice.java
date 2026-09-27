package io.github.chaotix345.rigtune.core.hardware;

import io.github.chaotix345.rigtune.core.model.Text;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// docs/v0.5/SPEC.md 2L (RW-16 part a; rw §12.4): the launch-time advice Tools shows while Windows' performance counters are
// off (Perflib's own switch; a service's own switch needs none, OSHI skips those counters itself). It says what the
// setting is, the crash-report setup's time at this launch when it was measured (the whole time: other work is in it
// too), that 0 is Windows' default and a change needs an administrator and a restart, that a tuning tool may have set it
// on purpose, and that RigTune doesn't change Windows settings, with Microsoft's two pages. Never lodctr /R (the counter
// names are intact; Microsoft's own article first checks this value), never who set it.
public final class PerfCounterAdvice {
	// "Disable Performance Counters Entry", the Windows Server 2003 registry reference (still the one that documents it).
	public static final String ENTRY_URL = "https://learn.microsoft.com/en-us/previous-versions/windows/it-pro/windows-server-2003/cc737243(v=ws.10)";
	// KB 2554336, whose first resolution step is making sure the counters aren't disabled in the registry.
	public static final String KB_URL = "https://learn.microsoft.com/en-us/troubleshoot/windows-server/performance/manually-rebuild-performance-counters";

	// One line of the advice; url: the page it links to, or null.
	public record Line(Text text, @Nullable String url) {
	}

	private PerfCounterAdvice() {
	}

	// preloadMs: CrashReport.preload()'s time at this launch, or null when it wasn't measured.
	public static List<Line> lines(PerfCounters counters, @Nullable Long preloadMs) {
		if (!counters.off()) {
			return List.of();
		}
		List<Line> out = new ArrayList<>();
		out.add(new Line(Text.of("rigtune.startup.perf_counters.off", "Windows performance counters are turned off on this PC. \"Disable "
				+ "Performance Counters\" is Windows' own Perflib setting; it turns off every registry-based performance counter on the PC."), null));
		if (preloadMs != null) {
			out.add(new Line(Text.of("rigtune.startup.perf_counters.measured", "Minecraft's crash-report setup took %s s at this launch; about 1 s is "
					+ "usual. It asks Windows for these counters, so part of that time may be related to this setting.",
					String.format(Locale.ROOT, "%.1f", preloadMs / 1000.0)), null));
		}
		out.add(new Line(Text.of("rigtune.startup.perf_counters.windows", "0 is Windows' default. Changing it needs an administrator and a Windows "
				+ "restart, and a tuning tool may have set it on purpose. RigTune doesn't change Windows settings."), null));
		out.add(new Line(Text.of("rigtune.startup.perf_counters.link.entry", "Microsoft's registry reference, \"Disable Performance Counters Entry\": %s",
				ENTRY_URL), ENTRY_URL));
		out.add(new Line(Text.of("rigtune.startup.perf_counters.link.kb", "Microsoft's KB 2554336, whose first step is checking this setting: %s",
				KB_URL), KB_URL));
		return out;
	}
}
