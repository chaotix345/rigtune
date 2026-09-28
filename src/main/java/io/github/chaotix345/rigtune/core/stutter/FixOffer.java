package io.github.chaotix345.rigtune.core.stutter;

import org.jspecify.annotations.Nullable;

import java.util.List;

// docs/v0.5/SPEC.md 5 (C20), docs/research/v0.5/feature-stutter-fixes.md §2.3: what the Stutter Doctor offers under one
// fired advice. Offer: a one-click change of key from `from` to `to`, now (vanilla) or at the next restart; profile, the
// name of the active saved profile that also sets the key (the offer row says so), or null. NotYet: why not (yet), with
// the reason's arguments. FixOffers computes them.
public sealed interface FixOffer permits FixOffer.Offer, FixOffer.NotYet {
	String adviceId();

	record Offer(String adviceId, String key, String from, String to, boolean now, @Nullable String profile) implements FixOffer {
		public Offer(String adviceId, String key, String from, String to, boolean now) {
			this(adviceId, key, from, to, now, null);
		}

		public Offer withProfile(@Nullable String name) {
			return new Offer(adviceId, key, from, to, now, name);
		}

		// The same change (the profile note aside).
		public boolean sameChange(Offer other) {
			return adviceId.equals(other.adviceId) && key.equals(other.key) && from.equals(other.from) && to.equals(other.to) && now == other.now;
		}
	}

	record NotYet(String adviceId, Reason reason, List<String> args) implements FixOffer {
		public NotYet {
			args = args == null ? List.of() : List.copyOf(args);
		}
	}

	// sf §2.7's rigtune.stutter.fix.not_yet.* reasons. EXCLUDED (WS-B's M4 rule): the session ran around a benchmark run or
	// while Distant Horizons generated terrain, so it can't be a comparison's before side.
	enum Reason {
		LENGTH,
		EVIDENCE,
		BENCHMARK,
		EXCLUDED,
		SERVER,
		BUSY,
		STORE
	}
}
