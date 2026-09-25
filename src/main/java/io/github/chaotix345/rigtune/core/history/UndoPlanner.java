package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.history.UndoPlan.Action;
import io.github.chaotix345.rigtune.core.history.UndoPlan.Item;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

// Works out what "Undo last apply" or "Undo everything" does (docs/v0.2/SPEC.md item 3), without touching anything.
// - STAGED changes: their whole group is dropped from pending.json (changes of other applies in that group too).
// - Settings: put back when the current value is still the latest `after`; chained newest to oldest, stopping where
//   the user changed the value between two applies.
// - Mod files: newest group first, each group all-or-nothing against a simulated mods folder, so every step is checked
//   against what the earlier steps leave. A group that would make a mod id load twice or leave a jar without a mod it
//   depends on is skipped (review M9). The net renames become one new group per set of original groups that touched
//   the same files, which also undoes update chains that kept one file name (review M6).
// - RigTune's own jar is never undone, nor is a staged update of it dropped (review L3).
public final class UndoPlanner {
	public static final String ALL = "all";
	static final String RIGTUNE = "rigtune";
	static final Set<String> ALWAYS_PROVIDED = Set.of("minecraft", "java", "fabricloader");
	private static final String VANILLA_PREFIX = "vanilla.";
	private static final String PRESET_KEY = "vanilla.graphicsPreset";
	private static final String DISABLED_SUFFIX = ".disabled";

	static final String CHANGED_SINCE = "You changed it since (it's now %s)";
	static final String ALREADY_ORIGINAL = "It's already back at its original value (%s)";
	static final String CHANGED_BETWEEN = "You changed it after this apply, so this older value isn't restored";
	static final String ABSENT_BEFORE = "It didn't exist before, and RigTune can't remove a setting";
	static final String NOT_CHANGEABLE = "RigTune doesn't change this option itself; set it in the game's options";
	static final String PRESET_MAY_CHANGE = "RigTune can't set this option itself; restoring the graphics preset may change it";
	static final String UPDATE_STAGED = "Another change of %s is staged; cancel it first (Discard pending)";
	static final String NOT_STAGED = "It's no longer waiting for a restart";
	static final String RIGTUNE_JAR = "RigTune never undoes its own update";
	static final String RIGTUNE_STAGED = "RigTune's own update stays staged; use Discard pending to cancel it";
	static final String GROUP_CHANGED = "What's staged with it changed since this list was made";
	static final String STAGED_TOGETHER = "Staged together with a change above, so it's cancelled too";
	static final String FILE_GONE = "%s is no longer in the mods folder";
	static final String NOT_A_MOD = "%s isn't a readable Fabric mod jar, so RigTune can't check that re-enabling it is safe";
	static final String FILE_EXISTS = "%s already exists";
	static final String BREAKS = "Undoing it would stop the game from starting: %s";
	static final String GONE = "It was undone or changed since this list was made";
	static final String SUPERSEDED = "Changed again by a later apply";
	static final String SUPERSEDED_GROUP = "Goes with a change a later apply changed again";

	private UndoPlanner() {
	}

	public interface State {
		// The current value of a settings key ("vanilla.x", "sodium.x", ...), or null when it's absent.
		String setting(String key);

		// True when the key is set now (vanilla options); false when it's staged for after a restart (config files).
		boolean immediate(String key);

		// True when RigTune may write this key.
		boolean changeable(String key);

		Folder folder();

		// How a settings key and its values are shown on the Undo screen.
		default String label(String key) {
			return UndoPlanner.label(key);
		}

		default String value(String key, String value) {
			return value;
		}
	}

	public interface Folder {
		Path dir();

		// The file names in the mods folder.
		Set<String> files();

		// That file's fabric.mod.json, or null when it isn't a readable mod jar.
		JarInfo jar(String fileName);

		// Mod ids provided by mods that aren't jars in the mods folder (built-in mods, -Dfabric.addMods).
		Set<String> providedElsewhere();
	}

	// undo: the change the undo records (status and op id are filled in when it's carried out). opRef: for a file
	// change, the id of the op in fileOps whose result the undo change follows; null when the folder is already right.
	public record Revert(String changeId, JournalChange undo, String opRef) {
	}

