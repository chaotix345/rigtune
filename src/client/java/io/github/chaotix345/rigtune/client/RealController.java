package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.awareness.AwarenessService;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkStore;
import io.github.chaotix345.rigtune.client.benchmark.TrendService;
import io.github.chaotix345.rigtune.client.footprint.StartupTimes;
import io.github.chaotix345.rigtune.client.jvm.JvmService;
import io.github.chaotix345.rigtune.client.notice.BatteryNoticeSource;
import io.github.chaotix345.rigtune.client.notice.BenchmarkStaleNoticeSource;
import io.github.chaotix345.rigtune.client.notice.HardwareChangeNoticeSource;
import io.github.chaotix345.rigtune.client.notice.NoticeCenter;
import io.github.chaotix345.rigtune.client.notice.RegressionNoticeSource;
import io.github.chaotix345.rigtune.client.notice.ServerLimitNoticeSource;
import io.github.chaotix345.rigtune.client.notice.WhatsNewNoticeSource;
import io.github.chaotix345.rigtune.client.probe.HardwareProbe;
import io.github.chaotix345.rigtune.client.probe.LauncherProbe;
import io.github.chaotix345.rigtune.client.probe.ModScanner;
import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.profile.ProfileService;
import io.github.chaotix345.rigtune.client.server.ServerLimitsTracker;
import io.github.chaotix345.rigtune.client.stutter.StutterService;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.Texts;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.client.undo.GameState;
import io.github.chaotix345.rigtune.client.undo.Staging;
import io.github.chaotix345.rigtune.client.undo.UndoService;
import io.github.chaotix345.rigtune.client.undo.VanillaChanges;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.HelperLauncher;
import io.github.chaotix345.rigtune.core.apply.InstanceDirs;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.SafeFileNames;
import io.github.chaotix345.rigtune.core.apply.SodiumConfigPatcher;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecords;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend;
import io.github.chaotix345.rigtune.core.history.ChangeRecorder;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import io.github.chaotix345.rigtune.core.jvm.JvmReport;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.BenchmarkSummary;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.modrinth.DependencyResolver;
import io.github.chaotix345.rigtune.core.modrinth.DownloadPlanner;
import io.github.chaotix345.rigtune.core.modrinth.GatedModrinthClient;
import io.github.chaotix345.rigtune.core.modrinth.HttpModrinthClient;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthClient;
import io.github.chaotix345.rigtune.core.modrinth.OnlineDataFetcher;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import io.github.chaotix345.rigtune.core.preview.DownloadInputs;
import io.github.chaotix345.rigtune.core.preview.PreviewPlanner;
import io.github.chaotix345.rigtune.core.profile.ProfileImport;
import io.github.chaotix345.rigtune.core.profile.ProfileView;
import io.github.chaotix345.rigtune.core.recommend.ModConflicts;
import io.github.chaotix345.rigtune.core.recommend.Recommender;
import io.github.chaotix345.rigtune.core.report.ModrinthOffAdvice;
import io.github.chaotix345.rigtune.core.report.ShareReport;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesSources;
import io.github.chaotix345.rigtune.core.stutter.StutterView;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.function.BiPredicate;

public final class RealController implements RigTuneController {
	private static final String VANILLA = SettingsBridge.VANILLA_PREFIX;
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
	private final ClientSettings settings;
	private final ClientState state;
	private final StagedRecommendations staged = new StagedRecommendations();
	private int carriedOverOps;
	private final boolean selfFileActions = HelperLauncher.selfUpdateSupported();
	private final Staging staging;
	private final UndoService undoService;
	// v0.4 (docs/v0.4/SPEC.md C4): one service per feature, each its workstream's own file.
	private final ProfileService profileService;
	private final StutterService stutterService;
	private final JvmService jvmService;
	private final TrendService trendService;
	private final ServerLimitsTracker serverLimitsTracker;
	private final AwarenessService awarenessService;
	private final StartupTimes startupTimes;
	private final NoticeCenter noticeCenter;

