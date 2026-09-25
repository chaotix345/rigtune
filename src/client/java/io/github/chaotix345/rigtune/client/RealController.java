package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.probe.HardwareProbe;
import io.github.chaotix345.rigtune.client.probe.ModScanner;
import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.core.apply.ApplyLock;
import io.github.chaotix345.rigtune.core.apply.HelperLauncher;
import io.github.chaotix345.rigtune.core.apply.InstanceDirs;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.SafeFileNames;
import io.github.chaotix345.rigtune.core.apply.SodiumConfigPatcher;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.modrinth.DependencyResolver;
import io.github.chaotix345.rigtune.core.modrinth.DownloadPlanner;
import io.github.chaotix345.rigtune.core.modrinth.HttpModrinthClient;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthClient;
import io.github.chaotix345.rigtune.core.modrinth.OnlineDataFetcher;
import io.github.chaotix345.rigtune.core.recommend.Recommender;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesSources;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RealController implements RigTuneController {
	private static final String VANILLA = SettingsBridge.VANILLA_PREFIX;
	private static final Duration STAGE_LOCK_WAIT = Duration.ofSeconds(2);
	// Rules loads can wait up to a minute on the network; one thread keeps them off the report builders' pool and
	// runs them in order, so a superseded load gives up before its next request.
	private static final ExecutorService RULES_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "RigTune rules");
		thread.setDaemon(true);
		return thread;
	});

	private Minecraft minecraft;
	private final Path configDir;
	private final Path modsDir;
	private final Path pendingFile;
	private final String modVersion;
	private final ModrinthClient modrinth;
	private final ClientState state;
	private final Set<String> staged = new HashSet<>();
	private int carriedOverOps;
	private final boolean selfFileActions = HelperLauncher.selfUpdateSupported();

	private volatile @Nullable RulesDocument rules;
	private volatile @Nullable HardwareProfile hardware;
	private volatile @Nullable List<InstalledMod> mods;
	private volatile OnlineDataFetcher.Result online = OnlineDataFetcher.Result.offline();
	private volatile @Nullable Report report;
	private volatile @Nullable Component status;
	private volatile Goal goal;
	private int generation;
	private final Object rulesLock = new Object();
	private final OnlineLookupGate onlineLookups = new OnlineLookupGate();
	private int rulesGeneration;
	private volatile boolean downloading;

	public RealController() {
		FabricLoader loader = FabricLoader.getInstance();
		this.configDir = loader.getConfigDir();
		this.modsDir = InstanceDirs.modsDir(loader.getGameDir());
		this.pendingFile = PendingActions.defaultPath(configDir);
		this.modVersion = loader.getModContainer(RigTune.MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0.0.0");
		this.modrinth = new HttpModrinthClient(modVersion);
		this.state = ClientState.shared(configDir);
		this.goal = state.goalOrDefault();
		this.carriedOverOps = pendingOpCount();
	}

	public void start(Minecraft minecraft) {
		this.minecraft = minecraft;
		reloadRules();
		rescan();
	}

	// Re-runnable: a newer load (settingsChanged) makes an older one stale, whether it is still queued or fetching.
	private void reloadRules() {
		int gen;
		synchronized (rulesLock) {
			gen = ++rulesGeneration;
		}
		CompletableFuture.runAsync(() -> loadRules(gen), RULES_EXECUTOR);
	}

	private void loadRules(int gen) {
		if (!currentRules(gen)) {
			return;
		}
		URI baseUrl = RulesSources.baseUrl(System.getProperty(RulesSources.BASE_URL_PROPERTY));
		new RulesSources(configDir, baseUrl, modVersion).load(() -> currentRules(gen) && ClientSettings.shared(configDir).remoteRulesAllowed(), (doc, remote) -> {
			synchronized (rulesLock) {
				if (gen != rulesGeneration) {
					return;
				}
				rules = doc;
			}
			rebuild();
			// Also for the local rules: if the scan finished first, its own fetchOnline() found no rules yet (the offline
			// race in docs/v0.2/design/ws-g.md). The gate makes the lookup happen once, whichever comes last.
			fetchOnline();
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

	private boolean currentRules(int gen) {
		synchronized (rulesLock) {
			return gen == rulesGeneration;
		}
	}

	@Override
	public void settingsChanged() {
		reloadRules();
		rescan();
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
		OnlineLookupGate.Lookup lookup = onlineLookups.next(mods, rules, hardware);
		if (lookup == null) {
			return;
		}
		CompletableFuture.supplyAsync(() -> new OnlineDataFetcher(modrinth).fetchAll(lookup.mods(), lookup.slugs(), lookup.hardware().mcVersion()),
						Probes.EXECUTOR)
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
		List<Recommendation> kept = built.recommendations().stream()
				.filter(r -> !staged.contains(r.id()))
				.filter(r -> selfFileActions || !touchesRigTune(r))
				.toList();
		if (kept.size() == built.recommendations().size()) {
			return built;
		}
		return new Report(built.hardware(), built.gpuClass(), built.tier(), built.goal(), kept, built.rulesRevision(),
				built.rulesSource(), built.online(), built.createdAt());
	}

	// Renaming RigTune's own jar is only safe when the helper runs from copies (see HelperLauncher.launch).
	private static boolean touchesRigTune(Recommendation r) {
		return switch (r.action()) {
			case Action.UpdateMod update -> RigTune.MOD_ID.equals(update.modId());
			case Action.DisableMod disable -> RigTune.MOD_ID.equals(disable.modId());
			default -> false;
		};
	}

	@Override
	public Component apply(List<Recommendation> selected) {
		if (downloading) {
			return Component.translatable("rigtune.status.busy");
		}
		Map<String, String> vanilla = new LinkedHashMap<>();
		List<ConfigTargets.Target> targets = ConfigTargets.all(configDir);
		Map<ConfigTargets.Target, Map<String, String>> configPatches = new LinkedHashMap<>();
		Map<String, String> configIds = new HashMap<>();
		List<Op> immediateOps = new ArrayList<>();
		List<String> immediateIds = new ArrayList<>();
		List<Recommendation> downloads = new ArrayList<>();
		for (Recommendation r : selected) {
			switch (r.action()) {
				case Action.SetSetting set when set.key().startsWith(VANILLA) -> vanilla.put(set.key(), set.newValue());
				case Action.SetSetting set when ConfigTargets.forKey(targets, set.key()) != null -> {
					ConfigTargets.Target target = ConfigTargets.forKey(targets, set.key());
					configPatches.computeIfAbsent(target, t -> new LinkedHashMap<>()).put(set.key().substring(target.prefix().length()), set.newValue());
					configIds.put(set.key(), r.id());
				}
				case Action.DisableMod disable when SafeFileNames.isDirectChild(modsDir, disable.file()) -> {
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
		for (Map.Entry<ConfigTargets.Target, Map<String, String>> entry : configPatches.entrySet()) {
			ConfigTargets.Target target = entry.getKey();
			SodiumConfigPatcher.Staged patches = target.stager().stage(target.file(), entry.getValue());
			patches.refused().forEach((key, problem) -> RigTune.LOGGER.warn("Not staging setting {}{}: {}", target.prefix(), key, problem));
			settingsFailed += patches.refused().size();
			immediateOps.addAll(0, patches.ops());
			patches.ops().forEach(op -> immediateIds.add(configIds.get(target.prefix() + op.patches().keySet().iterator().next())));
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
		try {
			Set<String> installedProjects = new HashSet<>(online.projectIdsByModId().values());
			HardwareProfile hw = hardware;
			String mcVersion = hw == null ? HardwareProbe.minecraftVersion() : hw.mcVersion();
			CompletableFuture.supplyAsync(() -> download(downloads, installedProjects, mcVersion), Probes.EXECUTOR)
					.whenComplete((result, error) -> {
						try {
							minecraft.execute(() -> {
								try {
									finishDownloads(result, error);
								} finally {
									downloading = false;
								}
							});
						} catch (RuntimeException e) {
							downloading = false;
							RigTune.LOGGER.error("Could not hand the RigTune downloads back to the game", e);
						}
					});
		} catch (RuntimeException e) {
			downloading = false;
			throw e;
		}
	}

	private void finishDownloads(DownloadPlanner.@Nullable Result result, @Nullable Throwable error) {
		if (error != null || result == null) {
			RigTune.LOGGER.error("RigTune downloads failed", error);
			status = Component.translatable("rigtune.status.download_failed", error == null ? "?" : error.getMessage());
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
	}

	private DownloadPlanner.Result download(List<Recommendation> recs, Set<String> installedProjects, String mcVersion) {
		DependencyResolver resolver = new DependencyResolver(modrinth, OnlineDataFetcher.LOADER, mcVersion);
		List<InstalledMod> scanned = mods;
		Set<String> loadedIds = new HashSet<>();
		if (scanned != null) {
			scanned.forEach(m -> loadedIds.add(m.modId()));
		}
		return new DownloadPlanner(resolver, modsDir, this::fetch).plan(recs, installedProjects, loadedIds, stagedJarsByModId());
	}

	// Mod ids that already have a staged ENABLE_FILE, with that op's pending jar. A newer download for the same id
	// replaces the staged one when it is merged (see PendingActions.merge).
	private Map<String, String> stagedJarsByModId() {
		Map<String, String> out = new HashMap<>();
		if (Files.exists(pendingFile)) {
			try {
				for (Op op : PendingActions.load(pendingFile).ops()) {
					if (op.type() == PendingActions.Type.ENABLE_FILE && op.modId() != null && op.from() != null) {
						out.put(op.modId(), op.from());
					}
				}
			} catch (IOException e) {
				RigTune.LOGGER.warn("Could not read {}", pendingFile, e);
			}
		}
		return out;
	}

	private Path fetch(ModFile file) throws IOException {
		Path pending = SafeFileNames.resolveJar(modsDir, file.filename(), PendingActions.PENDING_SUFFIX);
		modrinth.download(file, pending);
		return pending;
	}

	private boolean stage(List<Op> ops, List<String> ids) {
		try (ApplyLock lock = ApplyLock.acquire(ApplyLock.defaultPath(configDir), STAGE_LOCK_WAIT)) {
			if (lock == null) {
				RigTune.LOGGER.error("Could not stage RigTune changes: the apply helper still holds {}", ApplyLock.defaultPath(configDir));
				return false;
			}
			PendingActions plan = null;
			if (Files.exists(pendingFile)) {
				try {
					plan = PendingActions.load(pendingFile);
				} catch (IOException e) {
					RigTune.LOGGER.warn("Replacing unreadable {}", pendingFile, e);
				}
			}
			// The folders come from where pending.json is, not from what an existing plan records (the instance may be a copy).
			Path planMods = InstanceDirs.modsDirOf(pendingFile);
			Path planConfig = InstanceDirs.configDirOf(pendingFile);
			PendingActions base = plan != null ? plan.relocated(planMods, planConfig)
					: PendingActions.create(ProcessHandle.current().pid(), planMods, planConfig, List.of());
			if (plan != null && base.ops().size() < plan.ops().size()) {
				RigTune.LOGGER.warn("Dropped {} staged change(s) for another instance's folders ({})", plan.ops().size() - base.ops().size(), plan.modsDir());
			}
			PendingActions.Merged merged = base.merge(ops);
			merged.plan().save(pendingFile);
			for (Path old : merged.superseded()) {
				if (!SafeFileNames.isDirectChild(planMods, old)) {
					continue;
				}
				try {
					Path retired = PendingActions.retire(old);
					if (retired != null) {
						RigTune.LOGGER.info("Replaced staged {}; kept it as {}", old.getFileName(), retired.getFileName());
					}
				} catch (IOException e) {
					RigTune.LOGGER.warn("Could not retire replaced {}; it stays inert", old, e);
				}
			}
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

	@Override
	public boolean hasPendingChanges() {
		return Files.isRegularFile(pendingFile);
	}

	@Override
	public Component discardPending() {
		if (downloading) {
			return Component.translatable("rigtune.status.busy");
		}
		try {
			int dropped = PendingActions.discard(pendingFile, STAGE_LOCK_WAIT);
			if (dropped < 0) {
				return Component.translatable("rigtune.status.discard_busy");
			}
			staged.clear();
			carriedOverOps = 0;
			rebuild();
			return Component.translatable("rigtune.status.discarded", dropped);
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.error("Could not discard {}", pendingFile, e);
			return Component.translatable("rigtune.status.discard_failed");
		}
	}
}
