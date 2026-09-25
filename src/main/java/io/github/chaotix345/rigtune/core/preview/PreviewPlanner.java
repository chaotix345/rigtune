package io.github.chaotix345.rigtune.core.preview;

import io.github.chaotix345.rigtune.core.apply.ApplyExecutor;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.SafeFileNames;
import io.github.chaotix345.rigtune.core.apply.SodiumConfigPatcher;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

// Builds the preview from Apply's own inputs and code, writing nothing: the partition below is RealController.apply's
// (keep the two in step), config values go through the stagers Apply uses (they only read the file), a disabled jar
// gets ApplyExecutor's name, and downloads go through the real DownloadPlanner, dry (DryRunPlanner).
public final class PreviewPlanner {
	private static final String VANILLA = SettingKeys.VANILLA_PREFIX;

	// As ConfigTargets.Target: the namespace prefix with its dot, the file, Apply's stager and the current-values reader.
	public record ConfigFile(String prefix, Path file, BiFunction<Path, Map<String, String>, SodiumConfigPatcher.Staged> stager,
			Function<Path, Map<String, String>> reader) {
	}

	private final Path optionsFile;
	private final Map<String, String> vanillaNow;
	private final Map<String, String> vanillaProblems;
	private final List<ConfigFile> configFiles;
	private final Path modsDir;
	private final DownloadInputs downloads;

	// vanillaNow: the game's options as they are now, keyed without "vanilla."; vanillaProblems: the values the game would
	// refuse (SettingsBridge.problems), same keys.
	public PreviewPlanner(Path optionsFile, Map<String, String> vanillaNow, Map<String, String> vanillaProblems, List<ConfigFile> configFiles,
			Path modsDir, DownloadInputs downloads) {
		this.optionsFile = optionsFile;
		this.vanillaNow = Map.copyOf(vanillaNow);
		this.vanillaProblems = Map.copyOf(vanillaProblems);
		this.configFiles = List.copyOf(configFiles);
		this.modsDir = modsDir;
		this.downloads = Objects.requireNonNull(downloads);
	}

	public ApplyPreview preview(List<Recommendation> selected) {
		Out out = new Out();
		Map<String, Recommendation> vanilla = new LinkedHashMap<>();
		Map<ConfigFile, Map<String, String>> patches = new LinkedHashMap<>();
		Map<String, Recommendation> configRecs = new HashMap<>();
		List<Recommendation> downloadRecs = new ArrayList<>();
		for (Recommendation r : selected) {
			switch (r.action()) {
				case Action.SetSetting set when set.key().startsWith(VANILLA) -> vanilla.put(set.key(), r);
				case Action.SetSetting set when configFile(set.key()) != null -> {
					ConfigFile file = configFile(set.key());
					patches.computeIfAbsent(file, f -> new LinkedHashMap<>()).put(set.key().substring(file.prefix().length()), set.newValue());
					configRecs.put(set.key(), r);
				}
				case Action.DisableMod disable when SafeFileNames.isDirectChild(modsDir, disable.file()) ->
						out.disables.add(new ApplyPreview.Disable(r.id(), r.title(), disable.file(), ApplyExecutor.disabledTarget(disable.file()),
								r.titleText()));
				case Action.DisableMod ignored -> out.skip(r, ApplyPreview.Reason.OUTSIDE_MODS, null);
				case Action.AddMod ignored -> downloadRecs.add(r);
				case Action.UpdateMod ignored -> downloadRecs.add(r);
				default -> out.skip(r, ApplyPreview.Reason.NOTHING_TO_APPLY, null);
			}
		}
		vanilla.values().forEach(r -> vanilla(r, (Action.SetSetting) r.action(), out));
		patches.forEach((file, values) -> config(file, values, configRecs, out));
		boolean resolved = downloadRecs.isEmpty() || PreviewDownloads.add(downloadRecs, downloads, modsDir, out);
		return new ApplyPreview(out.now, out.atRestart, out.downloads, out.disables, out.skipped, resolved);
	}

	private @Nullable ConfigFile configFile(String key) {
		for (ConfigFile file : configFiles) {
			if (key.startsWith(file.prefix())) {
				return file;
			}
		}
		return null;
	}

	// SettingsBridge.applyVanilla's checks in its order, then only a value that differs is written.
	private void vanilla(Recommendation r, Action.SetSetting set, Out out) {
		String key = set.key().substring(VANILLA.length());
		if (!SettingKeys.changeable(set.key())) {
			out.skip(r, ApplyPreview.Reason.NOT_CHANGEABLE, null);
		} else if (!SettingKeys.safeValue(set.newValue())) {
			out.skipText(r, ApplyPreview.Reason.REFUSED, Text.of("rigtune.preview.detail.control_characters", "Value contains control characters"));
		} else if (!vanillaNow.containsKey(key)) {
			out.skip(r, ApplyPreview.Reason.UNKNOWN_SETTING, null);
		} else if (vanillaProblems.containsKey(key)) {
			out.skip(r, ApplyPreview.Reason.REFUSED, vanillaProblems.get(key));
		} else if (Objects.equals(vanillaNow.get(key), set.newValue())) {
			out.skip(r, ApplyPreview.Reason.UNCHANGED, null);
		} else {
			out.now.add(new ApplyPreview.Setting(r.id(), optionsFile, key, vanillaNow.get(key), set.newValue()));
		}
	}

	private static void config(ConfigFile file, Map<String, String> values, Map<String, Recommendation> recs, Out out) {
		SodiumConfigPatcher.Staged staged = file.stager().apply(file.file(), values);
		Map<String, String> current = file.reader().apply(file.file());
		for (Op op : staged.ops()) {
			op.patches().forEach((key, value) -> {
				Recommendation r = recs.get(file.prefix() + key);
				if (Objects.equals(current.get(key), value)) {
					out.skip(r, ApplyPreview.Reason.UNCHANGED, null);
				} else {
					out.atRestart.add(new ApplyPreview.Setting(r.id(), file.file(), key, current.get(key), value));
				}
			});
		}
		staged.refused().forEach((key, problem) -> out.skip(recs.get(file.prefix() + key), ApplyPreview.Reason.REFUSED, problem));
	}

	static final class Out {
		final List<ApplyPreview.Setting> now = new ArrayList<>();
		final List<ApplyPreview.Setting> atRestart = new ArrayList<>();
		final List<ApplyPreview.Download> downloads = new ArrayList<>();
		final List<ApplyPreview.Disable> disables = new ArrayList<>();
		final List<ApplyPreview.Skipped> skipped = new ArrayList<>();

		// detail: the game's or the config patcher's own message, shown as it is.
		void skip(Recommendation r, ApplyPreview.Reason reason, @Nullable String detail) {
			skipText(r, reason, detail == null ? null : Text.literal(detail));
		}

		void skipText(Recommendation r, ApplyPreview.Reason reason, @Nullable Text detail) {
			skipped.add(new ApplyPreview.Skipped(r.id(), r.title(), reason, detail == null ? null : detail.english(), r.titleText(), detail));
		}
	}
}
