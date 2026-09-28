package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.profile.ServerProfilePrompt.Reason;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

// docs/v0.5/SPEC.md 7 (C16), sp §2.1 and §2.7: the per-server offer's state for one game session, kept by
// ServerProfileService. JOIN (render thread) starts a connection, except a repeated JOIN with the same kind and address
// and no DISCONNECT between (a proxy's backend switch or a reconfiguration may fire JOIN again: the same connection).
// The join lookup (Probes.EXECUTOR) may offer only while its connection is still the current one, so a lookup that
// finishes after a DISCONNECT offers nothing. One offer is pending at a time and is retired only as it was seen (a newer
// one stays): kept to show or to hold (kept), then re-decided whenever a screen asks for its notice (now, settle). Each
// server (its entry key) gets at most one toast per game session. The address stays in memory: it's never stored,
// logged or shown (Connection's toString leaves it out).
public final class ServerProfileOffers {
	// A pending offer, re-decided: its notice shows, it's held (kept, not shown), or it's retired.
	public enum Now { SHOW, HOLD, RETIRE }

	// id: this session's connection count; address: normalised (ServerLimitsStore), null when unrecognised.
	public record Connection(int id, ServerLimits.Kind kind, @Nullable String address, long joinedAtMillis) {
		// Only remote servers, LAN guests and Realms get offers: never the own world, an Open-to-LAN host or the
		// benchmark world (SINGLEPLAYER), nor a server whose address can't be read.
		public boolean offerable() {
			return kind != ServerLimits.Kind.SINGLEPLAYER && address != null;
		}

		@Override
		public String toString() {
			return "Connection[id=" + id + ", kind=" + kind + ", joinedAtMillis=" + joinedAtMillis + "]";
		}
	}

	// key: the server's entry key in server-profiles.json; profile: the profile id set for it.
	public record Offer(Connection connection, String key, String profile) {
		// Per join, so its × hides it for this connection only.
		public String noticeKey() {
			return ServerProfilePrompt.KEY_PREFIX + connection.joinedAtMillis();
		}
	}

	// The join lookup keeps an offer to show it now, or to hold it while a benchmark runs or on battery power with the
	// Battery profile on (its notice shows once that ends, still on this server; no toast then).
	public static boolean kept(Reason reason) {
		return reason == Reason.OFFER || reason == Reason.BENCHMARK || reason == Reason.ON_BATTERY;
	}

	// stillSet: the server's entry still maps to the offer's profile (not forgotten, nor set to another one meanwhile).
	public static Now now(Reason reason, boolean stillSet) {
		if (!stillSet) {
			return Now.RETIRE;
		}
		return switch (reason) {
			case OFFER -> Now.SHOW;
			case BENCHMARK, ON_BATTERY -> Now.HOLD;
			default -> Now.RETIRE;
		};
	}

	private final AtomicReference<@Nullable Offer> offer = new AtomicReference<>();
	private final Set<String> toasted = new HashSet<>();
	private volatile @Nullable Connection current;
	private int connections;

	// JOIN: the new connection, or null when this is the current one again.
	public synchronized @Nullable Connection joined(ServerLimits.Kind kind, @Nullable String address, long joinedAtMillis) {
		Connection before = current;
		if (before != null && before.kind() == kind && Objects.equals(before.address(), address)) {
			return null;
		}
		Connection next = new Connection(++connections, kind, address, joinedAtMillis);
		current = next;
		offer.set(null);
		return next;
	}

	public synchronized void disconnected() {
		current = null;
		offer.set(null);
	}

	public @Nullable Connection current() {
		return current;
	}

	// The join lookup's answer: the pending offer, or null when that connection is no longer the current one (or can't
	// have offers).
	public synchronized @Nullable Offer offer(@Nullable Connection connection, @Nullable String key, @Nullable String profile) {
		if (connection == null || connection != current || !connection.offerable() || key == null || profile == null) {
			return null;
		}
		Offer next = new Offer(connection, key, profile);
		offer.set(next);
		return next;
	}

	public @Nullable Offer pending() {
		return offer.get();
	}

	// Clears the pending offer only if it is still `seen`.
	public boolean retire(@Nullable Offer seen) {
		return seen != null && offer.compareAndSet(seen, null);
	}

	// now(...) applied to a pending offer: true when its notice shows; a retired one is cleared (only as it was seen).
	public boolean settle(Offer offer, Reason reason, boolean stillSet) {
		Now choice = now(reason, stillSet);
		if (choice == Now.RETIRE) {
			retire(offer);
		}
		return choice == Now.SHOW;
	}

	// A server was forgotten (key null: all of them): its pending offer goes.
	public void forgot(@Nullable String key) {
		Offer seen = offer.get();
		if (seen != null && (key == null || key.equals(seen.key()))) {
			retire(seen);
		}
	}

	// Whether this offer's toast shows now: it's still pending on the current connection, and its server hasn't had one
	// this session.
	public synchronized boolean toast(@Nullable Offer shown) {
		return shown != null && offer.get() == shown && current == shown.connection() && toasted.add(shown.key());
	}

	// Whether this server's toast was shown this session (ServerProfilesGameTest reads it: the live toast may be gone).
	public synchronized boolean toasted(String key) {
		return toasted.contains(key);
	}
}
