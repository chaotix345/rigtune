package io.github.chaotix345.rigtune.core.report;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.BenchmarkSummary;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.CpuInfo;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.TierResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssueLinkTest {
	private static final ShareReport.Versions VERSIONS = new ShareReport.Versions("0.3.0+mc26.2", "26.2", "0.19.5");
	private static final String PREFIX = "https://github.com/chaotix345/rigtune/issues/new?template=problem.yml";

	private static Report report(HardwareProfile hw, int recommendations, String titleSuffix) {
		List<Recommendation> recs = new ArrayList<>();
		Category[] categories = Category.values();
		for (int i = 0; i < recommendations; i++) {
			recs.add(new Recommendation("rec-" + i, categories[i % categories.length], Impact.MEDIUM, "Recommendation " + i + titleSuffix,
					"reason", new Action.None(), false));
		}
		return new Report(hw, new GpuClass(GpuVendor.AMD, false, 5, "x"), new TierResult(4, 4, 5, 4, 5, "cpu"), Goal.BALANCED,
				recs, 11, "remote", true, Instant.parse("2026-09-26T10:00:00Z"));
	}

	// The raw query's parameters, decoded as a browser form would be.
	private static Map<String, String> params(String url) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String pair : URI.create(url).getRawQuery().split("&")) {
			int eq = pair.indexOf('=');
			out.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return out;
	}

	private static List<String> lines(String text) {
		return Arrays.asList(text.split("\n", -1));
	}

	@Test
	void encodesFormStyle() {
		assertEquals("a+b%2Bc%25d%26e%23f%0Ag%C2%B7%C3%97%C3%A9%F0%9F%98%80", IssueLink.encode("a b+c%d&e#f\ng·×é😀"));
	}

	@Test
	void encodedValuesRoundTrip() {
		String tricky = "1% low + avg & more #1\nsecond line · 2560×1440 · Café 😀";
		String url = IssueLink.url(VERSIONS, tricky, IssueLink.MAX_URL);

		assertEquals(tricky, params(url).get(IssueLink.REPORT_FIELD));
		assertFalse(url.contains("\n") || url.contains(" ") || url.contains("#"), url);
	}

	@Test
	void titleCarriesVersions() {
		assertEquals("[RigTune 0.3.0+mc26.2] MC 26.2: ", IssueLink.title(VERSIONS));

		String url = IssueLink.url(VERSIONS, "x", IssueLink.MAX_URL);
		assertTrue(url.contains("&title=%5BRigTune+0.3.0%2Bmc26.2%5D+MC+26.2%3A+&"), url);
		assertEquals("[RigTune 0.3.0+mc26.2] MC 26.2: ", params(url).get(IssueLink.TITLE_FIELD));
	}

	@Test
	void shortReportIsSentWhole() {
		String report = "**RigTune 0.3.0+mc26.2** · Minecraft 26.2\n**Hardware**\n- CPU: test";
		String url = IssueLink.uri(VERSIONS, report).toString();

		assertTrue(url.startsWith(PREFIX + "&title="), url);
		assertEquals(report, params(url).get(IssueLink.REPORT_FIELD));
		assertEquals(List.of("template", "title", "report"), List.copyOf(params(url).keySet()));
	}

	@Test
	void longReportIsCutAtALineBoundary() {
		String full = ShareReport.format(report(Fixtures.userRig().build(), 50, ""), VERSIONS, null);
		String url = IssueLink.url(VERSIONS, full, IssueLink.MAX_URL);

		assertTrue(url.length() <= IssueLink.MAX_URL && IssueLink.MAX_URL <= 800, url.length() + ": " + url);
		String sent = params(url).get(IssueLink.REPORT_FIELD);
		assertTrue(sent.endsWith("\n" + IssueLink.SHORTENED), sent);
		List<String> kept = lines(sent.substring(0, sent.length() - IssueLink.SHORTENED.length() - 1));
		List<String> all = lines(full);
		assertEquals(all.subList(0, kept.size()), kept);
		assertTrue(kept.size() >= 7, "the versions and hardware lines fit: " + sent);
		String oneMore = String.join("\n", all.subList(0, kept.size() + 1)) + "\n" + IssueLink.SHORTENED;
		assertTrue((PREFIX + "&title=" + IssueLink.encode(IssueLink.title(VERSIONS)) + "&report=" + IssueLink.encode(oneMore)).length() > IssueLink.MAX_URL,
				"the longest prefix that fits: " + sent);
	}

	@Test
	void worstCaseReportStaysWithinBudget() {
		Fixtures.Hw hw = Fixtures.userRig();
		String longName = "Ünïcödé ✓ ".repeat(20);
		hw.cpu = new CpuInfo(longName, 64, 128, 5000);
		hw.gpu = new GpuInfo(longName, longName, longName, GraphicsBackend.VULKAN, 49152);
		BenchmarkSummary bench = new BenchmarkSummary("2026-09-26T09:30:00Z", "tune", "BENCHMARK_WORLD", 240, 32, 999.4, 888.8, false);
		String full = ShareReport.format(report(hw.build(), 50, " " + "×".repeat(200)), VERSIONS, bench);
		String url = IssueLink.uri(VERSIONS, full).toString();

		assertTrue(url.length() <= IssueLink.MAX_URL, url.length() + ": " + url);
		assertTrue(url.length() <= 800, "AC10.1");
		String sent = params(url).get(IssueLink.REPORT_FIELD);
		assertTrue(sent.endsWith(IssueLink.SHORTENED), sent);
		assertTrue(full.startsWith(sent.substring(0, sent.length() - IssueLink.SHORTENED.length())), sent);
	}

	@Test
	void crlfIsNormalised() {
		String url = IssueLink.url(VERSIONS, "one\r\ntwo\rthree\n\n", IssueLink.MAX_URL);

		assertEquals("one\ntwo\nthree", params(url).get(IssueLink.REPORT_FIELD));
	}

	@Test
	void firstLineTooLongLeavesOnlyTheNote() {
		String url = IssueLink.url(VERSIONS, "x".repeat(IssueLink.MAX_URL) + "\nsecond", IssueLink.MAX_URL);

		assertEquals(IssueLink.SHORTENED, params(url).get(IssueLink.REPORT_FIELD));
		assertTrue(url.length() <= IssueLink.MAX_URL, url);
	}

	@Test
	void nullVersionsLeaveTheTitleOut() {
		String url = IssueLink.url(null, "report", IssueLink.MAX_URL);

		assertTrue(url.startsWith(PREFIX + "&report="), url);
		assertNull(params(url).get(IssueLink.TITLE_FIELD));
	}

	@Test
	void hugeTitleIsLeftOutRatherThanOverflowing() {
		ShareReport.Versions huge = new ShareReport.Versions("9".repeat(IssueLink.MAX_URL), "26.2", "0.19.5");
		String url = IssueLink.url(huge, "report", IssueLink.MAX_URL);

		assertTrue(url.length() <= IssueLink.MAX_URL, url);
		assertNull(params(url).get(IssueLink.TITLE_FIELD));
		assertEquals("report", params(url).get(IssueLink.REPORT_FIELD));
	}

	@Test
	void emptyReportSendsNoReportField() {
		String url = IssueLink.url(VERSIONS, "\n", IssueLink.MAX_URL);

		assertEquals(List.of("template", "title"), List.copyOf(params(url).keySet()));
	}

	private record FormField(String type, boolean required) {
	}

	// The issue form's fields by id: each `id:` belongs to the `- type:` item above it.
	private static Map<String, FormField> formFields(List<String> yaml) {
		Map<String, FormField> fields = new LinkedHashMap<>();
		String type = null;
		String id = null;
		for (String line : yaml) {
			String trimmed = line.strip();
			if (trimmed.startsWith("- type:")) {
				type = trimmed.substring("- type:".length()).strip();
				id = null;
			} else if (trimmed.startsWith("id:") && type != null) {
				id = trimmed.substring("id:".length()).strip();
				fields.put(id, new FormField(type, false));
			} else if (trimmed.equals("required: true") && id != null) {
				fields.put(id, new FormField(type, true));
			}
		}
		return fields;
	}

	@Test
	void fieldNamesMatchTheIssueForm() throws IOException {
		Path form = RepoFiles.resolve(".github/ISSUE_TEMPLATE/" + IssueLink.TEMPLATE);
		List<String> yaml = Files.readAllLines(form, StandardCharsets.UTF_8);
		Map<String, FormField> fields = formFields(yaml);

		assertEquals(new FormField("textarea", false), fields.get(IssueLink.REPORT_FIELD), fields.toString());
		assertEquals(new FormField("textarea", true), fields.get("what-happened"), fields.toString());
		assertEquals(new FormField("textarea", false), fields.get("expected"), fields.toString());
		assertTrue(yaml.stream().anyMatch(l -> l.startsWith("labels:")), "labels come from the form");

		String url = IssueLink.url(VERSIONS, ShareReport.format(report(Fixtures.userRig().build(), 5, ""), VERSIONS, null), IssueLink.MAX_URL);
		for (String name : params(url).keySet()) {
			assertTrue(name.equals("template") || name.equals(IssueLink.TITLE_FIELD) || fields.containsKey(name), name + " is a form field");
		}
		for (String forbidden : List.of("labels=", "assignees=", "projects=", "milestone=")) {
			assertFalse(url.contains(forbidden), url);
		}
	}

	@Test
	void noNetworkClient() throws IOException {
		String source = Files.readString(RepoFiles.resolve("src/main/java/io/github/chaotix345/rigtune/core/report/IssueLink.java"), StandardCharsets.UTF_8);
		Set<String> allowed = Set.of("org.jspecify.annotations.Nullable", "java.net.URI", "java.net.URLEncoder", "java.nio.charset.StandardCharsets",
				"java.util.Arrays", "java.util.List");
		source.lines().filter(l -> l.startsWith("import ")).forEach(l ->
				assertTrue(allowed.contains(l.substring("import ".length(), l.length() - 1)), "unexpected import: " + l));
		for (String network : List.of("openConnection", "openStream", "HttpClient", "HttpURLConnection", "Socket", "new URL(", "java.net.http")) {
			assertFalse(source.contains(network), network);
		}
	}
}
