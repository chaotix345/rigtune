package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import io.github.chaotix345.rigtune.core.rules.RulesDocument.SettingLabel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

// A profile switch is an ordinary Apply (docs/v0.4/SPEC.md 4, AC4.5): one SetSetting recommendation per managed key that
// this instance has (present in the snapshot: a key is never created), that RigTune may change, whose value is safe and
// differs from the current one. A mod's keys take part only while that mod is loaded (Sodium's patcher would otherwise
// create a missing field; DH's and Iris's refuse one anyway). vanilla.graphicsPreset is never managed.
public final class ProfileSwitch {
	private static final Map<String, String> MOD_OF_PREFIX = Map.of(SettingKeys.SODIUM_PREFIX, "sodium", SettingKeys.DH_PREFIX, "distanthorizons",
			SettingKeys.IRIS_PREFIX, "iris");

	private ProfileSwitch() {
	}

	// values: the profile's target values; loadedMods: the loaded mod ids; labels: the rules' settingLabels; name: the
	// profile's name for the reason line.
	public static List<Recommendation> build(Map<String, String> values, SettingsSnapshot snapshot, Set<String> loadedMods,
			Map<String, SettingLabel> labels, String name) {
		List<Recommendation> out = new ArrayList<>();
		for (ShareKeys.Key tableKey : ShareKeys.V1) {
			String key = tableKey.key();
			String raw = values.get(key);
			Integer wire = raw == null || !SettingKeys.safeValue(raw) ? null : tableKey.encode(raw);
			if (wire == null || !takesPart(key, snapshot, loadedMods)) {
				continue;
			}
			// The table's own spelling, whatever the profile or rule wrote ("always" -> ALWAYS, "12.0" -> 12).
			String target = tableKey.decode(wire, 60);
			String current = snapshot.get(key);
			if (SettingValues.same(current, target)) {
				continue;
			}
			out.add(Recommendation.of("set:" + key, Category.SETTING, Impact.LOW, SettingValues.describe(labels.get(key), key, current, target),
					Text.of("rigtune.profile.reason", "Part of the %s profile.", name), new Action.SetSetting(key, current, target), true));
		}
		return out;
	}

	// Whether this instance can take a value for key: managed, changeable, present now, and its mod (if any) loaded.
	public static boolean takesPart(String key, SettingsSnapshot snapshot, Set<String> loadedMods) {
		if (!ShareKeys.managed(key) || !SettingKeys.changeable(key) || !snapshot.has(key) || key.equals("vanilla.graphicsPreset")) {
			return false;
		}
		for (Map.Entry<String, String> mod : MOD_OF_PREFIX.entrySet()) {
			if (key.startsWith(mod.getKey())) {
				return loadedMods.contains(mod.getValue());
			}
		}
		return key.startsWith(SettingKeys.VANILLA_PREFIX);
	}
}
