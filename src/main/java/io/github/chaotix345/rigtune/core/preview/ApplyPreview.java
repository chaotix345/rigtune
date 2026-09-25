package io.github.chaotix345.rigtune.core.preview;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// What Apply would do for the ticked items (docs/v0.3/SPEC.md item 13). resolved: false when mods are to be downloaded
// while Modrinth is off in the settings: an addition's files aren't known (Download.fileName null), and Apply can't
// download anything until Modrinth is on again.
public record ApplyPreview(List<Setting> now, List<Setting> atRestart, List<Download> downloads, List<Disable> disables, List<Skipped> skipped,
		boolean resolved) {
	public static final ApplyPreview EMPTY = new ApplyPreview(List.of(), List.of(), List.of(), List.of(), List.of(), true);

	public record Setting(String recommendationId, Path file, String key, @Nullable String oldValue, String newValue) {
	}

	public record Download(String recommendationId, String title, @Nullable String fileName, @Nullable Path target, boolean dependency) {
	}

	public record Disable(String recommendationId, String title, Path file, Path disabledAs) {
	}

	public enum Reason {
		NOTHING_TO_APPLY, NOT_CHANGEABLE, UNKNOWN_SETTING, UNCHANGED, REFUSED, OUTSIDE_MODS, DOWNLOAD_FAILED, NO_NEW_FILES
	}

	public record Skipped(String recommendationId, String title, Reason reason, @Nullable String detail) {
	}

	public ApplyPreview {
		now = List.copyOf(now);
		atRestart = List.copyOf(atRestart);
		downloads = List.copyOf(downloads);
		disables = List.copyOf(disables);
		skipped = List.copyOf(skipped);
	}

	public boolean isEmpty() {
		return now.isEmpty() && atRestart.isEmpty() && downloads.isEmpty() && disables.isEmpty();
	}

	public Set<Path> filesNow() {
		Set<Path> out = new LinkedHashSet<>();
		now.forEach(s -> out.add(s.file()));
		return out;
	}

	// Every file the next restart creates, renames or patches: the config files, each download's jar, and each disabled
	// jar with its .disabled name.
	public Set<Path> filesAtRestart() {
		Set<Path> out = new LinkedHashSet<>();
		atRestart.forEach(s -> out.add(s.file()));
		downloads.forEach(d -> {
			if (d.target() != null) {
				out.add(d.target());
			}
		});
		disables.forEach(d -> {
			out.add(d.file());
			out.add(d.disabledAs());
		});
		return out;
	}

	public static String relative(Path base, Path file) {
		Path b = base.toAbsolutePath().normalize();
		Path f = file.toAbsolutePath().normalize();
		if (f.startsWith(b) && !f.equals(b)) {
			return b.relativize(f).toString().replace('\\', '/');
		}
		return f.getFileName() == null ? f.toString() : f.getFileName().toString();
	}
}
