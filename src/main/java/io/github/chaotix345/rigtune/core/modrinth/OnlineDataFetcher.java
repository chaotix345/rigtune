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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OnlineDataFetcher {
	public static final String LOADER = "fabric";
	static final int VERSION_CHECK_PARALLELISM = 4;

	// versionIdsByModId: the Modrinth version each loaded mod is (by its hash). installedVersions and updateVersions: the
	// loaded mods' versions and their updates' versions, by version id, dependencies included (plan review A-H1).
	public record Result(OnlineData data, Map<String, String> projectIdsByModId, Map<String, String> versionIdsByModId,
			Map<String, ModrinthVersion> installedVersions, Map<String, ModrinthVersion> updateVersions) {
		public Result(OnlineData data, Map<String, String> projectIdsByModId, Map<String, String> versionIdsByModId) {
			this(data, projectIdsByModId, versionIdsByModId, Map.of(), Map.of());
		}

		public static Result offline() {
			return new Result(OnlineData.offline(), Map.of(), Map.of());
		}

		// Each loaded mod's Modrinth version id -> its project id.
		public Map<String, String> projectIdsByVersionId() {
			Map<String, String> out = new LinkedHashMap<>();
			installedVersions.forEach((id, version) -> {
				if (version.projectId() != null) {
					out.put(id, version.projectId());
				}
			});
			return out;
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
			Map<String, String> versionIds = new LinkedHashMap<>();
			Map<String, UpdateInfo> updates = new LinkedHashMap<>();
			Map<String, ModrinthVersion> installedVersions = new LinkedHashMap<>();
			Map<String, ModrinthVersion> updateVersions = new LinkedHashMap<>();
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
						if (cur.id() != null) {
							versionIds.put(mod.modId(), cur.id());
							installedVersions.put(cur.id(), cur);
						}
						if (isUpdate(cur, next)) {
							ModFile file = next.primaryFile();
							updates.put(mod.modId(), new UpdateInfo(mod.modId(), next.projectId(), cur.versionNumber(),
									next.id(), next.versionNumber(), file));
							updateVersions.put(next.id(), next);
						}
					}
				}
			}

			// Project loaders and game versions are unions over all versions, so they can only rule a mod out.
			// Saying it is available takes a version that has both Fabric and this MC version.
			Map<String, Boolean> available = new LinkedHashMap<>();
			Map<String, String> maybe = new LinkedHashMap<>();
			if (!candidateSlugs.isEmpty()) {
				List<ModrinthProject> projects = client.projects(candidateSlugs);
				for (String slug : candidateSlugs) {
					for (ModrinthProject project : projects) {
						if (slug.equalsIgnoreCase(project.slug()) || slug.equals(project.id())) {
							if (project.supports(LOADER, mcVersion)) {
								maybe.put(slug, project.id() != null ? project.id() : slug);
							} else {
								available.put(slug, false);
							}
							break;
						}
					}
				}
			}
			available.putAll(checkVersions(maybe, mcVersion));

			return new Result(new OnlineData(true, Map.copyOf(available), Map.copyOf(updates)), Map.copyOf(projectIds), Map.copyOf(versionIds),
					Map.copyOf(installedVersions), Map.copyOf(updateVersions));
		} catch (Exception e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			if (e instanceof IOException) {
				RigTune.LOGGER.warn("Modrinth lookups failed; using offline data: {}", e.toString());
			} else {
				RigTune.LOGGER.warn("Modrinth lookups failed; using offline data", e);
			}
			return Result.offline();
		}
	}

	// Runs GET /v2/project/{id}/version for each candidate, VERSION_CHECK_PARALLELISM at a time. A failed check
	// leaves the candidate unknown, and once Modrinth rate-limits us the remaining checks are skipped.
	private Map<String, Boolean> checkVersions(Map<String, String> projectIdsBySlug, String mcVersion) throws InterruptedException {
		if (projectIdsBySlug.isEmpty()) {
			return Map.of();
		}
		AtomicBoolean rateLimited = new AtomicBoolean();
		ExecutorService pool = Executors.newFixedThreadPool(Math.min(VERSION_CHECK_PARALLELISM, projectIdsBySlug.size()), runnable -> {
			Thread thread = new Thread(runnable, "RigTune Modrinth check");
			thread.setDaemon(true);
			return thread;
		});
		try {
			Map<String, Future<Boolean>> checks = new LinkedHashMap<>();
			projectIdsBySlug.forEach((slug, projectId) -> checks.put(slug, pool.submit(() -> {
				if (rateLimited.get()) {
					return null;
				}
				try {
					return client.latestVersion(projectId, LOADER, mcVersion).isPresent();
				} catch (ModrinthException e) {
					if (e.rateLimited()) {
						rateLimited.set(true);
					}
					throw e;
				}
			})));
			Map<String, Boolean> out = new LinkedHashMap<>();
			for (Map.Entry<String, Future<Boolean>> check : checks.entrySet()) {
				try {
					Boolean result = check.getValue().get();
					if (result != null) {
						out.put(check.getKey(), result);
					}
				} catch (ExecutionException e) {
					RigTune.LOGGER.warn("Could not check {} versions of {}: {}", LOADER, check.getKey(), e.getCause().toString());
				}
			}
			return out;
		} finally {
			pool.shutdownNow();
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
