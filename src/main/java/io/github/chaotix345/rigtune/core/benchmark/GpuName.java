package io.github.chaotix345.rigtune.core.benchmark;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Pattern;

// The GPU as the benchmark's context (review-11 COMPAT-2), C20's FixConditions and the rerun marker compare it: the
// renderer string without the build versions Mesa puts in it (review-12 R12FEAT-1). "AMD Radeon RX 9070 XT (radeonsi,
// gfx1201, LLVM 21.1.7, DRM 3.64)" and "llvmpipe (LLVM 20.1.2, 256 bits)" change with every Mesa, LLVM or kernel update;
// a parenthesised group holding a version number (digits, a dot, digits) is dropped, so a driver update isn't "another
// GPU", while a chip or a trademark in parentheses ("(RADV GFX1201)", "(TM)") stays. Used when recording and again when
// comparing, so records written before this (the raw string) still compare with new ones.
public final class GpuName {
	private static final Pattern VERSION_GROUP = Pattern.compile("\\([^()]*\\d+\\.\\d+[^()]*\\)");
	private static final Pattern SPACES = Pattern.compile("\\s+");

	private GpuName() {
	}

	// The renderer without its version groups, spaces collapsed; null when nothing is left.
	public static @Nullable String clean(@Nullable String renderer) {
		if (renderer == null) {
			return null;
		}
		String out = SPACES.matcher(VERSION_GROUP.matcher(renderer).replaceAll(" ")).replaceAll(" ").strip();
		return out.isEmpty() ? null : out;
	}

	// Both known and the same GPU, case aside.
	public static boolean same(String a, String b) {
		String ca = clean(a);
		String cb = clean(b);
		return ca != null && cb != null && ca.toLowerCase(Locale.ROOT).equals(cb.toLowerCase(Locale.ROOT));
	}
}
