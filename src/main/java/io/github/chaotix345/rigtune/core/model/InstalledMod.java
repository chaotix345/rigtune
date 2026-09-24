package io.github.chaotix345.rigtune.core.model;

import java.nio.file.Path;

/** file and sha1 are null for mods not backed by a jar in the mods folder (e.g. built-in or nested). */
public record InstalledMod(String modId, String name, String version, Path file, String sha1) {
}