	// immediate: vanilla values to set now; staged: config-file values to stage; fileOps: mod-file ops to stage;
	// discardOpIds: staged ops to drop (whole groups).
	public record Script(Map<String, String> immediate, Map<String, String> staged, List<Op> fileOps, Set<String> discardOpIds,
			List<Revert> reverts) {
		static Script empty() {
			return new Script(Map.of(), Map.of(), List.of(), Set.of(), List.of());
		}
	}

	public record Result(UndoPlan plan, Script script) {
	}

	public static Result plan(List<JournalEntry> entries, List<Op> pending, State state, boolean all) {
		Context ctx = new Context(entries);
		if (all) {
			return build(ctx, ctx.candidates(l -> true), pending, state, null, true, ALL, null);
		}
		for (int i = entries.size() - 1; i >= 0; i--) {
			JournalEntry entry = entries.get(i);
			if (JournalEntry.UNDO.equals(entry.kind()) || ctx.undone.contains(i)) {
				continue;
			}
			int index = i;
			List<Located> selected = ctx.candidates(l -> l.entry() == index);
			if (selected.isEmpty()) {
				continue;
			}
			Result result = build(ctx, selected, pending, state, null, false, entry.id(), entry.at());
			if (!result.plan().isEmpty()) {
				return result;
			}
		}
		return new Result(new UndoPlan(false, null, List.of()), Script.empty());
	}

	// "Undo this" on one history entry (docs/v0.3/SPEC.md item 6): what's left of it to undo, planned like Undo last.
	// An unknown id, an undo entry or an entry with nothing left gives an empty plan (undoOf null).
	public static Result planEntry(List<JournalEntry> entries, List<Op> pending, State state, String entryId) {
		Context ctx = new Context(entries);
		for (int i = 0; i < entries.size(); i++) {
			JournalEntry entry = entries.get(i);
			if (entryId == null || !entryId.equals(entry.id()) || JournalEntry.UNDO.equals(entry.kind())) {
				continue;
			}
			int index = i;
			List<Located> selected = ctx.candidates(l -> l.entry() == index);
			if (!selected.isEmpty()) {
				return build(ctx, selected, pending, state, null, false, entry.id(), entry.at());
			}
		}
		return new Result(new UndoPlan(false, null, List.of()), Script.empty());
	}

	// The entries with something left to undo (review B-L1): not undo entries, with a change that is staged, or applied
	// and not being reverted.
	public static Set<String> undoable(List<JournalEntry> entries) {
		Context ctx = new Context(entries);
		Set<String> out = new LinkedHashSet<>();
		ctx.candidates(l -> true).forEach(l -> out.add(l.owner().id()));
		return out;
	}

	// The plan the player confirmed, re-planned against the current state (review M8): only the changes it would
	// undo are considered, and any whose state changed become skips.
	public static Result recheck(UndoPlan shown, List<JournalEntry> entries, List<Op> pending, State state) {
		Context ctx = new Context(entries);
		Set<String> wanted = new LinkedHashSet<>();
		Set<String> shownOps = new HashSet<>();
		for (Item item : shown.items()) {
			if (item.action() != Action.SKIP) {
				wanted.addAll(item.changeIds());
			}
			if (item.action() == Action.DISCARD_STAGED) {
				shownOps.addAll(item.opIds());
			}
		}
		List<Located> selected = ctx.candidates(l -> wanted.contains(l.change().id()));
		Result result = build(ctx, selected, pending, state, shownOps, shown.all(), shown.undoOf(), shown.at());
		Set<String> found = new HashSet<>();
		selected.forEach(l -> found.add(l.change().id()));
		List<Item> items = new ArrayList<>(result.plan().items());
		for (String id : wanted) {
			Located l = ctx.byId.get(id);
			// A group mate an earlier undo staged isn't a candidate, but it goes with its group.
			boolean dropped = l != null && l.change().opId() != null && result.script().discardOpIds().contains(l.change().opId());
			if (!found.contains(id) && !dropped) {
				items.add(new Item(l == null ? id : describe(l.change(), state), Action.SKIP, GONE, false, List.of(id), List.of()));
			}
		}
		return new Result(new UndoPlan(shown.all(), shown.undoOf(), items, shown.at(), null), result.script());
	}

