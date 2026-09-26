package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

// The result of decoding a share code (docs/v0.4/SPEC.md 4, C4): the sanitised name, what applying it would do (Preview
// always comes first; its notes list the clamps and the skipped keys), how many settings need a newer RigTune, or the
// error that rejected it. values: the decoded values as the code has them (Save only keeps these; Apply and every later
// switch apply them with this PC's clamps, which Preview lists). Nothing is written while one of these exists.
public record ProfileImport(@Nullable String name, ApplyPreview preview, int unknownKeys, @Nullable Text error, Map<String, String> values) {
	public ProfileImport {
		preview = preview == null ? ApplyPreview.EMPTY : preview;
		values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}

	public ProfileImport(@Nullable String name, ApplyPreview preview, int unknownKeys, @Nullable Text error) {
		this(name, preview, unknownKeys, error, Map.of());
	}

	public static ProfileImport failed(Text error) {
		return new ProfileImport(null, ApplyPreview.EMPTY, 0, error);
	}

	public boolean ok() {
		return error == null;
	}
}
