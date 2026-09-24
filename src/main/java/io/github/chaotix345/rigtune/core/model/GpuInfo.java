package io.github.chaotix345.rigtune.core.model;

/** Raw GPU facts as reported by the game/driver. vramMb is -1 when unknown. */
public record GpuInfo(String vendorString, String renderer, String driverVersion, GraphicsBackend backend, long vramMb) {
}