	private record Located(int entry, int index, JournalEntry owner, JournalChange change) {
	}

	private static final class Context {
		final List<JournalEntry> entries;
		final Map<String, Located> byId = new HashMap<>();
		final List<Located> all = new ArrayList<>();
		final Set<String> beingReverted = new HashSet<>();
		final Set<Integer> undone = new HashSet<>();

		Context(List<JournalEntry> entries) {
			this.entries = entries;
			Map<String, Integer> indexById = new HashMap<>();
			for (int i = 0; i < entries.size(); i++) {
				JournalEntry entry = entries.get(i);
				indexById.put(entry.id(), i);
				for (int j = 0; j < entry.changes().size(); j++) {
					Located l = new Located(i, j, entry, entry.changes().get(j));
					byId.put(l.change().id(), l);
					all.add(l);
				}
			}
			for (int i = 0; i < entries.size(); i++) {
				JournalEntry entry = entries.get(i);
				if (!JournalEntry.UNDO.equals(entry.kind())) {
					continue;
				}
				// An undo whose reversals were all dropped or abandoned (or that did nothing) didn't undo its target.
				boolean undid = entry.changes().stream()
						.anyMatch(c -> !JournalChange.DISCARDED.equals(c.status()) && !JournalChange.ABANDONED.equals(c.status()));
				if (!undid) {
					continue;
				}
				if (ALL.equals(entry.undoOf())) {
					for (int k = 0; k < i; k++) {
						undone.add(k);
					}
				} else if (entry.undoOf() != null && indexById.containsKey(entry.undoOf())) {
					undone.add(indexById.get(entry.undoOf()));
				}
				for (JournalChange c : entry.changes()) {
					if (c.reverts() != null && (JournalChange.STAGED.equals(c.status()) || JournalChange.APPLIED.equals(c.status()))) {
						beingReverted.add(c.reverts());
					}
				}
			}
		}

		// Changes an undo can still act on: staged ones, and applied ones no undo is reverting yet.
		List<Located> candidates(Predicate<Located> filter) {
			return all.stream()
					.filter(l -> !JournalEntry.UNDO.equals(l.owner().kind()))
					.filter(l -> JournalChange.STAGED.equals(l.change().status())
							|| JournalChange.APPLIED.equals(l.change().status()) && !beingReverted.contains(l.change().id()))
					.filter(filter)
					.toList();
		}

		List<Located> stagedWithOp(String opId) {
			return all.stream()
					.filter(l -> JournalChange.STAGED.equals(l.change().status()) && opId != null && opId.equals(l.change().opId()))
					.toList();
		}
	}

	private static final class Builder {
		final List<Item> discards = new ArrayList<>();
		final List<Item> reverts = new ArrayList<>();
		final List<Item> skips = new ArrayList<>();
		final Map<String, String> immediate = new LinkedHashMap<>();
		final Map<String, String> staged = new LinkedHashMap<>();
		final List<Op> fileOps = new ArrayList<>();
		final Set<String> discardOpIds = new LinkedHashSet<>();
		final List<Revert> revertList = new ArrayList<>();

		final State state;

		Builder(State state) {
			this.state = state;
		}

		void skip(Located l, String reason) {
			skips.add(new Item(describe(l.change(), state), Action.SKIP, reason, false, List.of(l.change().id()), List.of()));
		}
	}

	private static Result build(Context ctx, List<Located> selected, List<Op> pending, State state, Set<String> shownOps, boolean all,
			String undoOf, String at) {
		Builder b = new Builder(state);
		Folder folder = state.folder();
		List<Located> kept = withoutSuperseded(ctx, selected, pending, b);
		planStaged(ctx, kept, pending, folder, shownOps, b);
		planSettings(kept, state, b);
		planFiles(kept, folder, pending, b);
		List<Item> items = new ArrayList<>(b.discards);
		items.addAll(b.reverts);
		items.addAll(b.skips);
		Script script = new Script(Map.copyOf(b.immediate), Map.copyOf(b.staged), List.copyOf(b.fileOps), Set.copyOf(b.discardOpIds),
				List.copyOf(b.revertList));
		return new Result(new UndoPlan(all, undoOf, items, at, null), script);
	}

