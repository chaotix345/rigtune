package io.github.chaotix345.rigtune.core.launcher;

import io.github.chaotix345.rigtune.core.RepoFiles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 4b, AC4b.7: only the classes docs/research/v0.5/launcher-managed-mods.md §4.3 lists make a mod-file op
// (ENABLE_FILE, DISABLE_FILE): Apply's own path (RealController, Staging, StagedRecommendations, DownloadPlanner,
// StagedProjects, PreviewDownloads), the history classes (Undo), the helper (ApplyExecutor, PendingActions). Profiles, the
// benchmark and every v0.5 feature change settings only (X10), so none of them can rename a jar behind the launcher.
class ModFileOpsSourceTest {
	private static final Pattern MAKES_A_FILE_OP = Pattern.compile("\\b(enableFile|disableFile)\\s*\\(|new\\s+Op\\s*\\(\\s*(PendingActions\\.)?Type\\.(ENABLE_FILE|DISABLE_FILE)");
	private static final Set<String> ALLOWED = Set.of(
			"client/RealController.java", "client/StagedRecommendations.java", "client/undo/Staging.java",
			"core/apply/ApplyExecutor.java", "core/apply/PendingActions.java",
			"core/modrinth/DownloadPlanner.java", "core/modrinth/StagedProjects.java", "core/preview/PreviewDownloads.java");

	private static boolean allowed(String path) {
		return ALLOWED.contains(path) || path.startsWith("core/history/");
	}

	private static List<String> makers() throws IOException {
		List<String> out = new ArrayList<>();
		for (String root : List.of("src/main/java/io/github/chaotix345/rigtune", "src/client/java/io/github/chaotix345/rigtune")) {
			Path base = RepoFiles.resolve(root);
			try (Stream<Path> walk = Files.walk(base)) {
				for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
					if (MAKES_A_FILE_OP.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
						out.add(base.relativize(file).toString().replace('\\', '/'));
					}
				}
			}
		}
		return out;
	}

	@Test
	void onlyTheListedClassesMakeAModFileOp() throws IOException {
		List<String> makers = makers();
		assertTrue(makers.contains("client/RealController.java") && makers.contains("core/modrinth/DownloadPlanner.java"), "the scan finds Apply's own: " + makers);
		assertEquals(List.of(), makers.stream().filter(path -> !allowed(path)).toList(), "a mod-file op outside lm §4.3's classes");
		assertTrue(makers.stream().noneMatch(path -> path.contains("/benchmark/") || path.contains("/profile/")), makers.toString());
	}
}
