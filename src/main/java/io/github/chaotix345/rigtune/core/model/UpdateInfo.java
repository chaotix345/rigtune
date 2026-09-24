package io.github.chaotix345.rigtune.core.model;

public record UpdateInfo(String modId, String projectId, String currentVersion, String newVersionId, String newVersionNumber, ModFile file) {
}
