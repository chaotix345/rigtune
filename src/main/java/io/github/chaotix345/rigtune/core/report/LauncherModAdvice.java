package io.github.chaotix345.rigtune.core.report;

import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.LauncherModText;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.Text;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

// docs/v0.5/SPEC.md 4b (P0.4): under LAUNCHER or PENDING every mod-file recommendation becomes the launcher's own steps
// (apply: the report post-step, V05Hooks.afterRecommend, before ModrinthOffAdvice), and RealController.apply refuses what
// the policy forbids (guard: V05Hooks.beforeApply). A sibling of ModrinthOffAdvice: the row keeps its id, title, category
// and impact, becomes Action.None and unticked, and its reason gets one sentence: the launcher's note (the steps line
// under it is LauncherLines', from the note's kind), or PENDING's neutral one, which names no launcher and gives no steps.
// The Modrinth lookups still run (read-only), so only available mods are advised. Recommender is unchanged.
public final class LauncherModAdvice {
	public static final String PENDING_NOTE = "Checking which launcher manages this instance's mods; mod changes wait until then.";
	private static final String NOTE_PREFIX = "rigtune.launcher.mod_files.note.";
	// Recommender's id for RigTune's own update ("update:" + the mod id).
	private static final String SELF_UPDATE_ID = "update:rigtune";

	private LauncherModAdvice() {
	}

	// launcher: LauncherInfo.UNKNOWN (or LauncherProbe.NOT_YET) until detection answers.
	public static Report apply(Report report, ModFilesPolicy policy, LauncherInfo launcher) {
		if (!policy.launcherManages()) {
			return report;
		}
		boolean changed = false;
		List<Recommendation> out = new ArrayList<>(report.recommendations().size());
		for (Recommendation r : report.recommendations()) {
			String kind = kind(r.action());
			if (kind == null) {
				out.add(r);
				continue;
			}
			changed = true;
			Text reason = Text.sentences(r.reasonText(), policy == ModFilesPolicy.PENDING ? pendingNote() : note(kind, launcher));
			out.add(new Recommendation(r.id(), r.category(), r.impact(), r.title(), reason.english(), new Action.None(), false, r.titleText(), reason));
		}
		if (!changed) {
			return report;
		}
		return new Report(report.hardware(), report.gpuClass(), report.tier(), report.goal(), List.copyOf(out), report.rulesRevision(),
				report.rulesSource(), report.online(), report.createdAt(), report.tierBasis());
	}

	// Defence in depth (4b): what Apply may still do under the policy. Unreachable after apply() above, which leaves no
	// mod-file action to select; under RIGTUNE the selection as it is.
	public static List<Recommendation> guard(List<Recommendation> selected, ModFilesPolicy policy) {
		if (!policy.launcherManages()) {
			return selected;
		}
		return selected.stream().filter(r -> kind(r.action()) == null).toList();
	}

	// The kind of launcher steps a row this class turned into advice gets ("add", "update", "disable", "self_update": the
	// LauncherInfo.modStepsKey kinds), read back from its note; null for any other row, a PENDING one included.
	public static @Nullable String kindOf(Recommendation r) {
		String kind = kindIn(r.reasonText());
		return "update".equals(kind) && SELF_UPDATE_ID.equals(r.id()) ? "self_update" : kind;
	}

	private static @Nullable String kindIn(Text text) {
		return switch (text) {
			case Text.Translatable t when t.key().startsWith(NOTE_PREFIX) -> {
				String kind = t.key().substring(NOTE_PREFIX.length());
				yield "pending".equals(kind) ? null : kind;
			}
			case Text.Joined joined -> {
				String found = null;
				for (Text part : joined.parts()) {
					String kind = kindIn(part);
					found = kind != null ? kind : found;
				}
				yield found;
			}
			default -> null;
		};
	}

	private static @Nullable String kind(Action action) {
		return switch (action) {
			case Action.AddMod ignored -> "add";
			case Action.UpdateMod update -> "rigtune".equals(update.modId()) ? "self_update" : "update";
			case Action.DisableMod ignored -> "disable";
			default -> null;
		};
	}

	// RigTune's own update reads as any update; kindOf tells it apart by its id (the Modrinth App has steps of its own for it).
	private static Text note(String kind, LauncherInfo launcher) {
		Text name = LauncherModText.nameOrYours(launcher);
		return switch (kind) {
			case "add" -> Text.of("rigtune.launcher.mod_files.note.add", "This instance's mods are managed by %s: install it there.", name);
			case "disable" -> Text.of("rigtune.launcher.mod_files.note.disable", "This instance's mods are managed by %s: turn it off there.", name);
			default -> Text.of("rigtune.launcher.mod_files.note.update", "This instance's mods are managed by %s: update it there.", name);
		};
	}

	private static Text pendingNote() {
		return Text.of("rigtune.launcher.mod_files.note.pending", PENDING_NOTE);
	}
}
