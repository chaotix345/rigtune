package io.github.chaotix345.rigtune.core.jvm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

// docs/v0.4/SPEC.md 6: a finding's name is the last guard before the screen, the log and the share report; it takes only
// flag names, never a value, a path or anything else from the command line.
class JvmFindingTest {
	@Test
	void onlyFlagNames() {
		for (String name : List.of("-XX:+ZGenerational", "-XX:-UseNUMA", "-XX:G1NewSizePercent", "-Xmx", "-Xms", "-Xmn", "-Dusing.aikars.flags",
				"-Daikars.new.flags")) {
			assertDoesNotThrow(() -> new JvmFinding(JvmFinding.Kind.IGNORED, name), name);
		}
		for (String bad : List.of("-XX:HeapDumpPath=C:/Users/x/dump", "-XX:G1NewSizePercent=30", "-Dusing.aikars.flags=https://mcflags.emc.gs",
				"-javaagent:C:/Users/x/agent.jar", "-Djava.library.path=C:/Users/x", "-XX:+Use NUMA", "-Xmx6144M", "-Xss1M", "C:/Users/x", "",
				"-XX:+", "-D", "-DUpper.case")) {
			assertThrows(IllegalArgumentException.class, () -> new JvmFinding(JvmFinding.Kind.IGNORED, bad), bad);
		}
		assertThrows(IllegalArgumentException.class, () -> new JvmFinding(JvmFinding.Kind.IGNORED, null));
		assertThrows(NullPointerException.class, () -> new JvmFinding(null, "-Xmx"));
	}
}