	// --- changes a later entry changed again (review B-H1)

	// A selected change is skipped when a later non-undo entry has a change, not part of this plan, that is staged, or
	// applied and not being reverted, on the same settings key, the same file name (file or resultFile, either way) or
	// that enables the same mod id: undoing the older one would undo or break the later one. Its group goes with it.
	// Changes of one plan never supersede each other, so Undo everything is unaffected.
	private static List<Located> withoutSuperseded(Context ctx, List<Located> selected, List<Op> pending, Builder b) {
		Set<String> selectedIds = new HashSet<>();
		selected.forEach(l -> selectedIds.add(l.change().id()));
		List<Located> later = ctx.candidates(l -> !selectedIds.contains(l.change().id()));
		Set<Located> superseded = new LinkedHashSet<>();
		for (Located l : selected) {
			if (later.stream().anyMatch(m -> m.entry() > l.entry() && touchesSame(l.change(), m.change()))) {
				superseded.add(l);
			}
		}
		if (superseded.isEmpty()) {
			return selected;
		}
		Set<String> groups = new HashSet<>();
		superseded.forEach(l -> groups.addAll(groupsOf(l.change(), pending)));
		List<Located> kept = new ArrayList<>();
		for (Located l : selected) {
			if (superseded.contains(l)) {
				b.skip(l, SUPERSEDED);
			} else if (groupsOf(l.change(), pending).stream().anyMatch(groups::contains)) {
				b.skip(l, SUPERSEDED_GROUP);
			} else {
				kept.add(l);
			}
		}
		return kept;
	}

	private static boolean touchesSame(JournalChange older, JournalChange newer) {
		if (older.isSetting() || newer.isSetting()) {
			return older.isSetting() && newer.isSetting() && older.key() != null && older.key().equals(newer.key());
		}
		Set<String> names = new HashSet<>();
		if (older.file() != null) {
			names.add(older.file());
		}
		if (older.resultFile() != null) {
			names.add(older.resultFile());
		}
		return names.contains(newer.file()) || names.contains(newer.resultFile())
				|| JournalChange.ENABLE.equals(newer.action()) && newer.modId() != null && newer.modId().equals(older.modId());
	}

	// The groups a change goes with: its own, and for a staged change the group of its op in pending.json.
	private static Set<String> groupsOf(JournalChange c, List<Op> pending) {
		Set<String> out = new HashSet<>();
		if (c.group() != null) {
			out.add(c.group());
		}
		if (JournalChange.STAGED.equals(c.status()) && c.opId() != null) {
			pending.stream().filter(op -> op != null && c.opId().equals(op.id()) && op.group() != null).forEach(op -> out.add(op.group()));
		}
		return out;
	}

	// --- staged changes: drop their whole group from pending.json

	private static void planStaged(Context ctx, List<Located> selected, List<Op> pending, Folder folder, Set<String> shownOps, Builder b) {
		Map<String, List<Located>> byGroup = new LinkedHashMap<>();
		Map<String, List<Op>> groupOps = new HashMap<>();
		for (Located l : selected) {
			if (!JournalChange.STAGED.equals(l.change().status())) {
				continue;
			}
			Op op = pending.stream().filter(o -> o != null && o.id() != null && o.id().equals(l.change().opId())).findFirst().orElse(null);
			if (op == null) {
				b.skip(l, NOT_STAGED);
				continue;
			}
			String key = op.group() != null ? "g:" + op.group() : "o:" + op.id();
			byGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(l);
			groupOps.computeIfAbsent(key, k -> op.group() == null ? List.of(op)
					: pending.stream().filter(o -> o != null && op.group().equals(o.group())).toList());
		}
		for (Map.Entry<String, List<Located>> group : byGroup.entrySet()) {
			List<Op> ops = groupOps.get(group.getKey());
			List<String> opIds = ops.stream().map(Op::id).filter(Objects::nonNull).toList();
			boolean rigtune = ops.stream().anyMatch(op -> touchesRigTune(op, folder))
					|| group.getValue().stream().anyMatch(l -> RIGTUNE.equals(l.change().modId()));
			if (rigtune) {
				group.getValue().forEach(l -> b.skip(l, RIGTUNE_STAGED));
				continue;
			}
			if (shownOps != null && !shownOps.containsAll(opIds)) {
				group.getValue().forEach(l -> b.skip(l, GROUP_CHANGED));
				continue;
			}
			Set<String> covered = new HashSet<>();
			for (Located l : group.getValue()) {
				covered.add(l.change().id());
				b.discards.add(new Item(describe(l.change(), b.state), Action.DISCARD_STAGED, null, false, List.of(l.change().id()), opIds));
			}
			for (Op op : ops) {
				List<Located> mates = ctx.stagedWithOp(op.id());
				if (mates.isEmpty()) {
					b.discards.add(new Item(describe(op), Action.DISCARD_STAGED, STAGED_TOGETHER, false, List.of(), opIds));
				}
				for (Located mate : mates) {
					if (covered.add(mate.change().id())) {
						b.discards.add(new Item(describe(mate.change(), b.state), Action.DISCARD_STAGED, STAGED_TOGETHER, false, List.of(mate.change().id()), opIds));
					}
				}
			}
			b.discardOpIds.addAll(opIds);
		}
	}

