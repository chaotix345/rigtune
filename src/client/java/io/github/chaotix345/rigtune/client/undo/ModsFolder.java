package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.core.apply.SafeFileNames;
import io.github.chaotix345.rigtune.core.history.JarInfo;
import io.github.chaotix345.rigtune.core.history.UndoPlanner;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

// The mods folder as the undo planner sees it. Jars the game loaded use Fabric's metadata (their nested mods count as
// provided by them); any other file (a .disabled jar, one added since launch) is read when asked, and kept until the
// file changes, so the re-check when an undo is confirmed doesn't read it again on the render thread. Mods that aren't
// jars in this folder (built-in ones, -Dfabric.addMods) are provided elsewhere.
public final class ModsFolder implements UndoPlanner.Folder {
	private static final JarCache JARS = new JarCache(JarInfo::read);

	private final Path dir;
	private final Set<String> files;
	private final Map<String, JarInfo> loaded;
	private final Set<String> elsewhere;

	// A jar's metadata by path, read again only when its size or modification time changed.
	static final class JarCache {
		private record Cached(long size, long modified, JarInfo info) {
		}

		private final Function<Path, JarInfo> reader;
		private final Map<Path, Cached> cached = new ConcurrentHashMap<>();

		JarCache(Function<Path, JarInfo> reader) {
			this.reader = reader;
		}

		JarInfo get(Path file) {
			try {
				BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
				long size = attributes.size();
				long modified = attributes.lastModifiedTime().toMillis();
				Cached hit = cached.get(file);
				if (hit != null && hit.size() == size && hit.modified() == modified) {
					return hit.info();
				}
				JarInfo info = reader.apply(file);
				cached.put(file, new Cached(size, modified, info));
				return info;
			} catch (IOException e) {
				return null;
			}
		}
	}

	ModsFolder(Path dir, Map<String, JarInfo> loaded, Set<String> elsewhere) {
		this.dir = dir;
		this.loaded = Map.copyOf(loaded);
		this.elsewhere = Set.copyOf(elsewhere);
		Set<String> names = new LinkedHashSet<>();
		try (Stream<Path> list = Files.list(dir)) {
			list.filter(Files::isRegularFile).forEach(p -> names.add(p.getFileName().toString()));
		} catch (IOException ignored) {
		}
		this.files = Set.copyOf(names);
	}

	public static ModsFolder current(Path dir) {
		Map<String, String> ids = new HashMap<>();
		Map<String, Set<String>> provides = new HashMap<>();
		Map<String, Set<String>> depends = new HashMap<>();
		Set<String> elsewhere = new HashSet<>();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			ModContainer top = mod;
			while (top.getContainingMod().isPresent()) {
				top = top.getContainingMod().get();
			}
			ModMetadata meta = mod.getMetadata();
			Path jar = jarOf(top);
			if (jar == null || !SafeFileNames.isDirectChild(dir, jar)) {
				elsewhere.add(meta.getId());
				elsewhere.addAll(meta.getProvides());
				continue;
			}
			String name = jar.getFileName().toString();
			Set<String> provided = provides.computeIfAbsent(name, n -> new HashSet<>());
			provided.addAll(meta.getProvides());
			if (mod == top) {
				ids.put(name, meta.getId());
				Set<String> needs = depends.computeIfAbsent(name, n -> new HashSet<>());
				meta.getDepends().stream().map(ModDependency::getModId).forEach(needs::add);
			} else {
				provided.add(meta.getId());
			}
		}
		Map<String, JarInfo> loaded = new HashMap<>();
		ids.forEach((name, id) -> loaded.put(name, new JarInfo(id, provides.get(name), depends.get(name))));
		return new ModsFolder(dir, loaded, elsewhere);
	}

	private static Path jarOf(ModContainer mod) {
		ModOrigin origin = mod.getOrigin();
		if (origin == null || origin.getKind() != ModOrigin.Kind.PATH) {
			return null;
		}
		List<Path> paths = origin.getPaths();
		if (paths == null || paths.size() != 1) {
			return null;
		}
		Path path = paths.getFirst();
		return path.getFileName() != null && path.getFileName().toString().endsWith(".jar") ? path : null;
	}

	@Override
	public Path dir() {
		return dir;
	}

	@Override
	public Set<String> files() {
		return files;
	}

	@Override
	public JarInfo jar(String fileName) {
		if (loaded.containsKey(fileName)) {
			return loaded.get(fileName);
		}
		return files.contains(fileName) ? JARS.get(dir.resolve(fileName)) : null;
	}

	@Override
	public Set<String> providedElsewhere() {
		return elsewhere;
	}
}
