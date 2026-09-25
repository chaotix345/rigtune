package io.github.chaotix345.rigtune.core.rules;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.DisplayInfo;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import io.github.chaotix345.rigtune.core.model.TierResult;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionEvaluatorTest {
	private final Fixtures.Hw hw = Fixtures.userRig();
	private GpuClass gpu = new GpuClass(GpuVendor.AMD, false, 5, null);
	private TierResult tier = new TierResult(4, 3, 5, 4, 5, "cpu");
	private Goal goal = Goal.PERFORMANCE;
	private Set<String> mods = Set.of("sodium", "lithium");

	private boolean eval(String json) {
		return ConditionEvaluator.matches(RulesLoader.condition(json), new EvalContext(hw.build(), gpu, tier, goal, mods));
	}

	@Test
	void emptyAndNullAreTrue() {
		assertTrue(eval("{}"));
		assertTrue(ConditionEvaluator.matches(null, new EvalContext(hw.build(), gpu, tier, goal, mods)));
	}

	@Test
	void always() {
		assertTrue(eval("{\"always\":true}"));
		assertFalse(eval("{\"always\":false}"));
	}

	@Test
	void tiersUseEffectiveRawGpuAndCpu() {
		assertTrue(eval("{\"tierAtLeast\":3,\"tierAtMost\":3}"));
		assertFalse(eval("{\"tierAtLeast\":4}"));
		assertTrue(eval("{\"rawTierAtLeast\":4,\"rawTierAtMost\":4}"));
		assertFalse(eval("{\"rawTierAtMost\":3}"));
		assertTrue(eval("{\"gpuTierAtLeast\":5}"));
		assertFalse(eval("{\"gpuTierAtMost\":4}"));
		assertTrue(eval("{\"cpuTierAtMost\":4}"));
		assertFalse(eval("{\"cpuTierAtLeast\":5}"));
	}

	@Test
	void gpuVendorAndIntegrated() {
		assertTrue(eval("{\"gpuVendor\":[\"nvidia\",\"amd\"]}"));
		assertFalse(eval("{\"gpuVendor\":[\"nvidia\"]}"));
		assertTrue(eval("{\"gpuIntegrated\":false}"));
		gpu = new GpuClass(GpuVendor.INTEL, true, 2, null);
		assertTrue(eval("{\"gpuVendor\":[\"intel\"],\"gpuIntegrated\":true}"));
	}

	@Test
	void battery() {
		assertTrue(eval("{\"hasBattery\":false,\"onBattery\":false}"));
		hw.hasBattery = true;
		hw.onBattery = true;
		assertTrue(eval("{\"hasBattery\":true,\"onBattery\":true}"));
		assertFalse(eval("{\"onBattery\":false}"));
	}

	@Test
	void memory() {
		assertTrue(eval("{\"heapMbAtLeast\":6144,\"heapMbAtMost\":6144}"));
		assertFalse(eval("{\"heapMbAtMost\":4096}"));
		assertTrue(eval("{\"ramMbAtLeast\":16000}"));
		assertFalse(eval("{\"ramMbAtMost\":16000}"));
		hw.ramMb = -1;
		assertFalse(eval("{\"ramMbAtMost\":16000}"));
	}

	@Test
	void vramUnknownIsFalse() {
		assertTrue(eval("{\"vramMbAtLeast\":8192}"));
		assertFalse(eval("{\"vramMbAtMost\":8192}"));
		hw.gpu = new GpuInfo("ATI Technologies Inc.", "AMD Radeon RX 7800 XT", "1", GraphicsBackend.OPENGL, -1);
		assertFalse(eval("{\"vramMbAtLeast\":1}"));
		assertFalse(eval("{\"vramMbAtMost\":100000}"));
	}

	@Test
	void refreshRateUnknownIsFalse() {
		assertTrue(eval("{\"refreshRateAtLeast\":144}"));
		assertFalse(eval("{\"refreshRateAtLeast\":240}"));
		hw.display = new DisplayInfo(1920, 1080, -1, false);
		assertFalse(eval("{\"refreshRateAtLeast\":30}"));
		hw.display = null;
		assertFalse(eval("{\"refreshRateAtLeast\":30}"));
	}

	@Test
	void backendOsGoalAndVersion() {
		assertTrue(eval("{\"backend\":[\"opengl\"]}"));
		assertFalse(eval("{\"backend\":[\"vulkan\"]}"));
		assertTrue(eval("{\"os\":[\"windows\"]}"));
		assertTrue(eval("{\"os\":[\"win\"]}"));
		assertFalse(eval("{\"os\":[\"linux\",\"macos\"]}"));
		hw.os = "Mac OS X";
		assertTrue(eval("{\"os\":[\"macos\"]}"));
		assertTrue(eval("{\"goal\":[\"performance\"]}"));
		assertFalse(eval("{\"goal\":[\"balanced\",\"quality\"]}"));
		assertTrue(eval("{\"mcVersion\":[\"26.2\"]}"));
		assertFalse(eval("{\"mcVersion\":[\"26.2.1\",\"26.3\"]}"));
	}

	@Test
	void osFamilies() {
		assertEquals("windows", ConditionEvaluator.osFamily("Windows 11"));
		assertEquals("macos", ConditionEvaluator.osFamily("Mac OS X"));
		assertEquals("linux", ConditionEvaluator.osFamily("Linux"));
		assertEquals("freebsd", ConditionEvaluator.osFamily("FreeBSD"));
	}

	@Test
	void modsAndFlags() {
		assertTrue(eval("{\"modPresent\":[\"sodium\",\"lithium\"]}"));
		assertFalse(eval("{\"modPresent\":[\"sodium\",\"iris\"]}"));
		assertTrue(eval("{\"modAbsent\":[\"iris\",\"optifabric\"]}"));
		assertFalse(eval("{\"modAbsent\":[\"iris\",\"sodium\"]}"));
		assertFalse(eval("{\"flags\":[\"shaders-enabled\"]}"));
		hw.flags = Set.of("shaders-enabled", "sodium-workaround:AMD_GAME_OPTIMIZATION_BROKEN");
		assertTrue(eval("{\"flags\":[\"shaders-enabled\",\"sodium-workaround:AMD_GAME_OPTIMIZATION_BROKEN\"]}"));
	}

	@Test
	void anyOfAndNot() {
		assertTrue(eval("{\"anyOf\":[{\"gpuVendor\":[\"nvidia\"]},{\"tierAtLeast\":3}]}"));
		assertFalse(eval("{\"anyOf\":[{\"gpuVendor\":[\"nvidia\"]},{\"tierAtLeast\":4}]}"));
		assertFalse(eval("{\"anyOf\":[]}"));
		assertTrue(eval("{\"not\":{\"gpuVendor\":[\"nvidia\"]}}"));
		assertFalse(eval("{\"not\":{\"gpuVendor\":[\"amd\"]}}"));
		assertTrue(eval("{\"not\":{\"not\":{\"modPresent\":[\"sodium\"]}}}"));
	}

	@Test
	void fieldsAreAnded() {
		assertTrue(eval("{\"gpuVendor\":[\"amd\"],\"tierAtMost\":3,\"modPresent\":[\"sodium\"]}"));
		assertFalse(eval("{\"gpuVendor\":[\"amd\"],\"tierAtMost\":3,\"modPresent\":[\"iris\"]}"));
	}
}
