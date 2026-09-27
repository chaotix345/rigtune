package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.client.ui.SafeLiteral;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.history.StaleOps;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// docs/v0.5/SPEC.md 2H RW-3: at each rebuild, next to Staging.dropQueuedUpdates (on the rebuild's worker), a staged group
// that can never run (its download gone, or its mod already loaded from another jar) is unstaged (Staging.dropStale); the
// render thread then shows one status line for it: "RigTune dropped its pending change to X: it is already installed
// (file)" or "…: its download is gone".
public final class StaleGroups {
	// What drop() found, by the op id it names, until status() says it: drop runs on the rebuild's worker, status on the
	// render thread right after.
	private static final Map<String, StaleOps.Stale> FOUND = new ConcurrentHashMap<>();

	private StaleGroups() {
	}

	// The ops dropped from pending.json (empty: none; a busy lock leaves them for the next rebuild). loaded: the loaded mod
	// ids (ModScanner.loadedIds()); the jars they were loaded from are FabricLoader's, asked only while something is staged.
	public static List<Op> drop(@Nullable Staging staging, Set<String> loaded) throws IOException {
		if (staging == null || !Files.exists(staging.pendingFile())) {
			return List.of();
		}
		return drop(staging, loaded, loadedFrom(FabricLoader.getInstance().getAllMods()));
	}

	static List<Op> drop(Staging staging, Set<String> loaded, Map<String, Set<String>> loadedFrom) throws IOException {
		Map<String, Set<String>> origins = new HashMap<>(loadedFrom);
		origins.keySet().retainAll(loaded);
		Staging.StaleDrop drop = staging.dropStale(origins);
		if (drop == null) {
			return List.of();
		}
		drop.stale().forEach(stale -> FOUND.put(stale.opId(), stale));
		return drop.dropped();
	}

	// The status line for what drop() dropped, or null for none: one sentence per dropped group.
	public static @Nullable Component status(List<Op> dropped, List<InstalledMod> scanned) {
		List<Component> parts = new ArrayList<>();
		for (Op op : dropped) {
			StaleOps.Stale stale = op == null || op.id() == null ? null : FOUND.remove(op.id());
			if (stale == null) {
				continue;
			}
			Component name = SafeLiteral.of(name(stale, scanned));
			parts.add(stale.why() == StaleOps.Why.INSTALLED ? Component.translatable("rigtune.status.stale_installed", name, SafeLiteral.of(stale.installedAs()))
					: Component.translatable("rigtune.status.stale_gone", name));
		}
		if (parts.isEmpty()) {
			return null;
		}
		var out = Component.empty();
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0) {
				out.append(" ");
			}
			out.append(parts.get(i));
		}
		return out;
	}

	// The mod's name as the main list shows it, else its id, else the file the change would have enabled.
	private static String name(StaleOps.Stale stale, List<InstalledMod> scanned) {
		if (stale.modId() == null) {
			return stale.file();
		}
		for (InstalledMod mod : scanned == null ? List.<InstalledMod>of() : scanned) {
			if (stale.modId().equals(mod.modId()) && mod.name() != null && !mod.name().isBlank()) {
				return mod.name();
			}
		}
		return stale.modId();
	}

	// A loaded mod id -> the file names of the top-level jars it was loaded from (FabricLoader's origins; no jar is opened,
	// so preLaunch may use it too). A mod nested in another isn't a jar of its own.
	public static Map<String, Set<String>> loadedFrom(Collection<ModContainer> mods) {
		Map<String, Set<String>> out = new HashMap<>();
		for (ModContainer mod : mods) {
			ModOrigin origin = mod.getOrigin();
			if (mod.getContainingMod().isPresent() || origin == null || origin.getKind() != ModOrigin.Kind.PATH) {
				continue;
			}
			for (Path path : origin.getPaths()) {
				Path name = path.getFileName();
				if (name != null && name.toString().endsWith(".jar")) {
					out.computeIfAbsent(mod.getMetadata().getId(), id -> new HashSet<>()).add(name.toString());
				}
			}
		}
		return out;
	}
}
