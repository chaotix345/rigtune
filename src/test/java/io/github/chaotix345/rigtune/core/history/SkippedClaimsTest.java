package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.UnfinishedGroups;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 4f, real world RW-20: the user's history.json says 0.1.0's DH pair was APPLIED only because 0.4.0's
// helper reported both ops SKIPPED_ALREADY_DONE after the Modrinth App had installed that jar itself. While last-apply.json
// is still that run, such a claim (no resultPath, no unfinished-groups record) becomes ABANDONED "installed another way",
// in the journal and in that run's result; once, and never for a later run. Over the real capture
// (src/test/resources/realworld/2026-09-28/).
class SkippedClaimsTest {
	private static final String CAPTURE = "src/test/resources/realworld/2026-09-28/rigtune/";
	// src/test/resources/realworld/README.md's rules, as WS-L1's RealWorldFixturesTest checks them.
	private static final List<String> FORBIDDEN = List.of("Users", "Admin", "AppData", "ModrinthApp", "profiles", "Fabric 26.2", "home/", ":\\", ":/");
	private static final Pattern PATH_VALUE = Pattern.compile("\"(path|from|to|modsDir|configDir)\"\\s*:\\s*\"([^\"]*)\"");
	static final String DISABLE_DH = "041919d9-18c9-4b04-89c4-f15e4e4a80c5";
	static final String ENABLE_DH = "db7f487d-6371-43c5-944e-c053428ad70d";

	@TempDir
	Path instance;
	Path config;
	List<JournalEntry> entries;
	ApplyResult lastApply;

	// The capture's two files, with the instance folder put in, as 0.5 finds them at its first start.
	static Path install(Path instance) throws IOException {
		Path rigtune = Files.createDirectories(instance.resolve("config").resolve("rigtune"));
		Files.createDirectories(instance.resolve("mods"));
		for (String name : List.of("history.json", "last-apply.json")) {
			String text = Files.readString(RepoFiles.resolve(CAPTURE + name), StandardCharsets.UTF_8)
					.replace("${INSTANCE}", instance.toAbsolutePath().toString().replace('\\', '/'));
			Files.writeString(rigtune.resolve(name), text, StandardCharsets.UTF_8);
		}
		return instance.resolve("config");
	}

	@BeforeEach
	void setUp() throws IOException {
		config = install(instance);
		entries = new Journal(config, "0.5.0+mc26.2", "26.2", (m, e) -> {
			throw new AssertionError(m, e);
		}).entries();
		lastApply = ApplyResult.load(ApplyResult.defaultPath(config));
	}

	private static List<String> statuses(List<JournalEntry> entries, String status) {
		List<String> out = new ArrayList<>();
		entries.forEach(e -> e.changes().stream().filter(c -> status.equals(c.status())).forEach(c -> out.add(c.opId())));
		return out;
	}

	@Test
	void theFixtureHoldsNoMachinePathOrName() throws IOException {
		for (String name : List.of("history.json", "last-apply.json")) {
			String text = Files.readString(RepoFiles.resolve(CAPTURE + name), StandardCharsets.UTF_8);
			for (String word : FORBIDDEN) {
				assertFalse(text.contains(word), name + " holds \"" + word + "\"");
			}
			Matcher m = PATH_VALUE.matcher(text);
			while (m.find()) {
				assertTrue(m.group(2).startsWith("${INSTANCE}/"), name + ": " + m.group());
			}
		}
	}

	@Test
	void theRealDhPairTurnsAbandonedAndTheFifteenRealChangesStayApplied() {
		assertEquals(17, statuses(entries, JournalChange.APPLIED).size());

		SkippedClaims.Relabel relabel = SkippedClaims.of(entries, lastApply, List::of);

		assertNotNull(relabel);
		assertEquals(List.of(DISABLE_DH, ENABLE_DH), statuses(relabel.entries(), JournalChange.ABANDONED));
		assertEquals(15, statuses(relabel.entries(), JournalChange.APPLIED).size());
		assertEquals(lastApply.finishedAt(), relabel.lastApply().finishedAt());
		assertEquals(List.of(ApplyResult.Status.ABANDONED, ApplyResult.Status.ABANDONED),
				relabel.lastApply().results().stream().map(ApplyResult.OpResult::status).toList());
		assertEquals(List.of("installed another way", "installed another way"),
				relabel.lastApply().results().stream().map(ApplyResult.OpResult::message).toList());
		assertEquals(lastApply.results().stream().map(ApplyResult.OpResult::op).toList(),
				relabel.lastApply().results().stream().map(ApplyResult.OpResult::op).toList());
		assertEquals(List.of(DISABLE_DH, ENABLE_DH), relabel.opIds());
	}

