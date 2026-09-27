package io.github.chaotix345.rigtune.core.hardware;

import io.github.chaotix345.rigtune.core.RepoFiles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 2L (AC2L.1): RigTune reads Windows' performance-counter switches and never writes the registry (HKLM,
// system-wide, needs an administrator, and someone may have set it on purpose). No source calls a registry write.
class NoRegistryWriteTest {
	private static final List<String> WRITES = List.of("RegSetValue", "RegSetKeyValue", "RegCreateKey", "RegDeleteKey", "RegDeleteValue",
			"RegDeleteTree", "registrySet", "registryCreateKey", "registryDelete");

	@Test
	void noSourceWritesTheRegistry() throws IOException {
		List<String> found = new ArrayList<>();
		try (Stream<Path> files = Files.walk(RepoFiles.resolve("src"))) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java") && !f.getFileName().toString().equals("NoRegistryWriteTest.java")).toList()) {
				String source = Files.readString(file);
				for (String write : WRITES) {
					if (source.contains(write)) {
						found.add(file.getFileName() + ": " + write);
					}
				}
			}
		}
		assertEquals(List.of(), found);
	}

	// The reader exists and only reads.
	@Test
	void theReaderOnlyReads() throws IOException {
		String reader = Files.readString(RepoFiles.resolve("src/client/java/io/github/chaotix345/rigtune/client/probe/WindowsRegistry.java"));
		assertTrue(reader.contains("Advapi32Util.registryGetValue("));
		assertTrue(reader.contains("Advapi32Util.registryValueExists("));
	}
}
