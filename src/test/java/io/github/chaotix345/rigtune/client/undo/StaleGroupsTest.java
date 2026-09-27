package io.github.chaotix345.rigtune.client.undo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.TestJars;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

// docs/v0.5/SPEC.md 2H RW-3: the status line for the stale groups a rebuild dropped: "RigTune dropped its pending change to
// %s: it is already installed (%s)" or "…: its download is gone", one sentence per group.
class StaleGroupsTest {
	private static final String DH = "DistantHorizons-3.3.2-26.2-fabric-neoforge.jar";

	@TempDir
	Path game;
	Path mods;
	Staging staging;

	@BeforeEach
	void setUp() throws IOException {
		mods = Files.createDirectories(game.resolve("mods"));
		Path config = Files.createDirectories(game.resolve("config"));
		Journal journal = new Journal(config, "0.5.0", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		staging = new Staging(config, PendingActions.defaultPath(config), List.of(), journal, Duration.ofMillis(200));
	}

	private static String english(Component text) throws IOException {
		JsonObject lang = JsonParser.parseString(Files.readString(RepoFiles.resolve("src/main/resources/assets/rigtune/lang/en_us.json"))).getAsJsonObject();
		StringBuilder out = new StringBuilder();
		for (Component part : text.getSiblings().isEmpty() ? List.of(text) : text.getSiblings()) {
			if (part.getContents() instanceof TranslatableContents t) {
				List<Object> args = new ArrayList<>();
				for (Object arg : t.getArgs()) {
					args.add(arg instanceof Component c ? c.getString() : arg);
				}
				out.append(lang.get(t.getKey()).getAsString().formatted(args.toArray()));
			} else {
				out.append(part.getString());
			}
		}
		return out.toString();
	}

	private List<Op> stage(String download, String target, String modId, String entry) throws IOException {
		List<Op> group = PendingActions.group(Op.enableFile(TestJars.modJar(mods.resolve(download + PendingActions.PENDING_SUFFIX), modId), mods.resolve(target))
				.withModId(modId));
		assertNotNull(staging.stage(group, entry));
		return group;
	}

	@Test
	void oneSentencePerDroppedGroupNamingTheModAndWhere() throws IOException {
		stage(DH, DH, "distanthorizons", "e1");
		stage("lithium-0.21.jar", "lithium-0.21.jar", "lithium", "e2");
		Files.delete(mods.resolve(DH + PendingActions.PENDING_SUFFIX));
		TestJars.modJar(mods.resolve(DH), "distanthorizons");
		Files.delete(mods.resolve("lithium-0.21.jar" + PendingActions.PENDING_SUFFIX));

		List<Op> dropped = StaleGroups.drop(staging, Set.of("distanthorizons", "sodium"), Map.of("distanthorizons", Set.of(DH), "sodium", Set.of("sodium.jar")));
		Component status = StaleGroups.status(dropped, List.of(new InstalledMod("distanthorizons", "Distant Horizons", "3.3.2", mods.resolve(DH), "sha1")));

		assertEquals(2, dropped.size());
		assertEquals("RigTune dropped its pending change to Distant Horizons: it is already installed (" + DH + ")."
				+ " RigTune dropped its pending change to lithium: its download is gone.", english(status));
		assertNull(StaleGroups.status(dropped, List.of()), "said once");
	}

	@Test
	void nothingDroppedSaysNothing() throws IOException {
		stage("lithium-0.21.jar", "lithium-0.21.jar", "lithium", "e1");

		List<Op> dropped = StaleGroups.drop(staging, Set.of(), Map.of());

		assertEquals(List.of(), dropped);
		assertNull(StaleGroups.status(dropped, List.of()));
	}
}