	private static boolean touchesRigTune(Op op, Folder folder) {
		if (op.type() == PendingActions.Type.ENABLE_FILE) {
			return RIGTUNE.equals(op.modId());
		}
		if (op.type() == PendingActions.Type.DISABLE_FILE && op.path() != null) {
			JarInfo info = folder.jar(HistoryUpdates.fileName(op.path()));
			return info != null && RIGTUNE.equals(info.id());
		}
		return false;
	}

	// --- settings

	private static final Comparator<Located> NEWEST_FIRST = Comparator.comparingInt(Located::entry).thenComparingInt(Located::index).reversed();

	private static void planSettings(List<Located> selected, State state, Builder b) {
		Map<String, List<Located>> byKey = new LinkedHashMap<>();
		for (Located l : selected) {
			if (JournalChange.APPLIED.equals(l.change().status()) && l.change().isSetting() && l.change().key() != null) {
				byKey.computeIfAbsent(l.change().key(), k -> new ArrayList<>()).add(l);
			}
		}
		List<Located> unwritable = new ArrayList<>();
		Map<String, String> skippedNow = new LinkedHashMap<>();
		for (Map.Entry<String, List<Located>> keyed : byKey.entrySet()) {
			String key = keyed.getKey();
			List<Located> changes = keyed.getValue().stream().sorted(NEWEST_FIRST).toList();
			String current = state.setting(key);
			if (state.immediate(key) && current != null) {
				skippedNow.put(key, current);
			}
			if (!Objects.equals(current, changes.getFirst().change().after())) {
				// Back where it was before the oldest of these changes (another apply and its undo can do that).
				boolean original = current != null && current.equals(changes.getLast().change().before());
				String reason = (original ? ALREADY_ORIGINAL : CHANGED_SINCE).formatted(show(state, key, current));
				changes.forEach(l -> b.skip(l, reason));
				continue;
			}
			// Newest to oldest while each older change ends where the newer one started; after the first mismatch
			// (the user changed it in between) every older change stays as it is.
			List<Located> chain = new ArrayList<>();
			String target = null;
			boolean broken = false;
			for (Located l : changes) {
				if (!broken && (chain.isEmpty() || Objects.equals(l.change().after(), target))) {
					chain.add(l);
					target = l.change().before();
				} else {
					broken = true;
					b.skip(l, CHANGED_BETWEEN);
				}
			}
			if (target == null) {
				chain.forEach(l -> b.skip(l, ABSENT_BEFORE));
				continue;
			}
			if (!state.changeable(key)) {
				unwritable.addAll(chain);
				continue;
			}
			skippedNow.remove(key);
			boolean now = state.immediate(key);
			b.reverts.add(new Item(state.label(key) + ": " + show(state, key, current) + " → " + show(state, key, target), Action.REVERT, null, !now,
					chain.stream().map(l -> l.change().id()).toList(), List.of()));
			(now ? b.immediate : b.staged).put(key, target);
			for (Located l : chain) {
				JournalChange c = l.change();
				b.revertList.add(new Revert(c.id(), JournalChange.setting(key, c.after(), c.before(), null, null).reverting(c.id()), null));
			}
		}
		// Restoring graphicsPreset rewrites its options (review M7): the ones left alone are written back as they are now
		// (after the preset, which SettingsBridge applies first), so the screen's "skipped" holds.
		boolean preset = b.immediate.containsKey(PRESET_KEY);
		unwritable.forEach(l -> b.skip(l, preset ? PRESET_MAY_CHANGE : NOT_CHANGEABLE));
		if (preset) {
			skippedNow.forEach((key, current) -> {
				if (state.changeable(key)) {
					b.immediate.putIfAbsent(key, current);
				}
			});
		}
	}

