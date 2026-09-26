package io.github.chaotix345.rigtune.client.probe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.apply.ModJars;
import io.github.chaotix345.rigtune.core.modrinth.VersionPins;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

// docs/v0.4/SPEC.md 2o, H2: the loaded mods' fabric.mod.json `depends` and `breaks` on other mods, for the download
// planner. A version is matched with Fabric Loader's own predicates (ModDependency.matches), so a range means exactly what
// Fabric makes of it at the next start. Nested mods count too: Fabric checks them the same way.
public final class FabricPins {
	private static final String SEPARATOR = " || ";

	private FabricPins() {
	}

	public static VersionPins loaded() {
		return of(FabricLoader.getInstance().getAllMods());
	}

	static VersionPins of(Collection<ModContainer> mods) {
		Map<String, String> names = new HashMap<>();
		for (ModContainer mod : mods) {
			names.putIfAbsent(mod.getMetadata().getId(), name(mod.getMetadata()));
		}
		List<VersionPins.Pin> pins = new ArrayList<>();
		for (ModContainer mod : mods) {
			try {
				ModMetadata meta = mod.getMetadata();
				String top = top(mod).getMetadata().getId();
				for (ModDependency dep : meta.getDependencies()) {
					VersionPins.Kind kind = switch (dep.getKind()) {
						case DEPENDS -> VersionPins.Kind.DEPENDS;
						case BREAKS -> VersionPins.Kind.BREAKS;
						default -> null;
					};
					if (kind != null) {
						pins.add(new VersionPins.Pin(meta.getId(), names.get(meta.getId()), top, dep.getModId(), names.get(dep.getModId()), kind,
								() -> declared(mod, kind, dep), version -> satisfied(dep, kind, version)));
					}
				}
			} catch (RuntimeException e) {
				RigTune.LOGGER.warn("Could not read the dependencies of {}", mod.getMetadata().getId(), e);
			}
		}
		return new VersionPins(pins);
	}

	// A depends range must match the version and a breaks range must not; a version Fabric can't parse is fine for neither.
	static boolean satisfied(ModDependency dep, VersionPins.Kind kind, String version) {
		try {
			boolean matches = dep.matches(Version.parse(version));
			return kind == VersionPins.Kind.DEPENDS ? matches : !matches;
		} catch (Exception e) {
			return false;
		}
	}

	// The ranges as the mod wrote them ("0.9.x"; an array's entries joined), else Fabric's own form of them ("~0.9-").
	static String declared(ModContainer mod, VersionPins.Kind kind, ModDependency dep) {
		String written = written(mod, kind == VersionPins.Kind.DEPENDS ? "depends" : "breaks", dep.getModId());
		return written != null ? written
				: dep.getVersionRequirements().stream().map(Object::toString).collect(Collectors.joining(SEPARATOR));
	}

	private static String written(ModContainer mod, String section, String modId) {
		try {
			Optional<Path> json = mod.findPath("fabric.mod.json");
			if (json.isEmpty()) {
				return null;
			}
			byte[] bytes;
			try (InputStream in = Files.newInputStream(json.get())) {
				bytes = ModJars.readFabricModJson(in, Files.size(json.get()));
			}
			if (bytes == null) {
				return null;
			}
			JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
			if (!(root instanceof JsonObject object) || !(object.get(section) instanceof JsonObject ranges)) {
				return null;
			}
			JsonElement value = ranges.get(modId);
			if (value instanceof JsonArray array) {
				List<String> out = new ArrayList<>();
				array.forEach(e -> out.add(e.isJsonPrimitive() ? e.getAsString() : e.toString()));
				return String.join(SEPARATOR, out);
			}
			return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static String name(ModMetadata meta) {
		String name = ModJars.sanitizeName(meta.getName());
		return name != null ? name : meta.getId();
	}

	private static ModContainer top(ModContainer mod) {
		ModContainer top = mod;
		while (top.getContainingMod().isPresent()) {
			top = top.getContainingMod().get();
		}
		return top;
	}
}
