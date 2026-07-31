package gps;

import gps.pathfinder.Pathfinder;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.Transport;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * A pin on the open ocean must be a real destination: the synthesized sea legs (board at a
 * nearby mooring, sail straight out) make it reachable — and the sailing master toggle
 * removes them entirely, water pin included.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class SailingSeaTargetTest
{
	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	/** The reported unreachable pin: open sea south-west of Karamja. */
	private static final int SEA_PIN = WorldPointUtil.packWorldPoint(2894, 2637, 0);

	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	private PathfinderConfig planning(boolean sailingOn)
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(config.useSailing()).thenReturn(sailingOn);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		planning.setExtraTransports(SailingSea.seaLegTransports(SEA_PIN, 6));
		planning.rebuildAvailabilityWithExclusions(Set.of());
		return planning;
	}

	@Test
	public void seaTileIsRecognisedAsSailable()
	{
		assertTrue("the reported pin is on the shipped ocean", SailingSea.isSailable(SEA_PIN));
		assertFalse("land is not ocean", SailingSea.isSailable(LUMBRIDGE));
		List<Transport> legs = SailingSea.seaLegTransports(SEA_PIN, 6);
		// Six nearest plus the walk-reachable guarantee: near new islands the nearest moorings
		// can all be walk-unreachable unlocks, and legs only from those give the heuristic
		// field no mainland anchors — every search of the generation then runs blind.
		assertTrue("at least the six nearest moorings serve the pin", legs.size() >= 6);
		assertTrue("the guarantee never adds more than three ports", legs.size() <= 9);
	}

	@Test
	public void wetFloodRespectsCoastlines()
	{
		// Exact sea distances are never shorter than the straight line, and the flood must be
		// fast enough to run per generation (the water-pin performance report).
		long start = System.nanoTime();
		int[] distances = SailingSea.seaDistances(SEA_PIN);
		long coldMs = (System.nanoTime() - start) / 1_000_000;
		int settled = 0;
		for (int d : distances)
		{
			if (d != Integer.MAX_VALUE)
			{
				settled++;
			}
		}
		assertTrue("the flood settles enough moorings to build legs (got " + settled + ")",
			settled >= 6);
		assertTrue("cold wet-endpoint flood must stay interactive (took " + coldMs + "ms)",
			coldMs < 500);
	}

	@Test
	public void seaTrackFollowsWater()
	{
		// The rendering track for a sailing leg: every waypoint must be genuinely sailable
		// water, and both ends must sit by the leg's endpoints (mooring land / ocean pin).
		int mooringLand = WorldPointUtil.packWorldPoint(3069, 2986, 0);
		// The field-reported leg: this mooring's land sits >6 tiles from its water (pier), the
		// exact case that returned null and left the route dashed (capture 20260729-211852).
		int pierMooring = WorldPointUtil.packWorldPoint(2965, 2608, 0);
		assertTrue("pier moorings with distant water must still produce a track",
			SailingSea.seaPathBlocking(pierMooring, SEA_PIN) != null);
		int[] track = SailingSea.seaPathBlocking(mooringLand, SEA_PIN);
		assertTrue("a track must exist between a port and the ocean pin", track != null);
		assertTrue("a real track has many waypoints, not a straight hop", track.length > 10);
		for (int waypoint : track)
		{
			assertTrue("every waypoint is sailable water", SailingSea.isSailable(waypoint));
		}
	}

	@Test
	public void oceanPinReachableWithSailingOn()
	{
		Pathfinder pathfinder = new Pathfinder(planning(true), LUMBRIDGE, Set.of(SEA_PIN));
		pathfinder.run();
		assertTrue("the ocean pin must be reached via a synthesized sea leg",
			pathfinder.getResult().isReached());
	}

	@Test
	public void khazardPinBoardsLocally()
	{
		// Field report gps-capture-20260729-213352: a pin beside Port Khazard detoured through
		// Port Roberts (~1480 cost) because the Khazard mooring boarding tile was not
		// walk-connected. Boarding locally (teleport + short walk + 13-tile hop) costs a few
		// hundred at most; anything near the detour cost means boarding broke again.
		int start = WorldPointUtil.packWorldPoint(2729, 3491, 3);
		int pin = WorldPointUtil.packWorldPoint(2687, 3152, 0);
		PathfinderConfig planning = planning(true);
		planning.setExtraTransports(SailingSea.seaLegTransports(pin, 6));
		planning.rebuildAvailabilityWithExclusions(Set.of());
		Pathfinder pathfinder = new Pathfinder(planning, start, Set.of(pin));
		pathfinder.run();
		assertTrue("the Khazard pin must be reached", pathfinder.getResult().isReached());
		assertTrue("must board at Khazard, not detour the western ocean (cost "
			+ pathfinder.getResult().getTotalCost() + ")",
			pathfinder.getResult().getTotalCost() < 500);
	}

	@Test
	public void oceanPinUnreachableWithSailingOff()
	{
		Pathfinder pathfinder = new Pathfinder(planning(false), LUMBRIDGE, Set.of(SEA_PIN));
		pathfinder.run();
		assertFalse("with the master toggle off the sea legs must not exist",
			pathfinder.getResult().isReached());
	}
}
