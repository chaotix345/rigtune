package io.github.chaotix345.rigtune.core.model;

// titleText/reasonText (docs/v0.3/SPEC.md item 9, G-M1): what the screen shows, translated where the language has the
// key. title/reason stay the English, for the share report, the logs and anything written against 0.1/0.2.
public record Recommendation(
		String id,
		Category category,
		Impact impact,
		String title,
		String reason,
		Action action,
		boolean selectedByDefault,
		Text titleText,
		Text reasonText) {
	public Recommendation {
		titleText = titleText != null ? titleText : Text.literal(title);
		reasonText = reasonText != null ? reasonText : Text.literal(reason);
	}

	// Rule data or text built elsewhere: shown as it is.
	public Recommendation(String id, Category category, Impact impact, String title, String reason, Action action, boolean selectedByDefault) {
		this(id, category, impact, title, reason, action, selectedByDefault, null, null);
	}

	public static Recommendation of(String id, Category category, Impact impact, Text title, Text reason, Action action, boolean selectedByDefault) {
		return new Recommendation(id, category, impact, title.english(), reason.english(), action, selectedByDefault, title, reason);
	}

	public boolean appliable() {
		return !(action instanceof Action.None);
	}
}
