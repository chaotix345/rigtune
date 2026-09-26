package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.RepoFiles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 11 (AC11.2): with high contrast off every colour is unchanged; on, each of RigTune's own colours has
// a high-contrast value, and every colour written in client/ui has one.
class PaletteTest {
	private static final Path UI = RepoFiles.resolve("src/client/java/io/github/chaotix345/rigtune/client/ui");
	private static final Pattern ARGB = Pattern.compile("0x[0-9A-Fa-f]{8}");

	private static Set<Integer> uiColours() throws IOException {
		Set<Integer> out = new TreeSet<>();
		try (Stream<Path> files = Files.list(UI)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java") && !f.endsWith("Palette.java")).toList()) {
				Matcher m = ARGB.matcher(Files.readString(file));
				while (m.find()) {
					out.add(Integer.parseUnsignedInt(m.group().substring(2), 16));
				}
			}
		}
		return out;
	}

	@Test
	void offChangesNothing() throws IOException {
		for (int argb : uiColours()) {
			assertEquals(argb, Palette.of(argb, false));
		}
		assertEquals(0x12345678, Palette.of(0x12345678, false));
	}

	@Test
	void everyColourTheScreensDrawHasAHighContrastValue() throws IOException {
		Set<Integer> colours = uiColours();
		assertTrue(colours.size() > 20, "the scan found the screens' colours: " + colours.size());
		for (int argb : colours) {
			if (argb == 0xFFFFFFFF) {
				assertEquals(argb, Palette.of(argb, true), "white stays white");
				continue;
			}
			assertNotEquals(argb, Palette.of(argb, true), String.format("no high-contrast value for 0x%08X", argb));
		}
	}

	@Test
	void highContrastIsStableAndKeepsOpacity() throws IOException {
		for (int argb : uiColours()) {
			int high = Palette.of(argb, true);
			assertEquals(high, Palette.of(high, true), String.format("0x%08X maps twice", argb));
			if (argb >>> 24 == 0xFF) {
				assertEquals(0xFF, high >>> 24, String.format("0x%08X stays opaque", argb));
			} else {
				assertTrue((high >>> 24) > (argb >>> 24), String.format("0x%08X: a stronger fill", argb));
			}
		}
	}

	@Test
	void textColoursGetLighter() throws IOException {
		for (int argb : uiColours()) {
			if (argb >>> 24 != 0xFF || argb == 0xFFFFFFFF) {
				continue;
			}
			assertTrue(luminance(Palette.of(argb, true)) > luminance(argb), String.format("0x%08X gets lighter", argb));
		}
	}

	// WCAG relative luminance of an ARGB colour.
	private static double luminance(int argb) {
		return 0.2126 * channel(argb >> 16) + 0.7152 * channel(argb >> 8) + 0.0722 * channel(argb);
	}

	private static double channel(int value) {
		double c = (value & 0xFF) / 255.0;
		return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}
}
