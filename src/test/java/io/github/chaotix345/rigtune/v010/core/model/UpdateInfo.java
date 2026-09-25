package io.github.chaotix345.rigtune.v010.core.model;

public record UpdateInfo(String modId, String projectId, String currentVersion, String newVersionId, String newVersionNumber, ModFile file) {
}
