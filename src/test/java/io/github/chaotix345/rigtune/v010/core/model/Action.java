package io.github.chaotix345.rigtune.v010.core.model;

import java.nio.file.Path;

public sealed interface Action {
	record None() implements Action {
	}

	/** Version is resolved against Modrinth at apply time. */
	record AddMod(String slug, String projectId, String title) implements Action {
	}

	record UpdateMod(String modId, Path currentFile, UpdateInfo update) implements Action {
	}

	record DisableMod(String modId, Path file) implements Action {
	}

	record SetSetting(String key, String currentValue, String newValue) implements Action {
	}
}
