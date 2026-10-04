package dev.agentcraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AutoWorldSpecTest {
	private static AutoWorldSpec spec(Map<String, String> env) {
		return AutoWorldSpec.from(env::get);
	}

	@Test
	void defaultsAreTheFlatHq() {
		AutoWorldSpec s = spec(Map.of());
		assertEquals(HqWorld.LEVEL_NAME, s.name());
		assertEquals(AutoWorldSpec.Preset.FLAT, s.preset());
		assertEquals("agentcraft-hq".hashCode(), s.seed());
		assertTrue(s.hq());
	}

	@Test
	void namedNaturalWorldIsNotHq() {
		AutoWorldSpec s = spec(Map.of("AGENTCRAFT_AUTOWORLD_NAME", " Docs World ", "AGENTCRAFT_AUTOWORLD_PRESET", "Normal"));
		assertEquals("Docs World", s.name());
		assertEquals(AutoWorldSpec.Preset.NORMAL, s.preset());
		assertEquals(AutoWorldSpec.DEFAULT_NATURAL_SEED, s.seed());
		assertFalse(s.hq());
	}

	@Test
	void seedsParseLikeTheVanillaBox() {
		assertEquals(-12L, spec(Map.of("AGENTCRAFT_AUTOWORLD_SEED", "-12")).seed());
		assertEquals("glacier".hashCode(), spec(Map.of("AGENTCRAFT_AUTOWORLD_SEED", "glacier")).seed());
	}

	@Test
	void badValuesAreRefused() {
		assertThrows(IllegalArgumentException.class, () -> spec(Map.of("AGENTCRAFT_AUTOWORLD_PRESET", "amplified")));
		assertThrows(IllegalArgumentException.class, () -> spec(Map.of("AGENTCRAFT_AUTOWORLD_NAME", "../escape")));
		assertThrows(IllegalArgumentException.class, () -> spec(Map.of("AGENTCRAFT_AUTOWORLD_NAME", ".hidden")));
	}
}
