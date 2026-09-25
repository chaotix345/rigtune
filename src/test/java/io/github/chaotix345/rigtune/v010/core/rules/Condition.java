package io.github.chaotix345.rigtune.v010.core.rules;

import java.util.List;

public final class Condition {
	public Boolean always;
	public Integer tierAtLeast;
	public Integer tierAtMost;
	public Integer rawTierAtLeast;
	public Integer rawTierAtMost;
	public List<String> gpuVendor;
	public Boolean gpuIntegrated;
	public Integer gpuTierAtLeast;
	public Integer gpuTierAtMost;
	public Integer cpuTierAtLeast;
	public Integer cpuTierAtMost;
	public Boolean hasBattery;
	public Boolean onBattery;
	public Long heapMbAtLeast;
	public Long heapMbAtMost;
	public Long ramMbAtLeast;
	public Long ramMbAtMost;
	public Long vramMbAtLeast;
	public Long vramMbAtMost;
	public Integer refreshRateAtLeast;
	public List<String> backend;
	public List<String> os;
	public List<String> goal;
	public List<String> mcVersion;
	public List<String> modPresent;
	public List<String> modAbsent;
	public List<String> flags;
	public List<Condition> anyOf;
	public Condition not;
}
