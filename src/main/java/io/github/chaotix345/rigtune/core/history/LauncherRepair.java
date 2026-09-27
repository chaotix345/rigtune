package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.LogSafe;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.model.Text;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

// docs/v0.5/SPEC.md 4g (launcher-managed-mods.md §5): what an older RigTune's mod-file changes left for a launcher that
// keeps its own list of mods to catch up with, read from RigTune's own records only: history.json's APPLIED file changes
// and the mods folder (UndoPlanner.Folder: names, and a jar's fabric.mod.json id). Never another program's database or
// metadata. RigTune itself never renames, moves or deletes the old .disabled copies: the notice says what to do in the
// launcher. Pure.
public final class LauncherRepair {
	public static final String KEY_PREFIX = "launcher-repair:";

	// An update still in effect: RigTune disabled oldFile (the .disabled name it gave it) and enabled newFile, the same mod.
	public record Pair(String modId, String oldFile, String newFile) {
	}

	// added: jars RigTune added (no disable in their group), still there; disabledOnly: .disabled copies of jars RigTune
	// disabled without enabling another version of that mod.
	public record Findings(List<Pair> pairs, List<String> added, List<String> disabledOnly) {
		public Findings {
			pairs = List.copyOf(pairs);
			added = List.copyOf(added);
			disabledOnly = List.copyOf(disabledOnly);
		}

		public boolean isEmpty() {
			return pairs.isEmpty() && added.isEmpty() && disabledOnly.isEmpty();
		}

		// The notice's key: Dismiss hides it until the set of pairs and disabled copies changes (awareness.json's dismissed
		// ids). Added jars need nothing done, so they don't change it.
		public String key() {
			TreeSet<String> parts = new TreeSet<>();
			pairs.forEach(p -> parts.add("pair:" + p.oldFile() + ">" + p.newFile()));
			disabledOnly.forEach(d -> parts.add("disabled:" + d));
			try {
				byte[] hash = MessageDigest.getInstance("SHA-256").digest(String.join("\n", parts).getBytes(StandardCharsets.UTF_8));
				return KEY_PREFIX + HexFormat.of().formatHex(hash, 0, 8);
			} catch (NoSuchAlgorithmException e) {
				return KEY_PREFIX + Integer.toHexString(parts.hashCode());
			}
		}
	}

	private LauncherRepair() {
	}

	// Each APPLIED change of an entry that isn't an undo, per group (a change without one is alone): a disable counts only
	// with a resultFile (RW-14: without one RigTune renamed nothing) that is still there; an enable only while its file is
	// there. A change whose file is gone was repaired. A legacy disable's mod id (0.1.0 recorded none) is read from its
	// .disabled jar.
	public static Findings find(List<JournalEntry> entries, UndoPlanner.Folder folder) {
		List<Pair> pairs = new ArrayList<>();
		List<String> added = new ArrayList<>();
		List<String> disabledOnly = new ArrayList<>();
		for (JournalEntry entry : entries) {
			if (entry == null || JournalEntry.UNDO.equals(entry.kind())) {
				continue;
			}
			Map<String, List<JournalChange>> groups = new LinkedHashMap<>();
			for (JournalChange c : entry.changes()) {
				if (c != null && c.isFile() && JournalChange.APPLIED.equals(c.status()) && c.file() != null) {
					groups.computeIfAbsent(c.group() != null ? "group:" + c.group() : "change:" + c.id(), k -> new ArrayList<>()).add(c);
				}
			}
			for (List<JournalChange> group : groups.values()) {
				boolean hasDisable = group.stream().anyMatch(c -> JournalChange.DISABLE.equals(c.action()));
				List<JournalChange> enables = new ArrayList<>(group.stream()
						.filter(c -> JournalChange.ENABLE.equals(c.action()) && folder.files().contains(c.file())).toList());
				for (JournalChange d : group) {
					if (!JournalChange.DISABLE.equals(d.action()) || d.resultFile() == null || !folder.files().contains(d.resultFile())) {
						continue;
					}
					String id = d.modId() != null ? d.modId() : idOf(folder, d.resultFile());
					JournalChange match = id == null ? null : enables.stream().filter(e -> id.equals(e.modId() != null ? e.modId() : idOf(folder, e.file())))
							.findFirst().orElse(null);
					if (match != null) {
						enables.remove(match);
						pairs.add(new Pair(id, d.resultFile(), match.file()));
					} else {
						disabledOnly.add(d.resultFile());
					}
				}
				if (!hasDisable) {
					enables.stream().filter(e -> !"rigtune".equals(e.modId() != null ? e.modId() : idOf(folder, e.file()))).forEach(e -> added.add(e.file()));
				}
			}
		}
		return new Findings(pairs, added, disabledOnly);
	}

	private static String idOf(UndoPlanner.Folder folder, String file) {
		JarInfo jar = folder.jar(file);
		return jar == null ? null : jar.id();
	}

