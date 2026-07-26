package gps.pathfinder;

import gps.Destinations;
import gps.ShortestPathConfig;
import gps.WorldPointUtil;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A blocked target on a mapped object's FOOTPRINT snaps to the object's transport origin —
 * the interactable side — not to whichever adjacent tile is walkable. Captured at the Troll
 * Stronghold south cave (gps-capture-20260725-183521): Quest Helper targeted the cave tiles,
 * the route ended one tile BEHIND the mouth, and the game walked the long way around.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ObjectTargetSnapTest
{
	// A footprint tile of Cave Entrance 3759 (3x2 at 2892,3672; mapped origins at y 3671).
	private static final int CAVE_FOOTPRINT = WorldPointUtil.packWorldPoint(2893, 3673, 0);
	private static final int BEHIND_THE_CAVE = WorldPointUtil.packWorldPoint(2892, 3674, 0);
	private static final int CAVE_FRONT = WorldPointUtil.packWorldPoint(2893, 3671, 0);

	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	@Test
	public void footprintTargetSnapsToTheMappedEntranceSide()
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();

		assertTrue("premise: the cave footprint tile is blocked",
			planning.getMap().isBlocked(2893, 3673, 0));
		assertTrue("premise: the cave's data rows make 2893,3671 a transport origin",
			planning.isTransportOrigin(CAVE_FRONT));

		Set<Integer> targets = Destinations.walkableTargets(
			planning.getMap(), CAVE_FOOTPRINT, planning::isTransportOrigin);
		assertTrue("the mapped entrance side must be targeted", targets.contains(CAVE_FRONT));
		assertFalse("the tile behind the mouth must not be", targets.contains(BEHIND_THE_CAVE));

		// Without origin data the old nearest-ring behaviour stands (no regression for
		// unmapped objects).
		Set<Integer> plain = Destinations.walkableTargets(planning.getMap(), CAVE_FOOTPRINT);
		assertTrue(plain.contains(BEHIND_THE_CAVE) || plain.contains(CAVE_FOOTPRINT));
	}
}
