import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;

import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.instrument.Instrumentation;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

// NEW-1 measurement (docs/v0.5/SPEC.md 2S, AC2S.11): logs every GarbageCollectorMXBean notification's raw strings
// (gcName | gcAction | gcCause), GcInfo id/start/end (ms since JVM start) and each heap pool's used MB before -> after, into
// the file given as the agent argument (%p = pid). Measurement tooling only; not part of the mod.
public final class GcProbeAgent {
	public static void premain(String args, Instrumentation inst) throws Exception {
		String path = (args == null || args.isEmpty() ? "gcprobe-%p.txt" : args).replace("%p", Long.toString(ProcessHandle.current().pid()));
		PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(path, true), StandardCharsets.UTF_8), true);
		out.println("# pid " + ProcessHandle.current().pid() + " args " + ManagementFactory.getRuntimeMXBean().getInputArguments());
		for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
			out.println("# pool " + p.getName() + " type=" + (p.getType() == MemoryType.HEAP ? "HEAP" : "NON_HEAP"));
		}
		for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
			out.println("# bean " + b.getName() + " pools=" + Arrays.toString(b.getMemoryPoolNames()));
			((NotificationEmitter) b).addNotificationListener((n, h) -> {
				if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(n.getType())) {
					return;
				}
				long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
				GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) n.getUserData());
				GcInfo gc = info.getGcInfo();
				StringBuilder pools = new StringBuilder();
				Map<String, MemoryUsage> before = new TreeMap<>(gc.getMemoryUsageBeforeGc());
				Map<String, MemoryUsage> after = gc.getMemoryUsageAfterGc();
				for (Map.Entry<String, MemoryUsage> e : before.entrySet()) {
					MemoryUsage a = after.get(e.getKey());
					pools.append(" | ").append(e.getKey()).append(' ').append(e.getValue().getUsed() >> 20).append("->")
							.append(a == null ? -1 : a.getUsed() >> 20).append(" MB");
				}
				synchronized (out) {
					out.println("rx=" + uptime + " | " + info.getGcName() + " | " + info.getGcAction() + " | " + info.getGcCause() + " | id=" + gc.getId()
							+ " start=" + gc.getStartTime() + " end=" + gc.getEndTime() + pools);
				}
			}, null, null);
		}
	}
}
