package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.compat.OptionalMods;
import io.github.chaotix345.rigtune.client.probe.HardwareProbe;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.model.ModSetHash;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// Benchmark history (docs/v0.4/SPEC.md 7): the optional context fields of a new run (modSetHash, journalCursor; since
// 0.5 stagedAtStart, docs/v0.5/SPEC.md BH-2) and the conditions the game is in now, for the "needs a rerun" marker.
public final class BenchmarkConditions {
	// BH-2 (amendment SPEC-28): more staged changes than this and the field is left out.
	static final int MAX_STAGED = 64;
	private static volatile @Nullable String modSetHash;

	private BenchmarkConditions() {
	}

	// The loaded mods can't change while the game runs, so this is worked out once. Builtin entries (Java, Minecraft,
	// Fabric Loader) are left out, and so is RigTune itself: its own update is named as a RigTune version change.
	public static String modSetHash() {
		String hash = modSetHash;
		if (hash == null) {
			Map<String, String> mods = new TreeMap<>();
			for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
				String id = mod.getMetadata().getId();
				if (!"builtin".equals(mod.getMetadata().getType())) {
					mods.put(id, mod.getMetadata().getVersion().getFriendlyString());
				}
			}
			hash = ModSetHash.ofLoadedMods(mods);
			modSetHash = hash;
		}
		return hash;
	}

	// What a new run records from history.json, from one snapshot: cursor, the id of its newest entry (null when there is
	// none or it can't be read); staged (BH-2), the ids of its changes still staged for the next start (they don't run
	// during the run): [] with no history.json, null (left out) when it can't be read or more than MAX_STAGED are staged.
	public record JournalAtStart(@Nullable String cursor, @Nullable List<String> staged) {
		public static JournalAtStart current() {
			try {
				return of(ClientJournal.get());
			} catch (RuntimeException e) {
				RigTune.LOGGER.warn("Could not read the history for the benchmark's journal cursor and staged changes", e);
				return new JournalAtStart(null, null);
			}
		}

		// One read (review 11 BENCH-8, a marked WS-H edit): a second read failing after an OK first one gave [] for "nothing
		// staged".
		static JournalAtStart of(Journal journal) {
			Journal.Snapshot snapshot = journal.snapshot();
			return of(snapshot.state(), snapshot.entries());
		}

		static JournalAtStart of(Journal.State state, List<JournalEntry> entries) {
			String cursor = state == Journal.State.OK && !entries.isEmpty() ? entries.getLast().id() : null;
			return new JournalAtStart(cursor, stagedIds(state, entries));
		}
	}

	// The journal answers no entries for a corrupt, newer or unreadable history.json: that isn't "nothing staged" (review M3).
	static @Nullable List<String> stagedIds(Journal.State state, List<JournalEntry> entries) {
		return state == Journal.State.OK ? stagedIds(entries) : state == Journal.State.MISSING ? List.of() : null;
	}

	static @Nullable List<String> stagedIds(List<JournalEntry> entries) {
		List<String> ids = new ArrayList<>();
		for (JournalEntry entry : entries) {
			for (JournalChange change : entry.changes()) {
				if (JournalChange.STAGED.equals(change.status()) && change.id() != null) {
					if (ids.size() == MAX_STAGED) {
						return null;
					}
					ids.add(change.id());
				}
			}
		}
		return List.copyOf(ids);
	}

	// On the render thread: the same sources as the context of a new run (BenchmarkController.context).
	public static BenchmarkTrend.Current current(Minecraft minecraft) {
		boolean shaders = OptionalMods.shadersInUse();
		return new BenchmarkTrend.Current(HardwareProbe.minecraftVersion(), minecraft.options.renderDistance().get(),
				minecraft.options.simulationDistance().get(), minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight(),
				minecraft.options.fullscreen().get(), shaders, shaders ? BenchmarkController.shaderPack() : null, OptionalMods.dhRendering(),
				modSetHash());
	}
}
