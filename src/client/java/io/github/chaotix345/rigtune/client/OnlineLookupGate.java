package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthClient;
import io.github.chaotix345.rigtune.core.modrinth.OnlineDataFetcher;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

// Decides when RealController looks the installed mods up on Modrinth. It's asked after every scan and every rules
// publish; a lookup is due once the scan, the hardware and the rules are all known, and again only for a new scan or a
// changed set of rule slugs. So whichever of the scan and the rules arrives last triggers it, exactly once per launch.
// gameVersion: the version Modrinth is asked about; hardware.mcVersion() (normalized) stays for the rules' conditions.
final class OnlineLookupGate {
	record Lookup(List<InstalledMod> mods, List<String> slugs, HardwareProfile hardware, String gameVersion) {
		// The lookup on `network` (Probes.NETWORK in the game; Phase 5 P5A-F4), never on the worker pool.
		CompletableFuture<OnlineDataFetcher.Result> start(ModrinthClient client, Executor network) {
			return CompletableFuture.supplyAsync(() -> new OnlineDataFetcher(client).fetchAll(mods, slugs, gameVersion), network);
		}
	}

	private final Supplier<@Nullable String> rawGameVersion;
	private @Nullable List<InstalledMod> lastMods;
	private @Nullable Set<String> lastSlugs;

	// rawGameVersion: Loader's raw game version (FabricLoader.getRawGameVersion() in the game).
	OnlineLookupGate(Supplier<@Nullable String> rawGameVersion) {
		this.rawGameVersion = rawGameVersion;
	}

	// The Minecraft version to ask Modrinth about: the raw one (26.4-snapshot-1), since a snapshot's normalized version
	// (26.4-alpha.1) isn't a Modrinth game version; the normalized one only when the raw one is missing (SPEC item 1).
	String modrinthGameVersion(String normalized) {
		String raw = rawGameVersion.get();
		return raw == null || raw.isBlank() ? normalized : raw;
	}

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
		return new Lookup(mods, slugs, hardware, modrinthGameVersion(hardware.mcVersion()));
	}
}
