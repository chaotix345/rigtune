package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.core.TextChecks;
import io.github.chaotix345.rigtune.core.model.Text;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

// docs/v0.4/SPEC.md 2o, H2: an installed mod's fabric.mod.json `depends`/`breaks` on the mod being installed. Fabric's own
// predicates are applied by client/probe/FabricPins (FabricPinsTest); here a stand-in: "0.9.x" is a prefix, "0.9.2" exact.
class VersionPinsTest {
	private static VersionPins.Pin depends(String by, String byName, String top, String target, String targetName, String range) {
		return new VersionPins.Pin(by, byName, top, target, targetName, VersionPins.Kind.DEPENDS, () -> range, v -> accepts(range, v));
	}

	private static VersionPins.Pin breaks(String by, String byName, String target, String range) {
		return new VersionPins.Pin(by, byName, by, target, null, VersionPins.Kind.BREAKS, () -> range, v -> !accepts(range, v));
	}

	private static boolean accepts(String range, String version) {
		return range.equals("*") || (range.endsWith(".x") ? version.startsWith(range.substring(0, range.length() - 1)) : version.equals(range));
	}

	// Iris 1.11.4 (the real instance) declares sodium: ["0.9.x"]; Nvidium 0.4.4 declares sodium: ["0.9.2"].
	private static final VersionPins PINS = new VersionPins(List.of(
			depends("iris", "Iris", "iris", "sodium", "Sodium", "0.9.x"),
			depends("modmenu", "Mod Menu", "modmenu", "fabric-api", "Fabric API", "*")));

	@Test
	void aVersionOutsideAnInstalledModsRangeIsRefusedNamingThatMod() {
		Text problem = PINS.problem("sodium", "0.10.0+mc26.2");

		Text.Translatable t = assertInstanceOf(Text.Translatable.class, problem);
		assertEquals("rigtune.download.pinned", t.key());
		assertEquals("Iris, which is installed, needs Sodium 0.9.x, not 0.10.0+mc26.2", problem.english());
		TextChecks.assertPseudoLocalised(problem, Set.of(), problem.english());
	}

	@Test
	void aVersionInsideEveryRangeIsFine() {
		assertNull(PINS.problem("sodium", "0.9.3+mc26.2"));
		assertNull(PINS.problem("fabric-api", "0.161.0+26.2"));
		assertNull(PINS.problem("lithium", "0.20.0"));
		assertNull(VersionPins.NONE.problem("sodium", "0.10.0"));
	}

	@Test
	void anExactPinRefusesEveryOtherVersion() {
		VersionPins nvidium = new VersionPins(List.of(depends("nvidium", "Nvidium", "nvidium", "sodium", "Sodium", "0.9.2")));

		assertEquals("Nvidium, which is installed, needs Sodium 0.9.2, not 0.9.3", nvidium.problem("sodium", "0.9.3").english());
		assertNull(nvidium.problem("sodium", "0.9.2"));
	}

	@Test
	void aBreaksRangeThatMatchesIsRefused() {
		VersionPins pins = new VersionPins(List.of(breaks("iris", "Iris", "optifabric", "*")));

		Text problem = pins.problem("optifabric", "1.14.0");

		assertEquals("rigtune.download.pinned_breaks", assertInstanceOf(Text.Translatable.class, problem).key());
		// No loaded mod names the target, so its id is shown.
		assertEquals("Iris, which is installed, doesn't work with optifabric 1.14.0", problem.english());
		TextChecks.assertPseudoLocalised(problem, Set.of(), problem.english());
	}

	// The replaced jar's own declarations go with it: the mod itself, and a mod nested in it (top is the updated mod).
	@Test
	void theReplacedJarsOwnDeclarationsDontCount() {
		VersionPins pins = new VersionPins(List.of(
				depends("sodium", "Sodium", "sodium", "sodium", "Sodium", "0.9.x"),
				depends("sodium-api", "Sodium API", "sodium", "sodium", "Sodium", "0.9.x")));

		assertNull(pins.problem("sodium", "0.10.0"));
	}