	private volatile @Nullable RulesDocument rules;
	private volatile @Nullable HardwareProfile hardware;
	private volatile @Nullable List<InstalledMod> mods;
	private volatile OnlineDataFetcher.Result online = OnlineDataFetcher.Result.offline();
	private volatile @Nullable Report report;
	private volatile @Nullable Component status;
	private volatile Goal goal;
	private int generation;
	private final Object rulesLock = new Object();
	private final OnlineLookupGate onlineLookups = new OnlineLookupGate(() -> FabricLoader.getInstance().getRawGameVersion());
	private int rulesGeneration;
	private volatile boolean downloading;
	private volatile @Nullable LauncherInfo launcher;

	public RealController() {
		FabricLoader loader = FabricLoader.getInstance();
		this.configDir = loader.getConfigDir();
		this.modsDir = InstanceDirs.modsDir(loader.getGameDir());
		this.pendingFile = PendingActions.defaultPath(configDir);
		this.modVersion = loader.getModContainer(RigTune.MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0.0.0");
		this.settings = ClientSettings.shared(configDir);
		this.modrinth = new GatedModrinthClient(new HttpModrinthClient(modVersion), settings::modrinthAllowed);
		this.state = ClientState.shared(configDir);
		this.goal = state.goalOrDefault();
		this.carriedOverOps = Math.max(0, staged.recount(pendingFile));
		this.staging = new Staging(configDir, pendingFile, ConfigTargets.all(configDir), ClientJournal.get());
		// The Undo screen plans off the render thread; the options are still read on it.
		this.undoService = new UndoService(staging, ClientJournal.get(),
				() -> minecraft.isSameThread() ? new GameState(minecraft.options, staging.targets(), modsDir, settingLabels())
						: minecraft.submit(() -> new GameState(minecraft.options, staging.targets(), modsDir, settingLabels())).join(),
				values -> {
					Map<String, Boolean> written = new LinkedHashMap<>();
					SettingsBridge.applyVanilla(minecraft.options, values).forEach((key, result) -> written.put(key, result.ok()));
					return written;
				});
		// v0.4 (C4): the services only keep references here; none of them does work in its constructor.
		this.profileService = new ProfileService(this, configDir);
		this.stutterService = new StutterService(this, configDir);
		this.jvmService = new JvmService(this, configDir);
		this.trendService = new TrendService(this, configDir);
		this.serverLimitsTracker = new ServerLimitsTracker(this, configDir);
		this.awarenessService = new AwarenessService(this, configDir);
		this.startupTimes = new StartupTimes(this, configDir);
		// C3: one source per notice priority, in NoticePriority order; each reaches its service through this controller.
		this.noticeCenter = new NoticeCenter(List.of(new BatteryNoticeSource(this), new ServerLimitNoticeSource(this),
				new RegressionNoticeSource(this), new HardwareChangeNoticeSource(this), new WhatsNewNoticeSource(this),
				new BenchmarkStaleNoticeSource(this)), awarenessService);
	}

	private Map<String, RulesDocument.SettingLabel> settingLabels() {
		RulesDocument doc = rules;
		return doc == null ? Map.of() : doc.settingLabels;
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
		// A report still being built from before the rescan (say, before a settings change) must not be published.
		minecraft.execute(() -> generation++);
		CompletableFuture<HardwareProfile> probe;
		try {
			probe = HardwareProbe.probe(minecraft);
		} catch (RuntimeException e) {
			probe = CompletableFuture.failedFuture(e);
		}
		// Before the report is built, so the screen shows the report and its launcher together; never fails.
		CompletableFuture<LauncherInfo> launcherProbe = LauncherProbe.probeAsync(FabricLoader.getInstance().getGameDir());
		probe.thenCombine(ModScanner.scanAsync(), (hw, scanned) -> {
			hardware = hw;
			mods = scanned;
			return scanned;
		}).thenCombine(launcherProbe, (scanned, detected) -> {
			launcherDetected(detected);
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

	// Only the launcher's name is logged: no instance name, path or property value.
	private void launcherDetected(LauncherInfo detected) {
		if (!detected.equals(launcher)) {
			RigTune.LOGGER.info("RigTune: launcher {}", detected.known() ? detected.launcher().displayName() : "not recognised (generic memory advice)");
		}
		launcher = detected;
	}

	@Override
	public LauncherInfo launcher() {
		LauncherInfo detected = launcher;
		return detected == null ? LauncherInfo.UNKNOWN : detected;
	}

	private void fetchOnline() {
		// Before the lookup gate, so the lookup isn't used up while Modrinth is off; turning it back on goes through
		// settingsChanged(), whose rescan makes a lookup due again. rebuild() already ignores online data while off.
		if (!settings.modrinthAllowed()) {
			online = OnlineDataFetcher.Result.offline();
			return;
		}
		OnlineLookupGate.Lookup lookup = onlineLookups.next(mods, rules, hardware);
		if (lookup == null) {
			return;
		}
		CompletableFuture.supplyAsync(() -> new OnlineDataFetcher(modrinth).fetchAll(lookup.mods(), lookup.slugs(), lookup.gameVersion()),
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
			var data = this.settings.modrinthAllowed() ? online.data() : OnlineData.offline();
			int gen = ++generation;
			CompletableFuture.supplyAsync(() -> {
						Set<String> queued = ModScanner.queuedUpdates();
						Set<String> loaded = ModScanner.loadedIds();
						List<Op> dropped = dropQueuedUpdates(queued, loaded);
						return new Rebuilt(Recommender.recommend(doc, hw, scanned, settings, data, g, modVersion, queued), dropped, queued, loaded);
					}, Probes.EXECUTOR)
					.whenComplete((rebuilt, error) -> minecraft.execute(() -> {
						if (error != null) {
							RigTune.LOGGER.error("Could not build the RigTune report", error);
							status = Component.translatable("rigtune.status.scan_failed");
							return;
						}
						if (!rebuilt.dropped().isEmpty()) {
							droppedQueuedUpdates(rebuilt.dropped(), rebuilt.queued(), rebuilt.loaded(), scanned);
						}
						if (gen == generation) {
							report = this.settings.modrinthAllowed() ? withoutStaged(rebuilt.report())
									: ModrinthOffAdvice.apply(withoutStaged(rebuilt.report()), !this.settings.networkEnabled);
						}
					}));
		});
	}

	private record Rebuilt(Report report, List<Op> dropped, Set<String> queued, Set<String> loaded) {
	}

	// Also at exit: an updater can have queued its build since the last rebuild.
	public void unstageQueuedUpdates() {
		dropQueuedUpdates(ModScanner.queuedUpdates(), ModScanner.loadedIds());
	}

	// A staged update (or undo re-enable) of a loaded mod whose own updater has a build waiting in mods/update/ would race
	// it at exit, so it is unstaged (re-check of review 4, SPEC 3a). A busy lock leaves it for the next rebuild.
	private List<Op> dropQueuedUpdates(Set<String> queued, Set<String> loaded) {
		try {
			List<Op> dropped = staging.dropQueuedUpdates(queued, loaded);
			if (dropped == null || dropped.isEmpty()) {
				return List.of();
			}
			RigTune.LOGGER.info("Unstaged {} RigTune change(s) for {}: an update of its own is waiting in mods/update", dropped.size(), queued);
			return dropped;
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("Could not unstage the RigTune updates of {}", queued, e);
			return List.of();
		}
	}

	private void droppedQueuedUpdates(List<Op> dropped, Set<String> queued, Set<String> loaded, List<InstalledMod> scanned) {
		recountStaged();
		status = Component.translatable("rigtune.status.queued_update_dropped",
				String.join(", ", StagedRecommendations.droppedModNames(dropped, queued, loaded, scanned)));
	}

	// After a drop, an undo or a discard: a recommendation stays staged only while its ops are still in pending.json,
	// and the staged ops none of them owns were carried over from another session (plan review A-M1). An unreadable
	// pending.json changes nothing.
	private void recountStaged() {
		int carried = staged.recount(pendingFile);
		if (carried >= 0) {
			carriedOverOps = carried;
		}
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
				built.rulesSource(), built.online(), built.createdAt(), built.tierBasis());
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
		return apply(selected, ChangeRecorder.newEntryId());
	}

	// v0.4 (docs/v0.4/SPEC.md 4, C4): the same Apply, journaled under a given entry id (a profile switch labels it in
	// profiles.json). One journal entry per Apply, downloads that finish later included (review H4).
	public Component apply(List<Recommendation> selected, String entryId) {
		if (downloading) {
			return Component.translatable("rigtune.status.busy");
		}
		Map<String, String> vanilla = new LinkedHashMap<>();
		List<ConfigTargets.Target> targets = ConfigTargets.all(configDir);
		Map<ConfigTargets.Target, Map<String, String>> configPatches = new LinkedHashMap<>();
		Map<String, String> configIds = new HashMap<>();
		List<Op> immediateOps = new ArrayList<>();
		Map<String, List<String>> immediateOpIds = new LinkedHashMap<>();
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
					Op op = Op.disableFile(disable.file());
					immediateOps.add(op);
					immediateOpIds.computeIfAbsent(r.id(), k -> new ArrayList<>()).add(op.id());
				}
				case Action.AddMod ignored -> downloads.add(r);
				case Action.UpdateMod ignored -> downloads.add(r);
				default -> RigTune.LOGGER.warn("Nothing to apply for {}", r.id());
			}
		}