	@Test
	void aSecondPassChangesNothing() {
		SkippedClaims.Relabel first = SkippedClaims.of(entries, lastApply, List::of);

		assertNull(SkippedClaims.of(first.entries(), first.lastApply(), List::of));
	}

	// A later helper run replaced last-apply.json: the claim's own run is gone, and nothing is touched.
	@Test
	void aLaterRunChangesNothing() {
		Op other = Op.enableFile(instance.resolve("mods/lithium.jar.rigtune-pending"), instance.resolve("mods/lithium.jar")).withModId("lithium");
		ApplyResult later = new ApplyResult("2026-09-28T00:00:00Z",
				List.of(new ApplyResult.OpResult(other, ApplyResult.Status.SKIPPED_ALREADY_DONE, "lithium.jar is already enabled")));

		assertNull(SkippedClaims.of(entries, later, List::of));
		assertNull(SkippedClaims.of(entries, null, List::of));
	}

	// RigTune's own renames stay APPLIED: a result with a resultPath (redone from the record), an op the helper's
	// unfinished-groups.json records.
	@Test
	void recordedRenamesStay() {
		List<ApplyResult.OpResult> withPath = new ArrayList<>(lastApply.results());
		ApplyResult.OpResult enable = withPath.get(1);
		withPath.set(1, new ApplyResult.OpResult(enable.op(), enable.status(), enable.message(), enable.op().to()));

		SkippedClaims.Relabel relabel = SkippedClaims.of(entries, new ApplyResult(lastApply.finishedAt(), withPath),
				() -> List.of(new UnfinishedGroups.Rename(DISABLE_DH, "fabric-26.2.jar", "fabric-26.2.jar.disabled")));

		assertNull(relabel);
		assertNull(SkippedClaims.of(entries, lastApply, () -> List.of(new UnfinishedGroups.Rename(DISABLE_DH, "a", "b"),
				new UnfinishedGroups.Rename(ENABLE_DH, "c", "d"))));
	}

	// An undo's change keeps the change it reverted consistent (REVERTED), so it isn't relabelled, and neither is its
	// group's disable (review 11 APPLY-2: a disable goes only with its group's relabelled enable).
	@Test
	void anUndoChangeStays() {
		List<JournalEntry> undone = HistoryUpdates.map(entries, c -> ENABLE_DH.equals(c.opId()) ? c.reverting("some-change") : c);

		assertNull(SkippedClaims.of(undone, lastApply, List::of));
	}

	// review 11 APPLY-2: 0.5's own helper still reports a disable whose jar is already gone as a bare SKIPPED (a jar the
	// player removed by hand). That is no claim of RW-1's shape (an enable found in place): it stays APPLIED, at every start.
	@Test
	void aLoneDisableAlreadyGoneStays() {
		ApplyResult gone = new ApplyResult(lastApply.finishedAt(), List.of(lastApply.results().getFirst()));
		assertEquals(PendingActions.Type.DISABLE_FILE, gone.results().getFirst().op().type());

		assertNull(SkippedClaims.of(entries, gone, List::of));
	}

	// The DH shape with the enable proven RigTune's (a resultPath): its group's bare "already gone" disable stays too.
	@Test
	void aDisableStaysWhenItsGroupsEnableIsRigTunes() {
		List<ApplyResult.OpResult> withPath = new ArrayList<>(lastApply.results());
		ApplyResult.OpResult enable = withPath.get(1);
		withPath.set(1, new ApplyResult.OpResult(enable.op(), enable.status(), enable.message(), enable.op().to()));

		assertNull(SkippedClaims.of(entries, new ApplyResult(lastApply.finishedAt(), withPath), List::of));
	}

	// A start that died between the journal and last-apply.json: the journal already says ABANDONED, the results still
	// SKIPPED; the next start completes it, and then there's nothing left.
	@Test
	void anInterruptedRelabelIsCompleted() {
		SkippedClaims.Relabel first = SkippedClaims.of(entries, lastApply, List::of);

		SkippedClaims.Relabel resumed = SkippedClaims.of(first.entries(), lastApply, List::of);

		assertEquals(first.entries(), resumed.entries());
		assertEquals(first.lastApply(), resumed.lastApply());
		assertNull(SkippedClaims.of(resumed.entries(), resumed.lastApply(), List::of));
	}

	// The unfinished-groups record is read only when some result could be a claim.
	@Test
	void theRecordIsReadOnlyForACandidate() {
		ApplyResult ok = new ApplyResult(lastApply.finishedAt(), lastApply.results().stream()
				.map(r -> new ApplyResult.OpResult(r.op(), ApplyResult.Status.OK, "done", r.op().to())).toList());

		assertNull(SkippedClaims.of(entries, ok, () -> {
			throw new AssertionError("read the record");
		}));
		assertEquals(PendingActions.Type.DISABLE_FILE, lastApply.results().getFirst().op().type());
	}
}
