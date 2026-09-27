package io.github.chaotix345.rigtune.core.stutter;

import java.util.List;

// docs/v0.5/SPEC.md 5 (C20), docs/research/v0.5/feature-stutter-fixes.md §2.3: what the Stutter Doctor offers under one
// fired advice. Offer: a one-click change of key from `from` to `to`, now (vanilla) or at the next restart. NotYet: why
// not (yet), with the reason's arguments. Contracts skeleton (WS-K); WS-S2 computes them (FixOffers).
public sealed interface FixOffer permits FixOffer.Offer, FixOffer.NotYet {
	String adviceId();

	record Offer(String adviceId, String key, String from, String to, boolean now) implements FixOffer {
	}

	record NotYet(String adviceId, Reason reason, List<String> args) implements FixOffer {
		public NotYet {
			args = args == null ? List.of() : List.copyOf(args);
		}
	}

	// sf §2.7's rigtune.stutter.fix.not_yet.* reasons.
	enum Reason {
		LENGTH,
		EVIDENCE,
		BENCHMARK,
		SERVER,
		BUSY,
		STORE
	}
}
