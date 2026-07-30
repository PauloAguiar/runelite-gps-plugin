package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.Mockito.when;

/**
 * SUBSTRATE GUARD: the sailable ocean must be BLOCKED in the shipped collision map. When it
 * is not, searches wade to water pins and sailing competes against walking on the sea — the
 * root cause behind a week of detour reports (Port Roberts pier was the one seamless
 * land-to-sea ramp). Collision regenerations must preserve the watery-overlay blocking.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class OceanCollisionProbeTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	@Test
	public void probe()
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		CollisionMap map = planning.getMap();
		int[][] spots = {
			{2692, 3142}, {2699, 3103}, {2894, 2637}, {3091, 2955},
			{2400, 2580}, {1875, 3300}, {2100, 3000}, {2686, 3162},
		};
		for (int[] s : spots)
		{
			boolean blocked = map.isBlocked(s[0], s[1], 0);
			boolean step = map.canStep(WorldPointUtil.packWorldPoint(s[0], s[1], 0),
				WorldPointUtil.packWorldPoint(s[0] + 1, s[1], 0));
			org.junit.Assert.assertTrue("sea tile " + s[0] + "," + s[1]
				+ " must be blocked for walking", blocked && !step);
		}
	}
}
