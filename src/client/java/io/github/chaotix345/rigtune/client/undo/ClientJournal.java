package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.history.Journal;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

// The game's journal (config/rigtune/history.json), also installed as the ChangeRecorder at client start.
public final class ClientJournal {
	private static Journal journal;

	private ClientJournal() {
	}

	public static synchronized Journal get() {
		if (journal == null) {
			FabricLoader loader = FabricLoader.getInstance();
			Path configDir = loader.getConfigDir();
			String mcVersion = version(loader, "minecraft");
			journal = new Journal(configDir, version(loader, RigTune.MOD_ID), mcVersion, (message, error) -> RigTune.LOGGER.warn(message, error),
					() -> HistoryStartup.legacyEntry(configDir, mcVersion));
		}
		return journal;
	}

	static String version(FabricLoader loader, String modId) {
		return loader.getModContainer(modId).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null);
	}
}
