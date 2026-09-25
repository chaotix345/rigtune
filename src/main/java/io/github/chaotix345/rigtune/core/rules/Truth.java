package io.github.chaotix345.rigtune.core.rules;

// Three-valued (Kleene) logic for rule conditions: UNKNOWN is a value this client can't decide.
public enum Truth {
	TRUE, FALSE, UNKNOWN;

	public static Truth of(boolean value) {
		return value ? TRUE : FALSE;
	}

	public Truth and(Truth other) {
		if (this == FALSE || other == FALSE) {
			return FALSE;
		}
		return this == UNKNOWN || other == UNKNOWN ? UNKNOWN : TRUE;
	}

	public Truth or(Truth other) {
		if (this == TRUE || other == TRUE) {
			return TRUE;
		}
		return this == UNKNOWN || other == UNKNOWN ? UNKNOWN : FALSE;
	}

	public Truth not() {
		return switch (this) {
			case TRUE -> FALSE;
			case FALSE -> TRUE;
			case UNKNOWN -> UNKNOWN;
		};
	}
}
