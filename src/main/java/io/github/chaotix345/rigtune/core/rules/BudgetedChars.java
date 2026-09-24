package io.github.chaotix345.rigtune.core.rules;

import java.util.regex.Pattern;

// A CharSequence that throws after a fixed number of character reads, so a regex from the remote rules that
// backtracks catastrophically is cut off instead of pinning a worker thread.
final class BudgetedChars implements CharSequence {
	static final long DEFAULT_BUDGET = 1_000_000;

	static final class Exhausted extends RuntimeException {
		Exhausted() {
			super("regex read budget exhausted", null, false, false);
		}
	}

	private final String text;
	private final long[] remaining;

	private BudgetedChars(String text, long[] remaining) {
		this.text = text;
		this.remaining = remaining;
	}

	// TRUE or FALSE, or null when the match ran out of budget.
	static Boolean find(Pattern pattern, String subject, long budget) {
		try {
			return pattern.matcher(new BudgetedChars(subject, new long[]{budget})).find();
		} catch (Exhausted e) {
			return null;
		}
	}

	@Override
	public char charAt(int index) {
		if (--remaining[0] < 0) {
			throw new Exhausted();
		}
		return text.charAt(index);
	}

	@Override
	public int length() {
		return text.length();
	}

	@Override
	public CharSequence subSequence(int start, int end) {
		return new BudgetedChars(text.substring(start, end), remaining);
	}

	@Override
	public String toString() {
		return text;
	}
}
