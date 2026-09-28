package io.github.chaotix345.rigtune.core.launcher;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

// docs/v0.5/SPEC.md 4a: what the instance's own files say about who keeps a record of its mods. packwizIndex: <mods>/.index/
// holds at least one regular *.pw.toml (Prism's and PolyMC's packwiz metadata, lm §3.1), from a bounded listing: the
// first match or MAX_ENTRIES entries, whichever comes first; regular files only; no symbolic link followed (the folder
// itself neither); names only, never a file's content; never an exception. A folder that is there but can't be listed
// counts as evidence (it fails closed); anything else unexpected is no evidence.
public record InstanceEvidence(boolean packwizIndex) {
	public static final InstanceEvidence NONE = new InstanceEvidence(false);
	static final String INDEX = ".index";
	static final String SUFFIX = ".pw.toml";
	static final int MAX_ENTRIES = 256;

	// What the listing saw: a match, and how many entries it looked at.
	record Scan(boolean found, int examined) {
	}

	// On the caller's thread (LauncherProbe runs it as one task on Probes.EXECUTOR).
	public static InstanceEvidence list(@Nullable Path modsDir) {
		return scan(modsDir).found() ? new InstanceEvidence(true) : NONE;
	}

	static Scan scan(@Nullable Path modsDir) {
		if (modsDir == null) {
			return new Scan(false, 0);
		}
		Path index = modsDir.resolve(INDEX);
		int examined = 0;
		try {
			if (!Files.isDirectory(index, LinkOption.NOFOLLOW_LINKS)) {
				return new Scan(false, 0);
			}
			try (DirectoryStream<Path> entries = Files.newDirectoryStream(index)) {
				for (Path entry : entries) {
					if (examined >= MAX_ENTRIES) {
						break;
					}
					examined++;
					Path name = entry.getFileName();
					if (name != null && name.toString().endsWith(SUFFIX)
							&& Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
						return new Scan(true, examined);
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			// review-11 APPLY-7 (a WS-L2 edit in WS-L1's file): a .index/ folder that is there but can't be listed fails
			// closed, counted as packwiz metadata (LAUNCHER), never as its absence (RIGTUNE, which renames the jars).
			return new Scan(true, examined);
		}
		return new Scan(false, examined);
	}
}
