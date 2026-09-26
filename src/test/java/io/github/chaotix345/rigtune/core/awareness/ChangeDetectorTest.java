package io.github.chaotix345.rigtune.core.awareness;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.CpuInfo;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.chaotix345.rigtune.core.awareness.ChangeDetector.Kind.DRIVER;
import static io.github.chaotix345.rigtune.core.awareness.ChangeDetector.Kind.GPU;
import static io.github.chaotix345.rigtune.core.awareness.ChangeDetector.Kind.HARDWARE;
import static io.github.chaotix345.rigtune.core.awareness.ChangeDetector.Kind.NONE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 9 (AC9.5): the first run seeds silently (also a 0.3-shaped instance), unchanged is nothing, a driver
// change is the driver notice, a renderer change the GPU notice, a backend-only switch nothing, corrupt -> .bad and a
// silent seed, newer -> read-only.
class ChangeDetectorTest {
	@TempDir
	Path config;

	private static Fingerprint rig(String driver) {
		Fixtures.Hw hw = Fixtures.userRig();
		hw.gpu = new GpuInfo("ATI Technologies Inc.", "AMD Radeon RX 7800 XT", driver, GraphicsBackend.OPENGL, 16384);
		return Fingerprint.of(hw.build());
	}

	private static final Fingerprint AMD = rig("3.3.0 Core Profile Context 26.8.1.260810");

	private static Fingerprint with(Fingerprint f, String vendorString, String renderer, String driver, GraphicsBackend backend) {
		Fixtures.Hw hw = Fixtures.userRig();
		hw.gpu = new GpuInfo(vendorString, renderer, driver, backend, 8192);
		Fingerprint g = Fingerprint.of(hw.build());
		return new Fingerprint(g.gpuVendor(), g.gpuRenderer(), g.gpuDriverRaw(), g.backend(), f.cpuName(), f.totalRamMb());
	}

	@Test
	void fingerprintOfTheProbe() {
		assertEquals("AMD", AMD.gpuVendor());
		assertEquals("AMD Radeon RX 7800 XT", AMD.gpuRenderer());
		assertEquals("OPENGL", AMD.backend());
		assertEquals("AMD Ryzen 7 7800X3D 8-Core Processor", AMD.cpuName());
		assertEquals(32768, AMD.totalRamMb());
		assertEquals(AMD.id(), rig("3.3.0 Core Profile Context 26.8.1.260810").id());
		assertFalse(AMD.id().equals(rig("3.3.0 Core Profile Context 26.9.1.260910").id()));
	}

	@Test
	void unchangedIsNothingAndNoBaselineIsNothing() {
		assertEquals(NONE, ChangeDetector.compare(AMD, rig("3.3.0 Core Profile Context 26.8.1.260810")).kind());
		assertEquals(NONE, ChangeDetector.compare(null, AMD).kind());
		assertEquals(NONE, ChangeDetector.compare(AMD, rig("3.3.0 Core Profile Context 26.8.1.260901")).kind(),
				"same parsed version, other build stamp: the same driver");
	}

	@Test
	void driverChange() {
		ChangeDetector.Change change = ChangeDetector.compare(AMD, rig("3.3.0 Core Profile Context 26.9.1.260915"));
		assertEquals(DRIVER, change.kind());
		assertEquals("26.8.1", change.from());
		assertEquals("26.9.1", change.to());
		ChangeDetector.Change raw = ChangeDetector.compare(AMD, rig("3.3.0 Core Profile Context 27.1"));
		assertEquals(DRIVER, raw.kind(), "unparseable on the same backend: the raw strings differ");
		assertEquals("3.3.0 Core Profile Context 26.8.1.260810", raw.from());
		assertEquals("3.3.0 Core Profile Context 27.1", raw.to());
	}

	@Test
	void gpuChange() {
		assertEquals(GPU, ChangeDetector.compare(AMD, with(AMD, "ATI Technologies Inc.", "AMD Radeon RX 9070 XT", AMD.gpuDriverRaw(), GraphicsBackend.OPENGL)).kind());
		assertEquals(GPU, ChangeDetector.compare(AMD, with(AMD, "NVIDIA Corporation", "NVIDIA GeForce RTX 4070/PCIe/SSE2", "4.6.0 NVIDIA 566.03",
				GraphicsBackend.OPENGL)).kind(), "a GPU change beats the driver change that comes with it");
	}