	// Whether the launcher needs a step: an update pair everywhere but in ATLauncher, whose own record follows the new jar;
	// a disabled copy only in ATLauncher, which doesn't list such a mod at all. Added jars never need one.
	public static boolean actionable(Findings findings, Launcher launcher) {
		return launcher == Launcher.ATLAUNCHER ? !findings.disabledOnly().isEmpty() : !findings.pairs().isEmpty();
	}

	// Copy list: the files the steps name, one per line: names only (the mods folder's path holds the account name), with
	// hidden characters escaped.
	public static String copyList(Findings findings, Launcher launcher) {
		List<String> names = launcher == Launcher.ATLAUNCHER ? findings.disabledOnly() : findings.pairs().stream().map(Pair::oldFile).toList();
		return String.join("\n", names.stream().map(LogSafe::text).toList());
	}

	public static Text message(Launcher launcher) {
		return Text.of("rigtune.repair.message", "Mod changes from an older RigTune: help %s catch up", name(launcher));
	}

	public static Text detail(Findings findings, Launcher launcher) {
		String old = String.join(", ", findings.pairs().stream().map(p -> LogSafe.text(p.oldFile())).toList());
		String disabled = String.join(", ", findings.disabledOnly().stream().map(LogSafe::text).toList());
		String added = String.join(", ", findings.added().stream().map(LogSafe::text).toList());
		List<Text> parts = new ArrayList<>();
		switch (launcher) {
			case MODRINTH_APP -> {
				parts.add(Text.of("rigtune.repair.steps.modrinth_app", "Do this in the Modrinth App, not in File Explorer (the app keeps its own list):"
						+ " open this instance → Content → filter Disabled → select the old copy → Delete: %s. RigTune's newer versions stay. If an old"
						+ " copy isn't listed, the app has already hidden it: nothing to do.", old));
				parts.add(Text.of("rigtune.repair.fallback.modrinth_app", "Or, to let the app own every file: delete the new copy, update the old one,"
						+ " then switch it on."));
			}
			case PRISM -> parts.add(Text.of("rigtune.repair.steps.prism", "In Prism Launcher: Edit... → Mods → select the old disabled copy → Remove,"
					+ " before any Check for Updates: %s. RigTune's newer versions stay.", old));
			case GDLAUNCHER -> parts.add(Text.of("rigtune.repair.steps.gdlauncher", "In GDLauncher: Mods → the old disabled copy → Delete: %s. RigTune's"
					+ " newer versions stay.", old));
			case CURSEFORGE -> parts.add(Text.of("rigtune.repair.steps.curseforge", "In the CurseForge app, remove these mods from this profile and"
					+ " install them again there: %s.", old));
			case ATLAUNCHER -> {
				if (!disabled.isEmpty()) {
					parts.add(Text.of("rigtune.repair.disabled.atlauncher", "ATLauncher doesn't list the mods RigTune disabled: %s. To switch one back on,"
							+ " rename it from .jar.disabled to .jar, or undo that change in RigTune after choosing \"Let RigTune change them anyway\""
							+ " in its Settings.", disabled));
				}
			}
			case MULTIMC, OFFICIAL, UNKNOWN -> parts.add(Text.of("rigtune.repair.steps.generic", "In the launcher that keeps this instance's list of"
					+ " mods, remove the old disabled copies: %s. RigTune's newer versions stay.", old));
		}
		if (!added.isEmpty()) {
			parts.add(launcher == Launcher.MODRINTH_APP
					? Text.of("rigtune.repair.added.modrinth_app", "RigTune also added: %s. The app lists them as your own files and will update them"
							+ " itself; nothing to do.", added)
					: Text.of("rigtune.repair.added", "RigTune also added: %s; nothing to do for them.", added));
		}
		return Text.sentences(parts.toArray(Text[]::new));
	}

	// The launcher's name inside a sentence: "your launcher" when the evidence is packwiz metadata and the launcher
	// itself doesn't keep a list (the official launcher, an unknown one).
	public static Text name(Launcher launcher) {
		return launcher == Launcher.UNKNOWN || launcher == Launcher.OFFICIAL
				? Text.of("rigtune.repair.launcher.generic", "your launcher") : Text.literal(launcher.displayName());
	}

	// How many mod changes the held ops are, as History shows them: per group, an update's disable and enable are one.
	public static int modChanges(List<Op> ops) {
		Map<String, int[]> groups = new LinkedHashMap<>();
		for (int i = 0; i < ops.size(); i++) {
			Op op = ops.get(i);
			if (op == null || op.type() != PendingActions.Type.ENABLE_FILE && op.type() != PendingActions.Type.DISABLE_FILE) {
				continue;
			}
			int[] counts = groups.computeIfAbsent(op.group() != null ? "group:" + op.group() : "op:" + i, k -> new int[2]);
			counts[op.type() == PendingActions.Type.ENABLE_FILE ? 0 : 1]++;
		}
		return groups.values().stream().mapToInt(c -> Math.max(c[0], c[1])).sum();
	}
}
