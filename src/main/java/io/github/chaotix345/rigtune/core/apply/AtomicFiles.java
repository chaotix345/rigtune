package io.github.chaotix345.rigtune.core.apply;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class AtomicFiles {
	static final int MOVE_ATTEMPTS = 10;
	static final long MOVE_RETRY_MILLIS = 100;

	interface Mover {
		void move(Path from, Path to) throws IOException;
	}

	private AtomicFiles() {
	}

	public static void writeString(Path target, String content) throws IOException {
		writeString(target, content, AtomicFiles::replace, MOVE_ATTEMPTS, MOVE_RETRY_MILLIS);
	}

	static void writeString(Path target, String content, Mover mover, int attempts, long retryMillis) throws IOException {
		Path dir = target.toAbsolutePath().getParent();
		Files.createDirectories(dir);
		Path tmp = Files.createTempFile(dir, target.getFileName() + ".", ".tmp");
		try {
			Files.writeString(tmp, content, StandardCharsets.UTF_8);
			moveRetrying(tmp, target, mover, attempts, retryMillis);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	// On Windows the replace fails with AccessDenied while an AV scanner, indexer or sync client has the target open
	// without FILE_SHARE_DELETE, which usually lasts only a moment.
	private static void moveRetrying(Path tmp, Path target, Mover mover, int attempts, long retryMillis) throws IOException {
		for (int attempt = 1; ; attempt++) {
			try {
				mover.move(tmp, target);
				return;
			} catch (AccessDeniedException e) {
				if (attempt >= attempts) {
					throw e;
				}
				try {
					Thread.sleep(retryMillis);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw e;
				}
			}
		}
	}

	private static void replace(Path from, Path to) throws IOException {
		try {
			Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