	// --- mod files, against a simulated mods folder

	// A file's content, identified by the name it has in the real folder now. Its metadata is read only when needed.
	private static final class Content {
		final String origin;
		private final Folder folder;
		private JarInfo info;
		private boolean read;

		Content(String origin, Folder folder) {
			this.origin = origin;
			this.folder = folder;
		}

		JarInfo info() {
			if (!read) {
				read = true;
				info = folder.jar(origin);
			}
			return info;
		}
	}

	private record Accepted(List<Located> changes, Map<Located, Content> moved) {
	}

	private static void planFiles(List<Located> selected, Folder folder, List<Op> pending, Builder b) {
		Map<String, List<Located>> byGroup = new LinkedHashMap<>();
		for (Located l : selected.stream().sorted(NEWEST_FIRST).toList()) {
			JournalChange c = l.change();
			if (JournalChange.APPLIED.equals(c.status()) && c.isFile() && c.file() != null) {
				byGroup.computeIfAbsent(c.group() != null ? "g:" + c.group() : "c:" + c.id(), k -> new ArrayList<>()).add(l);
			}
		}
		if (byGroup.isEmpty()) {
			return;
		}
		List<Content> contents = new ArrayList<>();
		Map<String, Content> sim = new LinkedHashMap<>();
		for (String name : new TreeSet<>(folder.files())) {
			Content content = new Content(name, folder);
			contents.add(content);
			sim.put(name, content);
		}
		List<Accepted> accepted = new ArrayList<>();
		Set<String> problems = violations(sim, folder);
		for (List<Located> group : byGroup.values()) {
			// Reversal disables first, as the executor runs a group: that frees the name an update chain reuses.
			List<Located> ordered = new ArrayList<>(group.stream().filter(l -> JournalChange.ENABLE.equals(l.change().action())).toList());
			ordered.addAll(group.stream().filter(l -> !JournalChange.ENABLE.equals(l.change().action())).toList());
			if (ordered.stream().anyMatch(l -> isRigTune(l.change(), sim))) {
				ordered.forEach(l -> b.skip(l, RIGTUNE_JAR));
				continue;
			}
			Map<String, Content> trial = new LinkedHashMap<>(sim);
			Map<Located, Content> moved = new LinkedHashMap<>();
			String failure = null;
			for (Located l : ordered) {
				failure = move(l.change(), trial, moved, l);
				if (failure != null) {
					break;
				}
			}
			if (failure == null) {
				failure = stagedElsewhere(moved.values(), trial, pending, b.discardOpIds);
			}
			if (failure == null) {
				Set<String> added = new TreeSet<>(violations(trial, folder));
				added.removeAll(problems);
				if (!added.isEmpty()) {
					failure = BREAKS.formatted(String.join("; ", added));
				}
			}
			if (failure != null) {
				String reason = failure;
				ordered.forEach(l -> b.skip(l, reason));
				continue;
			}
			sim.clear();
			sim.putAll(trial);
			problems = violations(sim, folder);
			accepted.add(new Accepted(ordered, moved));
			for (Located l : ordered) {
				JournalChange c = l.change();
				String what = JournalChange.ENABLE.equals(c.action()) ? "Disable " + c.file() : "Re-enable " + c.file();
				b.reverts.add(new Item(what, Action.REVERT, null, true, List.of(c.id()), List.of()));
			}
		}
		netOps(contents, sim, accepted, folder.dir(), b);
	}

