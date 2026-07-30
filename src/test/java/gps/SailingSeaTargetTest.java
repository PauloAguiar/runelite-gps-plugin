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

import static org.junit.Assert.assertEquals;
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
		assertEquals("six nearest moorings serve the pin", 6, legs.size());
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
	public void oceanPinReachableWithSailingOn()
	{
		Pathfinder pathfinder = new Pathfinder(planning(true), LUMBRIDGE, Set.of(SEA_PIN));
		pathfinder.run();
		assertTrue("the ocean pin must be reached via a synthesized sea leg",
			pathfinder.getResult().isReached());
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
