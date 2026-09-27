package io.github.chaotix345.rigtune.core.profile;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// docs/v0.5/SPEC.md "Compatibility promise" (share codes), PF-4, PF-5, Latent 1: every code 0.5 emits decodes in the pinned
// 0.4.0 decoder (src/test/java/.../v040/core/profile/, verbatim v0.4.0) without an error and to the same values as in 0.5,
// for any importing display.
class V040ShareCodeCompatTest {
	private static final int[] SENDERS = {-1, 60, 65, 75, 120, 144, 180, 240};
	private static final int[] IMPORTERS = {-1, 60, 75, 144, 180};
	private static final String DH_RADIUS = "dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius";

	// Every value of every key (each wire value's canonical spelling), one key per code, from every sender display.
	@Test
	void everyCode05EmitsDecodesIn040ToTheSameValues() throws Exception {
		int codes = 0;
		int expected = 0;
		for (ShareKeys.Key key : ShareKeys.V1) {
			for (int wire = 0; wire <= key.maxWire(); wire++) {
				for (int sender : SENDERS) {
					codes += same(Map.of(key.key(), key.decode(wire, 60)), sender) ? 1 : 0;
					expected += key.shareable() ? 1 : 0;
				}
			}
		}
		assertEquals(expected, codes, "a code for every shareable value");
	}

	// Whole profiles: the test profiles and the goldens' values, a Recording/Battery 60 FPS cap and a DH radius above the
	// wire's 512 (PF-5: left out, the rest decodes in 0.4.0).
	@Test
	void wholeProfilesDecodeIn040ToTheSameValues() throws Exception {
		List<Map<String, String>> profiles = new ArrayList<>();
		profiles.add(ShareCodeTest.merge(ShareCodeTest.VANILLA, ShareCodeTest.SODIUM, ShareCodeTest.DH, ShareCodeTest.IRIS));
		Map<String, String> recording = new LinkedHashMap<>(ShareCodeTest.VANILLA);
		recording.put("vanilla.maxFps", "60");
		recording.put("vanilla.enableVsync", "true");
		profiles.add(recording);
		Map<String, String> farLods = new LinkedHashMap<>(ShareCodeTest.merge(ShareCodeTest.VANILLA, ShareCodeTest.DH));
		farLods.put(DH_RADIUS, "1024");
		profiles.add(farLods);
		for (Map<String, String> profile : profiles) {
			for (int sender : SENDERS) {
				assertNotNull(ShareCode.encode("Mine", profile, sender));
				same(profile, sender);
			}
		}
		String code = ShareCode.encode("Mine", farLods, 144);
		assertFalse(io.github.chaotix345.rigtune.v040.core.profile.ShareCode.decode(code).values(144).containsKey(DH_RADIUS),
				"a radius the wire can't carry never reaches it");
	}

	// PF-4: a 60 FPS cap from a 60 Hz sender is 60 in 0.4.0 too, whatever the importer's display.
	@Test
	void sixtyFromASixtyHzSenderIsSixtyIn040() throws Exception {
		String code = ShareCode.encode("Recording", Map.of("vanilla.maxFps", "60"), 60);
		for (int importer : IMPORTERS) {
			assertEquals("60", io.github.chaotix345.rigtune.v040.core.profile.ShareCode.decode(code).values(importer).get("vanilla.maxFps"));
		}
	}

	private static boolean same(Map<String, String> values, int sender) throws Exception {
		String code = ShareCode.encode("Mine", values, sender);
		if (code == null) {
			return false;
		}
		ShareCode.Decoded now = ShareCode.decode(code);
		io.github.chaotix345.rigtune.v040.core.profile.ShareCode.Decoded old = io.github.chaotix345.rigtune.v040.core.profile.ShareCode.decode(code);
		assertEquals(now.name(), old.name(), code);
		assertEquals(now.unknownKeys(), old.unknownKeys(), code);
		for (int importer : IMPORTERS) {
			assertEquals(now.values(importer), old.values(importer), values + " from " + sender + " Hz, imported at " + importer + " Hz: " + code);
		}
		return true;
	}
}
