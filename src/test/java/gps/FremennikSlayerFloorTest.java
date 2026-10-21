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

import static org.junit.Assert.assertEquals;

/**
 * The Fremennik Slayer Dungeon's strange floors (object 16544, the "chasm jump") need 43 Agility,
 * as RuneLite's own shortcut table has them; the rows said 81, so a player who could jump them
 * was routed the long way round the dungeon (capture 2026-10-20).
 */
public class FremennikSlayerFloorTest
{
	private static final int EAST = WorldPointUtil.packWorldPoint(2782, 9999, 0);
	private static final int WEST = WorldPointUtil.packWorldPoint(2722, 10003, 0);

	@Test
	public void fortyThreeAgilityJumpsBothFloors()
	{
		assertEquals(2, floors(43));
		assertEquals("42 walks round", 0, floors(42));
	}

	/** How many strange-floor jumps the owned-mode route across the dungeon takes at the given Agility. */
	private static int floors(int agility)
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
						return agility;
					case "getItemContainer":
						return (int) args[0] == InventoryID.INV ? inventory : null;
					default:
						return HybridPageFillTest.defaultValue(method.getReturnType());
				}
			});
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class, invocation ->
		{
			Class<?> type = invocation.getMethod().getReturnType();
			if (type == boolean.class)
			{
				return !"avoidWilderness".equals(invocation.getMethod().getName());
			}
			if (type == int.class)
			{
				return "calculationCutoff".equals(invocation.getMethod().getName()) ? 120 : 0;
			}
			if (type == TeleportationItem.class)
			{
				return TeleportationItem.INVENTORY;
			}
			return HybridPageFillTest.defaultValue(type);
		});
		PathfinderConfig owned = new TestPathfinderConfig(client, cfg, QuestState.FINISHED, false, true);
		owned.refresh();
		CollisionMap map = new CollisionMap(SplitFlagMap.fromResources());
		Pathfinder pathfinder = new Pathfinder(owned, EAST, Destinations.walkableTargets(map, WEST));
		pathfinder.run();
		List<PathStep> path = pathfinder.getPath();
		int jumps = 0;
		for (int i = 1; i < path.size(); i++)
		{
			for (Transport transport : EdgeTransports.forEdge(owned, path.get(i - 1), path.get(i)))
			{
				if ("Jump-over Strange floor 16544".equals(transport.getObjectInfo()))
				{
					jumps++;
				}
			}
		}
		return jumps;
	}
}
