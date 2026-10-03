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
