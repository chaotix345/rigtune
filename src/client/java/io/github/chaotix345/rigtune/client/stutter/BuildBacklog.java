package io.github.chaotix345.rigtune.client.stutter;

import io.github.chaotix345.rigtune.RigTune;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jspecify.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

// The chunk-build backlog (docs/v0.4/SPEC.md 5; research §3.3), read on the render thread from the tick and published for
// the sampler. Sodium 0.9.2: SodiumWorldRenderer.instanceNullable() -> renderSectionManager -> getBuilder() ->
// getScheduledJobCount / getBusyThreadCount / getTotalThreadCount, through reflection resolved once (Sodium is optional
// and not on RigTune's compile path). Any absent or changed member switches the Sodium reading off (logged once), and
// vanilla's SectionRenderDispatcher.getCompileQueueSize() is used instead; never a crash. -1 = unknown.
public final class BuildBacklog {
	private static final String RENDERER = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer";
	private static final MethodType COUNT = MethodType.methodType(int.class, Object.class);

	private static volatile int scheduled = -1;
	private static volatile int busy = -1;
	private static volatile int total = -1;

	private static boolean sodiumTried;
	private static boolean sodiumOff;
	private static @Nullable MethodHandle instance;
	private static @Nullable MethodHandle manager;
	private static @Nullable MethodHandle builder;
	private static @Nullable MethodHandle scheduledCount;
	private static @Nullable MethodHandle busyCount;
	private static @Nullable MethodHandle totalCount;

	private BuildBacklog() {
	}

	static int scheduled() {
		return scheduled;
	}

	static int busy() {
		return busy;
	}

	static int total() {
		return total;
	}

	// Render thread (END_CLIENT_TICK, a few times a second while capturing).
	static void refresh(Minecraft minecraft, boolean sodiumLoaded) {
		if (sodiumLoaded && !sodiumOff && sodium()) {
			return;
		}
		try {
			SectionRenderDispatcher dispatcher = minecraft.levelRenderer == null ? null : minecraft.levelRenderer.sectionRenderDispatcher();
			scheduled = dispatcher == null ? -1 : dispatcher.getCompileQueueSize();
		} catch (RuntimeException e) {
			scheduled = -1;
		}
		busy = -1;
		total = -1;
	}

	private static boolean sodium() {
		try {
			if (!sodiumTried) {
				sodiumTried = true;
				resolve();
			}
			Object renderer = (Object) instance.invokeExact();
			Object sections = renderer == null ? null : (Object) manager.invokeExact(renderer);
			Object chunks = sections == null ? null : (Object) builder.invokeExact(sections);
			if (chunks == null) {
				scheduled = -1;
				busy = -1;
				total = -1;
				return true;
			}
			scheduled = (int) scheduledCount.invokeExact(chunks);
			busy = (int) busyCount.invokeExact(chunks);
			total = (int) totalCount.invokeExact(chunks);
			return true;
		} catch (Throwable e) {
			sodiumOff = true;
			RigTune.LOGGER.warn("Stutter Doctor: Sodium's chunk builder isn't readable ({}); using vanilla's compile queue", e.toString());
			return false;
		}
	}

	private static void resolve() throws ReflectiveOperationException {
		MethodHandles.Lookup lookup = MethodHandles.publicLookup();
		Class<?> renderer = Class.forName(RENDERER);
		Method instanceMethod = renderer.getMethod("instanceNullable");
		instance = lookup.unreflect(instanceMethod).asType(MethodType.methodType(Object.class));
		Field field = renderer.getDeclaredField("renderSectionManager");
		field.setAccessible(true);
		manager = MethodHandles.lookup().unreflectGetter(field).asType(MethodType.methodType(Object.class, Object.class));
		Method getBuilder = field.getType().getMethod("getBuilder");
		builder = lookup.unreflect(getBuilder).asType(MethodType.methodType(Object.class, Object.class));
		Class<?> chunkBuilder = getBuilder.getReturnType();
		scheduledCount = lookup.unreflect(chunkBuilder.getMethod("getScheduledJobCount")).asType(COUNT);
		busyCount = lookup.unreflect(chunkBuilder.getMethod("getBusyThreadCount")).asType(COUNT);
		totalCount = lookup.unreflect(chunkBuilder.getMethod("getTotalThreadCount")).asType(COUNT);
	}
}
