package io.github.chaotix345.rigtune.core.stutter;

import java.util.Set;

// docs/v0.5/SPEC.md 5 (C20): the rules' stutterFixes section (RulesDocument.StutterFix). Contracts skeleton (WS-K): the
// constants the updater (tools/update_rules.py STUTTER_FIX_*) mirrors, tied by SchemaConsistencyTest. WS-S2 adds the
// validated entry (of(RulesDocument), target()).
public final class FixSpec {
	// The only feature a stutterFixes entry's `requires` may name; known to FixOffers, never to the main list or StutterAdvisor.
	public static final String FEATURE = "stutter-fix";
	// The allowlist: no fix can name another key (AC5.15).
	public static final Set<String> KEYS = Set.of("vanilla.renderDistance", "sodium.performance.chunk_build_defer_mode",
			"dh.common.multiThreading.numberOfThreads");
	// RulesDocument.StutterFix's and FixSet's fields.
	public static final Set<String> FIELDS = Set.of("requires", "adviceId", "evidence", "set");
	public static final Set<String> SET_FIELDS = Set.of("key", "value", "step", "min", "max");

	private FixSpec() {
	}
}
