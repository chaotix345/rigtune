package io.github.chaotix345.rigtune.core.model;

import java.io.IOException;

// An error whose message is shown in the UI: the message is its English, text() what the client translates.
public class TextException extends IOException {
	private final Text text;

	public TextException(Text text) {
		super(text.english());
		this.text = text;
	}

	public Text text() {
		return text;
	}
}