	// A mod nested inside another installed mod still counts: its container stays.
	@Test
	void aNestedModsDeclarationCountsWithItsName() {
		VersionPins pins = new VersionPins(List.of(depends("dh-lib", null, "distanthorizons", "fabric-api", "Fabric API", "0.149.x")));

		assertEquals("dh-lib, which is installed, needs Fabric API 0.149.x, not 0.161.0", pins.problem("fabric-api", "0.161.0").english());
	}

	// A jar with no readable version can't be judged here (the mod id check and Fabric decide); the range text is only
	// read for a refusal.
	@Test
	void anUnknownVersionIsNotJudgedAndTheRangeIsReadOnlyForARefusal() {
		AtomicInteger reads = new AtomicInteger();
		VersionPins pins = new VersionPins(List.of(new VersionPins.Pin("iris", "Iris", "iris", "sodium", "Sodium", VersionPins.Kind.DEPENDS,
				() -> {
					reads.incrementAndGet();
					return "0.9.x";
				}, v -> v.startsWith("0.9."))));

		assertNull(pins.problem("sodium", null));
		assertNull(pins.problem("sodium", "0.9.4"));
		assertEquals(0, reads.get());
		pins.problem("sodium", "0.10.0");
		assertEquals(1, reads.get());
	}

	// The first refusal in a stable order (by declaring mod id), whatever order the loader listed the mods in.
	@Test
	void theRefusalNamesTheFirstDeclaringModById() {
		VersionPins.Pin iris = depends("iris", "Iris", "iris", "sodium", "Sodium", "0.9.x");
		VersionPins.Pin nvidium = depends("nvidium", "Nvidium", "nvidium", "sodium", "Sodium", "0.9.2");

		assertEquals(new VersionPins(List.of(iris, nvidium)).problem("sodium", "0.10.0").english(),
				new VersionPins(List.of(nvidium, iris)).problem("sodium", "0.10.0").english());
	}

	// Two jars that are only fine together (Sodium 0.10 needs the installed Iris's pin gone; Iris 1.12 needs Sodium 0.10)
	// come back as reliances both ways, so the planner puts them in one group; nothing is refused.
	@Test
	void jarsThatAreOnlyFineTogetherAreReliances() {
		VersionPins pins = new VersionPins(List.of(depends("iris", "Iris", "iris", "sodium", "Sodium", "0.9.x")),
				List.of(new VersionPins.Loaded("sodium", "Sodium", "0.9.3", "sodium"), new VersionPins.Loaded("iris", "Iris", "1.11.4", "iris")),
				(ranges, version) -> ranges.stream().anyMatch(range -> accepts(range, version)));
		VersionPins.Jar sodium = new VersionPins.Jar("sodium", "Sodium", "0.10.0", "sodium", Map.of(), Map.of());
		VersionPins.Jar iris = new VersionPins.Jar("iris", "Iris", "1.12.0", "iris", Map.of("sodium", List.of("0.10.x")), Map.of());

		VersionPins.Outcome outcome = pins.check(List.of(sodium, iris));

		assertEquals(Map.of(), outcome.refused());
		assertEquals(Set.of(List.of(0, 1), List.of(1, 0)), outcome.reliances().stream().map(pair -> List.of(pair[0], pair[1])).collect(Collectors.toSet()));
		// Without Iris's update, Sodium 0.10 is refused; without Sodium's, Iris 1.12 is.
		assertEquals("Iris, which is installed, needs Sodium 0.9.x, not 0.10.0", pins.check(List.of(sodium)).refused().get(0).english());
		assertEquals("Iris needs Sodium 0.10.x, not the installed 0.9.3", pins.check(List.of(iris)).refused().get(0).english());
	}
}
