package io.github.chaotix345.rigtune.core.launcher;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

// AC5.1 (docs/v0.3/SPEC.md item 5, C-M1): every launcher from its real signals, the precedence, and the bounded reads.
// The instance files under src/test/resources/launcher/ are real files (sources in docs/v0.3/design/ws-c.md).
class LauncherDetectorTest {
	private static final String BRAND = "minecraft.launcher.brand";

	@TempDir
	Path temp;

	private static LauncherSignals signals(Map<String, String> properties, Map<String, String> env, Path gameDir) {
		return new LauncherSignals(properties, env, gameDir);
	}

	private LauncherInfo detect(Map<String, String> properties, Map<String, String> env) {
		return LauncherDetector.detect(signals(properties, env, temp.resolve("game")));
	}

	private static String resource(String name) {
		try (InputStream in = LauncherDetectorTest.class.getResourceAsStream("/launcher/" + name)) {
			if (in == null) {
				throw new IllegalStateException("missing fixture " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// A Prism instance: <instances>/<name>/{instance.cfg, mmc-pack.json}, the game dir is <instances>/<name>/minecraft.
	private Path prismInstance(String flavour) throws IOException {
		Path instance = Files.createDirectories(temp.resolve("instances").resolve("Ae6r"));
		Files.writeString(instance.resolve("instance.cfg"), resource(flavour + "/instance.cfg"));
		Files.writeString(instance.resolve("mmc-pack.json"), resource(flavour + "/mmc-pack.json"));
		return Files.createDirectories(instance.resolve("minecraft"));
	}

	// A CurseForge instance: minecraftinstance.json sits in the game dir itself.
	private Path curseForgeInstance(String json) throws IOException {
		Path instance = Files.createDirectories(temp.resolve("curseforge").resolve("minecraft").resolve("Instances").resolve("CreateTogether"));
		Files.writeString(instance.resolve("minecraftinstance.json"), json);
		return instance;
	}

	private static LauncherInfo detectDir(Path gameDir) {
		return LauncherDetector.detect(signals(Map.of(), Map.of(), gameDir));
	}

	@Test
	void noSignalIsUnknown() {
		assertEquals(LauncherInfo.UNKNOWN, detect(Map.of(), Map.of()));
		assertEquals(LauncherInfo.UNKNOWN, LauncherDetector.detect(new LauncherSignals(Map.of(), Map.of(), null)));
		assertEquals(LauncherInfo.UNKNOWN, LauncherDetector.detect(null));
	}

	@Test
	void prismFromItsProperties() {
		assertEquals(Launcher.PRISM, detect(Map.of("org.prismlauncher.instance.name", "Ae6r"), Map.of()).launcher());
		assertEquals(Launcher.PRISM, detect(Map.of("multimc.instance.title", "Ae6r"), Map.of()).launcher());
	}

	@Test
	void prismFromItsEnvironment() {
		assertEquals(Launcher.PRISM, detect(Map.of(), Map.of("INST_ID", "Ae6r")).launcher());
		assertEquals(Launcher.PRISM, detect(Map.of(), Map.of("INST_NAME", "Ae6r")).launcher());
	}

	@Test
	void blankValuesAreNoSignal() {
		assertEquals(LauncherInfo.UNKNOWN, detect(Map.of("org.prismlauncher.instance.name", " ", BRAND, ""), Map.of("INST_ID", "")));
	}

	@Test
	void brands() {
		assertEquals(Launcher.MODRINTH_APP, detect(Map.of(BRAND, "theseus"), Map.of()).launcher());
		assertEquals(Launcher.ATLAUNCHER, detect(Map.of(BRAND, "ATLauncher"), Map.of()).launcher());
		assertEquals(Launcher.OFFICIAL, detect(Map.of(BRAND, "minecraft-launcher"), Map.of()).launcher());
		// Only the verified literals; anything else (other launchers, a different case) is not guessed at.
		for (String other : new String[]{"PrismLauncher", "gdlauncher", "Theseus", "atlauncher", "java-launcher", "fabric-loom",
				"Minecraft-Launcher", "minecraft-launcher ", "minecraft-launcher-beta"}) {
			assertEquals(LauncherInfo.UNKNOWN, detect(Map.of(BRAND, other), Map.of()), other);
		}
	}

	@Test
	void prismFromItsInstanceFiles() throws IOException {
		assertEquals(Launcher.PRISM, detectDir(prismInstance("prism")).launcher());
	}

	@Test
	void multimcFromItsInstanceFiles() throws IOException {
		assertEquals(Launcher.PRISM, detectDir(prismInstance("multimc")).launcher());
	}

	@Test
	void prismNeedsBothFiles() throws IOException {
		Path gameDir = prismInstance("prism");
		Files.delete(gameDir.getParent().resolve("mmc-pack.json"));
		assertEquals(LauncherInfo.UNKNOWN, detectDir(gameDir));
	}

	@Test
	void curseForgeReadsTheMemoryOverride() throws IOException {
		String real = resource("curseforge/minecraftinstance.json");
		assertEquals(new LauncherInfo(Launcher.CURSEFORGE, false), detectDir(curseForgeInstance(real)));
		String overridden = real.replace("\"isMemoryOverride\": false", "\"isMemoryOverride\": true");
		assertEquals(new LauncherInfo(Launcher.CURSEFORGE, true), detectDir(curseForgeInstance(overridden)));
		String without = real.replace("\"isMemoryOverride\": false,", "");
		assertEquals(new LauncherInfo(Launcher.CURSEFORGE, null), detectDir(curseForgeInstance(without)));
	}

	@Test
	void curseForgeWithAByteOrderMark() throws IOException {
		String real = resource("curseforge/minecraftinstance.json").replace("\"isMemoryOverride\": false", "\"isMemoryOverride\": true");
		assertEquals(new LauncherInfo(Launcher.CURSEFORGE, true), detectDir(curseForgeInstance("﻿" + real)));
	}

	@Test
	void curseForgeOneLevelUp() throws IOException {
		Path instance = curseForgeInstance(resource("curseforge/minecraftinstance.json"));
		assertEquals(new LauncherInfo(Launcher.CURSEFORGE, false), detectDir(Files.createDirectories(instance.resolve("minecraft"))));
	}

	@Test
	void prismPropertyBeatsTheModrinthBrand() {
		assertEquals(Launcher.PRISM, detect(Map.of("org.prismlauncher.instance.name", "Ae6r", BRAND, "theseus"), Map.of()).launcher());
		assertEquals(Launcher.PRISM, detect(Map.of(BRAND, "theseus"), Map.of("INST_NAME", "Ae6r")).launcher());
	}

	@Test
	void brandBeatsInstanceFiles() throws IOException {
		Path gameDir = curseForgeInstance(resource("curseforge/minecraftinstance.json"));
		assertEquals(Launcher.MODRINTH_APP, LauncherDetector.detect(signals(Map.of(BRAND, "theseus"), Map.of(), gameDir)).launcher());
	}

	// CurseForge starts the game through the official launcher, so its brand is minecraft-launcher too (a public
	// crash report from a curseforge/minecraft/Instances game dir, docs/v0.3/design/ws-c.md): its file decides.
	@Test
	void curseForgeBeatsTheOfficialBrand() throws IOException {
		Path gameDir = curseForgeInstance(resource("curseforge/minecraftinstance.json"));
		LauncherInfo info = LauncherDetector.detect(signals(Map.of(BRAND, "minecraft-launcher"), Map.of(), gameDir));
		assertEquals(new LauncherInfo(Launcher.CURSEFORGE, false), info);
		Path prism = prismInstance("prism");
		assertEquals(Launcher.PRISM, LauncherDetector.detect(signals(Map.of(BRAND, "minecraft-launcher"), Map.of(), prism)).launcher());
	}

	@Test
	void theGameDirComesBeforeItsParent() throws IOException {
		// minecraftinstance.json in the game dir, Prism files one level up: the game dir's own file decides.
		Path gameDir = prismInstance("prism");
		Files.writeString(gameDir.resolve("minecraftinstance.json"), resource("curseforge/minecraftinstance.json"));
		assertEquals(Launcher.CURSEFORGE, detectDir(gameDir).launcher());
	}

	@Test
	void prismFilesBeatCurseForgeInTheSameFolder() throws IOException {
		Path gameDir = prismInstance("prism");
		Files.writeString(gameDir.getParent().resolve("minecraftinstance.json"), resource("curseforge/minecraftinstance.json"));
		assertEquals(Launcher.PRISM, detectDir(gameDir).launcher());
	}

	@Test
	void nothingAboveTheParentIsRead() throws IOException {
		Path gameDir = prismInstance("prism");
		Path deeper = Files.createDirectories(gameDir.resolve("a"));
		assertEquals(LauncherInfo.UNKNOWN, detectDir(deeper));
		Path curseForge = curseForgeInstance(resource("curseforge/minecraftinstance.json"));
		assertEquals(LauncherInfo.UNKNOWN, detectDir(Files.createDirectories(curseForge.resolve("a").resolve("b"))));
	}

	@Test
	void theGameDirIsNormalized() throws IOException {
		Path gameDir = prismInstance("prism");
		assertEquals(Launcher.PRISM, detectDir(gameDir.resolve("x").resolve("..")).launcher());
	}

	@Test
	void malformedInstanceCfgIsNotPrism() throws IOException {
		Path gameDir = prismInstance("prism");
		Path cfg = gameDir.getParent().resolve("instance.cfg");
		Files.writeString(cfg, "this is not an instance config\n");
		assertEquals(LauncherInfo.UNKNOWN, detectDir(gameDir));
		Files.write(cfg, new byte[]{0, (byte) 0xFF, (byte) 0xFE, 0x13, 0x10, '=', '='});
		assertEquals(LauncherInfo.UNKNOWN, detectDir(gameDir));
	}

	@Test
	void malformedInstanceCfgStillLetsThePropertyDecide() throws IOException {
		Path gameDir = prismInstance("prism");
		Files.writeString(gameDir.getParent().resolve("instance.cfg"), "garbage");
		LauncherInfo info = LauncherDetector.detect(signals(Map.of("org.prismlauncher.instance.name", "Ae6r"), Map.of(), gameDir));
		assertEquals(Launcher.PRISM, info.launcher());
	}

	@Test
	void oversizedInstanceCfgIsNotRead() throws IOException {
		Path gameDir = prismInstance("prism");
		Path cfg = gameDir.getParent().resolve("instance.cfg");
		String real = resource("prism/instance.cfg");
		Files.writeString(cfg, real + "notes=" + "x".repeat(InstanceFiles.INSTANCE_CFG_MAX) + "\n");
		assertEquals(LauncherInfo.UNKNOWN, detectDir(gameDir));
		// Exactly at the cap is still read.
		String padded = real + "notes=";
		padded = padded + "x".repeat(InstanceFiles.INSTANCE_CFG_MAX - padded.length() - 1) + "\n";
		assertEquals(InstanceFiles.INSTANCE_CFG_MAX, padded.getBytes(StandardCharsets.UTF_8).length);
		Files.writeString(cfg, padded);
		assertEquals(Launcher.PRISM, detectDir(gameDir).launcher());
	}

	@Test
	void directoriesWithTheFileNamesAreIgnored() throws IOException {
		Path instance = Files.createDirectories(temp.resolve("instances").resolve("Dirs"));
		Files.createDirectories(instance.resolve("instance.cfg"));
		Files.writeString(instance.resolve("mmc-pack.json"), resource("prism/mmc-pack.json"));
		Path gameDir = Files.createDirectories(instance.resolve("minecraft"));
		Files.createDirectories(gameDir.resolve("minecraftinstance.json"));
		assertEquals(LauncherInfo.UNKNOWN, detectDir(gameDir));
	}

	@Test
	void symlinksToRegularFilesWork() throws IOException {
		Path real = Files.createDirectories(temp.resolve("real"));
		Files.writeString(real.resolve("instance.cfg"), resource("prism/instance.cfg"));
		Files.writeString(real.resolve("mmc-pack.json"), resource("prism/mmc-pack.json"));
		Path instance = Files.createDirectories(temp.resolve("instances").resolve("Linked"));
		try {
			Files.createSymbolicLink(instance.resolve("instance.cfg"), real.resolve("instance.cfg"));
			Files.createSymbolicLink(instance.resolve("mmc-pack.json"), real.resolve("mmc-pack.json"));
		} catch (FileSystemException | UnsupportedOperationException e) {
			Assumptions.abort("symbolic links aren't available here: " + e);
		}
		assertEquals(Launcher.PRISM, detectDir(Files.createDirectories(instance.resolve("minecraft"))).launcher());
	}

	@Test
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void fifosAreNeverOpened() throws Exception {
		Path instance = Files.createDirectories(temp.resolve("instances").resolve("Fifo"));
		Files.writeString(instance.resolve("mmc-pack.json"), resource("prism/mmc-pack.json"));
		Path gameDir = Files.createDirectories(instance.resolve("minecraft"));
		mkfifo(instance.resolve("instance.cfg"));
		mkfifo(gameDir.resolve("minecraftinstance.json"));
		LauncherInfo info = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> detectDir(gameDir));
		assertEquals(LauncherInfo.UNKNOWN, info);
	}

	private static void mkfifo(Path path) throws Exception {
		Process process = new ProcessBuilder("mkfifo", path.toString()).inheritIO().start();
		Assumptions.assumeTrue(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0, "mkfifo failed");
	}

	@Test
	void malformedCurseForgeFileHasNoValue() throws IOException {
		for (String json : new String[]{"", "not json", "[1, 2]", "{\"isMemoryOverride\": ", "{\"isMemoryOverride\": \"yes\"}",
				"{\"isMemoryOverride\": null}", "{\"isMemoryOverride\": 1}", "{\"a\": {\"isMemoryOverride\": true}}", "{\"a\": [1,, 2], \"isMemoryOverride\": true}",
				"\u0000\u0001\u0002"}) {
			assertEquals(new LauncherInfo(Launcher.CURSEFORGE, null), detectDir(curseForgeInstance(json)), json);
		}
	}

	@Test
	void curseForgeKeyAfterALargeArrayIsRead() throws IOException {
		StringBuilder addons = new StringBuilder("{\"installedAddons\": [");
		for (int i = 0; i < 20_000; i++) {
			addons.append(i == 0 ? "" : ",").append("{\"addonID\": ").append(i).append(", \"installedFile\": {\"fileName\": \"mod-").append(i).append(".jar\"}}");
		}
		addons.append("], \"isMemoryOverride\": true}");
		assertEquals(new LauncherInfo(Launcher.CURSEFORGE, true), detectDir(curseForgeInstance(addons.toString())));
	}

	@Test
	void curseForgeStopsReadingAtTheCap() throws IOException {
		Path file = temp.resolve("capped.json");
		Files.writeString(file, "{\"installedAddons\": \"" + "x".repeat(2048) + "\", \"isMemoryOverride\": true}");
		assertNull(InstanceFiles.memoryOverride(file, 1024));
		assertEquals(Boolean.TRUE, InstanceFiles.memoryOverride(file, 4096));
	}

	@Test
	void curseForgeFileOverThirtyTwoMebibytes() throws IOException {
		Path gameDir = Files.createDirectories(temp.resolve("huge"));
		try (OutputStream out = Files.newOutputStream(gameDir.resolve("minecraftinstance.json"))) {
			out.write("{\"installedAddons\": \"".getBytes(StandardCharsets.UTF_8));
			byte[] chunk = "x".repeat(1024 * 1024).getBytes(StandardCharsets.UTF_8);
			for (int i = 0; i < 33; i++) {
				out.write(chunk);
			}
			out.write("\", \"isMemoryOverride\": true}".getBytes(StandardCharsets.UTF_8));
		}
		assertEquals(new LauncherInfo(Launcher.CURSEFORGE, null), detectDir(gameDir));
	}

	@Test
	void aFailingSignalSourceIsUnknown() {
		Map<String, String> throwing = new AbstractMap<>() {
			@Override
			public String get(Object key) {
				throw new IllegalStateException("boom");
			}

			@Override
			public Set<Entry<String, String>> entrySet() {
				throw new IllegalStateException("boom");
			}
		};
		assertEquals(LauncherInfo.UNKNOWN, detect(throwing, Map.of()));
		assertEquals(LauncherInfo.UNKNOWN, detect(Map.of(), throwing));
	}

	@Test
	void nullMapsAreNoSignal() {
		Map<String, String> withNull = new HashMap<>();
		withNull.put(BRAND, null);
		assertEquals(LauncherInfo.UNKNOWN, LauncherDetector.detect(new LauncherSignals(withNull, null, null)));
	}
}
