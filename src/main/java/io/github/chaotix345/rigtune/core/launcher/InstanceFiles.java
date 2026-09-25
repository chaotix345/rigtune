package io.github.chaotix345.rigtune.core.launcher;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.jspecify.annotations.Nullable;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

// The launchers' instance files, read defensively (C-M1): only regular files are opened (a symlink to one is fine, a
// FIFO or directory never is), instance.cfg only up to 64 KiB (size checked before opening, the read bounded too), and
// minecraftinstance.json streamed for its top-level isMemoryOverride, never past 32 MiB.
final class InstanceFiles {
	static final String INSTANCE_CFG = "instance.cfg";
	static final String MMC_PACK = "mmc-pack.json";
	static final String CURSEFORGE_INSTANCE = "minecraftinstance.json";
	static final int INSTANCE_CFG_MAX = 64 * 1024;
	static final long CURSEFORGE_MAX = 32L * 1024 * 1024;

	record CurseForge(@Nullable Boolean memoryOverride) {
	}

	private InstanceFiles() {
	}

	// A Prism/MultiMC instance folder: instance.cfg with the InstanceType key, next to mmc-pack.json.
	static boolean prismInstance(Path dir) {
		Path cfg = dir.resolve(INSTANCE_CFG);
		if (!Files.isRegularFile(cfg) || !Files.isRegularFile(dir.resolve(MMC_PACK))) {
			return false;
		}
		String text = readSmall(cfg, INSTANCE_CFG_MAX);
		return text != null && hasKey(text, "InstanceType");
	}

	static @Nullable String readSmall(Path file, int max) {
		try {
			if (Files.size(file) > max) {
				return null;
			}
			try (InputStream in = Files.newInputStream(file)) {
				byte[] bytes = in.readNBytes(max + 1);
				return bytes.length > max ? null : new String(bytes, StandardCharsets.UTF_8);
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static boolean hasKey(String ini, String key) {
		for (String raw : ini.split("\\R")) {
			String line = raw.strip();
			int eq = line.indexOf('=');
			if (eq > 0 && line.substring(0, eq).strip().equals(key) && !line.substring(eq + 1).isBlank()) {
				return true;
			}
		}
		return false;
	}

	// Present whenever minecraftinstance.json is a regular file; the override is null when it can't be read.
	static Optional<CurseForge> curseForge(Path dir) {
		Path file = dir.resolve(CURSEFORGE_INSTANCE);
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		return Optional.of(new CurseForge(memoryOverride(file, CURSEFORGE_MAX)));
	}

	static @Nullable Boolean memoryOverride(Path file, long maxBytes) {
		try (Reader reader = withoutBom(new InputStreamReader(new Capped(Files.newInputStream(file), maxBytes), StandardCharsets.UTF_8));
				JsonReader json = new JsonReader(reader)) {
			if (json.peek() != JsonToken.BEGIN_OBJECT) {
				return null;
			}
			json.beginObject();
			while (json.hasNext()) {
				String name = json.nextName();
				if (name.equals("isMemoryOverride")) {
					return json.peek() == JsonToken.BOOLEAN ? json.nextBoolean() : null;
				}
				json.skipValue();
			}
			return null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static Reader withoutBom(Reader reader) throws IOException {
		PushbackReader in = new PushbackReader(reader, 1);
		int first = in.read();
		if (first >= 0 && first != '﻿') {
			in.unread(first);
		}
		return in;
	}

	// Fails the read once more than max bytes would be read.
	private static final class Capped extends FilterInputStream {
		private long remaining;

		Capped(InputStream in, long max) {
			super(in);
			this.remaining = max;
		}

		@Override
		public int read() throws IOException {
			if (remaining <= 0) {
				throw new IOException("read limit reached");
			}
			int b = super.read();
			if (b >= 0) {
				remaining--;
			}
			return b;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			if (length == 0) {
				return 0;
			}
			if (remaining <= 0) {
				throw new IOException("read limit reached");
			}
			int n = super.read(buffer, offset, (int) Math.min(length, remaining));
			if (n > 0) {
				remaining -= n;
			}
			return n;
		}

		@Override
		public long skip(long n) throws IOException {
			long skipped = super.skip(Math.min(n, remaining));
			remaining -= skipped;
			return skipped;
		}
	}
}
