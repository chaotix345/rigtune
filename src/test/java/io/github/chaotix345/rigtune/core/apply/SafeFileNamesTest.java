package io.github.chaotix345.rigtune.core.apply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeFileNamesTest {
	@Test
	void acceptsOrdinaryModrinthNames() {
		for (String name : List.of("sodium-fabric-0.9.2+mc26.2.jar", "lithium-fabric-mc26.2-0.25.3.jar", "a.jar",
				"Fabric API 0.161.0.jar", "mod..jar", "console.jar", "com10.jar", "nullable-lib.jar", "lpt.jar", "aux-lib.jar", "ümlaut.jar")) {
			assertTrue(SafeFileNames.isSafeJarName(name), name);
		}
	}

	@Test
	void rejectsTraversalAndSeparators() {
		for (String name : List.of("../evil.jar", "..\\evil.jar", "mods/../../evil.jar", "a/b.jar", "a\\b.jar", "/abs.jar", "\\abs.jar",
				"C:\\Users\\x\\AppData\\Roaming\\Microsoft\\Windows\\Start Menu\\Programs\\Startup\\x.jar", "C:x.jar", "x.jar:stream",
				"..", ".", "x<y.jar", "x>y.jar", "x|y.jar", "x?.jar", "x*.jar", "x\".jar")) {
			assertFalse(SafeFileNames.isSafeJarName(name), name);
		}
	}

	@Test
	void rejectsControlCharactersDotsAndSpaces() {
		for (String name : List.of("a\u0000.jar", "a\n.jar", "a\r.jar", "a\t.jar", "a\u001f.jar", "a\u007f.jar",
				".hidden.jar", ".jar", "..jar", "x.jar.", "x.jar ", " ", "")) {
			assertFalse(SafeFileNames.isSafeJarName(name), name);
		}
		assertFalse(SafeFileNames.isSafeJarName(null));
		assertFalse(SafeFileNames.isSafeJarName("a".repeat(252) + ".jar"));
		assertTrue(SafeFileNames.isSafeJarName("a".repeat(251) + ".jar"));
	}

	@Test
	void rejectsReservedDeviceNamesWithAnyExtension() {
		for (String name : List.of("CON.jar", "con.jar", "Prn.jar", "AUX.jar", "nul.jar", "NUL.tar.jar", "COM1.jar", "com9.x.jar",
				"LPT1.jar", "lpt9.jar", "COM¹.jar", "CONIN$.jar", "conout$.jar", "NUL .jar", "con  .foo.jar")) {
			assertFalse(SafeFileNames.isSafeJarName(name), name);
		}
	}

	@Test
	void rejectsShortNameAliases() {
		for (String name : List.of("SODIUM~1.jar", "sodium~1.jar", "FABRIC~12.jar", "a~1b.jar", "ab~123456.x.jar")) {
			assertFalse(SafeFileNames.isSafeJarName(name), name);
		}
		for (String name : List.of("a~b.jar", "mod~.jar", "mod-1.0~1.jar", "~mod.jar")) {
			assertTrue(SafeFileNames.isSafeJarName(name), name);
		}
	}

	@Test
	void requiresJarExtension() {
		for (String name : List.of("x.bat", "x.exe", "x.cmd", "x.JAR", "x.jar.rigtune-pending", "x.jar.disabled", "x", "xjar")) {
			assertFalse(SafeFileNames.isSafeJarName(name), name);
		}
	}

	@Test
	void requireJarNameExplainsProblem() {
		IOException e = assertThrows(IOException.class, () -> SafeFileNames.requireJarName("..\\x\u0000.jar"));
		assertTrue(e.getMessage().contains("\\u0000"), e.getMessage());
		assertEquals("ok.jar", assertDoesNotThrowName("ok.jar"));
	}

	private static String assertDoesNotThrowName(String name) {
		try {
			return SafeFileNames.requireJarName(name);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	void resolvesOnlyDirectChildren(@TempDir Path dir) throws IOException {
		Path mods = dir.resolve("mods");
		assertEquals(mods.resolve("a.jar"), SafeFileNames.resolveJar(mods, "a.jar"));
		assertEquals(mods.resolve("a.jar.rigtune-pending"), SafeFileNames.resolveJar(mods, "a.jar", PendingActions.PENDING_SUFFIX));
		assertThrows(IOException.class, () -> SafeFileNames.resolveJar(mods, "../a.jar"));
		assertThrows(IOException.class, () -> SafeFileNames.resolveJar(mods, "a.jar", "/../../b"));
		assertThrows(IOException.class, () -> SafeFileNames.resolveJar(mods, "NUL.jar", PendingActions.PENDING_SUFFIX));
	}

	@Test
	void containmentChecksNormalisePaths(@TempDir Path dir) {
		Path mods = dir.resolve("mods");
		Path config = dir.resolve("config");
		assertTrue(SafeFileNames.isDirectChild(mods, mods.resolve("a.jar")));
		assertTrue(SafeFileNames.isDirectChild(mods, dir.resolve("x").resolve("..").resolve("mods").resolve("a.jar")));
		assertFalse(SafeFileNames.isDirectChild(mods, mods.resolve("sub").resolve("a.jar")));
		assertFalse(SafeFileNames.isDirectChild(mods, mods.resolve("..").resolve("a.jar")));
		assertFalse(SafeFileNames.isDirectChild(mods, mods));
		assertFalse(SafeFileNames.isDirectChild(null, mods.resolve("a.jar")));
		assertFalse(SafeFileNames.isDirectChild(mods, null));

		assertTrue(SafeFileNames.isInside(config, config.resolve("sodium-options.json")));
		assertTrue(SafeFileNames.isInside(config, config.resolve("rigtune").resolve("x.json")));
		assertFalse(SafeFileNames.isInside(config, config));
		assertFalse(SafeFileNames.isInside(config, config.resolve("..").resolve("options.txt")));
		assertFalse(SafeFileNames.isInside(config, dir.resolve("config-evil").resolve("x.json")));
		assertFalse(SafeFileNames.isInside(null, config.resolve("x.json")));
	}
}
