package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.UpdateInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OnlineDataFetcher {
	public static final String LOADER = "fabric";

	public record Result(OnlineData data, Map<String, String> projectIdsByModId) {
		public static Result offline() {
			return new Result(OnlineData.offline(), Map.of());
		}
	}

	private final ModrinthClient client;

	public OnlineDataFetcher(ModrinthClient client) {
		this.client = client;
	}

	public OnlineData fetch(List<InstalledMod> installed, Collection<String> candidateSlugs, String mcVersion) {
		return fetchAll(installed, candidateSlugs, mcVersion).data();
	}

	public Result fetchAll(List<InstalledMod> installed, Collection<String> candidateSlugs, String mcVersion) {
		try {
			Map<String, List<InstalledMod>> modsBySha1 = new LinkedHashMap<>();
			for (InstalledMod mod : installed) {
				if (mod.sha1() != null) {
					modsBySha1.computeIfAbsent(mod.sha1().toLowerCase(), k -> new ArrayList<>()).add(mod);
				}
			}

			Map<String, String> projectIds = new LinkedHashMap<>();
			Map<String, UpdateInfo> updates = new LinkedHashMap<>();
			if (!modsBySha1.isEmpty()) {
				Map<String, ModrinthVersion> current = client.versionsByHashes(modsBySha1.keySet());
				Map<String, ModrinthVersion> latest = client.latestVersionsByHashes(modsBySha1.keySet(), LOADER, mcVersion);
				for (Map.Entry<String, List<InstalledMod>> entry : modsBySha1.entrySet()) {
					ModrinthVersion cur = current.get(entry.getKey());
					if (cur == null || cur.projectId() == null) {
						continue;
					}
					ModrinthVersion next = latest.get(entry.getKey());
					for (InstalledMod mod : entry.getValue()) {
						projectIds.put(mod.modId(), cur.projectId());
						if (isUpdate(cur, next)) {
							ModFile file = next.primaryFile();
							updates.put(mod.modId(), new UpdateInfo(mod.modId(), next.projectId(), cur.versionNumber(),
									next.id(), next.versionNumber(), file));
						}
					}
				}
			}

			Map<String, Boolean> available = new LinkedHashMap<>();
			if (!candidateSlugs.isEmpty()) {
				List<ModrinthProject> projects = client.projects(candidateSlugs);
				for (String slug : candidateSlugs) {
					for (ModrinthProject project : projects) {
						if (slug.equalsIgnoreCase(project.slug()) || slug.equals(project.id())) {
							available.put(slug, project.supports(LOADER, mcVersion));
							break;
						}
					}
				}
			}

			return new Result(new OnlineData(true, Map.copyOf(available), Map.copyOf(updates)), Map.copyOf(projectIds));
		} catch (Exception e) {
			if (e instanceof IOException) {
				RigTune.LOGGER.warn("Modrinth lookups failed; using offline data: {}", e.toString());
			} else {
				RigTune.LOGGER.warn("Modrinth lookups failed; using offline data", e);
			}
			return Result.offline();
		}
	}

	// A "latest" that was published before the installed one would be a downgrade (e.g. installed build is mis-tagged).
	private static boolean isUpdate(ModrinthVersion cur, ModrinthVersion next) {
		return next != null
				&& !next.id().equals(cur.id())
				&& next.primaryFile() != null
				&& !next.datePublished().isBefore(cur.datePublished())
				&& stabilityRank(next.versionType()) >= stabilityRank(cur.versionType());
	}

	// release > beta > alpha; an unrecognized/missing type is treated as the least stable.
	private static int stabilityRank(String versionType) {
		return switch (versionType == null ? "" : versionType) {
			case "release" -> 2;
			case "beta" -> 1;
			case "alpha" -> 0;
			default -> 0;
		};
	}
}
