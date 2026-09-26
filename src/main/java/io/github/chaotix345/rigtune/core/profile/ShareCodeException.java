package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.model.Text;

// Why a share code was rejected (docs/v0.4/SPEC.md 4, AC4.2). reason: the exact check that failed (tests, logs); text: what
// the player sees, one of a few messages.
public final class ShareCodeException extends Exception {
	public enum Reason {
		TOO_LONG, NOT_A_CODE, NEWER, BAD_CHARACTERS, BAD_BASE64, DAMAGED, BAD_FORMAT, BAD_NAME, BAD_COUNT, TRUNCATED, VARINT_OVERLONG,
		VARINT_TOO_LONG, VALUE_TOO_LARGE, DUPLICATE_KEY, OUT_OF_RANGE, TRAILING_BYTES
	}

	private static final Text TOO_LONG = Text.of("rigtune.profile.code.error.too_long", "That code is too long to be a RigTune profile code.");
	private static final Text NOT_A_CODE = Text.of("rigtune.profile.code.error.not_a_code",
			"That isn't a RigTune profile code. Profile codes start with RT1-.");
	private static final Text NEWER = Text.of("rigtune.profile.code.error.newer",
			"That code was made by a newer RigTune. Update RigTune to import it.");
	private static final Text DAMAGED = Text.of("rigtune.profile.code.error.damaged",
			"That code is damaged or incomplete. Copy the whole code again.");
	private static final Text INVALID = Text.of("rigtune.profile.code.error.invalid",
			"That code has a setting RigTune can't accept, so nothing was imported.");

	private final Reason reason;
	private final transient Text text;

	public ShareCodeException(Reason reason, String detail) {
		super(reason + ": " + detail, null, false, false);
		this.reason = reason;
		this.text = switch (reason) {
			case TOO_LONG -> TOO_LONG;
			case NOT_A_CODE -> NOT_A_CODE;
			case NEWER -> NEWER;
			case BAD_CHARACTERS, BAD_BASE64, DAMAGED, TRUNCATED -> DAMAGED;
			default -> INVALID;
		};
	}

	public Reason reason() {
		return reason;
	}

	public Text text() {
		return text;
	}
}