		int settingsOk = 0;
		int settingsFailed = 0;
		if (!vanilla.isEmpty()) {
			for (SettingsBridge.Result result : VanillaChanges.apply(entryId, vanilla).values()) {
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
			for (Op op : patches.ops()) {
				for (String key : op.patches().keySet()) {
					String id = configIds.get(target.prefix() + key);
					if (id != null) {
						immediateOpIds.computeIfAbsent(id, k -> new ArrayList<>()).add(op.id());
					}
				}
			}
		}
		boolean stageFailed = !immediateOps.isEmpty() && !stage(immediateOps, immediateOpIds, entryId);
		if (!downloads.isEmpty()) {
			startDownloads(downloads, entryId);
		}
		rebuild();

		List<Component> parts = new ArrayList<>();
		if (settingsOk > 0) {
			parts.add(Component.translatable("rigtune.status.settings_applied", settingsOk));
		}
		if (settingsFailed > 0 || stageFailed) {
			parts.add(Component.translatable("rigtune.status.some_failed", settingsFailed + (stageFailed ? immediateOps.size() : 0)));
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

	private void startDownloads(List<Recommendation> downloads, String entryId) {
		downloading = true;
		try {
			OnlineDataFetcher.Result data = online;
			HardwareProfile hw = hardware;
			String mcVersion = onlineLookups.modrinthGameVersion(hw == null ? HardwareProbe.minecraftVersion() : hw.mcVersion());
			CompletableFuture.supplyAsync(() -> download(downloads, data, mcVersion), Probes.EXECUTOR)
					.whenComplete((result, error) -> {
						try {
							minecraft.execute(() -> {
								try {
									finishDownloads(result, error, entryId);
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

	private void finishDownloads(DownloadPlanner.@Nullable Result result, @Nullable Throwable error, String entryId) {
		if (error != null || result == null) {
			RigTune.LOGGER.error("RigTune downloads failed", error);
			status = Component.translatable("rigtune.status.download_failed", error == null ? "?" : error.getMessage());
			return;
		}
		boolean ok = result.ops().isEmpty() || stage(result.ops(), result.opIds(), entryId);
		List<Component> parts = new ArrayList<>();
		if (!result.errors().isEmpty()) {
			parts.add(Component.translatable("rigtune.status.download_failed", Texts.component(Text.join("; ", result.errorTexts()))));
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

	// Judged against the installed mods' Modrinth versions and the updates' own versions (SPEC 3b, plan review A-H1).
	private DownloadPlanner.Result download(List<Recommendation> recs, OnlineDataFetcher.Result data, String mcVersion) {
		DependencyResolver resolver = new DependencyResolver(modrinth, OnlineDataFetcher.LOADER, mcVersion, data.installedVersions());
		Set<String> installedProjects = new HashSet<>(data.projectIdsByModId().values());
		List<InstalledMod> scanned = mods;
		Set<String> loadedIds = new HashSet<>();
		if (scanned != null) {
			scanned.forEach(m -> loadedIds.add(m.modId()));
		}
		RulesDocument doc = rules;
		BiPredicate<String, String> conflicts = doc == null ? (a, b) -> false : ModConflicts.of(doc)::between;
		return new DownloadPlanner(resolver, modsDir, this::fetch, conflicts, data.updateVersions()).plan(recs, installedProjects, loadedIds, stagedJarsByModId());
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

	// Staging and its journal records live in Staging (lock, merge, record after the merge: review H5). Each
	// recommendation is recorded with the ids its ops have in pending.json after the merge (plan review A-M1).
	private boolean stage(List<Op> ops, Map<String, List<String>> opIdsByRecommendation, String entryId) {
		Staging.Merge merge = staging.stage(ops, entryId);
		if (merge == null) {
			return false;
		}
		staged.add(ops, opIdsByRecommendation, merge.merged().survivingIds());
		return true;
	}

	@Override
	public void startBenchmark() {
		startBenchmark(BenchmarkRequest.DEFAULT);
	}

	@Override
	public void startBenchmark(BenchmarkRequest request) {
		String refusal = BenchmarkController.tryStart(minecraft, request, BenchmarkController.defaultConfig());
		if (refusal != null) {
			status = Component.translatable(refusal);
		}
	}

	@Override
	public @Nullable BenchmarkSummary latestBenchmark() {
		return BenchmarkStore.history().latest().map(BenchmarkRecords::summary).orElse(null);
	}

	@Override
	public @Nullable Component status() {
		return status;
	}

	@Override
	public String shareReport() {
		Report shown = report;
		if (shown == null) {
			return "";
		}
		String loaderVersion = FabricLoader.getInstance().getModContainer("fabricloader")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
		LauncherInfo detected = launcher();
		return ShareReport.format(shown, new ShareReport.Versions(modVersion, shown.hardware().mcVersion(), loaderVersion), latestBenchmark(),
				detected.known() ? detected.launcher().displayName() : null, jvmService.report());
	}

	@Override
	public ShareReport.@Nullable Versions reportVersions() {
		Report shown = report;
		if (shown == null) {
			return null;
		}
		String loaderVersion = FabricLoader.getInstance().getModContainer("fabricloader")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
		return new ShareReport.Versions(modVersion, shown.hardware().mcVersion(), loaderVersion);
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
			List<Op> dropped = staging.discard();
			if (dropped == null) {
				return Component.translatable("rigtune.status.discard_busy");
			}
			recountStaged();
			rebuild();
			return Component.translatable("rigtune.status.discarded", dropped.size());
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.error("Could not discard {}", pendingFile, e);
			return Component.translatable("rigtune.status.discard_failed");
		}
	}

	@Override
	public @Nullable UndoPlan undoPlan(boolean all) {
		// Downloads that finish later add to their apply's entry, so undo waits for them.
		if (downloading) {
			return UndoPlan.unavailable(all, "rigtune.undo.busy");
		}
		try {
			UndoPlan plan = undoService.plan(all);
			return plan != null ? plan : UndoPlan.unavailable(all, "rigtune.undo.unavailable");
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Could not work out what to undo", e);
			return UndoPlan.unavailable(all, "rigtune.undo.error");
		}
	}

	@Override
	public Component undo(UndoPlan plan) {
		if (downloading) {
			return Component.translatable("rigtune.status.busy");
		}
		try {
			UndoService.Outcome outcome = undoService.undo(plan);
			if (outcome.busy()) {
				return Component.translatable("rigtune.undo.status.busy");
			}
			recountStaged();
			rebuild();
			return Component.translatable("rigtune.undo.status.done", outcome.now(), outcome.afterRestart(), outcome.cancelled(), outcome.skipped());
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.error("Could not undo", e);
			return Component.translatable("rigtune.undo.status.failed");
		}
	}

	// v0.3 (WS-B): the History screen and "Undo this" (docs/v0.3/SPEC.md item 6, 3e).

	@Override
	public @Nullable UndoPlan undoPlanFor(String entryId) {
		if (downloading) {
			return UndoPlan.unavailable(false, "rigtune.undo.busy");
		}
		try {
			UndoPlan plan = undoService.planEntry(entryId);
			return plan != null ? plan : UndoPlan.unavailable(false, "rigtune.undo.unavailable");
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Could not work out what to undo", e);
			return UndoPlan.unavailable(false, "rigtune.undo.error");
		}
	}

	@Override
	public HistoryModel.@Nullable View history() {
		Path last = ApplyResult.defaultPath(configDir);
		ApplyResult lastApply = null;
		try {
			lastApply = Files.isRegularFile(last) ? ApplyResult.load(last) : null;
		} catch (IOException e) {
			RigTune.LOGGER.warn("Could not read {}", last, e);
		}
		try {
			return undoService.history(lastApply, List.of(modsDir, configDir));
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Could not read RigTune's history", e);
			return null;
		}
	}

	// v0.3 (WS-P): Preview (docs/v0.3/SPEC.md item 13), from apply()'s and download()'s own inputs.

	@Override
	public ApplyPreview preview(List<Recommendation> selected) {
		Map<String, String> vanilla = new LinkedHashMap<>();
		for (Recommendation r : selected) {
			if (r.action() instanceof Action.SetSetting set && set.key().startsWith(VANILLA)) {
				vanilla.put(set.key(), set.newValue());
			}
		}
		// The game's options are read on the render thread, and only when a vanilla setting is ticked.
		GameOptions game = vanilla.isEmpty() ? new GameOptions(Map.of(), Map.of())
				: minecraft.isSameThread() ? gameOptions(vanilla) : minecraft.submit(() -> gameOptions(vanilla)).join();
		OnlineDataFetcher.Result data = online;
		HardwareProfile hw = hardware;
		List<InstalledMod> scanned = mods;
		Set<String> loadedIds = new HashSet<>();
		if (scanned != null) {
			scanned.forEach(m -> loadedIds.add(m.modId()));
		}
		RulesDocument doc = rules;
		DownloadInputs downloads = new DownloadInputs(modrinth, settings.modrinthAllowed(), OnlineDataFetcher.LOADER,
				onlineLookups.modrinthGameVersion(hw == null ? HardwareProbe.minecraftVersion() : hw.mcVersion()), data.installedVersions(),
				data.updateVersions(), new HashSet<>(data.projectIdsByModId().values()), loadedIds, stagedJarsByModId(),
				doc == null ? (a, b) -> false : ModConflicts.of(doc)::between);
		List<PreviewPlanner.ConfigFile> files = ConfigTargets.all(configDir).stream()
				.map(t -> new PreviewPlanner.ConfigFile(t.prefix(), t.file(), t.stager()::stage, t.reader()::read)).toList();
		return new PreviewPlanner(FabricLoader.getInstance().getGameDir().resolve("options.txt"), game.now(), game.problems(), files, modsDir, downloads)
				.preview(selected);
	}

	private record GameOptions(Map<String, String> now, Map<String, String> problems) {
	}

	private GameOptions gameOptions(Map<String, String> vanilla) {
		return new GameOptions(SettingsBridge.readVanilla(minecraft.options), SettingsBridge.problems(minecraft.options, vanilla));
	}

	// v0.4 (docs/v0.4/SPEC.md C4): read access for the feature services and notice sources, so they need no new
	// RealController code. Values may be null until the first scan, rules load or client start.

	public @Nullable Minecraft minecraft() {
		return minecraft;
	}

	public Path configDir() {
		return configDir;
	}

	public Path modsDir() {
		return modsDir;
	}

	public String modVersion() {
		return modVersion;
	}

	public ClientSettings settings() {
		return settings;
	}

	public @Nullable RulesDocument rules() {
		return rules;
	}

	public @Nullable HardwareProfile hardwareProfile() {
		return hardware;
	}

	public @Nullable List<InstalledMod> mods() {
		return mods;
	}

	public boolean downloading() {
		return downloading;
	}

	public ProfileService profileService() {
		return profileService;
	}

	public StutterService stutterService() {
		return stutterService;
	}

	public JvmService jvmService() {
		return jvmService;
	}

	public TrendService trendService() {
		return trendService;
	}

	public ServerLimitsTracker serverLimitsTracker() {
		return serverLimitsTracker;
	}

	public AwarenessService awarenessService() {
		return awarenessService;
	}

	public StartupTimes startupTimesService() {
		return startupTimes;
	}

	// v0.4 (C4): one-line delegations. Notice slot (C3).

	@Override
	public List<Notice> notices() {
		return noticeCenter.notices();
	}

	@Override
	public void noticeAction(String key, String actionId) {
		noticeCenter.act(key, actionId);
	}

	@Override
	public void dismissNotice(String key) {
		noticeCenter.dismiss(key);
	}

	// Profiles (item 4).

	@Override
	public List<ProfileView> profiles() {
		return profileService.profiles();
	}

	@Override
	public Component switchProfile(String id) {
		return profileService.switchProfile(id);
	}

	@Override
	public ApplyPreview previewProfile(String id) {
		return profileService.previewProfile(id);
	}

	@Override
	public Component saveCurrentProfile(String name) {
		return profileService.saveCurrentProfile(name);
	}

	@Override
	public ProfileImport importProfileCode(String code) {
		return profileService.importProfileCode(code);
	}

	@Override
	public @Nullable String exportProfileCode(String id) {
		return profileService.exportProfileCode(id);
	}

	@Override
	public void renameProfile(String id, String name) {
		profileService.renameProfile(id, name);
	}

	@Override
	public void deleteProfile(String id) {
		profileService.deleteProfile(id);
	}

	// Stutter Doctor (item 5).

	@Override
	public StutterView stutter() {
		return stutterService.view();
	}

	@Override
	public void setStutterMonitor(boolean on) {
		stutterService.setMonitor(on);
	}

	@Override
	public void pauseStutterMonitor(boolean paused) {
		stutterService.pause(paused);
	}

	@Override
	public void clearStutter() {
		stutterService.clear();
	}

	@Override
	public String stutterSummary() {
		return stutterService.summary();
	}

	// JVM & memory (item 6).

	@Override
	public JvmReport jvmReport() {
		return jvmService.report();
	}

	// Benchmark history (item 7).

	@Override
	public BenchmarkTrend.View benchmarkTrend(@Nullable String contextKey) {
		return trendService.trend(contextKey);
	}

	// Server-aware advice (item 8).

	@Override
	public @Nullable ServerLimits serverLimits() {
		return serverLimitsTracker.live();
	}

	// Startup-time report (item 13).

	@Override
	public StartupTimes.View startupTimes() {
		return startupTimes.view();
	}
}
