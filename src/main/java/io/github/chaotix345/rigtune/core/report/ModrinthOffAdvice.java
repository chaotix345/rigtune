package io.github.chaotix345.rigtune.core.report;

import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;

import java.util.ArrayList;
import java.util.List;

// With Modrinth (or the network) off in the settings RigTune can't download anything, so mod installs and updates stay
// in the report as advice for the launcher instead (docs/v0.2/SPEC.md item 8). Local changes are untouched.
public final class ModrinthOffAdvice {
	public static final String ADD_NOTE = "Modrinth is off in RigTune's settings: install it from your launcher.";
	public static final String UPDATE_NOTE = "Modrinth is off in RigTune's settings: update it in your launcher.";

	private ModrinthOffAdvice() {
	}

	public static Report apply(Report report) {
		boolean changed = false;
		List<Recommendation> out = new ArrayList<>(report.recommendations().size());
		for (Recommendation r : report.recommendations()) {
			String note = switch (r.action()) {
				case Action.AddMod ignored -> ADD_NOTE;
				case Action.UpdateMod ignored -> UPDATE_NOTE;
				default -> null;
			};
			if (note == null) {
				out.add(r);
				continue;
			}
			changed = true;
			String reason = r.reason() == null || r.reason().isBlank() ? note : r.reason() + " " + note;
			out.add(new Recommendation(r.id(), r.category(), r.impact(), r.title(), reason, new Action.None(), false));
		}
		if (!changed) {
			return report;
		}
		return new Report(report.hardware(), report.gpuClass(), report.tier(), report.goal(), List.copyOf(out), report.rulesRevision(),
				report.rulesSource(), report.online(), report.createdAt());
	}
}
