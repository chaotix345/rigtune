package io.github.chaotix345.rigtune.client.profile;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import io.github.chaotix345.rigtune.core.profile.ProfileImport;
import io.github.chaotix345.rigtune.core.profile.ProfileView;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

// Performance Profiles and share codes (docs/v0.4/SPEC.md 4): profiles.json, switching (an ordinary Apply through
// RealController.apply(selected, entryId)), templates, share codes and the battery offer. RealController delegates
// every C4 profile method here in one line. Skeleton from the contracts commit; WS-P owns it.
public final class ProfileService {
	private final RealController controller;
	private final Path configDir;

	public ProfileService(RealController controller, Path configDir) {
		this.controller = controller;
		this.configDir = configDir;
	}

	public List<ProfileView> profiles() {
		return List.of();
	}

	public Component switchProfile(String id) {
		return Component.translatable("rigtune.status.nothing");
	}

	public ApplyPreview previewProfile(String id) {
		return ApplyPreview.EMPTY;
	}

	public Component saveCurrentProfile(String name) {
		return Component.translatable("rigtune.status.nothing");
	}

	public ProfileImport importProfileCode(String code) {
		return new ProfileImport(null, ApplyPreview.EMPTY, 0, null);
	}

	public @Nullable String exportProfileCode(String id) {
		return null;
	}

	public void renameProfile(String id, String name) {
	}

	public void deleteProfile(String id) {
	}
}
