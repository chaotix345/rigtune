package io.github.chaotix345.rigtune.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

// docs/v0.5/SPEC.md 4j (AC4j.2 and the P0.4 game-test checks): RigTune in an instance whose launcher keeps its own
// record of the mods. Contracts skeleton (WS-K): registered at its C6 place in fabric.mod.json, returns at once under
// rigtune.smoke; one skeleton method per owner, each called once from runTest with the contracts' context record. An
// owner edits only its own method's body (and its own private helpers below it); each block puts back what it changed
// (the launcher brand property, fixture files, the network switch through GameTestNet).
public class LauncherManagedGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		V05TestContext v05 = V05TestContext.of(context);
		policyAndAdvice(v05);
		heldAndRepair(v05);
	}

	// ---- WS-L1 (AC4j.2, AC4a.3, AC4b.2, AC4b.6, AC4e.2): the policy, the launcher's steps, the refused Apply, the opt-in,
	// MOD_FILES_NEWS.

	private static void policyAndAdvice(V05TestContext v05) {
	}

	// ---- WS-L2 (AC4d.2, AC4d.4, AC4g.2): held pending file groups and the repair notice.

	private static void heldAndRepair(V05TestContext v05) {
	}
}
