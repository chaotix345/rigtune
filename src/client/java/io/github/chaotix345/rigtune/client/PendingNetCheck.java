package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.undo.Staging;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingNet;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

// #46: whether pending.json changes anything at the next exit (PendingNet), read again only when pending.json or a config
// file it may patch changed: the answer depends on both, since a queue that ends every key on its file's value changes
// nothing until the player edits that file. Discard pending and Apply's restart line use it (RealController), and Try it's
// pending stamp includes the same files (TryItService).
public final class PendingNetCheck {
	public static final String MISSING = "missing";

	private final Path pendingFile;
	private final List<ConfigTargets.Target> targets;
	private volatile @Nullable Read last;

	private record Read(List<Object> stamp, boolean changes) {
	}

	public PendingNetCheck(Path pendingFile, List<ConfigTargets.Target> targets) {
		this.pendingFile = pendingFile;
		this.targets = List.copyOf(targets);
	}

	// pending.json's last-modified time and size first (MISSING when it isn't there), then each config target's.
	public static List<Object> stamp(Path pendingFile, List<ConfigTargets.Target> targets) {
		List<Object> out = new ArrayList<>();
		out.add(stamp(pendingFile));
		for (ConfigTargets.Target target : targets) {
			out.add(stamp(target.file()));
		}
		return out;
	}

	private static Object stamp(Path file) {
		try {
			BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
			return List.of(attributes.lastModifiedTime(), attributes.size());
		} catch (IOException e) {
			return MISSING;
		}
	}

	// False without pending.json; true when it can't be read (offering to discard it, as before), which isn't kept.
	public boolean changesSomething() {
		List<Object> stamp = stamp(pendingFile, targets);
		if (MISSING.equals(stamp.getFirst()) || !Files.isRegularFile(pendingFile)) {
			return false;
		}
		Read read = last;
		if (read != null && read.stamp().equals(stamp)) {
			return read.changes();
		}
		try {
			boolean changes = PendingNet.changesSomething(PendingActions.load(pendingFile).ops(), Staging.configKeys(targets)::current);
			last = new Read(stamp, changes);
			return changes;
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("Could not read {}; offering to discard it", pendingFile.getFileName(), e);
			return true;
		}
	}
}
