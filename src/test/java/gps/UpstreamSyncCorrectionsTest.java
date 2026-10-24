package gps;

import gps.transport.Transport;
import gps.transport.TransportLoader;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Endpoint corrections taken from upstream Shortest Path on 2026-10-23, each confirmed against the
 * game cache, our collision map or our audit captures before it replaced the row we had carried
 * since the fork: the Lighthouse staircase landed 58 tiles south of the lighthouse (a typo), the
 * Tolna's Rift rows pointed at the wrong dungeon (our capture of the rope exit agrees with
 * upstream), the Warrens manhole ladder came back up on the wrong plane (our capture observed
 * the Port Piscarilius tile), and several stand tiles sat on the obstacle object itself.
 */
public class UpstreamSyncCorrectionsTest
{
	private static final Map<Integer, Set<Transport>> TRANSPORTS = TransportLoader.loadAllFromResources();

	@Test
	public void theLighthouseStaircaseLandsOnTheFloorBelow()
	{
		assertEquals(Set.of(at(2506, 3642, 1)), landings(at(2505, 3641, 2), "Climb-down Staircase 4570"));
	}

	@Test
	public void tolnasRiftLeadsIntoTheRiftAndTheRopeClimbsOutOfIt()
	{
		assertEquals(Set.of(at(3105, 5280, 0)), landings(at(3309, 3452, 0), "Enter Rift 13968"));
		assertEquals(Set.of(at(3312, 3450, 0)), landings(at(3105, 5280, 0), "Climb-up Rope 13999"));
		assertTrue("the old interior is gone", landings(at(3297, 9824, 0), "Climb-up Rope 13999").isEmpty());
	}

	@Test
	public void theWarrensManholeLadderComesUpInPortPiscarilius()
	{
		assertEquals(Set.of(at(1812, 3745, 0)), landings(at(1813, 10145, 0), "Climb-up Ladder 31708"));
	}

	@Test
	public void standTilesAreBesideTheirObstaclesNotOnThem()
	{
		assertFalse(landings(at(3016, 3352, 3), "Jump Gap 14919").isEmpty() && landings(at(3016, 3353, 3), "Jump Gap 14919").isEmpty());
		assertTrue("the gap tile is no longer a stand tile", landings(at(3016, 3352, 3), "Jump Gap 14919").isEmpty());
		assertEquals(Set.of(at(3180, 2435, 0)), landings(at(3180, 2431, 0), "Climb Rocks 57928"));
		assertEquals(Set.of(at(1438, 10098, 1)), landings(at(1438, 10097, 2), "Hang-over Platform edge 56371"));
		assertFalse("Arceuus minecart boards from beside the cart", TRANSPORTS.getOrDefault(at(1670, 3833, 0), Set.of()).isEmpty());
		assertTrue(TRANSPORTS.getOrDefault(at(1670, 3832, 0), Set.of()).isEmpty());
	}

	@Test
	public void boatAndCanoeLandingsAreWalkable()
	{
		Set<Integer> weiss = landings(at(2707, 3735, 0), "Weiss Boat 21176");
		assertTrue(weiss.contains(at(2849, 3967, 0)));
		assertFalse("the blocked tile north of the landing is gone", weiss.contains(at(2849, 3968, 0)));
		Set<Integer> canoe = landings(at(2439, 3135, 0), "Paddle Canoe Canoe Station 60845");
		assertTrue(canoe.contains(at(2486, 3188, 0)));
		assertFalse("the blocked tile west of the Tree Gnome Village station is gone", canoe.contains(at(2483, 3188, 0)));
	}

	private static Set<Integer> landings(int origin, String action)
	{
		Set<Integer> out = new HashSet<>();
		for (Transport transport : TRANSPORTS.getOrDefault(origin, Set.of()))
		{
			if (action.equals(transport.getObjectInfo()))
			{
				out.add(transport.getDestination());
			}
		}
		return out;
	}

	private static int at(int x, int y, int plane)
	{
		return WorldPointUtil.packWorldPoint(x, y, plane);
	}
}
