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
 * Neypotzli, the Moons of Peril dungeon, as the dev audit captured it on 2026-10-23 and 24: the
 * passages between its areas, the squeeze-through doors of the upper floor and the stairs between
 * floors. Each check routes across one captured link, and the last one crosses the dungeon from
 * the surface entrance's landing to the far north-east corner.
 */
public class NeypotzliTest
{
	@Test
	public void passagesLinkTheWestAreaAndTheUpperFloorBothWays()
	{
		assertTrue(uses(route(at(1388, 9575, 0), at(1423, 9615, 1)), "Pass-through Entrance 51375"));
		assertTrue(uses(route(at(1424, 9616, 1), at(1388, 9575, 0)), "Pass-through Entrance 51376"));
	}

	@Test
	public void theNorthPassageJoinsWestAndEast()
	{
		assertTrue(uses(route(at(1390, 9676, 0), at(1522, 9719, 0)), "Pass-through Entrance 51378"));
		assertTrue(uses(route(at(1522, 9719, 0), at(1390, 9676, 0)), "Pass-through Entrance 51378"));
	}

	@Test
	public void upperFloorDoorsAreSqueezedThrough()
	{
		assertTrue(uses(route(at(1430, 9567, 1), at(1432, 9567, 1)), "Squeeze-through Door 51005"));
		assertTrue(uses(route(at(1404, 9558, 1), at(1404, 9556, 1)), "Squeeze-through Door 51005"));
	}

	@Test
	public void stairsClimbBetweenTheFloorsWhereTheyWereCaptured()
	{
		assertTrue(uses(route(at(1420, 9555, 0), at(1423, 9558, 1)), "Climb-up Stairs 51555"));
		assertTrue(uses(route(at(1423, 9558, 1), at(1420, 9555, 0)), "Climb-down Stairs 51556"));
		assertTrue(uses(route(at(1452, 9587, 0), at(1449, 9590, 1)), "Climb-up Stairs 51555"));
	}

	@Test
	public void theDungeonIsCrossedFromTheEntranceToTheNorthEastCorner()
	{
		List<PathStep> path = route(at(1439, 9509, 1), at(1522, 9719, 0));
		assertNotNull("reached", path);
		assertTrue("uses the passages: " + path.size(), uses(path, "Pass-through Entrance 51378")
			|| uses(path, "Pass-through Entrance 51377") || uses(path, "Pass-through Entrance 51375"));
	}

	private static int at(int x, int y, int plane)
	{
		return WorldPointUtil.packWorldPoint(x, y, plane);
	}

	private static PathfinderConfig config;

	/** Whether some edge of the path is the named transport. */
	private static boolean uses(List<PathStep> path, String action)
	{
		assertNotNull("reached", path);
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

	/** The owned-mode path with an empty bag at level 99, or null when unreachable. */
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
