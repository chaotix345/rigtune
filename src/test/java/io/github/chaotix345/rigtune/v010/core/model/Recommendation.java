package io.github.chaotix345.rigtune.v010.core.model;

public record Recommendation(
		String id,
		Category category,
		Impact impact,
		String title,
		String reason,
		Action action,
		boolean selectedByDefault) {
	public boolean appliable() {
		return !(action instanceof Action.None);
	}
}
