package io.github.chaotix345.p5asm1;

import net.minecraft.client.Minecraft;

public final class Sm1Hook {
	private Sm1Hook() {
	}

	public static void tick(Minecraft minecraft) {
		minecraft.tick();
	}
}
