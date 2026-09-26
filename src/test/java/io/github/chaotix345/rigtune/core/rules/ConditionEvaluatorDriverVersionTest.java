package io.github.chaotix345.rigtune.core.rules;

import io.github.chaotix345.rigtune.core.hardware.GpuClassifier;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.github.chaotix345.rigtune.core.rules.Truth.FALSE;
import static io.github.chaotix345.rigtune.core.rules.Truth.TRUE;
import static io.github.chaotix345.rigtune.core.rules.Truth.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 9 (AC9.2, and AC9.3's current-vs-pinned part over fixture seeds until WS-R's content lands): the
// driverVersion condition is TRUE/FALSE only when the detected vendor matches and the driver string parses; everything
// else is UNKNOWN, so a `not` can never turn it on.
class ConditionEvaluatorDriverVersionTest {
	private static final String NV_RANGE = "{\"driverVersion\": {\"vendor\": \"nvidia\", \"atLeast\": \"526.47\", \"atMost\": \"536.22\"}}";

	private static EvalFixture nvidia(String driver) {
		return gpu("NVIDIA Corporation", "NVIDIA GeForce RTX 3070/PCIe/SSE2", driver, GraphicsBackend.OPENGL, GpuVendor.NVIDIA);
	}

	private static EvalFixture gpu(String vendorString, String renderer, String driver, GraphicsBackend backend, GpuVendor vendor) {
		EvalFixture f = new EvalFixture();
		f.hw.gpu = new GpuInfo(vendorString, renderer, driver, backend, 8192);
		f.gpu = new GpuClass(vendor, false, 4, null);
		return f;
	}

	@Test
	void trueInsideTheRangeFalseOutside() {
		assertEquals(TRUE, nvidia("4.6.0 NVIDIA 531.18").truth(NV_RANGE));
		assertEquals(TRUE, nvidia("4.6.0 NVIDIA 526.47").truth(NV_RANGE), "atLeast is inclusive");
		assertEquals(TRUE, nvidia("4.6.0 NVIDIA 536.22").truth(NV_RANGE), "atMost is inclusive");
		assertEquals(FALSE, nvidia("4.6.0 NVIDIA 526.46").truth(NV_RANGE));
		assertEquals(FALSE, nvidia("4.6.0 NVIDIA 536.23").truth(NV_RANGE));
		assertEquals(FALSE, nvidia("4.6.0 NVIDIA 560.94").truth(NV_RANGE));
		assertEquals(TRUE, nvidia("4.6.0 NVIDIA 560.94").truth("{\"driverVersion\": {\"vendor\": \"nvidia\"}}"), "no bound: parses = TRUE");
		assertEquals(TRUE, nvidia("4.6.0 NVIDIA 550.54.14").truth("{\"driverVersion\": {\"vendor\": \"nvidia\", \"atLeast\": \"550.54.14\"}}"));
		assertEquals(FALSE, nvidia("4.6.0 NVIDIA 550.54").truth("{\"driverVersion\": {\"vendor\": \"nvidia\", \"atLeast\": \"550.54.1\"}}"),
				"missing trailing parts are 0");
		assertEquals(TRUE, nvidia("4.6.0 NVIDIA 531.18").truth("{\"driverVersion\": {\"vendor\": \"NVIDIA\", \"atMost\": \"536.22\"}}"),
				"the vendor is compared case-insensitively");
	}

	@Test
	void vulkanDriverInfoPart() {
		EvalFixture f = gpu("NVIDIA", "NVIDIA GeForce RTX 3070", "1.3.296 NVIDIA 531.18", GraphicsBackend.VULKAN, GpuVendor.NVIDIA);
		assertEquals(TRUE, f.truth(NV_RANGE));
		EvalFixture radv = gpu("AMD", "AMD Radeon RX 7800 XT (RADV NAVI32)", "1.3.290 Mesa RADV 24.2.3", GraphicsBackend.VULKAN, GpuVendor.AMD);
		assertEquals(UNKNOWN, radv.truth("{\"driverVersion\": {\"vendor\": \"amd\", \"atLeast\": \"24.2\"}}"),
				"a Mesa version is never compared with Adrenalin's numbers");
		assertEquals(TRUE, gpu("AMD", "AMD Radeon RX 7800 XT", "1.4.349 AMD proprietary driver 26.8.1 (LLPC)", GraphicsBackend.VULKAN, GpuVendor.AMD)
				.truth("{\"driverVersion\": {\"vendor\": \"amd\", \"atLeast\": \"26.8\"}}"));
		assertEquals(UNKNOWN, gpu("AMD", "AMD Radeon RX 7800 XT", "1.3.260 AMD open-source driver 2023.Q3.1 (LLPC)", GraphicsBackend.VULKAN, GpuVendor.AMD)
				.truth("{\"driverVersion\": {\"vendor\": \"amd\", \"atLeast\": \"1\"}}"));
	}

