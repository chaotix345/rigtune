package io.github.chaotix345.rigtune.core.report;

import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

// "Report a problem" (docs/v0.3/SPEC.md item 10, amendment F-M1): a new-issue link on GitHub with the issue form
// .github/ISSUE_TEMPLATE/problem.yml, the title and a short report filled in. The player sees the link on vanilla's
// confirm screen, and the issue on GitHub before submitting it; the full report goes to the clipboard.
public final class IssueLink {
	public static final String NEW_ISSUE = "https://github.com/chaotix345/rigtune/issues/new";
	public static final String TEMPLATE = "problem.yml";
	public static final String TITLE_FIELD = "title";
	public static final String REPORT_FIELD = "report";
	// Fully readable on the confirm screen at the smallest GUI (320 wide): its text is 270 px wide and at most 15 rows,
	// and no character the encoder emits is wider than 6 px, so 15 × 45 characters (docs/v0.3/design/ws-f.md).
	public static final int MAX_URL = 675;
	public static final String SHORTENED = "(shortened; the full report is on your clipboard)";

	private IssueLink() {
	}

	public static String title(ShareReport.Versions versions) {
		return "[RigTune " + versions.rigtune() + "] MC " + versions.minecraft() + ": ";
	}

	public static URI uri(ShareReport.@Nullable Versions versions, String report) {
		return URI.create(url(versions, report, MAX_URL));
	}

	// The whole report when it fits, else as many whole lines as fit followed by the SHORTENED note. Only template,
	// title and report: a labels or assignees value the visitor can't set makes GitHub answer 404.
	static String url(ShareReport.@Nullable Versions versions, String report, int maxChars) {
		String base = NEW_ISSUE + "?template=" + TEMPLATE;
		if (versions != null) {
			String title = param(TITLE_FIELD, title(versions));
			if (base.length() + title.length() <= maxChars) {
				base += title;
			}
		}
		List<String> lines = lines(report);
		if (lines.isEmpty()) {
			return base;
		}
		String whole = base + param(REPORT_FIELD, String.join("\n", lines));
		if (whole.length() <= maxChars) {
			return whole;
		}
		for (int kept = lines.size() - 1; kept >= 0; kept--) {
			String text = kept == 0 ? SHORTENED : String.join("\n", lines.subList(0, kept)) + "\n" + SHORTENED;
			String candidate = base + param(REPORT_FIELD, text);
			if (candidate.length() <= maxChars) {
				return candidate;
			}
		}
		return base;
	}

	// Form encoding: a space becomes "+", so a literal "+" (every RigTune version has one) must become %2B.
	static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String param(String name, String value) {
		return "&" + name + "=" + encode(value);
	}

	private static List<String> lines(String report) {
		String text = report.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
		return text.isEmpty() ? List.of() : Arrays.asList(text.split("\n", -1));
	}
}
