package io.github.chaotix345.rigtune.core.hardware;

import io.github.chaotix345.rigtune.core.model.DriverVersion;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 2D (AC2D.1, AC2D.2; vg §4.2): the 38 real driver strings in src/test/resources/drivers/real-strings.tsv,
// each through GpuClassifier.detectVendor and then DriverVersionParser.parse, as the probe and the rules use them.
class DriverStringsTest {
	private static final String FILE = "/drivers/real-strings.tsv";

	record Row(String id, GraphicsBackend backend, String vendorName, String renderer, String raw, GpuVendor vendor, String family, int[] comparable,
			String display, String confidence, String source) {
	}

	static List<Row> rows() throws IOException {
		List<Row> out = new ArrayList<>();
		try (InputStream in = DriverStringsTest.class.getResourceAsStream(FILE)) {
			assertNotNull(in, FILE);
			List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.startsWith("#")).toList();
			assertEquals("id\tbackend\tvendorName\trenderer\traw\tvendor\tfamily\tcomparable\tconfidence\tsource", lines.getFirst());
			for (String line : lines.subList(1, lines.size())) {
				String[] c = line.split("\t", -1);
				assertEquals(10, c.length, line);
				int[] comparable = c[7].equals("-") ? new int[0] : Arrays.stream(c[7].split("\\.")).mapToInt(Integer::parseInt).toArray();
				out.add(new Row(c[0], GraphicsBackend.valueOf(c[1]), c[2], c[3], c[4], GpuVendor.valueOf(c[5]), c[6], comparable, c[7], c[8], c[9]));
			}
		}
		return out;
	}

	@TestFactory
	Stream<DynamicTest> everyRealStringParsesAsRecorded() throws IOException {
		return rows().stream().map(row -> DynamicTest.dynamicTest(row.id() + ": " + row.raw(), () -> {
			GpuVendor vendor = GpuClassifier.detectVendor(row.vendorName(), row.renderer());
			assertEquals(row.vendor(), vendor, "the detected vendor");
			DriverVersion parsed = DriverVersionParser.parse(vendor, row.backend(), row.raw());
			assertEquals(row.family(), parsed.family());
			assertArrayEquals(row.comparable(), parsed.comparable());
			assertEquals(row.comparable().length > 0, parsed.known());
			if (parsed.known()) {
				assertEquals(row.display(), parsed.display());
			}
		}));
	}

	@Test
	void theTableHas38SourcedRows() throws IOException {
		List<Row> rows = rows();
		assertEquals(38, rows.size());
		Set<String> ids = new HashSet<>();
		for (Row row : rows) {
			assertTrue(ids.add(row.id()), "unique id " + row.id());
			assertTrue(row.source().startsWith("https://"), row.id() + " has a source");
			assertTrue(row.confidence().equals("quoted") || row.confidence().equals("composed"), row.id());
		}
		// vg §4.2: rows 1-29, 31-34 and R1-R5 (row 30 is DriverVersionParserTest's lavapipe vector); 25-32 were composed.
		for (int i = 1; i <= 34; i++) {
			assertEquals(i != 30, ids.contains(Integer.toString(i)), "row " + i);
		}
		for (int i = 1; i <= 5; i++) {
			assertTrue(ids.contains("R" + i), "row R" + i);
		}
		for (Row row : rows) {
			boolean composed = row.id().matches("\\d+") && Integer.parseInt(row.id()) >= 25 && Integer.parseInt(row.id()) <= 32;
			assertEquals(composed ? "composed" : "quoted", row.confidence(), row.id());
		}
	}

	// AC2D.1: AMD's legacy-branch "Context 22.20.x.YYMMDD" isn't Adrenalin 22.20 (month 20); a real YY.M.rev still is.
	@Test
	void theLegacyAmdBranchIsUnknown() throws IOException {
		for (Row row : rows()) {
			if (row.id().equals("R1") || row.id().equals("R2")) {
				assertFalse(DriverVersionParser.parse(GpuVendor.AMD, row.backend(), row.raw()).known(), row.raw());
			}
			if (row.id().equals("R4")) {
				assertArrayEquals(new int[]{23, 1, 1}, DriverVersionParser.parse(GpuVendor.AMD, row.backend(), row.raw()).comparable());
			}
		}
	}
}
