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
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The Great Conch fairy ring (CJQ, 3178 2447) opens partway through Troubled Tortugans, once
 * Floopa is bandaged (quest varbit 18321 at 6 or more). The row used to demand the finished quest,
 * so a player at step 38 was routed from the Grand Exchange by Sailors' amulet and a 364-tick
 * voyage while they simply took the ring (capture 2026-10-28 22:54). The gate is the varbit now.
 */
public class GreatConchFairyRingTest
{
	private static final int EDGEVILLE_RING = WorldPointUtil.packWorldPoint(3129, 3496, 0);
	private static final int CONCH_BANK = WorldPointUtil.packWorldPoint(3178, 2421, 0);
	private static final int CONCH_RING = WorldPointUtil.packWorldPoint(3178, 2447, 0);
	private static final int QUEST_VARBIT = 18321;

	@Test
	public void aPlayerPartWayThroughTheQuestTakesTheRing()
	{
		List<PathStep> path = route(EDGEVILLE_RING, CONCH_BANK, 38);
		assertNotNull("reached", path);
		assertTrue("fairy ring to the island, not a voyage: " + path.size() + " steps", path.size() < 60);
		assertTrue(landsOnTheConchRing(path));
	}

	@Test
	public void beforeFloopaIsBandagedTheRingDoesNotOfferTheIsland()
	{
		List<PathStep> path = route(EDGEVILLE_RING, CONCH_BANK, 4);
		assertFalse(path != null && landsOnTheConchRing(path));
	}

	private static PathfinderConfig config;

	private static boolean landsOnTheConchRing(List<PathStep> path)
	{
		for (int i = 1; i < path.size(); i++)
		{
			for (Transport transport : EdgeTransports.forEdge(config, path.get(i - 1), path.get(i)))
			{
				if ("Configure Fairy ring 29495".equals(transport.getObjectInfo()) && transport.getDestination() == CONCH_RING)
				{
					return true;
				}
			}
		}
		return false;
	}

	/** The owned-mode path with a Dramen staff, Fairytale II far enough for the rings, at the given quest varbit. */
	private static List<PathStep> route(int from, int to, int questVarbit)
	{
		final Thread clientThread = Thread.currentThread();
		ItemContainer inventory = Mockito.mock(ItemContainer.class);
		Mockito.when(inventory.getItems()).thenReturn(new Item[]{new Item(ItemID.DRAMEN_STAFF, 1)});
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
					case "getVarbitValue":
						return (int) args[0] == VarbitID.FAIRY2_QUEENCURE_QUEST ? 100
							: (int) args[0] == QUEST_VARBIT ? questVarbit : 0;
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