	private static String move(JournalChange c, Map<String, Content> sim, Map<Located, Content> moved, Located l) {
		String file = c.file();
		if (JournalChange.ENABLE.equals(c.action())) {
			Content content = sim.remove(file);
			if (content == null) {
				return FILE_GONE.formatted(file);
			}
			sim.put(disabledName(file, sim), content);
			moved.put(l, content);
			return null;
		}
		String disabled = c.resultFile() != null ? c.resultFile() : file + DISABLED_SUFFIX;
		if (!sim.containsKey(disabled)) {
			return FILE_GONE.formatted(disabled);
		}
		if (sim.containsKey(file)) {
			return FILE_EXISTS.formatted(file);
		}
		// Without a mod id neither this plan nor the executor could tell it isn't a second copy of a mod (review 3).
		if (sim.get(disabled).info() == null) {
			return NOT_A_MOD.formatted(disabled);
		}
		Content content = sim.remove(disabled);
		sim.put(file, content);
		moved.put(l, content);
		return null;
	}

	// A jar this group re-enables would replace a staged enable of the same mod when merged (PendingActions.merge), and
	// that one belongs to a change this undo doesn't cancel.
	private static String stagedElsewhere(Collection<Content> moved, Map<String, Content> sim, List<Op> pending, Set<String> discarded) {
		for (Map.Entry<String, Content> e : sim.entrySet()) {
			Content content = e.getValue();
			boolean reEnabled = e.getKey().endsWith(".jar") && !content.origin.endsWith(".jar");
			if (!reEnabled || !moved.contains(content) || content.info() == null) {
				continue;
			}
			String modId = content.info().id();
			boolean staged = pending.stream().anyMatch(op -> op != null && op.type() == PendingActions.Type.ENABLE_FILE
					&& modId.equals(op.modId()) && (op.id() == null || !discarded.contains(op.id())));
			if (staged) {
				return UPDATE_STAGED.formatted(modId);
			}
		}
		return null;
	}

	// As ApplyExecutor.disabledTarget names it.
	private static String disabledName(String file, Map<String, Content> sim) {
		String base = file + DISABLED_SUFFIX;
		String candidate = base;
		for (int i = 1; sim.containsKey(candidate); i++) {
			candidate = base + "." + i;
		}
		return candidate;
	}

	private static boolean isRigTune(JournalChange c, Map<String, Content> sim) {
		if (RIGTUNE.equals(c.modId())) {
			return true;
		}
		String name = JournalChange.ENABLE.equals(c.action()) ? c.file() : c.resultFile() != null ? c.resultFile() : c.file() + DISABLED_SUFFIX;
		Content content = sim.get(name);
		return content != null && content.info() != null && RIGTUNE.equals(content.info().id());
	}

	// Reasons the folder wouldn't start: a mod id on two active jars, or an active jar without a mod it depends on.
	private static Set<String> violations(Map<String, Content> sim, Folder folder) {
		Set<String> provided = new HashSet<>(ALWAYS_PROVIDED);
		provided.addAll(folder.providedElsewhere());
		Map<String, List<String>> namesById = new HashMap<>();
		List<JarInfo> active = new ArrayList<>();
		for (Map.Entry<String, Content> e : sim.entrySet()) {
			if (!e.getKey().endsWith(".jar")) {
				continue;
			}
			JarInfo info = e.getValue().info();
			if (info == null) {
				continue;
			}
			active.add(info);
			namesById.computeIfAbsent(info.id(), k -> new ArrayList<>()).add(e.getKey());
			provided.add(info.id());
			provided.addAll(info.provides());
		}
		Set<String> out = new TreeSet<>();
		namesById.forEach((id, names) -> {
			if (names.size() > 1) {
				out.add("mod " + id + " would be loaded twice (" + String.join(", ", new TreeSet<>(names)) + ")");
			}
		});
		for (JarInfo info : active) {
			for (String dep : info.depends()) {
				if (!provided.contains(dep)) {
					out.add(info.id() + " would be missing " + dep);
				}
			}
		}
		return out;
	}

