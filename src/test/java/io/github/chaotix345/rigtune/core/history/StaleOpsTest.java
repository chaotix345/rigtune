package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

// docs/v0.5/SPEC.md 2H RW-3: the staged groups that can never run. An enable's download is gone (a mod installed at its
// target is "installed another way", else the download is simply gone), or its mod is loaded from a jar that is neither its
// own target nor one a staged disable turns off. A group the helper left half done is never one.
class StaleOpsTest {
	private static final Path MODS = Path.of("instance", "mods").toAbsolutePath();
	private final Set<Path> files = new HashSet<>();

	private Path file(String name) {
		Path path = MODS.resolve(name);
		files.add(path);
		return path;
	}

	private List<StaleOps.Stale> find(List<Op> ops, Map<String, Set<String>> loadedFrom, Set<String> halfDone) {
		return StaleOps.find(ops, files::contains, loadedFrom, Op::modId, halfDone);
	}

	private static Op enable(String download, String target, String modId) {
		return Op.enableFile(MODS.resolve(download), MODS.resolve(target)).withModId(modId);
	}

	// The real case (real-world-2026-09-27.md §3): 0.1.0's DH group, the download deleted, `fabric-26.2.jar` removed and DH
	// 3.3.2 installed by the Modrinth App at the staged name, where it's loaded from.
	@Test
	void theRealDhGroupIsInstalledAnotherWay() {
		String dh = "DistantHorizons-3.3.2-26.2-fabric-neoforge.jar";
		file(dh);
		List<Op> group = PendingActions.group(Op.disableFile(MODS.resolve("fabric-26.2.jar")), enable(dh + ".rigtune-pending", dh, "distanthorizons"));

		List<StaleOps.Stale> stale = find(group, Map.of("distanthorizons", Set.of(dh)), Set.of());

		assertEquals(List.of(new StaleOps.Stale(group.get(1).id(), StaleOps.Why.INSTALLED, "distanthorizons", dh, dh)), stale);
	}

	// Variant: the old jar disabled in the app instead of removed; the same.
	@Test
	void theOldJarDisabledInTheAppInsteadChangesNothing() {
		String dh = "DistantHorizons-3.3.2-26.2-fabric-neoforge.jar";
		file(dh);
		file("fabric-26.2.jar.disabled");
		List<Op> group = PendingActions.group(Op.disableFile(MODS.resolve("fabric-26.2.jar")), enable(dh + ".rigtune-pending", dh, "distanthorizons"));

		assertEquals(StaleOps.Why.INSTALLED, find(group, Map.of("distanthorizons", Set.of(dh)), Set.of()).getFirst().why());
	}

	// The download gone and the mod nowhere: it can never apply.
	@Test
	void aDownloadThatIsGoneWithTheModNowhereIsGone() {
		file("lithium-0.20.jar");
		List<Op> group = PendingActions.group(Op.disableFile(MODS.resolve("lithium-0.20.jar")), enable("lithium-0.21.jar.rigtune-pending", "lithium-0.21.jar",
				"lithium"));

		assertEquals(List.of(new StaleOps.Stale(group.get(1).id(), StaleOps.Why.GONE, "lithium", "lithium-0.21.jar", null)),
				find(group, Map.of("lithium", Set.of("lithium-0.20.jar")), Set.of()));
	}

	// An update to a file of the same name as the jar it replaces (allowed): with the download gone the old jar at that
	// name is no sign of an install, so the group is gone, not "installed".
	@Test
	void anOldJarAtTheTargetNameThatTheGroupDisablesIsNoInstall() {
		file("x.jar");
		List<Op> group = PendingActions.group(Op.disableFile(MODS.resolve("x.jar")), enable("x.jar.rigtune-pending", "x.jar", "x"));

		assertEquals(StaleOps.Why.GONE, find(group, Map.of("x", Set.of("x.jar")), Set.of()).getFirst().why());
	}

