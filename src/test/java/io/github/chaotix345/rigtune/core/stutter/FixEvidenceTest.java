package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.rules.Truth;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

// AC5.4 (the core half): causeSpikesAtLeast counts the spikes in which a cause claimed at least half of the lost time, and
// is UNKNOWN for what wasn't measured or can't be read. Wave B wires it into StutterFacts and ConditionEvaluator.
class FixEvidenceTest {
	private static final long MS = StutterAnalyzer.MS;

	// A spike losing `lost` ms, with the claims given in ms and the rest unexplained.
	private static Attributor.Attribution spike(long lost, Map<String, Long> claimsMs) {
		SpikeDetector.Spike s = new SpikeDetector.Spike(1_000 * MS, (lost + 10) * MS, 10 * MS);
		Map<String, Long> claims = new HashMap<>();
		long claimed = 0;
		for (Map.Entry<String, Long> e : claimsMs.entrySet()) {
			claims.put(e.getKey(), e.getValue() * MS);
			claimed += e.getValue() * MS;
		}
		return new Attributor.Attribution(s, claims, List.of(), Set.of(), s.lost() - claimed);
	}

	@Test
	void aCauseCountsWhenItClaimedAtLeastHalfOfTheSpike() {
		List<Attributor.Attribution> spikes = new ArrayList<>();
		spikes.add(spike(100, Map.of(Attributor.CHUNK_BUILD, 40L, Attributor.GC, 30L)));
		spikes.add(spike(100, Map.of(Attributor.CHUNK_BUILD, 60L)));
		spikes.add(spike(100, Map.of(Attributor.CHUNK_BUILD, 50L, Attributor.GC, 50L)));
		spikes.add(spike(100, Map.of(Attributor.GC, 10L)));
		Map<String, Integer> counts = FixEvidence.dominatedSpikes(spikes);
		assertEquals(2, counts.get(Attributor.CHUNK_BUILD));
		assertEquals(1, counts.get(Attributor.GC));
		// The unexplained part counts as "unknown": the first spike's 30 ms don't, the last one's 90 ms do.
		assertEquals(1, counts.get(Attributor.UNKNOWN));
		assertEquals(null, counts.get(Attributor.CHUNK_LOAD));
	}

	@Test
	void noSpikesCountNothing() {
		assertEquals(Map.of(), FixEvidence.dominatedSpikes(List.of()));
	}

	@Test
	void trueAndFalse() {
		Map<String, Integer> counts = Map.of(Attributor.CHUNK_BUILD, 5, Attributor.GC, 1);
		assertEquals(Truth.TRUE, FixEvidence.causeSpikesAtLeast(Map.of(Attributor.CHUNK_BUILD, "5"), counts, Set.of()));
		assertEquals(Truth.FALSE, FixEvidence.causeSpikesAtLeast(Map.of(Attributor.CHUNK_BUILD, "6"), counts, Set.of()));
		// A measurable cause that dominated nothing is 0.
		assertEquals(Truth.FALSE, FixEvidence.causeSpikesAtLeast(Map.of(Attributor.CHUNK_LOAD, "1"), counts, Set.of()));
		assertEquals(Truth.TRUE, FixEvidence.causeSpikesAtLeast(Map.of(Attributor.CHUNK_LOAD, "0"), counts, Set.of()));
		assertEquals(Truth.TRUE, FixEvidence.causeSpikesAtLeast(Map.of(Attributor.CHUNK_BUILD, "5.0"), counts, Set.of()));
		// Every entry must hold.
		assertEquals(Truth.FALSE, FixEvidence.causeSpikesAtLeast(Map.of(Attributor.CHUNK_BUILD, "5", Attributor.GC, "2"), counts, Set.of()));
		assertEquals(Truth.TRUE, FixEvidence.causeSpikesAtLeast(Map.of(), counts, Set.of()));
	}

	@Test
	void unknownForWhatWasntMeasuredOrCantBeRead() {
		Map<String, Integer> counts = Map.of(Attributor.CHUNK_BUILD, 5);
		assertEquals(Truth.UNKNOWN, FixEvidence.causeSpikesAtLeast(Map.of(Attributor.CHUNK_BUILD, "1"), counts, Set.of(Attributor.CHUNK_BUILD)));
		// render never claims milliseconds, so it's always unmeasured in the facts; a cause this version doesn't know.
		assertEquals(Truth.UNKNOWN, FixEvidence.causeSpikesAtLeast(Map.of("shaders", "1"), counts, Set.of()));
		assertEquals(Truth.UNKNOWN, FixEvidence.causeSpikesAtLeast(Map.of(Attributor.CHUNK_BUILD, "2.5"), counts, Set.of()));
		assertEquals(Truth.UNKNOWN, FixEvidence.causeSpikesAtLeast(Map.of(Attributor.CHUNK_BUILD, "-1"), counts, Set.of()));
		assertEquals(Truth.UNKNOWN, FixEvidence.causeSpikesAtLeast(Map.of(Attributor.CHUNK_BUILD, "five"), counts, Set.of()));
		Map<String, String> nullValue = new HashMap<>();
		nullValue.put(Attributor.CHUNK_BUILD, null);
		assertEquals(Truth.UNKNOWN, FixEvidence.causeSpikesAtLeast(nullValue, counts, Set.of()));
		// FALSE wins over UNKNOWN (Kleene AND), so an entry that fails still fails.
		assertEquals(Truth.FALSE, FixEvidence.causeSpikesAtLeast(Map.of(Attributor.CHUNK_BUILD, "9", "shaders", "1"), counts, Set.of()));
	}
}
