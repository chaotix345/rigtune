package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;

// Decides when RealController looks the installed mods up on Modrinth. It's asked after every scan and every rules
// publish; a lookup is due once the scan, the hardware and the rules are all known, and again only for a new scan or a
// changed set of rule slugs. So whichever of the scan and the rules arrives last triggers it, exactly once per launch.
final class OnlineLookupGate {
	record Lookup(List<InstalledMod> mods, List<String> slugs, HardwareProfile hardware) {
	}

	private @Nullable List<InstalledMod> lastMods;
	private @Nullable Set<String> lastSlugs;

	synchronized @Nullable Lookup next(@Nullable List<InstalledMod> mods, @Nullable RulesDocument rules, @Nullable HardwareProfile hardware) {
		if (mods == null || rules == null || hardware == null) {
			return null;
		}
		List<String> slugs = rules.mods.stream().map(m -> m.slug).filter(Objects::nonNull).distinct().toList();
		Set<String> slugSet = Set.copyOf(slugs);
		// A new scan is a new list object, even with the same mods.
		if (mods == lastMods && slugSet.equals(lastSlugs)) {
			return null;
		}
		lastMods = mods;
		lastSlugs = slugSet;
		return new Lookup(mods, slugs, hardware);
	}
}
