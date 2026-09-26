package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.recommend.Recommender.Clamp;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import io.github.chaotix345.rigtune.core.rules.RulesDocument.SettingLabel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// The lines Preview lists under a profile's changes (docs/v0.4/SPEC.md 4, plan review P-L2): each clamp RigTune applied for
// this PC, the settings a newer RigTune would understand, and the ones this game doesn't have.
public final class ProfileNotes {
	private ProfileNotes() {
	}

	public static List<Text> of(List<Clamp> clamps, int newerKeys, int notHere, Map<String, SettingLabel> labels) {
		List<Text> out = new ArrayList<>();
		for (Clamp clamp : clamps) {
			SettingLabel label = labels.get(clamp.key());
			out.add(Text.of("rigtune.profile.preview.clamped", "%s limited to %s for this PC: %s", SettingValues.name(label, clamp.key()),
					SettingValues.valueLabel(label, clamp.to()), clamp.reason()));
		}
		if (newerKeys > 0) {
			out.add(Text.of("rigtune.profile.preview.newer_keys", "%s settings in this code need a newer RigTune, so they're left out.", newerKeys));
		}
		if (notHere > 0) {
			out.add(Text.of("rigtune.profile.preview.not_here", "%s settings in this profile aren't in this game (a mod or option it doesn't have), so they're left out.",
					notHere));
		}
		return out;
	}
}