	@Test
	void backendOnlySwitchIsNothing() {
		Fingerprint gl = with(AMD, "NVIDIA Corporation", "NVIDIA GeForce RTX 3070/PCIe/SSE2", "4.6.0 NVIDIA 560.94", GraphicsBackend.OPENGL);
		Fingerprint vk = with(AMD, "NVIDIA", "NVIDIA GeForce RTX 3070", "1.3.296 NVIDIA 560.94", GraphicsBackend.VULKAN);
		assertEquals(NONE, ChangeDetector.compare(gl, vk).kind());
		assertEquals(NONE, ChangeDetector.compare(vk, gl).kind());
		Fingerprint amdVk = with(AMD, "AMD", "AMD Radeon RX 7800 XT", "1.3.296 AMD proprietary driver 26.8.1", GraphicsBackend.VULKAN);
		assertEquals(NONE, ChangeDetector.compare(AMD, amdVk).kind(), "unparseable across a backend switch: can't tell, no notice");
		Fingerprint vkNewer = with(AMD, "NVIDIA", "NVIDIA GeForce RTX 3070", "1.3.296 NVIDIA 566.03", GraphicsBackend.VULKAN);
		ChangeDetector.Change both = ChangeDetector.compare(gl, vkNewer);
		assertEquals(DRIVER, both.kind(), "a backend switch plus a driver update is still a driver update");
		assertEquals("560.94", both.from());
		assertEquals("566.03", both.to());
		Fingerprint mesaGl = with(AMD, "AMD", "AMD Radeon RX 7800 XT (radeonsi, navi32, LLVM 17.0.6, DRM 3.54, 6.5.0-generic)",
				"4.6 (Core Profile) Mesa 24.2.3", GraphicsBackend.OPENGL);
		Fingerprint radv = with(AMD, "AMD", "AMD Radeon RX 7800 XT (RADV NAVI32)", "1.3.290 Mesa RADV 24.2.3", GraphicsBackend.VULKAN);
		assertEquals(NONE, ChangeDetector.compare(mesaGl, radv).kind());
	}

	@Test
	void mesaUpdateChangesTheRendererSuffixButIsADriverChange() {
		Fingerprint a = with(AMD, "AMD", "AMD Radeon RX 7800 XT (radeonsi, navi32, LLVM 17.0.6, DRM 3.54)", "4.6 (Core Profile) Mesa 24.0.9",
				GraphicsBackend.OPENGL);
		Fingerprint b = with(AMD, "AMD", "AMD Radeon RX 7800 XT (radeonsi, navi32, LLVM 18.1.8, DRM 3.57)", "4.6 (Core Profile) Mesa 24.2.3",
				GraphicsBackend.OPENGL);
		assertEquals(DRIVER, ChangeDetector.compare(a, b).kind());
		assertEquals("intel uhd graphics 620", ChangeDetector.normalisedRenderer("Mesa Intel(R) UHD Graphics 620 (KBL GT2)"));
		assertEquals("nvidia geforce rtx 3070", ChangeDetector.normalisedRenderer("NVIDIA GeForce RTX 3070/PCIe/SSE2"));
	}

	@Test
	void cpuOrRamChangeIsAHardwareChange() {
		Fingerprint cpu = new Fingerprint(AMD.gpuVendor(), AMD.gpuRenderer(), AMD.gpuDriverRaw(), AMD.backend(), "AMD Ryzen 7 9800X3D 8-Core Processor", 32768);
		assertEquals(HARDWARE, ChangeDetector.compare(AMD, cpu).kind());
		Fingerprint ram = new Fingerprint(AMD.gpuVendor(), AMD.gpuRenderer(), AMD.gpuDriverRaw(), AMD.backend(), AMD.cpuName(), 65536);
		assertEquals(HARDWARE, ChangeDetector.compare(AMD, ram).kind());
		Fingerprint noise = new Fingerprint(AMD.gpuVendor(), AMD.gpuRenderer(), AMD.gpuDriverRaw(), AMD.backend(), AMD.cpuName(), 32700);
		assertEquals(NONE, ChangeDetector.compare(AMD, noise).kind());
		Fingerprint unknownRam = new Fingerprint(AMD.gpuVendor(), AMD.gpuRenderer(), AMD.gpuDriverRaw(), AMD.backend(), AMD.cpuName(), -1);
		assertEquals(NONE, ChangeDetector.compare(AMD, unknownRam).kind());
	}

	@Test
	void emptyProbeValuesAreNoChange() {
		Fixtures.Hw hw = Fixtures.userRig();
		hw.gpu = new GpuInfo("", "", "", GraphicsBackend.UNKNOWN, -1);
		hw.cpu = new CpuInfo("", 8, 16, -1);
		hw.ramMb = -1;
		assertEquals(NONE, ChangeDetector.compare(AMD, Fingerprint.of(hw.build())).kind());
	}

	@Test
	void firstRunSeedsSilentlyThenDetects() {
		AwarenessStore store = AwarenessStore.shared(config);
		assertEquals(NONE, ChangeDetector.check(store, AMD).kind());
		assertEquals(AMD, Fingerprint.read(store.read()), "seeded");
		assertEquals(NONE, ChangeDetector.check(store, AMD).kind());
		Fingerprint newer = rig("3.3.0 Core Profile Context 26.9.1.260915");
		assertEquals(DRIVER, ChangeDetector.check(store, newer).kind());
		assertEquals(DRIVER, ChangeDetector.check(store, newer).kind(), "not committed yet: still a change");
		assertTrue(ChangeDetector.commit(store, newer));
		assertEquals(NONE, ChangeDetector.check(store, newer).kind());
	}

