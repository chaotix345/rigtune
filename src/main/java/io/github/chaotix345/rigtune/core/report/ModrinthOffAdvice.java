package io.github.chaotix345.rigtune.core.report;

import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.Text;

import java.util.ArrayList;
import java.util.List;

// With Modrinth (or the network) off in the settings RigTune can't download anything, so mod installs and updates stay
// in the report as advice for the launcher instead (docs/v0.2/SPEC.md item 8). Local changes are untouched. The note
// names the switch that is off: with the network off the Modrinth switch itself still reads on.
public final class ModrinthOffAdvice {
	public static final String ADD_NOTE = "Modrinth is off in RigTune's settings: install it from your launcher.";
	public static final String UPDATE_NOTE = "Modrinth is off in RigTune's settings: update it in your launcher.";
	public static final String NETWORK_ADD_NOTE = "Network access is off in RigTune's settings: install it from your launcher.";
	public static final String NETWORK_UPDATE_NOTE = "Network access is off in RigTune's settings: update it in your launcher.";

	private ModrinthOffAdvice() {
	}

	public static Report apply(Report report) {
		return apply(report, false);
	}

	public static Report apply(Report report, boolean networkOff) {
		boolean changed = false;
		List<Recommendation> out = new ArrayList<>(report.recommendations().size());
		for (Recommendation r : report.recommendations()) {
			Text note = switch (r.action()) {
				case Action.AddMod ignored -> networkOff ? Text.of("rigtune.rec.network_off.install", NETWORK_ADD_NOTE)
						: Text.of("rigtune.rec.modrinth_off.install", ADD_NOTE);
				case Action.UpdateMod ignored -> networkOff ? Text.of("rigtune.rec.network_off.update", NETWORK_UPDATE_NOTE)
						: Text.of("rigtune.rec.modrinth_off.update", UPDATE_NOTE);
				default -> null;
			};
			if (note == null) {
				out.add(r);
				continue;
			}
			changed = true;
			Text reason = Text.sentences(r.reasonText(), note);
			out.add(new Recommendation(r.id(), r.category(), r.impact(), r.title(), reason.english(), new Action.None(), false, r.titleText(), reason));
		}
		if (!changed) {
			return report;
		}
		return new Report(report.hardware(), report.gpuClass(), report.tier(), report.goal(), List.copyOf(out), report.rulesRevision(),
				report.rulesSource(), report.online(), report.createdAt(), report.tierBasis());
	}
}
