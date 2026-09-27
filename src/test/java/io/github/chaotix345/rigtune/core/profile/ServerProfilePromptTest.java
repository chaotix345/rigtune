package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.profile.ServerProfilePrompt.Reason;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// docs/v0.5/SPEC.md 7 (AC7.4, AC7.17; the "no offer" halves of AC7.8 and AC7.9): whether joining a server offers its
// profile, checked in the documented order NO_SERVER, NO_MAPPING, MISSING_PROFILE, ALREADY_ACTIVE, BENCHMARK, ON_BATTERY,
// else OFFER. RigTune never switches by itself: this only decides whether the toast and the notice appear.
class ServerProfilePromptTest {
	private static final String MAX_FPS = "template:max_fps";
	private static final String QUALITY = "template:quality";
	private static final String BATTERY = "template:battery";
	private static final ServerLimits.Kind REMOTE = ServerLimits.Kind.REMOTE;

	@Test
	void eachReasonAlone() {
		assertEquals(Reason.NO_SERVER, ServerProfilePrompt.decide(null, MAX_FPS, true, QUALITY, false, false), "not connected");
		assertEquals(Reason.NO_SERVER, ServerProfilePrompt.decide(ServerLimits.Kind.SINGLEPLAYER, MAX_FPS, true, QUALITY, false, false),
				"the own world, an Open-to-LAN host, the benchmark world");
		assertEquals(Reason.NO_MAPPING, ServerProfilePrompt.decide(REMOTE, null, false, QUALITY, false, false));
		assertEquals(Reason.MISSING_PROFILE, ServerProfilePrompt.decide(REMOTE, "p-deleted", false, QUALITY, false, false), "fail closed");
		assertEquals(Reason.ALREADY_ACTIVE, ServerProfilePrompt.decide(REMOTE, MAX_FPS, true, MAX_FPS, false, false));
		assertEquals(Reason.BENCHMARK, ServerProfilePrompt.decide(REMOTE, MAX_FPS, true, QUALITY, true, false));
		assertEquals(Reason.ON_BATTERY, ServerProfilePrompt.decide(REMOTE, MAX_FPS, true, BATTERY, false, true));
		for (ServerLimits.Kind kind : List.of(REMOTE, ServerLimits.Kind.LAN_GUEST, ServerLimits.Kind.REALM)) {
			assertEquals(Reason.OFFER, ServerProfilePrompt.decide(kind, MAX_FPS, true, QUALITY, false, false), kind.name());
			assertEquals(Reason.OFFER, ServerProfilePrompt.decide(kind, "p-evening", true, null, false, false), kind + ", no profile active");
		}
	}

	// Every combination of the six conditions: the first one in the documented order decides.
	@Test
	void theChecksRunInTheDocumentedOrder() {
		List<Reason> order = List.of(Reason.NO_SERVER, Reason.NO_MAPPING, Reason.MISSING_PROFILE, Reason.ALREADY_ACTIVE, Reason.BENCHMARK,
				Reason.ON_BATTERY);
		for (int bits = 0; bits < 1 << order.size(); bits++) {
			boolean noServer = (bits & 1) != 0;
			boolean noMapping = (bits & 2) != 0;
			boolean missing = (bits & 4) != 0;
			boolean alreadyActive = (bits & 8) != 0;
			boolean benchmark = (bits & 16) != 0;
			boolean onBattery = (bits & 32) != 0;
			String active = onBattery ? BATTERY : QUALITY;
			String mapped = noMapping ? null : alreadyActive ? active : MAX_FPS;
			Reason expected = Reason.OFFER;
			for (int i = 0; i < order.size(); i++) {
				if ((bits & (1 << i)) != 0) {
					expected = order.get(i);
					break;
				}
			}
			assertEquals(expected, ServerProfilePrompt.decide(noServer ? ServerLimits.Kind.SINGLEPLAYER : REMOTE, mapped, !missing, active, benchmark,
					onBattery), "conditions " + Integer.toBinaryString(bits));
		}
		assertEquals(order, List.of(Reason.values()).subList(0, order.size()), "declared in the order they're checked");
	}

	@Test
	void aRememberedBatteryProfileOnBatteryIsAlreadyActive() {
		assertEquals(Reason.ALREADY_ACTIVE, ServerProfilePrompt.decide(REMOTE, BATTERY, true, BATTERY, false, true));
		assertEquals(Reason.OFFER, ServerProfilePrompt.decide(REMOTE, BATTERY, true, QUALITY, false, true), "Battery set here, not active yet");
	}

	@Test
	void onBatteryWithoutBatteryActiveStillOffers() {
		// The battery offer (a pending unplug offer) sorts first on the notice line; both show.
		assertEquals(Reason.OFFER, ServerProfilePrompt.decide(REMOTE, MAX_FPS, true, QUALITY, false, true));
		assertEquals(Reason.OFFER, ServerProfilePrompt.decide(REMOTE, MAX_FPS, true, null, false, true));
		assertEquals(Reason.OFFER, ServerProfilePrompt.decide(REMOTE, MAX_FPS, true, BATTERY, false, false), "Battery active on AC");
	}
}
