package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathStep;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan step L19: the arrival zone, out of the plugin class. The flood follows the collision
 * map's movement rules (a blocked cardinal also closes both diagonals beside it), no map or no
 * steps means the end tile alone, and the zone is cached per path end and radius.
 */
public class ArrivalZoneTest
{
	private static int at(int x, int y)
	{
		return WorldPointUtil.packWorldPoint(x, y, 0);
	}

	private static CollisionMap openMap()
	{
		CollisionMap map = mock(CollisionMap.class);
		when(map.n(anyInt(), anyInt(), anyInt())).thenReturn(true);
		when(map.s(anyInt(), anyInt(), anyInt())).thenReturn(true);
		when(map.e(anyInt(), anyInt(), anyInt())).thenReturn(true);
		when(map.w(anyInt(), anyInt(), anyInt())).thenReturn(true);
		return map;
	}

	@Test
	public void theFloodFollowsTheMovementRules()
	{
		assertEquals("open ground: the 3x3 block", 9, ArrivalZone.flood(openMap(), at(10, 10), 1).size());

		CollisionMap wallEast = openMap();
		when(wallEast.e(10, 10, 0)).thenReturn(false);
		Set<Integer> zone = ArrivalZone.flood(wallEast, at(10, 10), 1);
		assertEquals("east and both diagonals beside it are closed", 6, zone.size());
		assertFalse(zone.contains(at(11, 10)));
		assertFalse(zone.contains(at(11, 11)));
		assertFalse(zone.contains(at(11, 9)));
		assertTrue(zone.contains(at(9, 11)));
	}

	@Test
	public void noMapOrNoStepsIsTheEndTileAlone()
	{
		assertEquals(Set.of(at(10, 10)), ArrivalZone.flood(null, at(10, 10), 3));
		assertEquals(Set.of(at(10, 10)), ArrivalZone.flood(openMap(), at(10, 10), 0));
	}

	@Test
	public void theZoneIsCachedPerEndAndRadius()
	{
		ArrivalZone arrival = new ArrivalZone(ArrivalZoneTest::openMap);
		List<PathStep> path = List.of(new PathStep(at(1, 1), false), new PathStep(at(10, 10), false));
		Set<Integer> first = arrival.tiles(path, 1);
		assertSame(first, arrival.tiles(List.of(new PathStep(at(10, 10), false)), 1));
		assertEquals(25, arrival.tiles(path, 2).size());
		assertTrue(arrival.tiles(path, -1).isEmpty());
		assertTrue(arrival.tiles(List.of(), 1).isEmpty());
		assertTrue(arrival.tiles(null, 1).isEmpty());
	}
}
