package io.github.chaotix345.rigtune;

import java.util.List;

// docs/v0.5/PLAN.md contracts item 13h: the key families v0.5's features build at run time (LangCheckTest (c)). One method
// per owner, each adding only its own families from its own code's values, like LangCheckTest.families(); the unchanged
// lines between the methods keep the owners' edits apart. LangCheckTest.families() calls add(); this dispatch is frozen.
final class V05LangFamilies {
	private V05LangFamilies() {
	}

	static void add(List<LangCheckTest.Family> out) {
		launcherPolicy(out);
		launcherRepair(out);
		awareness(out);
		stutterFixes(out);
		tryIt(out);
		serverProfiles(out);
		launchAlerts(out);
		firstRun(out);
	}

	// ---- WS-L1 (P0.4: rigtune.launcher.mod_steps.*, rigtune.launcher.mod_files.*, rigtune.settings.mod_files*).

	private static void launcherPolicy(List<LangCheckTest.Family> out) {
	}

	// ---- WS-L2 (P0.4: rigtune.repair.*, the held-changes notice).

	private static void launcherRepair(List<LangCheckTest.Family> out) {
	}

	// ---- WS-W (4h rigtune.outside.*, 2L rigtune.startup.perf_counters.*, AW-1/AW-2 rigtune.awareness.*).

	private static void awareness(List<LangCheckTest.Family> out) {
	}

	// ---- WS-S2 (C20: rigtune.stutter.fix.*).

	private static void stutterFixes(List<LangCheckTest.Family> out) {
	}

	// ---- WS-T (C09: rigtune.tryit.*).

	private static void tryIt(List<LangCheckTest.Family> out) {
	}

	// ---- WS-P2 (C16: rigtune.profile.servers*, rigtune.profile.server.*).

	private static void serverProfiles(List<LangCheckTest.Family> out) {
	}

	// ---- WS-W2 (C18: rigtune.startup.regression*, rigtune.startup.notice.*).

	private static void launchAlerts(List<LangCheckTest.Family> out) {
	}

	// ---- WS-F (C02: rigtune.firstrun.*).

	private static void firstRun(List<LangCheckTest.Family> out) {
	}
}
