package dev.agentcraft.client.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.layout.Anchor;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * The agent advance must run exactly once per client tick, whether the entity tick runs (vanilla) or is
 * skipped by a culling mod (Entity Culling's tickCulling), in which case AgentManager's catch-up does it.
 */
class TickGateTest {
	@Test
	void claimIsIdempotentPerTick() {
		TickGate g = new TickGate();
		assertTrue(g.claim(5));
		assertFalse(g.claim(5));
		assertTrue(g.claim(6));
		assertFalse(g.claim(6));
	}

	@Test
	void entityTickThenCatchUpAdvancesOnce() {
		TickGate g = new TickGate();
		int[] n = {0};
		for (long t = 0; t < 10; t++) {
			assertTrue(g.fromEntityTick(t, () -> n[0]++));
			assertFalse(g.catchUp(t, () -> n[0]++));
		}
		assertEquals(10, n[0]);
		assertEquals(10, g.byEntity());
		assertEquals(0, g.byCatchUp());
	}

	@Test
	void skippedEntityTickIsCaughtUp() {
		TickGate g = new TickGate();
		int[] n = {0};
		for (long t = 0; t < 10; t++) {
			if (t % 3 != 0) { // culled two ticks out of three
				g.fromEntityTick(t, () -> n[0]++);
			}
			g.catchUp(t, () -> n[0]++);
		}
		assertEquals(10, n[0]);
		assertEquals(6, g.byEntity());
		assertEquals(4, g.byCatchUp());
	}

	/** Real walking: a culled agent ends exactly where a ticked one does, at the same pace. */
	@Test
	void frozenEntityTickStillWalksAtTheSamePace() {
		Vec3 ticked = walk(false, 40);
		Vec3 culled = walk(true, 40);
		assertEquals(ticked, culled);
		assertTrue(ticked.x - 0.5 > 2.0, "the agent walked: " + ticked);
		assertTrue(ticked.x - 0.5 <= 40 * AgentMotion.SPEED + 1e-6, "never faster than one step per tick: " + ticked);
	}

	private static Vec3 walk(boolean culled, int ticks) {
		AgentMotion m = new AgentMotion();
		Vec3[] pos = {m.placeAt(new Anchor("start", 0.5, 64, 0.5, 0f, 0f))};
		m.walkTo(new Anchor("end", 30.5, 64, 0.5, 0f, 0f), List.of(pos[0], new Vec3(30.5, 64, 0.5)));
		TickGate g = new TickGate();
		Runnable advance = () -> pos[0] = m.step(pos[0]);
		for (long t = 0; t < ticks; t++) {
			if (!culled) {
				g.fromEntityTick(t, advance);
			}
			g.catchUp(t, advance);
		}
		assertEquals(ticks, g.byEntity() + g.byCatchUp());
		return pos[0];
	}
}
