package io.github.chaotix345.rigtune.core.history;

// One change RigTune made (docs/v0.2/SPEC.md item 3). type: "setting" or "file".
// setting: key, before, after. file: action ("enable" or "disable"), modId, file (a bare file name in the mods folder).
// before/after null means the key was absent. resultFile: for a disable, the name the file actually got (x.jar.disabled,
// or x.jar.disabled.1 when that was taken), set when the helper applies it.
// status: STAGED, APPLIED, ABANDONED, DISCARDED or REVERTED. opId/group link to the op as it is in pending.json (after
// the merge). reverts: for a change made by an undo, the id of the change it reverts.
// modName (0.4.0 on, optional): the mod's display name (fabric.mod.json "name") for a file change, recorded at staging;
// null in older files and when it couldn't be read. 0.3.0 and older ignore it, and drop it if they rewrite the file
// (History then shows the file name again).
public record JournalChange(String id, String type, String key, String before, String after, String action, String modId,
		String file, String resultFile, String status, String opId, String group, String reverts, String modName) {
	public static final String SETTING = "setting";
	public static final String FILE = "file";
	public static final String ENABLE = "enable";
	public static final String DISABLE = "disable";
	public static final String STAGED = "STAGED";
	public static final String APPLIED = "APPLIED";
	public static final String ABANDONED = "ABANDONED";
	public static final String DISCARDED = "DISCARDED";
	public static final String REVERTED = "REVERTED";

	public JournalChange(String id, String type, String key, String before, String after, String action, String modId,
			String file, String resultFile, String status, String opId, String group, String reverts) {
		this(id, type, key, before, after, action, modId, file, resultFile, status, opId, group, reverts, null);
	}

	public static JournalChange setting(String key, String before, String after, String status, String opId) {
		return new JournalChange(newId(), SETTING, key, before, after, null, null, null, null, status, opId, null, null);
	}

	public static JournalChange file(String action, String modId, String file, String status, String opId, String group) {
		return new JournalChange(newId(), FILE, null, null, null, action, modId, file, null, status, opId, group, null);
	}

	public JournalChange withStatus(String newStatus) {
		return new JournalChange(id, type, key, before, after, action, modId, file, resultFile, newStatus, opId, group, reverts, modName);
	}

	public JournalChange reverting(String changeId) {
		return new JournalChange(id, type, key, before, after, action, modId, file, resultFile, status, opId, group, changeId, modName);
	}

	public JournalChange withGroup(String newGroup) {
		return new JournalChange(id, type, key, before, after, action, modId, file, resultFile, status, opId, newGroup, reverts, modName);
	}

	public JournalChange withResultFile(String newResultFile) {
		return new JournalChange(id, type, key, before, after, action, modId, file, newResultFile, status, opId, group, reverts, modName);
	}

	public JournalChange withOpId(String newOpId) {
		return new JournalChange(id, type, key, before, after, action, modId, file, resultFile, status, newOpId, group, reverts, modName);
	}

	public JournalChange withModName(String newModName) {
		return new JournalChange(id, type, key, before, after, action, modId, file, resultFile, status, opId, group, reverts, newModName);
	}

	public boolean isSetting() {
		return SETTING.equals(type);
	}

	public boolean isFile() {
		return FILE.equals(type);
	}

	static String newId() {
		return java.util.UUID.randomUUID().toString();
	}
}
