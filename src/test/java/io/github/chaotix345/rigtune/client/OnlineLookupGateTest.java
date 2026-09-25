package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

// RealController asks the gate on every scan completion and every rules publish; the lookup must run exactly once per
// launch whichever finishes first (the offline race in docs/v0.2/design/ws-g.md).
class OnlineLookupGateTest {
	private final OnlineLookupGate gate = new OnlineLookupGate(() -> null);
	private final HardwareProfile hw = Fixtures.userRig().build();
	private final List<InstalledMod> mods = new ArrayList<>(Fixtures.mods("sodium", "lithium"));

	private static RulesDocument rules(int revision, String... slugs) {
		StringBuilder json = new StringBuilder("{\"schemaVersion\":2,\"revision\":" + revision + ",\"mods\":[");
		for (int i = 0; i < slugs.length; i++) {
			json.append(i == 0 ? "" : ",").append("{\"slug\":\"").append(slugs[i]).append("\",\"modIds\":[\"").append(slugs[i]).append("\"]}");
		}
		return RulesLoader.parse(json.append("]}").toString());
	}

	private int lookups(List<OnlineLookupGate.Lookup> calls) {
		return (int) calls.stream().filter(l -> l != null).count();
	}

	@Test
	void rulesFirstThenScanLooksUpOnce() {
		RulesDocument rules = rules(5, "sodium", "lithium");
		List<OnlineLookupGate.Lookup> calls = new ArrayList<>();
		calls.add(gate.next(null, rules, null));
		calls.add(gate.next(mods, rules, hw));
		calls.add(gate.next(mods, rules(5, "sodium", "lithium"), hw));
		assertEquals(1, lookups(calls));
		assertNotNull(calls.get(1));
		assertEquals(List.of("sodium", "lithium"), calls.get(1).slugs());
	}

	@Test
	void scanFirstThenRulesLooksUpOnce() {
		RulesDocument rules = rules(5, "sodium", "lithium");
		List<OnlineLookupGate.Lookup> calls = new ArrayList<>();
		calls.add(gate.next(mods, null, hw));
		calls.add(gate.next(mods, rules, hw));
		calls.add(gate.next(mods, rules, hw));
		assertEquals(1, lookups(calls));
		assertNotNull(calls.get(1));
	}

	@Test
	void newerRulesWithTheSameSlugsDontLookUpAgain() {
		assertNotNull(gate.next(mods, rules(5, "sodium", "lithium"), hw));
		assertNull(gate.next(mods, rules(6, "lithium", "sodium"), hw));
	}

	@Test
	void aNewSlugLooksUpAgain() {
		assertNotNull(gate.next(mods, rules(5, "sodium"), hw));
		assertNotNull(gate.next(mods, rules(6, "sodium", "nvidium"), hw));
	}

	@Test
	void aRescanLooksUpAgain() {
		RulesDocument rules = rules(5, "sodium");
		assertNotNull(gate.next(mods, rules, hw));
		assertNotNull(gate.next(new ArrayList<>(mods), rules, hw));
	}

	@Test
	void nothingWithoutHardware() {
		assertNull(gate.next(mods, rules(5, "sodium"), null));
	}

	// SPEC item 1: Modrinth is asked for Loader's raw game version (a snapshot's normalized 26.4-alpha.1 isn't a
	// Modrinth game version); the rules' conditions keep the normalized one.
	@Test
	void modrinthIsAskedForTheRawGameVersionAndConditionsKeepTheNormalizedOne() {
		OnlineLookupGate snapshot = new OnlineLookupGate(() -> "26.4-snapshot-1");
		Fixtures.Hw rig = Fixtures.userRig();
		rig.mcVersion = "26.4-alpha.1";

		OnlineLookupGate.Lookup lookup = snapshot.next(mods, rules(5, "sodium"), rig.build());

		assertNotNull(lookup);
		assertEquals("26.4-snapshot-1", lookup.gameVersion());
		assertEquals("26.4-alpha.1", lookup.hardware().mcVersion());
		assertEquals("26.4-snapshot-1", snapshot.modrinthGameVersion("26.4-alpha.1"));
	}

	@Test
	void withoutARawGameVersionTheNormalizedOneIsUsed() {
		assertEquals("26.2", new OnlineLookupGate(() -> null).modrinthGameVersion("26.2"));
		OnlineLookupGate.Lookup lookup = new OnlineLookupGate(() -> " ").next(mods, rules(5, "sodium"), hw);
		assertNotNull(lookup);
		assertEquals("26.2", lookup.gameVersion());
	}
}
