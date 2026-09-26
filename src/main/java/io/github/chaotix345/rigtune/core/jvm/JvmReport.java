package io.github.chaotix345.rigtune.core.jvm;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// What RigTune knows about the running JVM (docs/v0.4/SPEC.md 6, C4): the JVM & memory screen, the share report's Java
// line and the jvm- facts. Never holds raw JVM arguments: findings carry flag names only. available = the HotSpot check
// ran (JvmFacts.PROBED is in facts); javaVersion null = nothing read yet.
public record JvmReport(boolean available, @Nullable String javaVersion, @Nullable String vendor, @Nullable JvmCollector collector,
		boolean collectorTyped, long maxHeapMb, long initialHeapMb, List<JvmFinding> findings, Set<String> facts) {
	public static final JvmReport UNAVAILABLE = new JvmReport(false, null, null, null, false, -1, -1, List.of(), Set.of());
	private static final String ADVICE = "advice:";

	public JvmReport {
		findings = findings == null ? List.of() : List.copyOf(findings);
		facts = facts == null ? Set.of() : Set.copyOf(facts);
	}

	public @Nullable String collectorName() {
		return collector == null ? null : collector.displayName();
	}

	// The flag names for the "Found in your Java arguments" line under a jvm- advice (with or without "advice:"): the
	// findings whose kind names that advice, and for an advice about a collector ("jvm-zgc-…") the flag that selected it
	// when it was typed. Empty for anything else.
	public List<String> flagsFor(@Nullable String adviceId) {
		String id = adviceId != null && adviceId.startsWith(ADVICE) ? adviceId.substring(ADVICE.length()) : adviceId;
		if (id == null || !id.startsWith(JvmFacts.PREFIX)) {
			return List.of();
		}
		Set<String> flags = new LinkedHashSet<>();
		for (JvmFinding finding : findings) {
			if (finding.kind().adviceId().equals(id)) {
				flags.add(finding.flag());
			}
		}
		if (collector != null && collector.flag() != null && collectorTyped
				&& id.startsWith(JvmFacts.PREFIX + collector.name().toLowerCase(Locale.ROOT) + "-")) {
			flags.add(collector.flag());
		}
		return List.copyOf(flags);
	}

	// Two or more -Xmx settings: in the Modrinth App and GDLauncher the typed one overrides the memory slider.
	public boolean xmxDuplicate() {
		return facts.contains(JvmFacts.XMX_DUPLICATE);
	}
}
