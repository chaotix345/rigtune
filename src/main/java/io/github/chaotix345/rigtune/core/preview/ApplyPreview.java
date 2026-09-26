package io.github.chaotix345.rigtune.core.preview;

import io.github.chaotix345.rigtune.core.model.Text;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// What Apply would do for the ticked items (docs/v0.3/SPEC.md item 13). resolved: false when mods are to be downloaded
// while Modrinth is off in the settings: an addition's files aren't known (Download.fileName null), and Apply can't
// download anything until Modrinth is on again. notes (v0.4, WS-P): lines Preview lists after the files, e.g. how RigTune
// limited a profile's values for this PC (docs/v0.4/plan-review.md P-L2); empty for an ordinary Apply.
public record ApplyPreview(List<Setting> now, List<Setting> atRestart, List<Download> downloads, List<Disable> disables, List<Skipped> skipped,
		boolean resolved, List<Text> notes) {
	public static final ApplyPreview EMPTY = new ApplyPreview(List.of(), List.of(), List.of(), List.of(), List.of(), true);

	public ApplyPreview(List<Setting> now, List<Setting> atRestart, List<Download> downloads, List<Disable> disables, List<Skipped> skipped,
			boolean resolved) {
		this(now, atRestart, downloads, disables, skipped, resolved, List.of());
	}

	public ApplyPreview withNotes(List<Text> extra) {
		List<Text> all = new ArrayList<>(notes);
		all.addAll(extra);
		return new ApplyPreview(now, atRestart, downloads, disables, skipped, resolved, all);
	}

	public record Setting(String recommendationId, Path file, String key, @Nullable String oldValue, String newValue) {
	}

	// titleText, detailText (docs/v0.3/SPEC.md item 9): what the screen shows, translated where the language has the key;
	// title and detail are their English.
	public record Download(String recommendationId, String title, @Nullable String fileName, @Nullable Path target, boolean dependency,
			Text titleText) {
		public Download {
			titleText = titleText != null ? titleText : Text.literal(title);
		}

		public Download(String recommendationId, String title, @Nullable String fileName, @Nullable Path target, boolean dependency) {
			this(recommendationId, title, fileName, target, dependency, null);
		}
	}

	public record Disable(String recommendationId, String title, Path file, Path disabledAs, Text titleText) {
		public Disable {
			titleText = titleText != null ? titleText : Text.literal(title);
		}

		public Disable(String recommendationId, String title, Path file, Path disabledAs) {
			this(recommendationId, title, file, disabledAs, null);
		}
	}

	public enum Reason {
		NOTHING_TO_APPLY, NOT_CHANGEABLE, UNKNOWN_SETTING, UNCHANGED, REFUSED, OUTSIDE_MODS, DOWNLOAD_FAILED, NO_NEW_FILES
	}

	public record Skipped(String recommendationId, String title, Reason reason, @Nullable String detail, Text titleText, @Nullable Text detailText) {
		public Skipped {
			titleText = titleText != null ? titleText : Text.literal(title);
			detailText = detailText != null || detail == null ? detailText : Text.literal(detail);
		}

		public Skipped(String recommendationId, String title, Reason reason, @Nullable String detail) {
			this(recommendationId, title, reason, detail, null, null);
		}
	}

	public ApplyPreview {
		now = List.copyOf(now);
		atRestart = List.copyOf(atRestart);
		downloads = List.copyOf(downloads);
		disables = List.copyOf(disables);
		skipped = List.copyOf(skipped);
		notes = notes == null ? List.of() : List.copyOf(notes);
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
