package io.github.chaotix345.rigtune.core.benchmark;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

// The Distant Horizons / shader half of the benchmark knobs. Before the first change the original values go into the
// restore marker (a marker that can't be written means nothing is touched); the marker is deleted once both are back.
// Each toggle's failure is collected and never stops the other one.
public final class ModToggles {
	public interface Mods {
		void setDhRendering(boolean on) throws Exception;

		/** Puts back exactly what DH had before the first setDhRendering. */
		void restoreDhRendering() throws Exception;

		void setShaders(boolean on) throws Exception;
	}

	private final Knobs original;
	private final Path markerFile;
	private final Mods mods;
	private final Supplier<String> now;
	private boolean markerWritten;

	public ModToggles(Knobs original, Path markerFile, Mods mods, Supplier<String> now) {
		this.original = original;
		this.markerFile = markerFile;
		this.mods = mods;
		this.now = now;
	}

	public void apply(Knobs from, Knobs to, List<String> failures) throws IOException {
		if (from.dhRendering() == to.dhRendering() && from.shaders() == to.shaders()) {
			return;
		}
		boolean backToOriginal = to.dhRendering() == original.dhRendering() && to.shaders() == original.shaders();
		if (!backToOriginal && !markerWritten) {
			new RestoreMarker(original.dhRendering() ? Boolean.TRUE : null, original.shaders() ? Boolean.TRUE : null, now.get()).save(markerFile);
			markerWritten = true;
		}
		int before = failures.size();
		if (from.dhRendering() != to.dhRendering()) {
			try {
				if (to.dhRendering() == original.dhRendering()) {
					mods.restoreDhRendering();
				} else {
					mods.setDhRendering(to.dhRendering());
				}
			} catch (Exception | LinkageError e) {
				failures.add("Distant Horizons rendering: " + e);
			}
		}
		if (from.shaders() != to.shaders()) {
			try {
				mods.setShaders(to.shaders());
			} catch (Exception | LinkageError e) {
				failures.add("Iris shaders: " + e);
			}
		}
		if (backToOriginal && failures.size() == before) {
			RestoreMarker.delete(markerFile);
			markerWritten = false;
		}
	}
}
