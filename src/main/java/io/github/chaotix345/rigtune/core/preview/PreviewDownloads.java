package io.github.chaotix345.rigtune.core.preview;

import io.github.chaotix345.rigtune.core.apply.ApplyExecutor;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.modrinth.DependencyResolver;
import io.github.chaotix345.rigtune.core.modrinth.DownloadPlanner;
import io.github.chaotix345.rigtune.core.modrinth.DryRunPlanner;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthVersion;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Add/Update recommendations through DryRunPlanner: each staged enable is a download (attributed to the first
// recommendation that owns it, in the planner's order), each staged disable a .disabled rename, and each refusal the
// planner's own reason. With Modrinth lookups off, updates are still planned (their data is local) and additions are
// left unresolved.
final class PreviewDownloads {
	private PreviewDownloads() {
	}

	// Returns false when Modrinth is off: then nothing can be downloaded (Apply's client refuses it too), and additions
	// are left unresolved.
	static boolean add(List<Recommendation> recs, DownloadInputs in, Path modsDir, PreviewPlanner.Out out) {
		// The planner's own order: every update before any addition.
		List<Recommendation> ordered = new ArrayList<>();
		recs.stream().filter(r -> r.action() instanceof Action.UpdateMod).forEach(ordered::add);
		recs.stream().filter(r -> !(r.action() instanceof Action.UpdateMod) && in.lookups()).forEach(ordered::add);
		if (!ordered.isEmpty()) {
			plan(ordered, in, modsDir, out);
		}
		for (Recommendation r : recs) {
			if (!in.lookups() && r.action() instanceof Action.AddMod) {
				out.downloads.add(new ApplyPreview.Download(r.id(), r.title(), null, null, false, r.titleText()));
			}
		}
		return in.lookups();
	}

	private static void plan(List<Recommendation> ordered, DownloadInputs in, Path modsDir, PreviewPlanner.Out out) {
		LookupOnlyClient client = new LookupOnlyClient(in.client(), in.lookups());
		Map<String, String> modIdsByFile = new HashMap<>();
		for (Recommendation r : ordered) {
			if (r.action() instanceof Action.UpdateMod update && update.update().file() != null) {
				modIdsByFile.put(update.update().file().filename(), update.modId());
			}
		}
		DependencyResolver resolver = new DependencyResolver(client, in.loader(), in.gameVersion(), in.installedVersions()).withStaged(in.staged());
		DownloadPlanner.Result result = DryRunPlanner.plan(resolver, modsDir, in.conflicts(), in.updateVersions(), modIdsByFile, ordered,
				in.installedProjects(), in.loadedIds(), in.stagedJars(), in.lookedUp() || !in.lookups());

		Map<String, Recommendation> owner = new HashMap<>();
		for (Recommendation r : ordered) {
			result.opIds().getOrDefault(r.id(), List.of()).forEach(opId -> owner.putIfAbsent(opId, r));
		}
		Set<String> withFiles = new HashSet<>();
		for (Op op : result.ops()) {
			Recommendation r = owner.get(op.id());
			if (r == null) {
				continue;
			}
			switch (op.type()) {
				case ENABLE_FILE -> {
					Path target = Path.of(op.to());
					String name = target.getFileName().toString();
					out.downloads.add(new ApplyPreview.Download(r.id(), r.title(), name, target, !name.equals(ownFile(r, client)), r.titleText()));
					withFiles.add(r.id());
				}
				case DISABLE_FILE -> {
					Path file = Path.of(op.path());
					out.disables.add(new ApplyPreview.Disable(r.id(), r.title(), file, ApplyExecutor.disabledTarget(file), r.titleText()));
				}
				default -> {
				}
			}
		}
		// The planner reports one error per refused recommendation, in its order, and each as a Text (errorTexts).
		List<String> errors = result.errors();
		int next = 0;
		for (Recommendation r : ordered) {
			if (!result.ids().contains(r.id())) {
				int at = next++;
				out.skipText(r, ApplyPreview.Reason.DOWNLOAD_FAILED, at < errors.size() ? reason(r, errors.get(at), result.errorTexts().get(at)) : null);
			} else if (!withFiles.contains(r.id())) {
				out.skip(r, ApplyPreview.Reason.NO_NEW_FILES, null);
			}
		}
	}

	// The planner reports "<title>: <reason>" (rigtune.download.error with the reason as its second argument).
	private static Text reason(Recommendation r, String error, Text shown) {
		String prefix = r.title() + ": ";
		if (shown instanceof Text.Translatable t && t.key().equals("rigtune.download.error") && t.args().size() == 2 && t.args().get(1) instanceof Text cause
				&& error.equals(prefix + cause.english())) {
			return cause;
		}
		return Text.literal(error.startsWith(prefix) ? error.substring(prefix.length()) : error);
	}

	private static @Nullable String ownFile(Recommendation r, LookupOnlyClient client) {
		return switch (r.action()) {
			case Action.UpdateMod update -> update.update().file() == null ? null : update.update().file().filename();
			case Action.AddMod add -> {
				ModrinthVersion root = client.answer(add.projectId() != null ? add.projectId() : add.slug());
				ModFile file = root == null ? null : root.primaryFile();
				yield file == null ? null : file.filename();
			}
			default -> null;
		};
	}
}
