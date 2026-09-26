package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.model.Text;

// One row of the Profiles screen (docs/v0.4/SPEC.md 4, C4): a template ("template:<id>") or a saved profile ("p-<uuid>").
// source: template, baseline, saved or imported. Skeleton from the contracts commit; WS-P owns and extends it.
public record ProfileView(String id, Text name, String source, boolean active) {
}
