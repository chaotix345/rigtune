package io.github.chaotix345.rigtune.core.benchmark;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.3/SPEC.md AC8.7 (unit half): the shader advice shows exactly when the shader cost was measured, the 1% low
// with shaders is below the target, the 1% low without shaders reaches it, and the gap is at least 10%.
class ShaderAdviceTest {
	private static final double TARGET = 100;

	private static SessionResult.Cost cost(double on, double off) {
		return new SessionResult.Cost(on, on * 1.5, off, off * 1.5);
	}

	// One row per combination of (shader cost measured, 1% low with shaders below the target, 1% low without shaders at
	// or above it, gap at least 10%). "Not below and not reaching" with a 10% gap can't happen: without shaders would
	// then be below the target while with shaders isn't, a negative gap.
	private record Row(boolean measured, double on, double off, boolean below, boolean reaches, boolean gap) {
	}

	@Test
	void truthTable() {
		List<Row> rows = new ArrayList<>();
		for (boolean measured : new boolean[]{true, false}) {
			rows.add(new Row(measured, 80, 120, true, true, true));
			rows.add(new Row(measured, 95, 101, true, true, false));
			rows.add(new Row(measured, 50, 90, true, false, true));
			rows.add(new Row(measured, 88, 92, true, false, false));
			rows.add(new Row(measured, 110, 140, false, true, true));
			rows.add(new Row(measured, 150, 155, false, true, false));
			rows.add(new Row(measured, 100, 60, false, false, false));
		}
		for (Row r : rows) {
			SessionResult.Cost c = cost(r.on(), r.off());
			// The row's numbers really have the row's properties.
			assertEquals(r.below(), r.on() < TARGET, r.toString());
			assertEquals(r.reaches(), r.off() >= TARGET, r.toString());
			assertEquals(r.gap(), (r.off() - r.on()) / r.off() * 100 >= 10, r.toString());
			boolean expected = r.measured() && r.below() && r.reaches() && r.gap();
			assertEquals(expected, ShaderAdvice.costPercent(r.measured() ? c : null, TARGET).isPresent(), r.toString());
		}
		assertEquals(14, rows.size());
	}

	@Test
	void everyShowingRowIsTheOneRow() {
		int shown = 0;
		double[] ons = {50, 80, 95, 99.9, 100, 110};
		double[] offs = {60, 90, 99, 100, 101, 110, 120, 200};
		for (double on : ons) {
			for (double off : offs) {
				OptionalInt advice = ShaderAdvice.costPercent(cost(on, off), TARGET);
				boolean expected = on < TARGET && off >= TARGET && (off - on) / off * 100 >= 10;
				assertEquals(expected, advice.isPresent(), "on " + on + ", off " + off);
				if (expected) {
					shown++;
				}
			}
		}
		assertTrue(shown > 0);
	}

	@Test
	void theCostIsTheShareOfTheOneLowWithoutShaders() {
		// v0.2 evidence with MakeUp-UltraFast: 502 with shaders, 601 without (+20%), so shaders cost about 16%.
		assertEquals(OptionalInt.of(16), ShaderAdvice.costPercent(cost(502, 601), 600));
		assertEquals(OptionalInt.of(33), ShaderAdvice.costPercent(cost(80, 120), TARGET));
	}

	@Test
	void boundaries() {
		// At the target with shaders on: not below it, so nothing to advise.
		assertEquals(OptionalInt.empty(), ShaderAdvice.costPercent(cost(100, 130), TARGET));
		// Exactly the target without shaders: reaches it.
		assertEquals(OptionalInt.of(17), ShaderAdvice.costPercent(cost(83, 100), TARGET));
		// A cost of exactly 10% of the shaders-off 1% low.
		assertEquals(OptionalInt.of(10), ShaderAdvice.costPercent(cost(90, 100), 95));
		// Just under 10% (though shaders off is 10% higher).
		assertEquals(OptionalInt.empty(), ShaderAdvice.costPercent(cost(100, 110), 105));
		assertEquals(OptionalInt.empty(), ShaderAdvice.costPercent(null, TARGET));
		assertEquals(OptionalInt.empty(), ShaderAdvice.costPercent(cost(0, 120), TARGET));
	}
}
