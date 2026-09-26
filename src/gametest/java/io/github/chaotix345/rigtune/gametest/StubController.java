package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.CpuInfo;
import io.github.chaotix345.rigtune.core.model.DisplayInfo;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.TierBasis;
import io.github.chaotix345.rigtune.core.model.TierResult;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeBoard;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public final class StubController implements RigTuneController {
	private final Supplier<@Nullable HardwareProfile> hardware;
	private Goal goal = Goal.BALANCED;
	private @Nullable Report report;
	private int benchmarkRequests;
	private final List<Notice> notices = new ArrayList<>();
	private final Set<String> dismissed = new HashSet<>();
	private final List<String> noticeActions = new ArrayList<>();

	public StubController(Supplier<@Nullable HardwareProfile> hardware) {
		this.hardware = hardware;
		this.report = build();
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
		this.report = build();
	}

	@Override
	public Component apply(List<Recommendation> selected) {
		return Component.literal("Stub: " + selected.size() + " change(s) would be applied.");
	}

	@Override
	public void startBenchmark() {
		benchmarkRequests++;
	}

	public int benchmarkRequests() {
		return benchmarkRequests;
	}

	// v0.4 (docs/v0.4/SPEC.md C3/C4): canned notices for the notice line; actions and dismissals are recorded.
	public void setNotices(List<Notice> canned) {
		notices.clear();
		notices.addAll(canned);
	}

	@Override
	public List<Notice> notices() {
		return NoticeBoard.select(notices, dismissed).visible();
	}

	@Override
	public void noticeAction(String key, String actionId) {
		noticeActions.add(key + ":" + actionId);
	}

	@Override
	public void dismissNotice(String key) {
		dismissed.add(key);
	}

	public List<String> noticeActions() {
		return List.copyOf(noticeActions);
	}

	@Override
	public void rescan() {
		this.report = build();
	}

	private Report build() {
		HardwareProfile hw = hardware.get();
		if (hw == null) {
			hw = new HardwareProfile(new CpuInfo("AMD Ryzen 7 7800X3D 8-Core Processor", 8, 16, 5050),
					new GpuInfo("AMD", "AMD Radeon RX 7800 XT", "25.9.1", GraphicsBackend.VULKAN, 16368),
					32_000, 4096, new DisplayInfo(2560, 1440, 165, true), false, false, "Windows 11", "26.2", Set.of());
		}
		Path mods = Path.of("mods");
		List<Recommendation> recs = List.of(
				new Recommendation("warn-heap", Category.WARNING, Impact.HIGH, "Only 2 GB of RAM allocated",
						"Minecraft has a 2 GB heap. Raise it to 4-6 GB in your launcher's Java settings to avoid stutter from garbage collection.",
						new Action.None(), false),
				new Recommendation("obsolete-indium", Category.REMOVE_MOD, Impact.HIGH, "Disable Indium",
						"Merged into Sodium since 0.6; the standalone mod conflicts with current Sodium.",
						new Action.DisableMod("indium", mods.resolve("indium-1.0.36.jar")), true),
				new Recommendation("add-lithium", Category.ADD_MOD, Impact.HIGH, "Add Lithium",
						"Optimises game logic (mob AI, block ticking, collisions) without changing behaviour.",
						new Action.AddMod("lithium", "gvQqBUqZ", "Lithium"), true),
				new Recommendation("add-ferritecore", Category.ADD_MOD, Impact.MEDIUM, "Add FerriteCore",
						"Cuts memory use of block states and models, which helps most with larger modpacks.",
						new Action.AddMod("ferrite-core", "uXXizFIs", "FerriteCore"), true),
				new Recommendation("add-entityculling", Category.ADD_MOD, Impact.LOW, "Add Entity Culling (alpha build)",
						"Skips rendering entities and block entities that are hidden behind walls.",
						new Action.AddMod("entityculling", "NNAgCjsB", "Entity Culling"), false),
				new Recommendation("update-sodium", Category.UPDATE_MOD, Impact.MEDIUM, "Update Sodium 0.9.1 → 0.9.2",
						"Bug fixes for the Vulkan draw path.",
						new Action.UpdateMod("sodium", mods.resolve("sodium-0.9.1.jar"), null), true),
				new Recommendation("set-vanilla.renderDistance", Category.SETTING, Impact.HIGH, "Render distance: 16 → 12",
						"Tier 4 hardware holds a steady frame rate at 12 chunks; the benchmark can fine-tune this.",
						new Action.SetSetting("vanilla.renderDistance", "16", "12"), true),
				new Recommendation("set-vanilla.simulationDistance", Category.SETTING, Impact.MEDIUM, "Simulation distance: 12 → 8",
						"Ticking fewer chunks lowers CPU load with little visible difference.",
						new Action.SetSetting("vanilla.simulationDistance", "12", "8"), true),
				new Recommendation("set-sodium.performance.use_entity_culling", Category.SETTING, Impact.LOW, "Entity culling: false → true",
						"Sodium can skip entities that are not visible.",
						new Action.SetSetting("sodium.performance.use_entity_culling", "false", "true"), true),
				new Recommendation("advice-driver", Category.ADVICE, Impact.MEDIUM, "Update your GPU driver",
						"Sodium enabled a driver workaround for your GPU. A newer driver may fix the underlying issue and improve performance.",
						new Action.None(), false));
		TierResult tier = new TierResult(4, Math.clamp(4 + goal.tierOffset(), 1, 5), 5, 4, 5, "cpu");
		// docs/v0.4/SPEC.md 2j: what each tier rests on, for the tier badge's tooltip.
		TierBasis basis = new TierBasis(new TierBasis.Gpu(5, TierBasis.Basis.TABLE_MATCH, "(?i)rx\\s*7[89]00", GpuVendor.AMD, false),
				new TierBasis.Cpu(4, TierBasis.Basis.FALLBACK_ESTIMATE, null, 16, 4201), new TierBasis.Memory(5, TierBasis.Basis.TABLE_MATCH, 6144));
		return new Report(hw, new GpuClass(GpuVendor.AMD, false, 5, "(?i)rx\\s*7[89]00"), tier, goal, recs, 2, "bundled", false, Instant.now(), basis);
	}
}
