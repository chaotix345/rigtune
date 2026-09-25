package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.core.rules.RulesDocument.SettingLabel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameStateTest {
	private static final String LOD = "dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius";
	private static final String THREADS = "sodium.performance.chunk_builder_threads";

	private static SettingLabel label(String name, Map<String, String> values) {
		SettingLabel label = new SettingLabel();
		label.name = name;
		label.values.putAll(values);
		return label;
	}

	private static final Map<String, SettingLabel> LABELS = Map.of(LOD, label("LOD distance", Map.of()),
			THREADS, label("Chunk builder threads", Map.of("0", "Auto")));

	// Phase 5 finding 7: the Undo screen showed "sodium.performance.use_fog_occlusion: ON → OFF".
	@Test
	void modKeysAreNamedLikeTheRecommendations() {
		assertEquals("Sodium: Use fog occlusion", GameState.label("sodium.performance.use_fog_occlusion", Map.of(), LABELS));
		assertEquals("Distant Horizons: LOD distance", GameState.label(LOD, Map.of(), LABELS));
		assertEquals("Sodium: Chunk builder threads", GameState.label(THREADS, Map.of(), LABELS));
	}

	@Test
	void vanillaKeysKeepTheOptionCaption() {
		assertEquals("Entity Shadows", GameState.label("vanilla.entityShadows", Map.of("entityShadows", "Entity Shadows"), LABELS));
		assertEquals("someOption", GameState.label("vanilla.someOption", Map.of(), LABELS));
	}

	@Test
	void labelledValuesUseTheirLabel() {
		assertEquals("Auto", GameState.valueLabel(THREADS, "0.0", LABELS));
		assertEquals("4", GameState.valueLabel(THREADS, "4", LABELS));
		assertEquals("256", GameState.valueLabel(LOD, "256", LABELS));
	}
}
