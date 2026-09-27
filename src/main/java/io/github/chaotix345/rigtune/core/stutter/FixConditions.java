package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.profile.ShareKeys;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

// docs/v0.5/SPEC.md 5 (C20): "same conditions" for a stutter fix's before and after sessions, captured when the fix is
// applied and at the start and end of every candidate session. The world itself (which save, where the player went) can't
// be held equal, so the wording always says the comparison isn't proof. mc: the Minecraft version; modSetHash: the
// benchmark's mod-set hash (RigTune left out); heapMaxMb and collector: the memory; width/height/fullscreen: the window;
// world: the kind of world (ServerLimits.Kind's name); phaseTiming and gcMeasured: what the Stutter Doctor could measure;
// settings: every ShareKeys.MANAGED key this instance has, plus iris.shaderPack.
public record FixConditions(@Nullable String mc, @Nullable String modSetHash, long heapMaxMb, @Nullable String collector, int width, int height,
		boolean fullscreen, @Nullable String world, boolean phaseTiming, boolean gcMeasured, Map<String, String> settings) {
	public static final String SHADER_PACK = "iris.shaderPack";

	// sf §2.7's rigtune.stutter.fix.skip.* reasons, in the order a session's differences are listed.
	public enum Reason {
		VERSION, MODS, MEMORY, DISPLAY, WORLD, MEASUREMENT, SETTING;

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		public static @Nullable Reason of(@Nullable String id) {
			for (Reason reason : values()) {
				if (reason.id().equals(id)) {
					return reason;
				}
			}
			return null;
		}
	}

	// args: for SETTING the key, the fix's value and the session's ("" when absent); none otherwise.
	public record Difference(Reason reason, List<String> args) {
		public Difference {
			args = args == null ? List.of() : List.copyOf(args);
		}
	}

	public FixConditions {
		Map<String, String> copy = new LinkedHashMap<>();
		if (settings != null) {
			settings.forEach((key, value) -> {
				if (key != null && value != null) {
					copy.put(key, value);
				}
			});
		}
		settings = Collections.unmodifiableMap(copy);
	}

	// What differs between the fix's conditions (this) and a session's (other), in Reason's order, then the settings in
	// the share-key table's order and any others alphabetically. The fixed key isn't compared: the tracker checks that it
	// equals the fix's target.
	public List<Difference> differences(FixConditions other, String fixKey) {
		List<Difference> out = new ArrayList<>();
		if (!Objects.equals(mc, other.mc)) {
			out.add(new Difference(Reason.VERSION, List.of()));
		}
		if (!Objects.equals(modSetHash, other.modSetHash)) {
			out.add(new Difference(Reason.MODS, List.of()));
		}
		if (heapMaxMb != other.heapMaxMb || !Objects.equals(collector, other.collector)) {
			out.add(new Difference(Reason.MEMORY, List.of()));
		}
		if (width != other.width || height != other.height || fullscreen != other.fullscreen) {
			out.add(new Difference(Reason.DISPLAY, List.of()));
		}
		if (!Objects.equals(world, other.world)) {
			out.add(new Difference(Reason.WORLD, List.of()));
		}
		if (phaseTiming != other.phaseTiming || gcMeasured != other.gcMeasured) {
			out.add(new Difference(Reason.MEASUREMENT, List.of()));
		}
		for (String key : settingKeys(other)) {
			String mine = settings.get(key);
			String theirs = other.settings.get(key);
			if (!key.equals(fixKey) && !SettingValues.same(mine, theirs)) {
				out.add(new Difference(Reason.SETTING, List.of(key, mine == null ? "" : mine, theirs == null ? "" : theirs)));
			}
		}
		return out;
	}

	private Set<String> settingKeys(FixConditions other) {
		Set<String> all = new TreeSet<>(settings.keySet());
		all.addAll(other.settings.keySet());
		Set<String> ordered = new LinkedHashSet<>();
		for (ShareKeys.Key key : ShareKeys.V1) {
			if (all.remove(key.key())) {
				ordered.add(key.key());
			}
		}
		ordered.addAll(all);
		return ordered;
	}
}
