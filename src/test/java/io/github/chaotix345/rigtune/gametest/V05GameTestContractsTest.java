package io.github.chaotix345.rigtune.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.core.RepoFiles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md C6 and docs/v0.5/PLAN.md contracts items 13i and 16, checked on the game-test sources (the unit tests
// can't load src/gametest's classes): the entrypoints in C6's order, every new class returning at once under rigtune.smoke,
// and ForwardingController forwarding every RigTuneController method.
class V05GameTestContractsTest {
	private static final Path GAMETEST = RepoFiles.resolve("src/gametest/java/io/github/chaotix345/rigtune/gametest");
	private static final List<String> C6_ORDER = List.of("FirstApplyGameTest", "RigTuneClientGameTest", "BenchmarkGameTest", "LauncherGameTest",
			"UndoGameTest", "UiGameTest", "ReportGameTest", "HistoryGameTest", "PreviewGameTest", "ProfilesGameTest", "StutterGameTest",
			"StutterFixGameTest", "JvmGameTest", "BenchmarkHistoryGameTest", "TryItGameTest", "ServerLimitsGameTest", "LanGuestGameTest",
			"ServerProfilesGameTest", "AwarenessGameTest", "BatteryFlowGameTest", "LauncherManagedGameTest", "FootprintGameTest", "A11yGameTest");
	private static final List<String> V05_CLASSES = List.of("FirstApplyGameTest", "StutterFixGameTest", "TryItGameTest", "LanGuestGameTest",
			"ServerProfilesGameTest", "BatteryFlowGameTest", "LauncherManagedGameTest");

	@Test
	void theEntrypointsAreInC6sOrder() throws IOException {
		List<String> registered = new ArrayList<>();
		for (JsonElement entry : JsonParser.parseString(Files.readString(RepoFiles.resolve("src/gametest/resources/fabric.mod.json")))
				.getAsJsonObject().getAsJsonObject("entrypoints").getAsJsonArray("fabric-client-gametest")) {
			String name = entry.getAsString();
			assertTrue(name.startsWith("io.github.chaotix345.rigtune.gametest."), name);
			registered.add(name.substring(name.lastIndexOf('.') + 1));
		}
		assertEquals(C6_ORDER, registered);
		for (String name : registered) {
			assertTrue(Files.isRegularFile(GAMETEST.resolve(name + ".java")), name);
		}
	}

	// SPEC-19: the first statement of each new class's runTest returns under rigtune.smoke.
	@Test
	void everyNewClassReturnsAtOnceUnderSmoke() throws IOException {
		Pattern first = Pattern.compile("public void runTest\\(ClientGameTestContext context\\) \\{\\s*if \\(Boolean\\.getBoolean\\(\"rigtune\\.smoke\"\\)\\) \\{\\s*return;\\s*}");
		for (String name : V05_CLASSES) {
			assertTrue(first.matcher(Files.readString(GAMETEST.resolve(name + ".java"))).find(), name);
		}
	}

	@Test
	void forwardingControllerForwardsEveryMethod() throws IOException {
		String source = Files.readString(GAMETEST.resolve("ForwardingController.java"));
		Set<String> forwarded = new TreeSet<>();
		Matcher m = Pattern.compile("@Override\\s+public [^(=;]*?\\b(\\w+)\\(([^)]*)\\) \\{\\s*(?:return )?delegate\\.(\\w+)\\(").matcher(source);
		while (m.find()) {
			assertEquals(m.group(1), m.group(3), "forwards to the same method");
			forwarded.add(m.group(1) + "/" + arity(m.group(2)));
		}
		Set<String> methods = new TreeSet<>();
		Arrays.stream(RigTuneController.class.getMethods()).map(method -> method.getName() + "/" + method.getParameterCount()).forEach(methods::add);
		assertEquals(methods, forwarded);
	}

	private static int arity(String parameters) {
		return parameters.isBlank() ? 0 : parameters.split(",(?![^<]*>)").length;
	}

	// ForwardingController's constructor refuses a wrapper overriding one apply overload only; the same rule on the sources,
	// so a unit run catches it too.
	@Test
	void everyWrapperOverridesBothApplyOverloadsOrNeither() throws IOException {
		try (var files = Files.list(GAMETEST)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				String source = Files.readString(file);
				if (source.contains("extends ForwardingController")) {
					assertEquals(List.of(), applyProblems(source), file.getFileName().toString());
				}
			}
		}
		assertEquals(List.of("apply(selected) without apply(selected, entryId)"), applyProblems("""
				private static final class Half extends ForwardingController {
					@Override
					public Component apply(List<Recommendation> selected) {
						return Component.empty();
					}
				}
				"""));
	}

	private static List<String> applyProblems(String source) {
		int one = count(source, "public Component apply(List<Recommendation> selected) {");
		int two = count(source, "public Component apply(List<Recommendation> selected, String entryId) {");
		if (one > two) {
			return List.of("apply(selected) without apply(selected, entryId)");
		}
		return one < two ? List.of("apply(selected, entryId) without apply(selected)") : List.of();
	}

	private static int count(String source, String what) {
		int n = 0;
		for (int i = source.indexOf(what); i >= 0; i = source.indexOf(what, i + 1)) {
			n++;
		}
		return n;
	}
}
