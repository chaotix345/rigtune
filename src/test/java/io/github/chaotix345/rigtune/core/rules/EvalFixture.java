package io.github.chaotix345.rigtune.core.rules;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.model.TierResult;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// A mutable evaluation context for condition tests: the user's rig (AMD, tier 5 GPU, effective tier 3) by default.
final class EvalFixture {
	final Fixtures.Hw hw = Fixtures.userRig();
	GpuClass gpu = new GpuClass(GpuVendor.AMD, false, 5, null);
	TierResult tier = new TierResult(4, 3, 5, 4, 5, "cpu");
	Goal goal = Goal.PERFORMANCE;
	Set<String> mods = Set.of("sodium", "lithium");
	final Map<String, String> versions = new HashMap<>(Map.of("sodium", "0.9.2", "lithium", "0.25.4"));
	final Map<String, String> settings = new HashMap<>();

	EvalContext context() {
		return new EvalContext(hw.build(), gpu, tier, goal, mods, versions, new SettingsSnapshot(Map.copyOf(settings)));
	}

	Truth truth(String json) {
		return ConditionEvaluator.evaluate(RulesLoader.condition(json), context());
	}

	boolean matches(String json) {
		return ConditionEvaluator.matches(RulesLoader.condition(json), context());
	}

	EvalFixture noGpuInfo() {
		hw.gpu = new GpuInfo("", "", null, GraphicsBackend.UNKNOWN, -1);
		gpu = new GpuClass(GpuVendor.UNKNOWN, false, 2, null);
		return this;
	}

	EvalFixture renderer(String vendorString, String renderer, GpuVendor vendor) {
		hw.gpu = new GpuInfo(vendorString, renderer, "1.0", GraphicsBackend.OPENGL, 8192);
		gpu = new GpuClass(vendor, false, 4, null);
		return this;
	}
}