	// The download still there, the mod loaded from another jar the group doesn't disable (installed through the launcher
	// under another name): installed another way.
	@Test
	void aModLoadedFromAnotherJarIsInstalledAnotherWay() {
		file("sodium-0.9.3.jar.rigtune-pending");
		file("sodium-0.9.2.jar");
		file("sodium-fabric-0.9.3+mc26.2.jar");
		List<Op> group = PendingActions.group(Op.disableFile(MODS.resolve("sodium-0.9.2.jar")), enable("sodium-0.9.3.jar.rigtune-pending", "sodium-0.9.3.jar",
				"sodium"));

		List<StaleOps.Stale> stale = find(group, Map.of("sodium", Set.of("sodium-0.9.2.jar", "sodium-fabric-0.9.3+mc26.2.jar")), Set.of());

		assertEquals(List.of(new StaleOps.Stale(group.get(1).id(), StaleOps.Why.INSTALLED, "sodium", "sodium-0.9.3.jar", "sodium-fabric-0.9.3+mc26.2.jar")),
				stale);
	}

	// What isn't stale: an update loaded from the jar it disables, an addition of a mod that isn't loaded, an undo's
	// re-enable whose other half disables the loaded jar, and a jar a disable in another group turns off.
	@Test
	void runnableGroupsStay() {
		file("sodium-0.9.2.jar");
		file("sodium-0.9.3.jar.rigtune-pending");
		file("lithium-1.0.jar.rigtune-pending");
		file("iris-1.10.jar.disabled");
		file("iris-1.11.jar");
		file("zoomify-2.jar");
		file("zoomify-1.jar.disabled");
		List<Op> ops = new ArrayList<>();
		ops.addAll(PendingActions.group(Op.disableFile(MODS.resolve("sodium-0.9.2.jar")), enable("sodium-0.9.3.jar.rigtune-pending", "sodium-0.9.3.jar",
				"sodium")));
		ops.addAll(PendingActions.group(enable("lithium-1.0.jar.rigtune-pending", "lithium-1.0.jar", "lithium")));
		ops.addAll(PendingActions.group(Op.disableFile(MODS.resolve("iris-1.11.jar")), enable("iris-1.10.jar.disabled", "iris-1.10.jar", "iris")));
		ops.addAll(PendingActions.group(Op.disableFile(MODS.resolve("zoomify-2.jar"))));
		ops.add(enable("zoomify-1.jar.disabled", "zoomify-1.jar", "zoomify"));

		assertEquals(List.of(), find(ops, Map.of("sodium", Set.of("sodium-0.9.2.jar"), "iris", Set.of("iris-1.11.jar"), "zoomify", Set.of("zoomify-2.jar")),
				Set.of()));
	}

	// A group the helper left half done is finished or rolled back at the next exit: never stale.
	@Test
	void aHalfDoneGroupIsNeverStale() {
		String dh = "DistantHorizons-3.3.2.jar";
		file(dh);
		List<Op> group = PendingActions.group(Op.disableFile(MODS.resolve("dh-3.3.0.jar")), enable(dh + ".rigtune-pending", dh, "distanthorizons"));

		assertEquals(List.of(), find(group, Map.of("distanthorizons", Set.of(dh)), Set.of(group.getFirst().group())));
	}

	// One entry per group; an "installed" op wins over a "gone" one; unrelated groups aren't touched; an enable without a
	// mod id (0.1.0's) is judged by its files alone.
	@Test
	void oneEntryPerGroupAndAnInstallWins() {
		file("a.jar");
		List<Op> group = PendingActions.group(enable("lib.jar.rigtune-pending", "lib.jar", "lib"), enable("a.jar.rigtune-pending", "a.jar", "a"));
		file("b.jar.rigtune-pending");
		List<Op> unrelated = PendingActions.group(enable("b.jar.rigtune-pending", "b.jar", "b"));
		Op noModId = Op.enableFile(MODS.resolve("c.jar.rigtune-pending"), MODS.resolve("c.jar"));
		List<Op> ops = new ArrayList<>(group);
		ops.addAll(unrelated);
		ops.add(noModId);

		List<StaleOps.Stale> stale = find(ops, Map.of(), Set.of());

		assertEquals(List.of(new StaleOps.Stale(group.get(1).id(), StaleOps.Why.INSTALLED, "a", "a.jar", "a.jar"),
				new StaleOps.Stale(noModId.id(), StaleOps.Why.GONE, null, "c.jar", null)), stale);
	}
}
