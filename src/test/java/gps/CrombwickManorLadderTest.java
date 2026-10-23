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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The ladder in Kroy's laboratory under Slepe is the only way into Crombwick Manor (wiki: the
 * manor's doors are locked). During Sins of the Father the fight room is instanced and the ladder
 * only examines: the quick guide has players leave by the southern gate, and a player at the
 * "Return to Veliaf" step was sent to it (report, 2026-10-21). The rows are gated on the quest.
 */
public class CrombwickManorLadderTest
{
	private static final int LAB = WorldPointUtil.packWorldPoint(3730, 9757, 1);
	private static final int MANOR = WorldPointUtil.packWorldPoint(3724, 3357, 0);

	@Test
	public void theLadderWaitsForSinsOfTheFather()
	{
		assertTrue(climbs(route(LAB, MANOR, QuestState.FINISHED), "Climb-up Ladder 32642"));
		assertFalse(climbs(route(LAB, MANOR, QuestState.IN_PROGRESS), "Climb-up Ladder 32642"));
		assertTrue(climbs(route(MANOR, LAB, QuestState.FINISHED), "Climb-down Trapdoor 32641"));
	}

	private static PathfinderConfig config;

	private static boolean climbs(List<PathStep> path, String action)
	{
		if (path == null)
		{
			return false;
		}
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

	private static List<PathStep> route(int from, int to, QuestState quests)
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
		PathfinderConfig owned = new TestPathfinderConfig(client, cfg, quests, false, true);
		owned.refresh();
		config = owned;
		CollisionMap map = new CollisionMap(SplitFlagMap.fromResources());
		Pathfinder pathfinder = new Pathfinder(owned, from, Destinations.walkableTargets(map, to));
		pathfinder.run();
		return pathfinder.getResult().isReached() ? pathfinder.getPath() : null;
	}
}
