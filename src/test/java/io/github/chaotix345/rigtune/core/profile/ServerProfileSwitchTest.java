package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.report.LauncherModAdvice;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 7 (AC7.18) and X10: taking a server's offer is ProfileService.switchProfile, whose recommendations
// are ProfileSwitch's. They are SetSetting only, so the switch stays one click under every launcher policy (P0.4): Apply's
// guard (LauncherModAdvice.guard, through V05Hooks.beforeApply) keeps every one of them. A pin for WS-L1's guard.
class ServerProfileSwitchTest {
	@Test
	void aSwitchIsSettingsOnlyUnderEveryLauncherPolicy() {
		List<String> mods = List.of("sodium", "distanthorizons", "iris");
		SettingsSnapshot snapshot = ProfileFixtures.snapshot(mods, false);
		// A profile that differs from this instance in every key the share table has.
		Map<String, String> values = new LinkedHashMap<>();
		for (ShareKeys.Key key : ShareKeys.V1) {
			Integer current = key.encode(snapshot.get(key.key()));
			values.put(key.key(), key.decode(current != null && current == 0 ? 1 : 0, 60));
		}
		List<Recommendation> switched = ProfileSwitch.build(values, snapshot, Set.copyOf(mods), Map.of(), "Max FPS");
		for (String prefix : List.of(SettingKeys.VANILLA_PREFIX, SettingKeys.SODIUM_PREFIX, SettingKeys.DH_PREFIX, SettingKeys.IRIS_PREFIX)) {
			assertTrue(switched.stream().anyMatch(r -> r.action() instanceof Action.SetSetting s && s.key().startsWith(prefix)), prefix);
		}
		assertTrue(switched.stream().allMatch(r -> r.action() instanceof Action.SetSetting), switched.toString());
		for (ModFilesPolicy policy : ModFilesPolicy.values()) {
			assertEquals(switched, LauncherModAdvice.guard(switched, policy), policy.name());
		}
	}
}
