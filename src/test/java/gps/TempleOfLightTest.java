package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.Pathfinder;
import gps.pathfinder.SplitFlagMap;
import gps.pathfinder.TestPathfinderConfig;
import java.lang.reflect.Proxy;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertTrue;

/**
 * The Temple of Light (Mourning's End Part II) is sealed in the cache: its tunnel gate keeps its
 * collision when open, and the four doorways of the top floor's corridor ring hold a nameless wall
 * object (3550) with no door definition, so the game spawns the doors itself and the shipped map
 * sees walls. Both are walk-through edges in transports.tsv. Capture gps-capture-20261003-154550:
 * Quest Helper's target 1860,4665,2 was unreachable from 1869,4651,2, fourteen tiles away.
 */
public class TempleOfLightTest
{
	private static final int TOP_FLOOR_TARGET = WorldPointUtil.packWorldPoint(1860, 4665, 2);

	@Test
	public void theTopFloorDoorsLetARouteReachTheNorthWestPocket()
	{
		assertTrue("1869,4651,2 -> 1860,4665,2 through two doors of the corridor ring",
			reached(WorldPointUtil.packWorldPoint(1869, 4651, 2), TOP_FLOOR_TARGET));
		assertTrue("the south doors: 1869,4651,2 -> 1869,4621,2",
			reached(WorldPointUtil.packWorldPoint(1869, 4651, 2), WorldPointUtil.packWorldPoint(1869, 4621, 2)));
	}

	@Test
	public void theWayInReachesTheTopFloor()
	{
		assertTrue("from the Mourner Tunnels side of the gate, 1918,4639,0, up to 1860,4665,2",
			reached(WorldPointUtil.packWorldPoint(1918, 4639, 0), TOP_FLOOR_TARGET));
	}

	/**
	 * The audit captures of 2026-09-07 and 2026-10-03, promoted: the middle floor's west obstacle course
	 * (low wall 10035, rock 10036, rope 10040), the ladders between the middle and top floors (9978/9979),
	 * the north staircase both ways, the wall support's east end and the Death Altar's tunnel (9977/9974).
	 */
	@Test
	public void thePromotedCapturesConnectTheTemple()
	{
		int[][] pairs = {
			{1918, 4639, 0, 1884, 4620, 1}, // gate side to the middle floor east of the low wall
			{1884, 4620, 1, 1878, 4620, 0}, // over the low wall, down the rock
			{1878, 4620, 0, 1876, 4619, 1}, // back up the rope
			{1918, 4639, 0, 1898, 4666, 2}, // gate side to the top of the north ladder
			{1918, 4639, 0, 1898, 4612, 2}, // gate side to the top of the south ladder
			{1898, 4666, 2, 1869, 4651, 2}, // north ladder top to the capture's start
			{1890, 4645, 2, 1890, 4640, 1}, // north staircase down
			{1918, 4639, 0, 1857, 4639, 0}, // gate side to the Death Altar room
			{1857, 4639, 0, 2311, 9793, 0}, // the altar's tunnel out to the cave
			{2311, 9793, 0, 1860, 4665, 2}, // and from the cave all the way to the top floor
		};
		for (int[] p : pairs)
		{
			assertTrue(p[0] + "," + p[1] + "," + p[2] + " -> " + p[3] + "," + p[4] + "," + p[5],
				reached(WorldPointUtil.packWorldPoint(p[0], p[1], p[2]), WorldPointUtil.packWorldPoint(p[3], p[4], p[5])));
		}
	}

	private static boolean reached(int start, int target)
	{
		final Thread clientThread = Thread.currentThread();
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getGameState":
						return GameState.LOGGED_IN;
					case "getClientThread":
						return clientThread;
					case "getBoostedSkillLevel":
						return 99;
					default:
						return HybridPageFillTest.defaultValue(method.getReturnType());
				}
			});
		ShortestPathConfig config = Mockito.mock(ShortestPathConfig.class, invocation ->
		{
			String name = invocation.getMethod().getName();
			Class<?> type = invocation.getMethod().getReturnType();
			if (type == boolean.class)
			{
				return !"avoidWilderness".equals(name);
			}
			if (type == int.class)
			{
				return "calculationCutoff".equals(name) ? 120 : 0;
			}
			if (type == TeleportationItem.class)
			{
				return TeleportationItem.NONE;
			}
			return HybridPageFillTest.defaultValue(type);
		});
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		CollisionMap map = new CollisionMap(SplitFlagMap.fromResources());
		Set<Integer> ring = Destinations.walkableTargets(map, target);
		Pathfinder pathfinder = new Pathfinder(planning, start, ring);
		pathfinder.run();
		return pathfinder.getResult().isReached();
	}
}