	@Test
	void a03ShapedInstanceSeedsSilently() throws IOException {
		Path dir = Files.createDirectories(config.resolve("rigtune"));
		Files.writeString(dir.resolve("rigtune.json"), "{\"goal\":\"BALANCED\"}", StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("history.json"), "{\"formatVersion\":1,\"entries\":[]}", StandardCharsets.UTF_8);
		AwarenessStore store = AwarenessStore.shared(config);
		assertEquals(NONE, ChangeDetector.check(store, AMD).kind());
		assertTrue(Files.isRegularFile(dir.resolve("awareness.json")));
		assertEquals(AMD, Fingerprint.read(store.read()));
	}

	@Test
	void corruptFileMovesAsideAndSeedsSilently() throws IOException {
		Path dir = Files.createDirectories(config.resolve("rigtune"));
		Files.writeString(dir.resolve("awareness.json"), "{not json", StandardCharsets.UTF_8);
		AwarenessStore store = AwarenessStore.shared(config);
		assertEquals(NONE, ChangeDetector.check(store, AMD).kind());
		assertTrue(Files.isRegularFile(dir.resolve("awareness.json.bad")));
		assertEquals(AMD, Fingerprint.read(store.read()));
	}

	@Test
	void anUnusableFingerprintIsAFirstRun() throws IOException {
		Path dir = Files.createDirectories(config.resolve("rigtune"));
		Files.writeString(dir.resolve("awareness.json"), "{\"formatVersion\":1,\"fingerprint\":{\"gpuVendor\":5,\"gpuRenderer\":[]},\"dismissed\":[]}",
				StandardCharsets.UTF_8);
		AwarenessStore store = AwarenessStore.shared(config);
		assertNull(Fingerprint.read(store.read()));
		assertEquals(NONE, ChangeDetector.check(store, AMD).kind());
		assertEquals(AMD, Fingerprint.read(store.read()));
	}

	@Test
	void newerFileIsReadOnly() throws IOException {
		Path dir = Files.createDirectories(config.resolve("rigtune"));
		String newer = "{\"formatVersion\":2,\"fingerprint\":{\"gpuVendor\":\"NVIDIA\",\"gpuRenderer\":\"x\",\"gpuDriverRaw\":\"y\",\"backend\":\"OPENGL\","
				+ "\"cpuName\":\"z\",\"totalRamMb\":1},\"dismissed\":[]}";
		Files.writeString(dir.resolve("awareness.json"), newer, StandardCharsets.UTF_8);
		AwarenessStore store = AwarenessStore.shared(config);
		assertEquals(NONE, ChangeDetector.check(store, AMD).kind(), "a notice it could never acknowledge doesn't show");
		assertFalse(ChangeDetector.commit(store, AMD));
		assertEquals(newer, Files.readString(dir.resolve("awareness.json"), StandardCharsets.UTF_8));
	}

	@Test
	void unknownFieldsSurviveAndTheShapeIsC1s() throws IOException {
		Path dir = Files.createDirectories(config.resolve("rigtune"));
		Files.writeString(dir.resolve("awareness.json"), "{\"formatVersion\":1,\"future\":{\"a\":1},\"fingerprint\":{\"gpuVendor\":\"AMD\","
				+ "\"gpuRenderer\":\"AMD Radeon RX 7800 XT\",\"gpuDriverRaw\":\"old\",\"backend\":\"OPENGL\",\"cpuName\":\"AMD Ryzen 7 7800X3D 8-Core Processor\","
				+ "\"totalRamMb\":32768,\"extra\":true},\"dismissed\":[\"x\"]}", StandardCharsets.UTF_8);
		AwarenessStore store = AwarenessStore.shared(config);
		assertTrue(ChangeDetector.commit(store, AMD));
		JsonObject root = JsonParser.parseString(Files.readString(dir.resolve("awareness.json"), StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals(1, root.get("formatVersion").getAsInt());
		assertNotNull(root.get("future"));
		JsonObject f = root.getAsJsonObject("fingerprint");
		assertTrue(f.get("extra").getAsBoolean());
		assertArrayEquals(new String[]{"gpuVendor", "gpuRenderer", "gpuDriverRaw", "backend", "cpuName", "totalRamMb", "extra"},
				f.keySet().stream().sorted((a, b) -> order(a) - order(b)).toArray(String[]::new));
		assertEquals(AMD.gpuDriverRaw(), f.get("gpuDriverRaw").getAsString());
		assertEquals("x", root.getAsJsonArray("dismissed").get(0).getAsString());
	}

	private static int order(String key) {
		int i = java.util.List.of("gpuVendor", "gpuRenderer", "gpuDriverRaw", "backend", "cpuName", "totalRamMb").indexOf(key);
		return i < 0 ? 99 : i;
	}
}
