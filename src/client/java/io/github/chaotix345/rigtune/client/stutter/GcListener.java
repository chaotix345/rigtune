package io.github.chaotix345.rigtune.client.stutter;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.stutter.GcKind;
import io.github.chaotix345.rigtune.core.stutter.StutterRings;
import org.jspecify.annotations.Nullable;

import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// The GC signal (docs/v0.4/SPEC.md 5; research §1): a NotificationListener on every GarbageCollectorMXBean, added only
// while a capture is on. The JDK enables a bean's notifications for its first listener and disables them again when the
// last one goes, so with capture off the JVM builds no GC notifications at all. Notifications arrive on the JVM's
// "Notification Thread"; each becomes one GC record (receive time, GcInfo start/end, GcKind flags, the old generation's
// usage after a major collection). A JVM without com.sun.management turns the signal off (logged once).
final class GcListener implements NotificationListener {
	private final List<NotificationEmitter> registered = new ArrayList<>();
	private volatile @Nullable StutterRings rings;
	private volatile @Nullable String collector;
	private boolean failureLogged;

	synchronized void start(StutterRings target) {
		stop();
		rings = target;
		List<String> names = new ArrayList<>();
		try {
			for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
				names.add(bean.getName());
				if (bean instanceof NotificationEmitter emitter) {
					emitter.addNotificationListener(this, null, null);
					registered.add(emitter);
				}
			}
		} catch (RuntimeException | LinkageError e) {
			RigTune.LOGGER.warn("Stutter Doctor: GC notifications unavailable; GC won't be measured", e);
		}
		collector = GcKind.family(names);
	}

	synchronized void stop() {
		for (NotificationEmitter emitter : registered) {
			try {
				emitter.removeNotificationListener(this);
			} catch (ListenerNotFoundException | RuntimeException e) {
				RigTune.LOGGER.debug("Stutter Doctor: GC listener already gone", e);
			}
		}
		registered.clear();
		rings = null;
	}

	synchronized boolean active() {
		return !registered.isEmpty();
	}

	// g1, zgc, shenandoah, parallel, serial, or null (unknown or never started).
	@Nullable String collector() {
		return collector;
	}

	@Override
	public void handleNotification(Notification notification, Object handback) {
		long received = System.nanoTime();
		StutterRings target = rings;
		if (target == null || !GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
			return;
		}
		try {
			GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
			GcInfo gc = info.getGcInfo();
			int flags = GcKind.classify(info.getGcName(), info.getGcAction(), info.getGcCause());
			long used = (flags & GcKind.MAJOR) != 0 ? oldGenerationUsed(gc.getMemoryUsageAfterGc()) : 0;
			target.gc(received, gc.getStartTime(), gc.getEndTime(), flags, used);
		} catch (RuntimeException | LinkageError e) {
			if (!failureLogged) {
				failureLogged = true;
				RigTune.LOGGER.warn("Stutter Doctor: could not read a GC notification", e);
			}
		}
	}

	// The old generation's pool when there is one, else every pool (Shenandoah has a single one).
	static long oldGenerationUsed(Map<String, MemoryUsage> after) {
		long old = 0;
		long all = 0;
		boolean found = false;
		for (Map.Entry<String, MemoryUsage> e : after.entrySet()) {
			long used = e.getValue() == null ? 0 : e.getValue().getUsed();
			all += used;
			if (GcKind.oldPool(e.getKey())) {
				old += used;
				found = true;
			}
		}
		return found ? old : all;
	}
}
