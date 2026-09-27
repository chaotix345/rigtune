package io.github.chaotix345.rigtune.client.probe;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import io.github.chaotix345.rigtune.core.hardware.PerfCounters;
import org.jspecify.annotations.Nullable;

// docs/v0.5/SPEC.md 2L: HKEY_LOCAL_MACHINE values, read through JNA's Advapi32Util (it ships with Minecraft; OSHI reads
// the same per-service entries this way). Read-only: no administrator rights are needed, and RigTune never writes the
// registry (NoRegistryWriteTest). Made only on Windows (PerfCounters.detect).
final class WindowsRegistry implements PerfCounters.Registry {
	@Override
	public @Nullable Object value(String key, String name) {
		if (!Advapi32Util.registryValueExists(WinReg.HKEY_LOCAL_MACHINE, key, name)) {
			return null;
		}
		return Advapi32Util.registryGetValue(WinReg.HKEY_LOCAL_MACHINE, key, name);
	}
}
