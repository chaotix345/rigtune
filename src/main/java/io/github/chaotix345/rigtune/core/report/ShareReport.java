package io.github.chaotix345.rigtune.core.report;

import io.github.chaotix345.rigtune.core.model.BenchmarkSummary;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.CpuInfo;
import io.github.chaotix345.rigtune.core.model.DisplayInfo;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

// The "Copy report" text (docs/v0.2/SPEC.md item 10): Markdown for a Discord message or an issue. Only what the report
// shows about the machine and the suggestions' titles: no reasons, no file paths, no user or world names.
public final class ShareReport {
	public static final int DISCORD_LIMIT = 2000;
	private static final int FIELD_LIMIT = 120;
	private static final String PATH = "<path>";
	private static final List<Pattern> PATHS = List.of(
			Pattern.compile("\\S*\\\\\\S*"),
			Pattern.compile("(?i)(?<!\\w)[a-z]:/\\S*"),
			Pattern.compile("~/\\S*"),
			Pattern.compile("(?<![\\w:/.])/(?:[^\\s/]+/)+[^\\s/]*"));

	public record Versions(String rigtune, String minecraft, String loader) {
	}

	private ShareReport() {
	}

	public static String format(Report report, Versions versions, BenchmarkSummary benchmark) {
		return format(report, versions, benchmark, DISCORD_LIMIT);
	}

	public static String format(Report report, Versions versions, BenchmarkSummary benchmark, int maxChars) {
		StringBuilder fixed = new StringBuilder();
		fixed.append("**RigTune ").append(field(versions.rigtune())).append("** · Minecraft ").append(field(versions.minecraft()))
				.append(" · Fabric Loader ").append(field(versions.loader())).append('\n');
		hardware(fixed, report);
		benchmark(fixed, benchmark);

		List<String> items = items(report.recommendations());
		if (items.isEmpty()) {
			fixed.append("**Recommendations** none\n");
		} else {
			fixed.append("**Recommendations** (").append(items.size()).append("; [x] = ticked)\n");
		}

		StringBuilder all = new StringBuilder(fixed);
		items.forEach(all::append);
		String text = stripTrailingNewline(all);
		if (text.length() > maxChars) {
			text = truncated(fixed.toString(), items, maxChars);
		}
		return hardCut(text, maxChars);
	}

	private static void hardware(StringBuilder out, Report report) {
		HardwareProfile hw = report.hardware();
		CpuInfo cpu = hw.cpu();
		out.append("**Hardware**\n");
		out.append("- CPU: ").append(field(cpu.name()));
		if (cpu.physicalCores() > 0) {
			out.append(" (").append(cpu.physicalCores()).append(" cores, ").append(cpu.logicalCores()).append(" threads)");
		} else if (cpu.logicalCores() > 0) {
			out.append(" (").append(cpu.logicalCores()).append(" threads)");
		}
		out.append('\n');

		GpuInfo gpu = hw.gpu();
		String gpuName = blank(gpu.renderer()) ? gpu.vendorString() : gpu.renderer();
		out.append("- GPU: ").append(field(gpuName));
		if (!blank(gpu.driverVersion())) {
			out.append(" · driver ").append(field(gpu.driverVersion()));
		}
		out.append(" · ").append(switch (gpu.backend()) {
			case OPENGL -> "OpenGL";
			case VULKAN -> "Vulkan";
			case UNKNOWN -> "?";
		});
		out.append(" · ").append(gb(gpu.vramMb())).append(" VRAM\n");

		DisplayInfo display = hw.display();
		out.append("- RAM ").append(gb(hw.totalRamMb())).append(" · heap ").append(gb(hw.maxHeapMb())).append(" · display ");
		if (display.width() > 0 && display.height() > 0) {
			out.append(display.width()).append('×').append(display.height());
			if (display.refreshRate() > 0) {
				out.append(" @ ").append(display.refreshRate()).append(" Hz");
			}
		} else {
			out.append('?');
		}
		out.append('\n');

		out.append("- Tier ").append(report.tier().rawTier()).append("/5 · limited by ").append(limit(report.tier().limitingFactor()))
				.append(" · goal ").append(capitalised(report.goal().name())).append('\n');
		out.append("- Rules r").append(report.rulesRevision()).append(" (").append(field(report.rulesSource())).append(") · ")
				.append(report.online() ? "online" : "offline").append('\n');
	}

