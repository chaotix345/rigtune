package io.github.chaotix345.rigtune.client.probe;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class ModScanner {
	private static final Set<String> BUILTIN_IDS = Set.of("minecraft", "java", "fabricloader", "mixinextras");

	private ModScanner() {
	}

	public static CompletableFuture<List<InstalledMod>> scanAsync() {
		return CompletableFuture.supplyAsync(ModScanner::scan, Probes.EXECUTOR);
	}

	public static List<InstalledMod> scan() {
		List<InstalledMod> out = new ArrayList<>();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			try {
				String id = mod.getMetadata().getId();
				ModOrigin origin = mod.getOrigin();
				ModOrigin.Kind kind = origin == null ? ModOrigin.Kind.UNKNOWN : origin.getKind();
				if (skip(id, mod.getMetadata().getType(), kind, mod.getContainingMod().isPresent())) {
					continue;
				}
				Path jar = kind == ModOrigin.Kind.PATH ? singleJar(origin.getPaths()) : null;
				out.add(new InstalledMod(id, mod.getMetadata().getName(), mod.getMetadata().getVersion().getFriendlyString(),
						jar, jar == null ? null : sha1(jar)));
			} catch (RuntimeException e) {
				RigTune.LOGGER.warn("Could not scan mod {}", mod.getMetadata().getId(), e);
			}
		}
		out.sort(Comparator.comparing(InstalledMod::modId));
		return List.copyOf(out);
	}

	static boolean skip(String id, String type, ModOrigin.Kind kind, boolean contained) {
		return BUILTIN_IDS.contains(id) || "builtin".equals(type) || kind == ModOrigin.Kind.NESTED || contained;
	}

	static Path singleJar(List<Path> paths) {
		if (paths == null || paths.size() != 1) {
			return null;
		}
		Path path = paths.getFirst();
		return path.getFileName() != null && path.getFileName().toString().endsWith(".jar") && Files.isRegularFile(path) ? path : null;
	}

	static String sha1(Path file) {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			byte[] buffer = new byte[65536];
			int read;
			while ((read = in.read(buffer)) > 0) {
				digest.update(buffer, 0, read);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (IOException | NoSuchAlgorithmException e) {
			RigTune.LOGGER.warn("Could not hash {}", file, e);
			return null;
		}
	}
}
