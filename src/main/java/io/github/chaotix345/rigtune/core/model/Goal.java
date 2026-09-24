package io.github.chaotix345.rigtune.core.model;

public enum Goal {
	PERFORMANCE(-1), BALANCED(0), QUALITY(1);

	private final int tierOffset;

	Goal(int tierOffset) {
		this.tierOffset = tierOffset;
	}

	public int tierOffset() {
		return tierOffset;
	}
}
