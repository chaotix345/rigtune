package io.github.chaotix345.rigtune.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.probe.HardwareProbe;
import io.github.chaotix345.rigtune.client.probe.ModScanner;
import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.modrinth.DependencyResolver;
import io.github.chaotix345.rigtune.core.modrinth.HttpModrinthClient;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthClient;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthVersion;
import io.github.chaotix345.rigtune.core.modrinth.OnlineDataFetcher;
import io.github.chaotix345.rigtune.core.recommend.Recommender;
import io.github.chaotix345.rigtune.core.rules.RemoteRulesFetcher;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class RealController implements RigTuneController {
	private static final String VANILLA = SettingsBridge.VANILLA_PREFIX;
	private static final String SODIUM = SettingsBridge.SODIUM_PREFIX;

	private Minecraft minecraft;
	private final Path configDir;
	private final Path modsDir;
	private final Path pendingFile;
	private final Path rulesCache;
	private final String modVersion;
	private final ModrinthClient modrinth;
	private final ClientState state;
	private final Set<String> staged = new HashSet<>();
	private final int carriedOverOps;

	private volatile @Nullable RulesDocument rules;
	private volatile @Nullable HardwareProfile hardware;
	private volatile @Nullable List<InstalledMod> mods;
	private volatile OnlineDataFetcher.Result online = OnlineDataFetcher.Result.offline();
	private volatile @Nullable Report report;
	private volatile @Nullable Component status;
	private volatile Goal goal;
	private int generation;
	private boolean downloading;

	public RealController() {
		FabricLoader loader = FabricLoader.getInstance();
		this.configDir = loader.getConfigDir();
		this.modsDir = loader.getGameDir().resolve("mods");
		this.pendingFile = PendingActions.defaultPath(configDir);
		this.rulesCache = configDir.resolve("rigtune").resolve("rules-cache.json");
		this.modVersion = loader.getModContainer(RigTune.MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0.0.0");
		this.modrinth = new HttpModrinthClient(modVersion);
		this.state = ClientState.load(configDir);
		this.goal = state.goalOrDefault();
		this.carriedOverOps = pendingOpCount();
	}

	public void start(Minecraft minecraft) {
		this.minecraft = minecraft;
		CompletableFuture.runAsync(this::loadRules, Probes.EXECUTOR);
		rescan();
	}

	private void loadRules() {
		List<RulesLoader.Candidate> candidates = new ArrayList<>();
		try {
			candidates.add(new RulesLoader.Candidate(RulesLoader.SOURCE_BUNDLED, RulesLoader.loadBundled()));
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Bundled rules are unusable", e);
		}
		RulesLoader.loadCache(rulesCache).ifPresent(doc -> candidates.add(new RulesLoader.Candidate(RulesLoader.SOURCE_CACHE, doc)));
		rules = RulesLoader.pickNewest(candidates).orElse(null);
		rebuild();
		new RemoteRulesFetcher(modVersion, rulesCache).fetch().ifPresent(remote -> {
			candidates.add(new RulesLoader.Candidate(RulesLoader.SOURCE_REMOTE, remote));
			RulesDocument best = RulesLoader.pickNewest(candidates).orElse(null);
			if (best != null && best != rules) {
				rules = best;
				rebuild();
				fetchOnline();
			}
		});
	}

	@Override
	public @Nullable Report report() {
		return report;
	}

	@Override
	public Goal goal() {
		return goal;
	}

	@Override
	public void setGoal(Goal goal) {
		this.goal = goal;
		state.goal = goal.name();
		CompletableFuture.runAsync(() -> state.save(configDir), Probes.EXECUTOR);
		rebuild();
	}

	@Override
	public void rescan() {
		report = null;
		CompletableFuture<HardwareProfile> probe;
		try {
			probe = HardwareProbe.probe(minecraft);
		} catch (RuntimeException e) {
			probe = CompletableFuture.failedFuture(e);
		}
		probe.thenCombine(ModScanner.scanAsync(), (hw, scanned) -> {
			hardware = hw;
			mods = scanned;
			return scanned;
		}).whenComplete((ignored, error) -> {
			if (error != null) {
				RigTune.LOGGER.error("RigTune scan failed", error);
				status = Component.translatable("rigtune.status.scan_failed");
				return;
			}
			RigTuneClient.setHardware(hardware);
			rebuild();
			fetchOnline();
		});
	}

	private void fetchOnline() {
		List<InstalledMod> scanned = mods;
		RulesDocument doc = rules;
		HardwareProfile hw = hardware;
		if (scanned == null || doc == null || hw == null) {
			return;
		}
		List<String> slugs = doc.mods.stream().map(m -> m.slug).filter(Objects::nonNull).distinct().toList();
		CompletableFuture.supplyAsync(() -> new OnlineDataFetcher(modrinth).fetchAll(scanned, slugs, hw.mcVersion()), Probes.EXECUTOR)
				.thenAccept(result -> {
					online = result;
					rebuild();
				});
	}

	private void rebuild() {
		minecraft.execute(() -> {
			RulesDocument doc = rules;
			HardwareProfile hw = hardware;
			List<InstalledMod> scanned = mods;
			if (doc == null || hw == null || scanned == null) {
				return;
			}
			SettingsSnapshot settings = SettingsBridge.read(minecraft);
			Goal g = goal;
			var data = online.data();
			int gen = ++generation;
			CompletableFuture.supplyAsync(() -> Recommender.recommend(doc, hw, scanned, settings, data, g, modVersion), Probes.EXECUTOR)
					.whenComplete((built, error) -> minecraft.execute(() -> {
						if (error != null) {
							RigTune.LOGGER.error("Could not build the RigTune report", error);
							status = Component.translatable("rigtune.status.scan_failed");
						} else if (gen == generation) {
							report = withoutStaged(built);
						}
					}));
		});
	}

	private Report withoutStaged(Report built) {
		if (staged.isEmpty()) {
			return built;
		}
		List<Recommendation> kept = built.recommendations().stream().filter(r -> !staged.contains(r.id())).toList();
		return new Report(built.hardware(), built.gpuClass(), built.tier(), built.goal(), kept, built.rulesRevision(),
				built.rulesSource(), built.online(), built.createdAt());
	}

	@Override
	public Component apply(List<Recommendation> selected) {
		if (downloading) {
			return Component.translatable("rigtune.status.busy");
		}
		Map<String, String> vanilla = new LinkedHashMap<>();
		Map<String, String> sodium = new LinkedHashMap<>();
		List<String> sodiumIds = new ArrayList<>();
		List<Op> immediateOps = new ArrayList<>();
		List<String> immediateIds = new ArrayList<>();
		List<Recommendation> downloads = new ArrayList<>();
		for (Recommendation r : selected) {
			switch (r.action()) {
				case Action.SetSetting set when set.key().startsWith(VANILLA) -> vanilla.put(set.key(), set.newValue());
				case Action.SetSetting set when set.key().startsWith(SODIUM) -> {
					sodium.put(set.key().substring(SODIUM.length()), set.newValue());
					sodiumIds.add(r.id());
				}
				case Action.DisableMod disable when disable.file() != null -> {
					immediateOps.add(Op.disableFile(disable.file()));
					immediateIds.add(r.id());
				}
				case Action.AddMod ignored -> downloads.add(r);
				case Action.UpdateMod ignored -> downloads.add(r);
				default -> RigTune.LOGGER.warn("Nothing to apply for {}", r.id());
			}
		}

		int settingsOk = 0;
		int settingsFailed = 0;
		if (!vanilla.isEmpty()) {
			for (SettingsBridge.Result result : SettingsBridge.applyVanilla(vanilla).values()) {
				if (result.ok()) {
					settingsOk++;
				} else {
					settingsFailed++;
					RigTune.LOGGER.warn("Could not apply {}: {}", result.key(), result.message());
				}
			}
		}
		if (!sodium.isEmpty()) {
			immediateOps.addFirst(Op.patchJson(SettingsBridge.sodiumConfig(), sodium));
			immediateIds.addAll(sodiumIds);
		}
		boolean stageFailed = !immediateOps.isEmpty() && !stage(immediateOps, immediateIds);
		if (!downloads.isEmpty()) {
			startDownloads(downloads);
		}
		rebuild();

		List<Component> parts = new ArrayList<>();
		if (settingsOk > 0) {
			parts.add(Component.translatable("rigtune.status.settings_applied", settingsOk));
		}
		if (settingsFailed > 0 || stageFailed) {
			parts.add(Component.translatable("rigtune.status.some_failed", settingsFailed + (stageFailed ? immediateIds.size() : 0)));
		}
		if (!downloads.isEmpty()) {
			parts.add(Component.translatable("rigtune.status.downloading", downloads.size()));
		} else if (pendingChanges() > 0) {
			parts.add(Component.translatable("rigtune.status.restart", pendingChanges()));
		}
		return join(parts);
	}

	private static Component join(List<Component> parts) {
		if (parts.isEmpty()) {
			return Component.translatable("rigtune.status.nothing");
		}
		var out = Component.empty();
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0) {
				out.append(" ");
			}
			out.append(parts.get(i));
		}
		return out;
	}

	private int pendingChanges() {
		return staged.size() + carriedOverOps;
	}

	private void startDownloads(List<Recommendation> downloads) {
		downloading = true;
		Set<String> installedProjects = new HashSet<>(online.projectIdsByModId().values());
		HardwareProfile hw = hardware;
		String mcVersion = hw == null ? "26.2" : hw.mcVersion();
		CompletableFuture.supplyAsync(() -> download(downloads, installedProjects, mcVersion), Probes.EXECUTOR)
				.whenComplete((result, error) -> minecraft.execute(() -> {
					downloading = false;
					if (error != null) {
						RigTune.LOGGER.error("RigTune downloads failed", error);
						status = Component.translatable("rigtune.status.download_failed", error.getMessage());
						return;
					}
					boolean ok = result.ops().isEmpty() || stage(result.ops(), result.ids());
					List<Component> parts = new ArrayList<>();
					if (!result.errors().isEmpty()) {
						parts.add(Component.translatable("rigtune.status.download_failed", String.join("; ", result.errors())));
					}
					if (!ok) {
						parts.add(Component.translatable("rigtune.status.some_failed", result.ids().size()));
					}
					if (pendingChanges() > 0) {
						parts.add(Component.translatable("rigtune.status.restart", pendingChanges()));
					}
					status = join(parts);
					rebuild();
				}));
	}

	private record DownloadResult(List<Op> ops, List<String> ids, List<String> errors) {
	}

	private DownloadResult download(List<Recommendation> recs, Set<String> installedProjects, String mcVersion) {
		DependencyResolver resolver = new DependencyResolver(modrinth, OnlineDataFetcher.LOADER, mcVersion);
		List<InstalledMod> scanned = mods;
		Set<String> loadedIds = new HashSet<>();
		if (scanned != null) {
			scanned.forEach(m -> loadedIds.add(m.modId()));
		}
		List<Op> ops = new ArrayList<>();
		List<String> ids = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		for (Recommendation rec : recs) {
			try {
				List<Op> recOps = new ArrayList<>();
				switch (rec.action()) {
					case Action.AddMod add -> {
						String ref = add.projectId() != null ? add.projectId() : add.slug();
						for (ModrinthVersion version : resolver.resolve(ref, installedProjects)) {
							ModFile file = version.primaryFile();
							if (file == null) {
								throw new IOException("No file for " + version.versionNumber());
							}
							Path target = modsDir.resolve(file.filename());
							installedProjects.add(version.projectId());
							if (Files.exists(target)) {
								continue;
							}
							Path pending = fetch(file);
							// A second jar with an already-loaded mod id would stop Fabric from starting, so drop it.
							String jarModId = modIdOf(pending);
							if (jarModId != null && !loadedIds.add(jarModId)) {
								RigTune.LOGGER.info("Skipping {}: mod {} is already present", file.filename(), jarModId);
								Files.deleteIfExists(pending);
								continue;
							}
							recOps.add(Op.enableFile(pending, target));
						}
					}
					case Action.UpdateMod update -> {
						ModFile file = update.update().file();
						if (file == null) {
							throw new IOException("No file for " + update.update().newVersionNumber());
						}
						Path pending = fetch(file);
						recOps.add(Op.disableFile(update.currentFile()));
						recOps.add(Op.enableFile(pending, modsDir.resolve(file.filename())));
					}
					default -> {
					}
				}
				ops.addAll(recOps);
				ids.add(rec.id());
			} catch (IOException | RuntimeException e) {
				RigTune.LOGGER.warn("Could not prepare {}", rec.id(), e);
				errors.add(rec.title() + ": " + e.getMessage());
			}
		}
		return new DownloadResult(ops, ids, errors);
	}

	static @Nullable String modIdOf(Path jar) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");
			if (entry == null) {
				return null;
			}
			try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
				JsonElement root = JsonParser.parseReader(reader);
				return root.isJsonObject() && root.getAsJsonObject().has("id") ? root.getAsJsonObject().get("id").getAsString() : null;
			}
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("Could not read the mod id of {}", jar, e);
			return null;
		}
	}

	private Path fetch(ModFile file) throws IOException {
		Path pending = modsDir.resolve(file.filename() + PendingActions.PENDING_SUFFIX);
		modrinth.download(file, pending);
		return pending;
	}

	private boolean stage(List<Op> ops, List<String> ids) {
		try {
			PendingActions plan = null;
			if (Files.exists(pendingFile)) {
				try {
					plan = PendingActions.load(pendingFile);
				} catch (IOException e) {
					RigTune.LOGGER.warn("Replacing unreadable {}", pendingFile, e);
				}
			}
			List<Op> merged = new ArrayList<>(plan == null ? List.of() : plan.ops());
			for (Op op : ops) {
				if (!merged.contains(op)) {
					merged.add(op);
				}
			}
			PendingActions out = plan == null
					? PendingActions.create(ProcessHandle.current().pid(), modsDir, configDir, merged)
					: plan.withOps(merged);
			out.save(pendingFile);
			staged.addAll(ids);
			return true;
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.error("Could not write {}", pendingFile, e);
			return false;
		}
	}

	private int pendingOpCount() {
		if (!Files.exists(pendingFile)) {
			return 0;
		}
		try {
			return PendingActions.load(pendingFile).ops().size();
		} catch (IOException e) {
			return 0;
		}
	}

	@Override
	public void startBenchmark() {
		if (!BenchmarkController.start(minecraft, BenchmarkController.Config.DEFAULT)) {
			status = Component.translatable("rigtune.status.benchmark_unavailable");
		}
	}

	@Override
	public @Nullable Component status() {
		return status;
	}
}
