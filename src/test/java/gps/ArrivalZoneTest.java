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
 * steps means the end tile alone, and the zone is cached per path end and radius. Plan step
 * L30 adds the arrival rule itself: in the zone, or moored near a sailable target; a round trip
 * only past its turnaround, and never while its round-trip route is regenerating; an unreachable
 * one-way target never completes.
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

	private static RouteOption route(int turnaround)
	{
		List<PathStep> path = List.of(new PathStep(at(1, 1), false), new PathStep(at(10, 10), false));
		return new RouteOption(path, List.of(), List.of(), List.of(), 10, 10, true, Set.of(), List.of(), 0, turnaround);
	}

	@Test
	public void arrivalIsTheZoneOrAMooringNearASailableTarget()
	{
		Set<Integer> zone = Set.of(at(10, 10), at(11, 10));
		assertTrue(ArrivalZone.arrived(at(11, 10), zone, Set.of(at(10, 10)), t -> false, 12, null, false, 0, false));
		assertFalse(ArrivalZone.arrived(at(12, 10), zone, Set.of(at(10, 10)), t -> false, 12, null, false, 0, false));

		// A water pin has an empty zone: a boat parked within the sea distance of it has arrived.
		Set<Integer> seaTarget = Set.of(at(20, 20));
		assertTrue(ArrivalZone.arrived(at(20, 10), Set.of(), seaTarget, t -> true, 12, null, false, 0, false));
		assertFalse("13 tiles off with a 12-tile sea distance",
			ArrivalZone.arrived(at(20, 7), Set.of(), seaTarget, t -> true, 12, null, false, 0, false));
		assertFalse("a land target gets no sea tolerance",
			ArrivalZone.arrived(at(20, 10), Set.of(), seaTarget, t -> false, 12, null, false, 0, false));
	}

	@Test
	public void aRoundTripCompletesOnlyPastItsTurnaround()
	{
		Set<Integer> zone = Set.of(at(10, 10));
		RouteOption roundTrip = route(10);
		assertFalse("standing at home before the turnaround: the outbound leg",
			ArrivalZone.arrived(at(10, 10), zone, Set.of(at(10, 10)), t -> false, 12, roundTrip, true, 5, false));
		assertTrue("two steps short of the turnaround counts as reached",
			ArrivalZone.arrived(at(10, 10), zone, Set.of(at(10, 10)), t -> false, 12, roundTrip, true, 8, false));
		assertFalse("a round trip wanted but only the one-way fallback displayed: suspended",
			ArrivalZone.arrived(at(10, 10), zone, Set.of(at(10, 10)), t -> false, 12, route(-1), true, 50, false));
		assertFalse("a round trip wanted and nothing displayed yet: suspended",
			ArrivalZone.arrived(at(10, 10), zone, Set.of(at(10, 10)), t -> false, 12, null, true, 50, false));
	}

	@Test
	public void anUnreachableOneWayTargetNeverCompletes()
	{
		Set<Integer> zone = Set.of(at(10, 10));
		assertFalse(ArrivalZone.arrived(at(10, 10), zone, Set.of(at(10, 10)), t -> false, 12, route(-1), false, 0, true));
		assertTrue("the flag is a one-way judgement; a round trip past its turnaround completes",
			ArrivalZone.arrived(at(10, 10), zone, Set.of(at(10, 10)), t -> false, 12, route(10), true, 9, true));
	}
}
