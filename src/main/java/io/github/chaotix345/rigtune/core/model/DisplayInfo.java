package io.github.chaotix345.rigtune.core.model;

/** refreshRate is -1 when unknown. */
public record DisplayInfo(int width, int height, int refreshRate, boolean fullscreen) {
}
