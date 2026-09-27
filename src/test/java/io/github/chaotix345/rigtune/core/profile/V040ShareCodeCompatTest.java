package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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

	// The pinned 0.4.0 decoder resolves "match the display" through the CURRENT SettingValues.refreshRateCap, which the pin
	// doesn't copy: its values are still 0.4.0's, so a pinned decode is 0.4.0's decode (coordinator review L6).
	@Test
	void theRefreshCapIsStill040s() {
		for (int hz = -5; hz <= 400; hz++) {
			assertEquals(refreshRateCap040(hz), SettingValues.refreshRateCap(hz), hz + " Hz");
		}
		assertEquals(List.of(60, 60, 30, 50, 60, 60, 70, 90, 110, 140, 160, 170, 230, 250),
				Stream.of(-1, 0, 25, 50, 60, 65, 75, 100, 120, 144, 165, 180, 240, 360).map(SettingValues::refreshRateCap).toList());
	}

	// v0.4.0's SettingValues.refreshRateCap, verbatim (git show v0.4.0:src/main/java/io/github/chaotix345/rigtune/core/
	// recommend/SettingValues.java, lines 51-58).
	private static int refreshRateCap040(int refreshRate) {
		int hz = refreshRate > 0 ? refreshRate : 60;
		int cap = hz / 10 * 10;
		if (cap == hz && hz >= 100) {
			cap -= 10;
		}
		return Math.clamp(cap, 30, 250);
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
