package io.github.chaotix345.rigtune.client.probe;

import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

// docs/v0.5/SPEC.md 2H L5: the ids loaded other than as a top-level jar, which a download whose nesting a read doesn't know
// may carry (DryRunPlanner.Checks.nestedOrProvided): the nested mods and every provided id, never a top-level mod's own.
class PreviewJarChecksTest {
	private static ModContainer mod(String id, List<String> provides, ModContainer containedIn) {
		ModMetadata meta = (ModMetadata) Proxy.newProxyInstance(PreviewJarChecksTest.class.getClassLoader(), new Class<?>[]{ModMetadata.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "getId" -> id;
					case "getProvides" -> provides;
					case "toString" -> id;
					default -> throw new UnsupportedOperationException(method.getName());
				});
		return (ModContainer) Proxy.newProxyInstance(PreviewJarChecksTest.class.getClassLoader(), new Class<?>[]{ModContainer.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "getMetadata" -> meta;
					case "getContainingMod" -> Optional.ofNullable(containedIn);
					case "toString" -> id;
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	@Test
	void nestedModsAndProvidedIdsButNoTopLevelMod() {
		ModContainer sodium = mod("sodium", List.of("indium"), null);
		ModContainer fabricApiBase = mod("fabric-api-base", List.of(), sodium);
		ModContainer dh = mod("distanthorizons", List.of(), null);
		ModContainer sqlite = mod("sqlite-jdbc", List.of("sqlite"), dh);

		assertEquals(Set.of("indium", "fabric-api-base", "sqlite-jdbc", "sqlite"),
				PreviewJarChecks.nestedOrProvided(List.of(sodium, fabricApiBase, dh, sqlite)));
	}
}
