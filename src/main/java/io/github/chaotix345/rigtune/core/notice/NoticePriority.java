package io.github.chaotix345.rigtune.core.notice;

// docs/v0.4/SPEC.md C3, docs/v0.5/SPEC.md C3: declaration order is priority order (first = shown first). In memory only
// (dismissals are stored by key), so inserting a slot is compatible; NoticeBoardTest pins the whole order.
public enum NoticePriority {
	BATTERY_OFFER,
	SERVER_PROFILE,
	HELD_MOD_CHANGES,
	LAUNCHER_REPAIR,
	FIRST_RUN,
	SERVER_LIMIT,
	TRY_IT,
	BENCHMARK_REGRESSION,
	STARTUP_REGRESSION,
	HARDWARE_CHANGED,
	SETTINGS_CHANGED_OUTSIDE,
	MOD_FILES_NEWS,
	WHATS_NEW,
	BENCHMARK_STALE
}
