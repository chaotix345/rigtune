package io.github.chaotix345.rigtune.client.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// The raw view-distance and simulation-distance the server sent (docs/v0.4/SPEC.md 8; javap: private ints on 26.2 and
// 26.3, set by handleLogin and the two live-update handlers). Read by ServerLimitsTracker and BenchmarkController.
@Mixin(ClientPacketListener.class)
public interface ClientPacketListenerAccessor {
	@Accessor("serverChunkRadius")
	int rigtune$serverChunkRadius();

	@Accessor("serverSimulationDistance")
	int rigtune$serverSimulationDistance();
}
