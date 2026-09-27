package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.profile.ServerProfileOffers.Connection;
import io.github.chaotix345.rigtune.core.profile.ServerProfileOffers.Offer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 7 (AC7.6, AC7.10, AC7.13's guard), sp §2.1 and §2.7: the offer's state for one game session. A
// repeated JOIN with the same identity and no DISCONNECT is the same connection; a lookup that finishes after its
// connection ended offers nothing; at most one toast per server per session; an offer is retired only as it was seen.
class ServerProfileOffersTest {
	private static final ServerLimits.Kind REMOTE = ServerLimits.Kind.REMOTE;
	private static final String PLAY = "play.example.com:25565";
	private static final String KEY = "a".repeat(64);
	private static final String OTHER_KEY = "b".repeat(64);

	@Test
	void aRepeatedJoinIsTheSameConnection() {
		ServerProfileOffers offers = new ServerProfileOffers();
		Connection first = offers.joined(REMOTE, PLAY, 1000);
		assertNotNull(first);
		assertEquals(new Connection(first.id(), REMOTE, PLAY, 1000), first);
		Offer offer = offers.offer(first, KEY, "template:max_fps");
		assertNull(offers.joined(REMOTE, PLAY, 2000), "a proxy switch or reconfiguration: the same connection");
		assertSame(first, offers.current());
		assertSame(offer, offers.pending(), "its offer stays");
		Connection other = offers.joined(REMOTE, "mc.example.net:25565", 3000);
		assertNotNull(other, "another server without a DISCONNECT between is a new connection");
		assertNotEquals(first.id(), other.id());
		assertNull(offers.pending(), "the old server's offer goes with it");
		assertNull(offers.joined(REMOTE, "mc.example.net:25565", 4000));
		assertNotNull(offers.joined(ServerLimits.Kind.REALM, "mc.example.net:25565", 5000), "another kind is another place");
	}

	@Test
	void aDisconnectEndsTheConnectionAndItsOffer() {
		ServerProfileOffers offers = new ServerProfileOffers();
		Connection first = offers.joined(REMOTE, PLAY, 1000);
		assertNotNull(offers.offer(first, KEY, "template:max_fps"));
		offers.disconnected();
		assertNull(offers.current());
		assertNull(offers.pending());
		Connection again = offers.joined(REMOTE, PLAY, 2000);
		assertNotNull(again, "joining again after a DISCONNECT is a new connection");
		assertNotEquals(first.id(), again.id());
		offers.disconnected();
		offers.disconnected();
		assertNull(offers.current());
	}

	@Test
	void aLookupThatFinishesAfterADisconnectOffersNothing() {
		ServerProfileOffers offers = new ServerProfileOffers();
		Connection first = offers.joined(REMOTE, PLAY, 1000);
		offers.disconnected();
		assertNull(offers.offer(first, KEY, "template:max_fps"));
		assertNull(offers.pending());
		Connection second = offers.joined(REMOTE, PLAY, 2000);
		assertNull(offers.offer(first, KEY, "template:max_fps"), "the first connection's late lookup, now that a second one is current");
		assertNull(offers.pending());
		Offer offer = offers.offer(second, KEY, "template:max_fps");
		assertEquals(new Offer(second, KEY, "template:max_fps"), offer);
		assertEquals("server-profile:2000", offer.noticeKey());
	}

	@Test
	void oneToastPerServerPerSession() {
		ServerProfileOffers offers = new ServerProfileOffers();
		Offer first = offers.offer(offers.joined(REMOTE, PLAY, 1000), KEY, "template:max_fps");
		assertTrue(offers.toast(first));
		assertFalse(offers.toast(first), "once");
		offers.disconnected();
		Offer second = offers.offer(offers.joined(REMOTE, PLAY, 2000), KEY, "template:max_fps");
		assertFalse(offers.toast(second), "the same server in the same session: the notice only");
		offers.disconnected();
		Offer other = offers.offer(offers.joined(REMOTE, "mc.example.net:25565", 3000), OTHER_KEY, "template:quality");
		offers.retire(other);
		assertFalse(offers.toast(other), "a retired offer shows no toast");
		Offer again = offers.offer(offers.current(), OTHER_KEY, "template:quality");
		assertTrue(offers.toast(again), "another server's first toast");
		offers.disconnected();
		assertFalse(offers.toast(again), "not after its connection ended");
	}

	@Test
	void retireOnlyWhatWasSeen() {
		ServerProfileOffers offers = new ServerProfileOffers();
		Connection connection = offers.joined(REMOTE, PLAY, 1000);
		Offer seen = offers.offer(connection, KEY, "template:max_fps");
		Offer newer = offers.offer(connection, KEY, "template:quality");
		assertFalse(offers.retire(seen), "a newer offer came meanwhile");
		assertSame(newer, offers.pending());
		assertFalse(offers.retire(null));
		assertTrue(offers.retire(newer));
		assertNull(offers.pending());
		assertFalse(offers.retire(newer));
	}

	@Test
	void forgettingAKeyRetiresItsOffer() {
		ServerProfileOffers offers = new ServerProfileOffers();
		Connection connection = offers.joined(REMOTE, PLAY, 1000);
		Offer offer = offers.offer(connection, KEY, "template:max_fps");
		offers.forgot(OTHER_KEY);
		assertSame(offer, offers.pending(), "another server forgotten");
		offers.forgot(KEY);
		assertNull(offers.pending());
		offers.offer(connection, KEY, "template:max_fps");
		offers.forgot(null);
		assertNull(offers.pending(), "Forget all");
	}

	@Test
	void theOwnWorldAndAnUnrecognisedServerAreConnectionsButNeverOffered() {
		ServerProfileOffers offers = new ServerProfileOffers();
		Connection own = offers.joined(ServerLimits.Kind.SINGLEPLAYER, null, 1000);
		assertNotNull(own);
		assertFalse(own.offerable());
		assertNull(offers.offer(own, KEY, "template:max_fps"));
		assertNull(offers.joined(ServerLimits.Kind.SINGLEPLAYER, null, 2000), "the same world");
		Connection unrecognised = offers.joined(REMOTE, null, 3000);
		assertNotNull(unrecognised);
		assertFalse(unrecognised.offerable());
		assertNull(offers.offer(unrecognised, KEY, "template:max_fps"));
		assertNull(offers.pending());
		assertTrue(offers.joined(REMOTE, PLAY, 4000).offerable());
		assertNull(offers.offer(offers.current(), KEY, null), "no profile, no offer");
		assertNull(offers.offer(null, KEY, "template:max_fps"));
	}
}
