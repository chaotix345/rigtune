package io.github.chaotix345.rigtune.core.modrinth;

import java.io.IOException;

public class ModrinthException extends IOException {
	private final int statusCode;

	public ModrinthException(int statusCode, String message) {
		super(message);
		this.statusCode = statusCode;
	}

	public int statusCode() {
		return statusCode;
	}

	public boolean rateLimited() {
		return statusCode == 429;
	}
}
