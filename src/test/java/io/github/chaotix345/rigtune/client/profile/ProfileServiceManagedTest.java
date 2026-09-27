package io.github.chaotix345.rigtune.client.profile;

import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// docs/v0.5/SPEC.md PF-5 (AC2P.5; the audit's AuditVerifyProfileTest.pf5*): "My settings" and Save current keep a DH LOD
// radius DH itself allows (DH 3.3.2: 32..4096) but a share code can't carry (32..512), and still drop one DH refuses.
class ProfileServiceManagedTest {
	private static final String DH_RADIUS = "dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius";

	@SuppressWarnings("unchecked")
	private static Map<String, String> managed(Map<String, String> values) throws ReflectiveOperationException {
		Method managed = ProfileService.class.getDeclaredMethod("managed", SettingsSnapshot.class);
		managed.setAccessible(true);
		return (Map<String, String>) managed.invoke(null, new SettingsSnapshot(values));
	}

	@Test
	void pf5BaselineKeepsADhRadiusAbove512() throws ReflectiveOperationException {
		assertEquals(Map.of(DH_RADIUS, "1024", "vanilla.renderDistance", "12"), managed(Map.of(DH_RADIUS, "1024", "vanilla.renderDistance", "12")));
	}

	@Test
	void pf5ARadiusDhRefusesIsStillDropped() throws ReflectiveOperationException {
		assertEquals(Map.of("vanilla.renderDistance", "12"), managed(Map.of(DH_RADIUS, "5000", "vanilla.renderDistance", "12")));
	}
}