	// One op per file whose place changed, grouped by the sets of original groups that touched the same files. Staging
	// each original group's reversal on its own doesn't work: two reversals of an update chain that kept one file name
	// (disable mod.jar twice) would be merged into one broken group by PendingActions.merge's dedupe. So the net renames
	// are staged, and original groups that moved the same file are joined (union-find) so they stay all-or-nothing.
	private static void netOps(List<Content> contents, Map<String, Content> sim, List<Accepted> accepted, Path dir, Builder b) {
		Map<Content, String> finalName = new HashMap<>();
		sim.forEach((name, content) -> finalName.put(content, name));
		int[] parent = new int[accepted.size()];
		for (int i = 0; i < parent.length; i++) {
			parent[i] = i;
		}
		Map<Content, Integer> movedBy = new HashMap<>();
		for (int i = 0; i < accepted.size(); i++) {
			for (Content content : accepted.get(i).moved().values()) {
				Integer other = movedBy.putIfAbsent(content, i);
				if (other != null) {
					parent[find(parent, i)] = find(parent, other);
				}
			}
		}
		Map<Integer, String> groupIds = new HashMap<>();
		Map<Content, Op> opOf = new HashMap<>();
		List<Op> ops = new ArrayList<>();
		for (Content content : contents) {
			Integer by = movedBy.get(content);
			String now = finalName.get(content);
			if (by == null || now == null) {
				continue;
			}
			boolean wasActive = content.origin.endsWith(".jar");
			boolean isActive = now.endsWith(".jar");
			Op op = null;
			if (wasActive && !isActive) {
				op = Op.disableFile(dir.resolve(content.origin));
			} else if (!wasActive && isActive) {
				op = Op.enableFile(dir.resolve(content.origin), dir.resolve(now)).withModId(content.info() == null ? null : content.info().id());
			}
			if (op != null) {
				op = op.inGroup(groupIds.computeIfAbsent(find(parent, by), k -> PendingActions.newId()));
				opOf.put(content, op);
				ops.add(op);
			}
		}
		ops.sort(Comparator.comparingInt(op -> op.type() == PendingActions.Type.DISABLE_FILE ? 0 : 1));
		Map<Integer, String> anchors = new HashMap<>();
		for (Op op : ops) {
			for (Map.Entry<Integer, String> g : groupIds.entrySet()) {
				if (g.getValue().equals(op.group())) {
					anchors.putIfAbsent(g.getKey(), op.id());
				}
			}
		}
		b.fileOps.addAll(ops);
		for (int i = 0; i < accepted.size(); i++) {
			String anchor = anchors.get(find(parent, i));
			for (Map.Entry<Located, Content> e : accepted.get(i).moved().entrySet()) {
				JournalChange c = e.getKey().change();
				Op op = opOf.get(e.getValue());
				String action = JournalChange.ENABLE.equals(c.action()) ? JournalChange.DISABLE : JournalChange.ENABLE;
				JournalChange undo = JournalChange.file(action, c.modId(), c.file(), null, null, null).reverting(c.id());
				b.revertList.add(new Revert(c.id(), undo, op != null ? op.id() : anchor));
			}
		}
	}

	private static int find(int[] parent, int i) {
		while (parent[i] != i) {
			parent[i] = parent[parent[i]];
			i = parent[i];
		}
		return i;
	}

	// --- descriptions

	static String label(String key) {
		return key != null && key.startsWith(VANILLA_PREFIX) ? key.substring(VANILLA_PREFIX.length()) : key;
	}

	private static String show(State state, String key, String value) {
		return value == null ? "(none)" : state.value(key, value);
	}

	static String describe(JournalChange c, State state) {
		if (c.isSetting()) {
			return state.label(c.key()) + ": " + show(state, c.key(), c.before()) + " → " + show(state, c.key(), c.after());
		}
		return (JournalChange.ENABLE.equals(c.action()) ? "Enable " : "Disable ") + c.file();
	}

	private static String describe(Op op) {
		if (op.type() == null) {
			return "Unknown change" + (op.path() == null ? "" : " to " + HistoryUpdates.fileName(op.path()));
		}
		return switch (op.type()) {
			case ENABLE_FILE -> "Enable " + HistoryUpdates.fileName(op.to());
			case DISABLE_FILE -> "Disable " + HistoryUpdates.fileName(op.path());
			case PATCH_JSON, PATCH_TOML, PATCH_PROPERTIES -> HistoryUpdates.fileName(op.path()) + ": " + op.patches();
		};
	}
}