	@Test
	void unknownForMismatchUnparseableUnknownKeyAndMissingVendor() {
		assertEquals(UNKNOWN, nvidia("4.6.0 NVIDIA 531.18").truth("{\"driverVersion\": {\"vendor\": \"amd\", \"atLeast\": \"1\"}}"), "vendor mismatch");
		assertEquals(UNKNOWN, nvidia("4.6.0 NVIDIA").truth(NV_RANGE), "unparseable driver string");
		assertEquals(UNKNOWN, nvidia("").truth(NV_RANGE), "empty driver string");
		assertEquals(UNKNOWN, nvidia("4.6.0 NVIDIA 531.18").truth("{\"driverVersion\": {\"vendor\": \"nvidia\", \"atleast\": \"526.47\"}}"), "unknown map key");
		assertEquals(UNKNOWN, nvidia("4.6.0 NVIDIA 531.18").truth("{\"driverVersion\": {\"vendor\": \"nvidia\", \"below\": \"600\"}}"), "unknown map key");
		assertEquals(UNKNOWN, nvidia("4.6.0 NVIDIA 531.18").truth("{\"driverVersion\": {\"atLeast\": \"526.47\"}}"), "missing vendor");
		assertEquals(UNKNOWN, nvidia("4.6.0 NVIDIA 531.18").truth("{\"driverVersion\": {}}"), "missing vendor");
		assertEquals(UNKNOWN, nvidia("4.6.0 NVIDIA 531.18").truth("{\"driverVersion\": {\"vendor\": \"3dfx\"}}"), "vendor outside the vocabulary");
		assertEquals(UNKNOWN, nvidia("4.6.0 NVIDIA 531.18").truth("{\"driverVersion\": {\"vendor\": \"nvidia\", \"atLeast\": \"526.x\"}}"), "bad number");
		assertEquals(UNKNOWN, nvidia("4.6.0 NVIDIA 531.18").truth("{\"driverVersion\": {\"vendor\": \"nvidia\", \"atMost\": \"\"}}"), "bad number");
		assertEquals(TRUE, nvidia("4.6.0 NVIDIA 531.18").truth("{\"driverVersion\": {\"vendor\": \"nvidia\", \"atMost\": 536.22}}"),
				"a JSON number is read as its text by the map adapter");
		EvalFixture unknownVendor = gpu("", "", "4.6.0 NVIDIA 531.18", GraphicsBackend.OPENGL, GpuVendor.UNKNOWN);
		assertEquals(UNKNOWN, unknownVendor.truth("{\"driverVersion\": {\"vendor\": \"unknown\"}}"), "an unknown detected vendor");
		assertEquals(UNKNOWN, unknownVendor.truth(NV_RANGE));
	}

	@Test
	void onlyTheVendorsOwnDriverFamilyTakesPart() {
		EvalFixture nouveau = gpu("nouveau", "NV167", "4.3 (Core Profile) Mesa 24.2.3", GraphicsBackend.OPENGL, GpuVendor.NVIDIA);
		assertEquals(UNKNOWN, nouveau.truth("{\"driverVersion\": {\"vendor\": \"nvidia\", \"atMost\": \"470\"}}"), "nouveau is not NVIDIA 24.2");
		EvalFixture zink = gpu("Mesa", "zink Vulkan 1.3(NVIDIA GeForce RTX 3070 (NVIDIA_PROPRIETARY))", "4.6 (Core Profile) Mesa 24.2.3",
				GraphicsBackend.OPENGL, GpuVendor.NVIDIA);
		assertEquals(UNKNOWN, zink.truth("{\"driverVersion\": {\"vendor\": \"nvidia\", \"atMost\": \"470\"}}"));
		EvalFixture radeonsi = gpu("AMD", "AMD Radeon RX 7800 XT (radeonsi, navi32)", "4.6 (Core Profile) Mesa 23.3.6", GraphicsBackend.OPENGL, GpuVendor.AMD);
		assertEquals(UNKNOWN, radeonsi.truth("{\"driverVersion\": {\"vendor\": \"amd\", \"atMost\": \"23.12\"}}"));
		EvalFixture anv = gpu("Intel", "Mesa Intel(R) UHD Graphics 620 (KBL GT2)", "4.6 (Core Profile) Mesa 24.0.5", GraphicsBackend.OPENGL, GpuVendor.INTEL);
		assertEquals(UNKNOWN, anv.truth("{\"driverVersion\": {\"vendor\": \"intel\", \"atMost\": \"10.18.10.5160\"}}"));
		EvalFixture adrenalin = gpu("ATI Technologies Inc.", "AMD Radeon RX 7800 XT", "3.3.0 Core Profile Context 26.8.1.260810", GraphicsBackend.OPENGL, GpuVendor.AMD);
		assertEquals(TRUE, adrenalin.truth("{\"driverVersion\": {\"vendor\": \"amd\", \"atLeast\": \"26.8\"}}"));
		assertEquals(FALSE, adrenalin.truth("{\"driverVersion\": {\"vendor\": \"amd\", \"atMost\": \"26.7.9\"}}"));
	}

