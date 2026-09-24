package io.github.chaotix345.rigtune.core.history;

// One change RigTune made (docs/v0.2/SPEC.md item 3). type: "setting" or "file".
// setting: key, before, after. file: action ("enable" or "disable"), modId, file (a bare file name in the mods folder).
// status: STAGED, APPLIED, ABANDONED, DISCARDED or REVERTED. opId/group link to the pending.json op.
// reverts: for a change made by an undo, the id of the change it reverts.
public record JournalChange(String id, String type, String key, String before, String after, String action, String modId,
		String file, String status, String opId, String group, String reverts) {
	public static final String SETTING = "setting";
	public static final String FILE = "file";
	public static final String ENABLE = "enable";
	public static final String DISABLE = "disable";
	public static final String STAGED = "STAGED";
	public static final String APPLIED = "APPLIED";
	public static final String ABANDONED = "ABANDONED";
	public static final String DISCARDED = "DISCARDED";
	public static final String REVERTED = "REVERTED";

	public static JournalChange setting(String key, String before, String after, String status, String opId) {
		return new JournalChange(newId(), SETTING, key, before, after, null, null, null, status, opId, null, null);
	}

	public static JournalChange file(String action, String modId, String file, String status, String opId, String group) {
		return new JournalChange(newId(), FILE, null, null, null, action, modId, file, status, opId, group, null);
	}

	public JournalChange withStatus(String newStatus) {
		return new JournalChange(id, type, key, before, after, action, modId, file, newStatus, opId, group, reverts);
	}

	public JournalChange reverting(String changeId) {
		return new JournalChange(id, type, key, before, after, action, modId, file, status, opId, group, changeId);
	}

	static String newId() {
		return java.util.UUID.randomUUID().toString();
	}
}
