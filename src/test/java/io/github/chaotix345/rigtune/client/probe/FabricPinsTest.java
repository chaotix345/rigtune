package io.github.chaotix345.rigtune.client.probe;

import io.github.chaotix345.rigtune.core.modrinth.VersionPins;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// docs/v0.4/SPEC.md 2o, H2: the loaded mods' pins as Fabric Loader itself reads them. The containers are stand-ins; the
// version predicates are Fabric's own (VersionPredicate.parse / Version.parse, what ModDependency.matches uses).
class FabricPinsTest {
	@TempDir
	Path dir;

	private static ModDependency dep(ModDependency.Kind kind, String modId, String... ranges) {
		List<VersionPredicate> predicates = new ArrayList<>();
		for (String range : ranges) {
			try {
				predicates.add(VersionPredicate.parse(range));
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		}
		return (ModDependency) Proxy.newProxyInstance(FabricPinsTest.class.getClassLoader(), new Class<?>[]{ModDependency.class}, (proxy, method, args) ->
				switch (method.getName()) {
					case "getKind" -> kind;
					case "getModId" -> modId;
					case "matches" -> predicates.stream().anyMatch(p -> p.test((Version) args[0]));
					case "getVersionRequirements" -> predicates;
					case "toString" -> kind + " " + modId;
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	private static ModContainer mod(String id, String name, ModContainer containedIn, Path json, ModDependency... deps) {
		ModMetadata meta = (ModMetadata) Proxy.newProxyInstance(FabricPinsTest.class.getClassLoader(), new Class<?>[]{ModMetadata.class}, (proxy, method, args) ->
				switch (method.getName()) {
					case "getId" -> id;
					case "getName" -> name;
					case "getDependencies" -> List.of(deps);
					case "toString" -> id;
					default -> throw new UnsupportedOperationException(method.getName());
				});
		return (ModContainer) Proxy.newProxyInstance(FabricPinsTest.class.getClassLoader(), new Class<?>[]{ModContainer.class}, (proxy, method, args) ->
				switch (method.getName()) {
					case "getMetadata" -> meta;
					case "getContainingMod" -> Optional.ofNullable(containedIn);
					case "findPath" -> Optional.ofNullable(json);
					case "toString" -> id;
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	private Path json(String name, String content) throws IOException {
		return Files.writeString(dir.resolve(name + ".json"), content);
	}

	private static VersionPins pins(ModContainer... mods) {
		return FabricPins.of(List.<ModContainer>of(mods));
	}

	// Iris 1.11.4 (the real instance): depends sodium ["0.9.x"]. Fabric reads "0.9.x" as any 0.9 version, build metadata
	// ignored; the message shows the range as Iris wrote it.
	@Test
	void irisPinsSodiumToZeroNine() throws IOException {
		ModContainer sodium = mod("sodium", "Sodium", null, null);
		ModContainer iris = mod("iris", "Iris", null, json("iris", "{\"id\":\"iris\",\"depends\":{\"sodium\":[\"0.9.x\"],\"minecraft\":\"26.2\"}}"),
				dep(ModDependency.Kind.DEPENDS, "sodium", "0.9.x"), dep(ModDependency.Kind.DEPENDS, "minecraft", "26.2"));

		VersionPins pins = pins(sodium, iris);

		assertEquals("Iris, which is installed, needs Sodium 0.9.x, not 0.10.0+mc26.2", pins.problem("sodium", "0.10.0+mc26.2").english());
		assertNull(pins.problem("sodium", "0.9.3+mc26.2"));
		assertNull(pins.problem("lithium", "1.0.0"));
	}

	// Nvidium 0.4.4 (docs/research/v0.2/triage.md): depends sodium "0.9.2", an exact version for Fabric.
	@Test
	void nvidiumPinsOneExactSodium() {
		VersionPins pins = pins(mod("sodium", "Sodium", null, null), mod("nvidium", "Nvidium", null, null, dep(ModDependency.Kind.DEPENDS, "sodium", "0.9.2")));

		// Without its fabric.mod.json the range is shown in Fabric's own form.
		assertEquals("Nvidium, which is installed, needs Sodium =0.9.2, not 0.9.3", pins.problem("sodium", "0.9.3").english());
		assertNull(pins.problem("sodium", "0.9.2+mc26.3"));
	}

	@Test
	void anArrayOfRangesMeansAnyOfThem() throws IOException {
		ModContainer a = mod("a", "A", null, json("a", "{\"depends\":{\"sodium\":[\"0.8.x\",\"0.9.x\"]}}"), dep(ModDependency.Kind.DEPENDS, "sodium", "0.8.x", "0.9.x"));

		VersionPins pins = pins(a);

		assertNull(pins.problem("sodium", "0.8.1"));
		assertNull(pins.problem("sodium", "0.9.1"));
		assertEquals("A, which is installed, needs sodium 0.8.x || 0.9.x, not 0.10.0", pins.problem("sodium", "0.10.0").english());
	}

	@Test
	void aBreaksRangeRefusesTheVersionsItMatches() {
		VersionPins pins = pins(mod("x", "X", null, null, dep(ModDependency.Kind.BREAKS, "sodium", "<0.9.0")));

		assertEquals("X, which is installed, doesn't work with sodium 0.8.5", pins.problem("sodium", "0.8.5").english());
		assertNull(pins.problem("sodium", "0.9.3"));
	}

	// Recommends, suggests and conflicts are warnings for Fabric, not a refusal to start.
	@Test
	void softDeclarationsDontCount() {
		VersionPins pins = pins(mod("x", "X", null, null, dep(ModDependency.Kind.RECOMMENDS, "sodium", "0.9.x"),
				dep(ModDependency.Kind.SUGGESTS, "sodium", "0.9.x"), dep(ModDependency.Kind.CONFLICTS, "sodium", "*")));

		assertNull(pins.problem("sodium", "0.10.0"));
	}

	// A mod nested in the jar being replaced goes with it; one nested in another installed mod stays and counts, named by
	// its own name.
	@Test
	void nestedModsCountUnlessInsideTheReplacedJar() {
		ModContainer sodium = mod("sodium", "Sodium", null, null);
		ModContainer sodiumApi = mod("sodium-api", "Sodium API", sodium, null, dep(ModDependency.Kind.DEPENDS, "sodium", "0.9.x"));
		ModContainer dh = mod("distanthorizons", "Distant Horizons", null, null);
		ModContainer dhLib = mod("dh-lib", "DH Lib", dh, null, dep(ModDependency.Kind.DEPENDS, "fabric-api", ">=0.149 <0.150"));

		VersionPins pins = pins(sodium, sodiumApi, dh, dhLib);

		assertNull(pins.problem("sodium", "0.10.0"));
		assertEquals("DH Lib, which is installed, needs fabric-api >=0.149 <0.150, not 0.161.0+26.2", pins.problem("fabric-api", "0.161.0+26.2").english());
	}

	// A version Fabric can't parse is fine for no declaration.
	@Test
	void anUnparseableVersionIsRefused() {
		VersionPins pins = pins(mod("iris", "Iris", null, null, dep(ModDependency.Kind.DEPENDS, "sodium", "*")));

		assertNull(pins.problem("sodium", "anything-at-all"));
		assertEquals("Iris, which is installed, needs sodium *, not ", pins.problem("sodium", "").english());
	}

	@Test
	void noModsNoPins() {
		Collection<ModContainer> none = List.of();
		assertNull(FabricPins.of(none).problem("sodium", "0.10.0"));
	}
}