	@Test
	void notOverUnknownStaysUnknown() {
		assertEquals(UNKNOWN, nvidia("4.6.0 NVIDIA").truth("{\"not\": " + NV_RANGE + "}"));
		assertEquals(UNKNOWN, nvidia("4.6.0 NVIDIA 531.18").truth("{\"not\": {\"driverVersion\": {\"vendor\": \"amd\"}}}"));
		assertEquals(FALSE, nvidia("4.6.0 NVIDIA 531.18").truth("{\"not\": " + NV_RANGE + "}"));
		assertEquals(TRUE, nvidia("4.6.0 NVIDIA 560.94").truth("{\"not\": " + NV_RANGE + "}"));
		assertEquals(UNKNOWN, nvidia("4.6.0 NVIDIA").truth("{\"anyOf\": [" + NV_RANGE + ", {\"always\": false}]}"));
	}

	// AC9.3 (the part this workstream owns): with the seeds as fixture rules, 0.3.0's pinned classes (= 0.2.0's) poison
	// every seeded `when` to UNKNOWN on every machine, while the current code gives the intended TRUE/FALSE.
	@Test
	void seedsFailClosedOn030AndDecideNow() throws IOException {
		String json;
		try (InputStream in = getClass().getResourceAsStream("/awareness/driver-seeds.json")) {
			json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		RulesDocument current = RulesLoader.parse(json);
		io.github.chaotix345.rigtune.v030.core.rules.RulesDocument old = io.github.chaotix345.rigtune.v030.core.rules.RulesLoader.parse(json);
		assertEquals(2, current.advice.size());
		assertEquals(2, old.advice.size());
		record Case(String vendor, String renderer, String driver, String os, Truth nvidiaSeed, Truth intelSeed) {
		}
		List<Case> cases = List.of(
				new Case("NVIDIA Corporation", "NVIDIA GeForce RTX 3070/PCIe/SSE2", "4.6.0 NVIDIA 531.18", "Windows 11", TRUE, FALSE),
				new Case("NVIDIA Corporation", "NVIDIA GeForce RTX 3070/PCIe/SSE2", "4.6.0 NVIDIA 560.94", "Windows 11", FALSE, FALSE),
				new Case("NVIDIA Corporation", "NVIDIA GeForce RTX 3070/PCIe/SSE2", "4.6.0 NVIDIA 531.18", "Linux", FALSE, FALSE),
				new Case("Intel", "Intel(R) HD Graphics 4000", "4.0.0 - Build 10.18.10.4358", "Windows 10", FALSE, TRUE),
				new Case("Intel", "Intel(R) HD Graphics 4000", "4.0.0 - Build 10.18.10.5161", "Windows 10", FALSE, FALSE),
				new Case("Intel", "Intel(R) HD Graphics 4000", "4.0.0 - Build", "Windows 10", FALSE, UNKNOWN),
				new Case("NVIDIA Corporation", "NVIDIA GeForce RTX 3070/PCIe/SSE2", "garbage", "Windows 11", UNKNOWN, FALSE));
		for (Case c : cases) {
			EvalFixture f = new EvalFixture();
			f.hw.gpu = new GpuInfo(c.vendor(), c.renderer(), c.driver(), GraphicsBackend.OPENGL, -1);
			f.hw.os = c.os();
			GpuVendor vendor = GpuClassifier.detectVendor(c.vendor(), c.renderer());
			f.gpu = new GpuClass(vendor, vendor == GpuVendor.INTEL, 2, null);
			EvalContext now = f.context();
			assertEquals(c.nvidiaSeed(), ConditionEvaluator.evaluate(current.advice.get(0).when, now), c.toString());
			assertEquals(c.intelSeed(), ConditionEvaluator.evaluate(current.advice.get(1).when, now), c.toString());
			HardwareProfile hw = f.hw.build();
			var oldCtx = new io.github.chaotix345.rigtune.v030.core.rules.EvalContext(hw, f.gpu, f.tier, f.goal, f.mods);
			for (var rule : old.advice) {
				assertTrue(rule.when.unknownFields.contains("driverVersion"));
				assertEquals(io.github.chaotix345.rigtune.v030.core.rules.Truth.UNKNOWN,
						io.github.chaotix345.rigtune.v030.core.rules.ConditionEvaluator.evaluate(rule.when, oldCtx), rule.id + " on 0.3.0, " + c);
			}
		}
	}
}
