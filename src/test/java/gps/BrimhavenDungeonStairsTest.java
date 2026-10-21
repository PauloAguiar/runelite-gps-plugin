package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathStep;
import gps.pathfinder.Pathfinder;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.SplitFlagMap;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.Transport;
import java.lang.reflect.Proxy;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.QuestState;
import net.runelite.api.gameval.InventoryID;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Brimhaven Dungeon's top floor (plane 2) is reached by two staircases from the ground floor,
 * objects 21725 (south, by the moss giants, the Karamja medium diary's "climb up") and 21722
 * (north, by the entrance), with 21726 and 21724 the way down (game cache, 2026-10-20). The
 * data had neither, so a player at the foot of the south stairs was sent to Castle Wars and
 * back to "walk as close as possible" (capture 2026-10-20). Landing tiles are provisional
 * until captured.
 */
public class BrimhavenDungeonStairsTest
{
	private static final int SOUTH_FOOT = WorldPointUtil.packWorldPoint(2636, 9517, 0);
	private static final int SOUTH_TOP = WorldPointUtil.packWorldPoint(2636, 9510, 2);
	private static final int DIARY_TARGET = WorldPointUtil.packWorldPoint(2634, 9491, 2);
	private static final int NORTH_FOOT = WorldPointUtil.packWorldPoint(2650, 9591, 0);
	private static final int NORTH_TOP = WorldPointUtil.packWorldPoint(2643, 9594, 2);

	@Test
	public void theSouthStairsClimbToTheTopFloor()
	{
		List<PathStep> path = route(SOUTH_FOOT, DIARY_TARGET);
		assertNotNull("reached", path);
		assertTrue("a few dozen tiles, not Castle Wars: " + path.size(), path.size() < 60);
		assertTrue(climbs(path, "Walk-up Stairs 21725"));
		assertTrue(climbs(route(SOUTH_TOP, SOUTH_FOOT), "Walk-down Stairs 21726"));
	}

	@Test
	public void theNorthStairsClimbToTheTopFloor()
	{
		assertTrue(climbs(route(NORTH_FOOT, NORTH_TOP), "Walk-up Stairs 21722"));
		assertTrue(climbs(route(NORTH_TOP, NORTH_FOOT), "Walk-down Stairs 21724"));
	}

	private static PathfinderConfig config;

	/** Whether some edge of the path is the named transport. */
	private static boolean climbs(List<PathStep> path, String action)
	{
		assertNotNull(path);
		for (int i = 1; i < path.size(); i++)
		{
			for (Transport transport : EdgeTransports.forEdge(config, path.get(i - 1), path.get(i)))
			{
				if (action.equals(transport.getObjectInfo()))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** The owned-mode path with an empty bag, or null when unreachable. */
	private static List<PathStep> route(int from, int to)
	{
		final Thread clientThread = Thread.currentThread();
		ItemContainer inventory = Mockito.mock(ItemContainer.class);
		Mockito.when(inventory.getItems()).thenReturn(new Item[0]);
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
					case "getItemContainer":
						return (int) args[0] == InventoryID.INV ? inventory : null;
					default:
						return HybridPageFillTest.defaultValue(method.getReturnType());
				}
			});
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class, invocation ->
		{
			String name = invocation.getMethod().getName();
			Class<?> type = invocation.getMethod().getReturnType();
			if (type == boolean.class)
			{
				return !"avoidWilderness".equals(name);
			}
			if (type == int.class)
			{
				return "calculationCutoff".equals(name) ? 120 : "currencyThreshold".equals(name) ? 100000 : 0;
			}
			if (type == TeleportationItem.class)
			{
				return TeleportationItem.INVENTORY;
			}
			return HybridPageFillTest.defaultValue(type);
		});
		PathfinderConfig owned = new TestPathfinderConfig(client, cfg, QuestState.FINISHED, false, true);
		owned.refresh();
		config = owned;
		CollisionMap map = new CollisionMap(SplitFlagMap.fromResources());
		Pathfinder pathfinder = new Pathfinder(owned, from, Destinations.walkableTargets(map, to));
		pathfinder.run();
		return pathfinder.getResult().isReached() ? pathfinder.getPath() : null;
	}
}
