package io.github.chaotix345.rigtune.core.apply;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// Finds the instance's folders the way Fabric Loader 0.19.5 does (FabricLoaderImpl.getModsDirectory0 and
// LoaderUtil.normalizePath): the mods folder is -Dfabric.modsFolder taken as given, so a relative value is relative to
// the working directory, or else gameDir/mods; an existing folder is then turned into its real path.
public final class InstanceDirs {
	public static final String MODS_FOLDER_PROPERTY = "fabric.modsFolder";

	private InstanceDirs() {
	}

	public static Path modsDir(Path gameDir) {
		return modsDir(gameDir, System.getProperty(MODS_FOLDER_PROPERTY));
	}

	public static Path modsDir(Path gameDir, String modsFolderProperty) {
		Path dir = modsFolderProperty != null ? Path.of(modsFolderProperty) : gameDir.resolve("mods");
		if (Files.exists(dir)) {
			try {
				return dir.toRealPath();
			} catch (IOException ignored) {
			}
		}
		return dir.toAbsolutePath().normalize();
	}
}
