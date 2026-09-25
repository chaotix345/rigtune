package io.github.chaotix345.rigtune.client.compat;

import net.irisshaders.iris.api.v0.IrisApi;

// The only class that touches the Iris API (docs/research/v0.2/dh-iris.md §5). Only call it through OptionalMods,
// which checks that Iris is loaded first. Render thread.
final class IrisCompat {
	private IrisCompat() {
	}

	static boolean ready() {
		IrisApi api = IrisApi.getInstance();
		return api != null && api.getConfig() != null;
	}

	// True only when a pack is loaded and compiled, i.e. shaders really cost frames now.
	static boolean shaderPackInUse() {
		return IrisApi.getInstance().isShaderPackInUse();
	}

	// Saves iris.properties and reloads the shader pipeline at once (a visible hitch).
	static void setShadersEnabled(boolean on) {
		IrisApi.getInstance().getConfig().setShadersEnabledAndApply(on);
	}
}
