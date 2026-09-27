package io.github.chaotix345.rigtune.core.launcher;

// docs/v0.5/SPEC.md 4a: what the instance's own files say about who keeps a record of its mods. packwizIndex: <mods>/.index/
// holds at least one regular *.pw.toml (LauncherProbe's bounded listing, WS-L1).
public record InstanceEvidence(boolean packwizIndex) {
	public static final InstanceEvidence NONE = new InstanceEvidence(false);
}
