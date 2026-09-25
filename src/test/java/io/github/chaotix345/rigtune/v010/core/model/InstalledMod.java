package io.github.chaotix345.rigtune.v010.core.model;

import java.nio.file.Path;

/**
 * file is set only for a jar directly in the instance's mods folder (the only jars RigTune renames). sha1 is set for
 * any top-level jar, including one loaded from elsewhere, and is null for built-in or nested mods.
 */
public record InstalledMod(String modId, String name, String version, Path file, String sha1) {
}
