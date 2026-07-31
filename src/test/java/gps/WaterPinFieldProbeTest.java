package gps;

import gps.pathfinder.DistanceField;
import gps.pathfinder.Pathfinder;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.Mockito.when;

/** MEASUREMENT, not theory: field values + search mode for land-on-island vs water-off-island. */
@RunWith(MockitoJUnitRunner.Silent.class)
public class WaterPinFieldProbeTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	private PathfinderConfig planning(int targetPacked)
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.ALL);
		when(config.useSailing()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		planning.setExtraTransports(SailingSea.seaLegTransports(targetPacked, 6));
		planning.rebuildAvailabilityWithExclusions(Set.of());
		return planning;
	}

	private void scenario(String label, int pin)
	{
		System.out.println("=== " + label + " pin " + WorldPointUtil.unpackWorldX(pin) + ","
			+ WorldPointUtil.unpackWorldY(pin) + " sailable=" + SailingSea.isSailable(pin));
		PathfinderConfig planning = planning(pin);
		System.out.println("  extras: " + SailingSea.seaLegTransports(pin, 6).size());
		long start = System.nanoTime();
		DistanceField field = DistanceField.build(planning, Set.of(pin));
		System.out.println("  field build ms: " + (System.nanoTime() - start) / 1_000_000);
		int[][] probes = {{3222, 3218}, {3029, 3217}, {2951, 3146}, {1634, 3038}};
		for (int[] p : probes)
		{
			int d = field.distance(WorldPointUtil.packWorldPoint(p[0], p[1], 0));
			System.out.println("  field@" + p[0] + "," + p[1] + ": "
				+ (d == DistanceField.UNREACHED ? "UNREACHED" : d));
		}
		// The PRODUCTION sequence: bounded field + the start-gated heuristic.
		DistanceField bounded = DistanceField.buildIfCompact(planning, Set.of(pin), 6);
		System.out.println("  bounded field null: " + (bounded == null));
		if (bounded != null)
		{
			int[][] probes2 = {{3222, 3218}, {3029, 3217}};
			for (int[] p2 : probes2)
			{
				int d = bounded.distance(WorldPointUtil.packWorldPoint(p2[0], p2[1], 0));
				System.out.println("  bounded@" + p2[0] + "," + p2[1] + ": "
					+ (d == DistanceField.UNREACHED ? "UNREACHED" : d));
			}
			gps.pathfinder.SearchHeuristic h = gps.pathfinder.SearchHeuristic.buildWithField(
				planning, bounded, WorldPointUtil.packWorldPoint(3222, 3218, 0));
			System.out.println("  heuristic null: " + (h == null));
			if (h != null)
			{
				long t0 = System.nanoTime();
				Pathfinder seedStyle = new Pathfinder(planning,
					WorldPointUtil.packWorldPoint(3212, 3428, 0), Set.of(pin),
					Integer.MAX_VALUE, h);
				seedStyle.run();
				System.out.println("  seed-style search: reached="
					+ seedStyle.getResult().isReached()
					+ " ms=" + (System.nanoTime() - t0) / 1_000_000);
			}
		}
	}

	@Test
	public void measure()
	{
		scenario("LAND on Dognose", WorldPointUtil.packWorldPoint(3048, 2648, 0));
		scenario("WATER off Dognose", WorldPointUtil.packWorldPoint(3036, 2652, 0));
		scenario("WATER mid-ocean", WorldPointUtil.packWorldPoint(2894, 2637, 0));
	}
}
