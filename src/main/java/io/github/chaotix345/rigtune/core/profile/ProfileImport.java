package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import org.jspecify.annotations.Nullable;

// The result of decoding a share code (docs/v0.4/SPEC.md 4, C4): the sanitised name, what applying it would do (Preview
// always comes first), how many settings need a newer RigTune, or the error that rejected it. Skeleton from the
// contracts commit; WS-P owns and extends it.
public record ProfileImport(@Nullable String name, ApplyPreview preview, int unknownKeys, @Nullable Text error) {
	public ProfileImport {
		preview = preview == null ? ApplyPreview.EMPTY : preview;
	}

	public static ProfileImport failed(Text error) {
		return new ProfileImport(null, ApplyPreview.EMPTY, 0, error);
	}
}
