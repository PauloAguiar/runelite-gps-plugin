package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathStep;
import gps.pathfinder.Pathfinder;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.Transport;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Meaty Aura Logist, the sea charting weather troll, stands on shores and islets and is talked to
 * from the boat. Quest Helper sends her own tile, so routes landed at the nearest port and walked
 * (or walked "as close as possible" when her islet has no landing). Each of her 31 spots snaps to
 * the water beside her (destination-remaps.tsv), which the boat routes sail to.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class MeatyAuraLogistTest
{
	/** Quest Helper's 31 tiles (ChartingTasksData) plus the three the wiki's sea chart data places elsewhere. */
	private static final int[][] SPOTS = {
		{1188, 2822}, {1651, 3461}, {1714, 3193}, {1779, 2940}, {1817, 3965}, {1867, 3458}, {1881, 2445},
		{1928, 2791}, {1959, 4065}, {2082, 2590}, {2118, 3159}, {2130, 3395}, {2140, 2806}, {2149, 3068},
		{2280, 3518}, {2335, 2921}, {2341, 3790}, {2447, 3995}, {2498, 3546}, {2542, 2541}, {2594, 3644},
		{2600, 3908}, {2638, 3009}, {2656, 3195}, {2793, 4020}, {2864, 2311}, {2903, 2698}, {2912, 3213},
		{2951, 3962}, {2979, 2905}, {3321, 2327},
		{2283, 3520}, {2600, 3910}, {1958, 4065},
	};

	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	@Test
	public void everySpotHasWaterBesideHer()
	{
		CollisionMap map = map();
		for (int[] spot : SPOTS)
		{
			int tile = WorldPointUtil.packWorldPoint(spot[0], spot[1], 0);
			boolean water = false;
			for (int target : Destinations.walkableTargets(map, tile))
			{
				// Three tiles: the widest gap between the wiki's and Quest Helper's tile for one spot.
				water |= wet(map, target) && WorldPointUtil.distanceBetween(target, tile) <= 3;
			}
			assertTrue("no water target beside " + spot[0] + "," + spot[1], water);
		}
	}

	/** gps-capture-20261126-234245: her islet south-east of Wintumber Island has no landing. */
	@Test
	public void sailsToHerIsletSouthEastOfWintumberIsland()
	{
		assertSailsToHer(WorldPointUtil.packWorldPoint(1942, 2653, 0), WorldPointUtil.packWorldPoint(2082, 2590, 0));
	}

	/**
	 * gps-capture-20261126-234817: aboard a few tiles off her on the Isle of Souls' south-west coast,
	 * the route disembarked 140 tiles east and walked back.
	 */
	@Test
	public void sailsToHerOnTheIsleOfSoulsCoast()
	{
		assertSailsToHer(WorldPointUtil.packWorldPoint(2092, 2791, 0), WorldPointUtil.packWorldPoint(2140, 2806, 0));
	}

	/** Routes from the boat at {@code start} as the route service does, and ends on the water beside her. */
	private void assertSailsToHer(int start, int npc)
	{
		assertTrue("the test start must be on the water", SailingSea.isSailable(start));
		when(config.calculationCutoff()).thenReturn(120);
		when(config.useSailing()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		Set<Integer> ends = Destinations.externalTargets(Set.of(npc), planning.getMap(), null);
		Set<Integer> wet = SailingSea.waterPins(planning.getMap(), ends, true);
		List<Transport> legs = new ArrayList<>();
		for (int target : wet)
		{
			legs.addAll(SailingSea.seaLegTransports(target, 6));
		}
		legs.addAll(SailingSea.aboardLegTransports(start, wet));
		planning.setExtraTransports(legs);
		planning.rebuildAvailabilityWithExclusions(Set.of());
		Pathfinder pathfinder = new Pathfinder(planning, start, ends);
		pathfinder.run();
		assertTrue("must reach her", pathfinder.getResult().isReached());
		List<PathStep> path = pathfinder.getPath();
		int last = path.get(path.size() - 1).getPackedPosition();
		assertTrue("must end on the water beside her, not ashore: " + WorldPointUtil.unpackWorldX(last) + ","
			+ WorldPointUtil.unpackWorldY(last), wet.contains(last));
		assertTrue("one sail, no disembark and walk: " + path.size() + " steps", path.size() == 2);
	}

	private static CollisionMap map()
	{
		return new CollisionMap(gps.pathfinder.SplitFlagMap.fromResources());
	}

	private static boolean wet(CollisionMap map, int packed)
	{
		return SailingSea.isSailable(packed) && map.isBlocked(WorldPointUtil.unpackWorldX(packed),
			WorldPointUtil.unpackWorldY(packed), 0);
	}
}
