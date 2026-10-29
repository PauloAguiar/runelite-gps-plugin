package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathStep;
import gps.pathfinder.Pathfinder;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.SplitFlagMap;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.Transport;
import gps.transport.TransportType;
import java.lang.reflect.Proxy;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.QuestState;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The charter network reaches The Summer Shore, the Great Conch's southern dock, from every charter
 * port (upstream Shortest Path #534 and #659). Our file had left it out with Deepfin Point, Port
 * Roberts and Red Rock, so a player at Corsair Cove with coins could not be sent across (the
 * 2026-10-28 captures). The Summer Shore opens partway through Troubled Tortugans, like its fairy
 * ring, so it is gated on quest varbit 18321.
 */
public class CharterToTheGreatConchTest
{
	private static final int CORSAIR_COVE_DOCK = WorldPointUtil.packWorldPoint(2589, 2851, 0);
	private static final int CONCH_BANK = WorldPointUtil.packWorldPoint(3178, 2421, 0);
	private static final int SUMMER_SHORE = WorldPointUtil.packWorldPoint(3189, 2369, 0);
	private static final int QUEST_VARBIT = 18321;

	@Test
	public void corsairCoveChartersToTheSummerShoreOnceTheIslandIsOpen()
	{
		List<PathStep> path = route(CORSAIR_COVE_DOCK, CONCH_BANK, 38);
		assertNotNull("reached", path);
		assertTrue("one charter and a short walk, got " + path.size() + " steps", path.size() < 80);
		assertTrue(chartersToTheSummerShore(path));
	}

	@Test
	public void beforeFloopaIsBandagedNoCharterGoesThere()
	{
		List<PathStep> path = route(CORSAIR_COVE_DOCK, CONCH_BANK, 4);
		assertFalse(path != null && chartersToTheSummerShore(path));
	}

	private static PathfinderConfig config;

	private static boolean chartersToTheSummerShore(List<PathStep> path)
	{
		for (int i = 1; i < path.size(); i++)
		{
			for (Transport transport : EdgeTransports.forEdge(config, path.get(i - 1), path.get(i)))
			{
				if (TransportType.CHARTER_SHIP.equals(transport.getType()) && transport.getDestination() == SUMMER_SHORE)
				{
					return true;
				}
			}
		}
		return false;
	}

	/** The owned-mode path with a purse of coins and no staff, at the given quest varbit. */
	private static List<PathStep> route(int from, int to, int questVarbit)
	{
		final Thread clientThread = Thread.currentThread();
		ItemContainer inventory = Mockito.mock(ItemContainer.class);
		Mockito.when(inventory.getItems()).thenReturn(new Item[]{new Item(ItemID.COINS, 10000)});
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
						return (int) args[0] == QUEST_VARBIT ? questVarbit : 0;
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