	private static void benchmark(StringBuilder out, BenchmarkSummary b) {
		if (b == null) {
			out.append("**Latest benchmark** not run yet\n");
			return;
		}
		String at = b.at() == null ? "?" : b.at().length() >= 10 ? b.at().substring(0, 10) : b.at();
		out.append("**Latest benchmark** ").append(field(at)).append(" · ").append(field(b.mode())).append(" · ")
				.append(field(b.scene() == null ? null : b.scene().toLowerCase(Locale.ROOT).replace('_', ' '))).append('\n');
		out.append("- render distance ").append(b.renderDistance())
				.append(" · avg ").append(Math.round(b.avgFps())).append(" FPS · 1% low ").append(Math.round(b.onePercentLowFps()))
				.append(" FPS · target ").append(b.targetFps()).append(" FPS ").append(b.targetMet() ? "met" : "missed").append('\n');
	}

	// One entry per recommendation; the first of each category carries the category's header, so a cut never leaves a
	// header without an item.
	private static List<String> items(List<Recommendation> recommendations) {
		Map<Category, List<Recommendation>> grouped = new EnumMap<>(Category.class);
		for (Recommendation r : recommendations) {
			grouped.computeIfAbsent(r.category(), c -> new ArrayList<>()).add(r);
		}
		List<String> items = new ArrayList<>();
		for (Map.Entry<Category, List<Recommendation>> group : grouped.entrySet()) {
			boolean first = true;
			for (Recommendation r : group.getValue()) {
				StringBuilder item = new StringBuilder();
				if (first) {
					item.append("__").append(label(group.getKey())).append("__\n");
					first = false;
				}
				item.append("- ");
				if (r.appliable()) {
					item.append(r.selectedByDefault() ? "[x] " : "[ ] ");
				}
				item.append(field(r.title())).append(" (").append(r.impact().name().toLowerCase(Locale.ROOT)).append(")\n");
				items.add(item.toString());
			}
		}
		return items;
	}

	private static String truncated(String fixed, List<String> items, int maxChars) {
		int best = 0;
		int length = fixed.length();
		for (int k = 1; k <= items.size(); k++) {
			length += items.get(k - 1).length();
			if (length + more(items.size() - k).length() <= maxChars) {
				best = k;
			}
		}
		StringBuilder out = new StringBuilder(fixed);
		for (int k = 0; k < best; k++) {
			out.append(items.get(k));
		}
		return out.append(more(items.size() - best)).toString();
	}

	private static String more(int count) {
		return "(" + count + " more)";
	}

	private static String hardCut(String text, int maxChars) {
		if (text.length() <= maxChars) {
			return text;
		}
		int end = Math.max(0, maxChars - 1);
		if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
			end--;
		}
		return text.substring(0, end) + "…";
	}

	private static String stripTrailingNewline(StringBuilder text) {
		int end = text.length();
		while (end > 0 && text.charAt(end - 1) == '\n') {
			end--;
		}
		return text.substring(0, end);
	}

	// Replaces anything that looks like a file path, which is where user names would show up.
	static String scrub(String text) {
		String out = text;
		for (Pattern pattern : PATHS) {
			out = pattern.matcher(out).replaceAll(PATH);
		}
		return out;
	}

	private static String field(String value) {
		if (blank(value)) {
			return "?";
		}
		String clean = scrub(value.strip().replaceAll("\\s+", " "));
		return clean.length() > FIELD_LIMIT ? clean.substring(0, FIELD_LIMIT - 1) + "…" : clean;
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static String gb(long mb) {
		if (mb <= 0) {
			return "?";
		}
		double gb = mb / 1024.0;
		return gb >= 10 ? Math.round(gb) + " GB" : String.format(Locale.ROOT, "%.1f GB", gb);
	}

	private static String limit(String factor) {
		return switch (factor == null ? "" : factor) {
			case "gpu" -> "GPU";
			case "cpu" -> "CPU";
			case "mem" -> "memory";
			default -> field(factor);
		};
	}

	private static String capitalised(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	private static String label(Category category) {
		return switch (category) {
			case WARNING -> "Warnings";
			case REMOVE_MOD -> "Remove mods";
			case ADD_MOD -> "Add mods";
			case UPDATE_MOD -> "Update mods";
			case SETTING -> "Settings";
			case ADVICE -> "Advice";
		};
	}
}
