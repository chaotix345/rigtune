package io.github.chaotix345.rigtune.client.probe;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.RepoFiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 2L (AC2L.4's timer part): vanilla's CrashReport.preload() timed by CrashReportMixin through two clock
// reads; not measured (the mixin didn't apply, or preload hasn't run) is null, so the advice leaves the number out.
class PreloadTimerTest {
	@AfterEach
	void reset() {
		PreloadTimer.reset();
	}

	@Test
	void notMeasuredIsNull() {
		PreloadTimer.reset();
		assertNull(PreloadTimer.preloadMs());
		PreloadTimer.end();
		assertNull(PreloadTimer.preloadMs(), "an end without a start measures nothing");
		PreloadTimer.reset();
		PreloadTimer.start();
		assertNull(PreloadTimer.preloadMs(), "still running");
	}

	@Test
	void startThenEndIsTheDuration() throws InterruptedException {
		PreloadTimer.reset();
		PreloadTimer.start();
		Thread.sleep(30);
		PreloadTimer.end();
		Long ms = PreloadTimer.preloadMs();
		assertNotNull(ms);
		assertTrue(ms >= 25 && ms < 5_000, "about the 30 ms slept: " + ms);
		assertEquals(ms, PreloadTimer.preloadMs(), "kept");
	}

	// Review-11 COMPAT-6: a dedicated server's Main in the same JVM (the game tests' in-process server) runs preload() again;
	// the launch's own measurement is the one kept ("at this launch"), not the later call's.
	@Test
	void aLaterPreloadInTheSameJvmKeepsTheLaunchsMeasurement() throws InterruptedException {
		PreloadTimer.reset();
		PreloadTimer.start();
		Thread.sleep(30);
		PreloadTimer.end();
		Long launch = PreloadTimer.preloadMs();
		PreloadTimer.start();
		PreloadTimer.end();
		assertEquals(launch, PreloadTimer.preloadMs());
		assertTrue(launch >= 25, "the launch's: " + launch);
	}

	// Optional: a missing target only leaves the number out (require = 0 on every injector), and the mixin is registered.
	@Test
	void theMixinIsRegisteredAndOptional() throws IOException {
		String json = Files.readString(RepoFiles.resolve("src/client/resources/rigtune.client.mixins.json"));
		JsonArray client = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("client");
		assertTrue(client.toString().contains("\"CrashReportMixin\""), client.toString());
		String mixin = Files.readString(RepoFiles.resolve("src/client/java/io/github/chaotix345/rigtune/client/mixin/CrashReportMixin.java"));
		Matcher injects = Pattern.compile("^\\s*@Inject\\((.*)\\)\\s*$", Pattern.MULTILINE).matcher(mixin);
		int count = 0;
		while (injects.find()) {
			count++;
			assertTrue(injects.group(1).contains("require = 0"), injects.group());
			assertTrue(injects.group(1).contains("method = \"preload\""), injects.group());
		}
		assertEquals(2, count, "HEAD and RETURN");
		assertTrue(mixin.contains("@Mixin(CrashReport.class)"));
	}
}
