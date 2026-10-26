package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.SplitFlagMap;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * SUBSTRATE GUARD, the inland sibling of {@link OceanCollisionProbeTest}: Lake Molch's water lost
 * its blocked tile setting in the cache of 2026-10-24, as the sailing ocean's did, so a raw dump
 * lets routes walk across the lake. The tooling's LakeCollisionPatchTest seals it after every
 * collisionDump; the island and the shore the Molch boat leaves from stay walkable.
 */
public class InlandWaterCollisionTest
{
	private static final CollisionMap MAP = new CollisionMap(SplitFlagMap.fromResources());

	@Test
	public void lakeMolchsWaterIsNotWalkable()
	{
		int[][] water = {{1355, 3620}, {1390, 3615}, {1350, 3650}, {1400, 3606}, {1380, 3645}};
		for (int[] w : water)
		{
			int at = WorldPointUtil.packWorldPoint(w[0], w[1], 0);
			assertTrue("lake tile " + w[0] + "," + w[1] + " blocked", MAP.isBlocked(w[0], w[1], 0));
			assertFalse("no step east from lake tile " + w[0] + "," + w[1],
				MAP.canStep(at, WorldPointUtil.packWorldPoint(w[0] + 1, w[1], 0)));
		}
	}

	@Test
	public void molchIslandStaysWalkable()
	{
		int a = WorldPointUtil.packWorldPoint(1366, 3632, 0);
		assertFalse(MAP.isBlocked(1366, 3632, 0));
		assertTrue(MAP.canStep(a, WorldPointUtil.packWorldPoint(1367, 3632, 0)));
	}
}
