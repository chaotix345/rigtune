package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.core.apply.TestJars;
import io.github.chaotix345.rigtune.core.history.JarInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModsFolderTest {
	@Test
	void loadedJarsUseTheLoadersMetadataAndOthersAreRead(@TempDir Path mods) throws Exception {
		TestJars.modJar(mods.resolve("sodium.jar"), "sodium-on-disk");
		TestJars.modJar(mods.resolve("indium.jar.disabled"), "indium");
		Files.writeString(mods.resolve("notes.txt"), "x");
		JarInfo loaded = new JarInfo("sodium", Set.of("nested-lib"), Set.of("minecraft"));

		ModsFolder folder = new ModsFolder(mods, Map.of("sodium.jar", loaded), Set.of("fabric-api"));

		assertEquals(Set.of("sodium.jar", "indium.jar.disabled", "notes.txt"), folder.files());
		assertEquals(loaded, folder.jar("sodium.jar"));
		assertEquals("indium", folder.jar("indium.jar.disabled").id());
		assertNull(folder.jar("notes.txt"));
		assertNull(folder.jar("missing.jar"));
		assertEquals(Set.of("fabric-api"), folder.providedElsewhere());
		assertEquals(mods, folder.dir());
	}

	// Planning reads jars off the render thread; the re-check when the undo is confirmed reuses what it read, unless the
	// file changed since.
	@Test
	void jarsAreReadOnceUntilTheyChange(@TempDir Path mods) throws Exception {
		Path jar = TestJars.modJar(mods.resolve("indium.jar.disabled"), "indium");
		AtomicInteger reads = new AtomicInteger();
		ModsFolder.JarCache cache = new ModsFolder.JarCache(file -> {
			reads.incrementAndGet();
			return JarInfo.read(file);
		});

		assertEquals("indium", cache.get(jar).id());
		assertEquals("indium", cache.get(jar).id());
		assertEquals(1, reads.get());

		Files.setLastModifiedTime(TestJars.modJar(jar, "indium-renamed"), FileTime.fromMillis(System.currentTimeMillis() + 5000));

		assertEquals("indium-renamed", cache.get(jar).id());
		assertEquals(2, reads.get());
		assertNull(cache.get(mods.resolve("missing.jar")));
	}
}
