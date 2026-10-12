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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The Meiyerditch mine has no door: a Vyrewatch sends you there when you ask (any one patrolling
 * Meiyerditch once the Myreque hideout has been found in Darkness of Hallowvale, and the one
 * guarding the Ver Sinhaza bank), and a Vampyre Juvinate guard lets you out to the north-east
 * corner of Meiyerditch once The Blood Moon Rises is done or while you wear vyre noble clothing
 * (wiki: Meiyerditch mine, Vyrewatch (bank), Daeyalt essence mine). Player request, 2026-10-11.
 */
public class MeiyerditchMineTest
{
	private static final int BANK_VYREWATCH = WorldPointUtil.packWorldPoint(3648, 3208, 0);
	private static final int MEIYERDITCH_STREET = WorldPointUtil.packWorldPoint(3615, 3250, 0);
	private static final int MINE = WorldPointUtil.packWorldPoint(2389, 4623, 2);
	private static final int MINE_GUARD = WorldPointUtil.packWorldPoint(2380, 4627, 2);
	private static final int NORTH_EAST_MEIYERDITCH = WorldPointUtil.packWorldPoint(3623, 3324, 0);
	private static final int HIDEOUT_FOUND = 70;
	private static final Item[] NOTHING = {};
	private static final Item[] VYRE_NOBLE_CLOTHING = {
		new Item(ItemID.VYRELORD_TORSO, 1), new Item(ItemID.VYRELORD_LEGS, 1), new Item(ItemID.VYRELORD_SHOES, 1)};

	@Test
	public void theBankVyrewatchSendsYouToTheMine()
	{
		List<PathStep> path = route(BANK_VYREWATCH, MINE, HIDEOUT_FOUND, QuestState.FINISHED, NOTHING);
		assertTrue(path != null && path.size() < 10);
		assertTrue(leapsTo(path, MINE));
	}

	@Test
	public void anyVyrewatchInMeiyerditchWillDo()
	{
		List<PathStep> path = route(MEIYERDITCH_STREET, MINE, HIDEOUT_FOUND, QuestState.FINISHED, NOTHING);
		assertTrue(path != null && path.size() < 20);
	}

	@Test
	public void notBeforeTheHideoutIsFound()
	{
		assertNull(route(BANK_VYREWATCH, MINE, HIDEOUT_FOUND - 10, QuestState.FINISHED, NOTHING));
	}

	@Test
	public void theGuardsLetYouOutAfterTheBloodMoonRises()
	{
		List<PathStep> path = route(MINE, NORTH_EAST_MEIYERDITCH, HIDEOUT_FOUND, QuestState.FINISHED, NOTHING);
		assertTrue(path != null && leapsTo(path, NORTH_EAST_MEIYERDITCH));
	}

	@Test
	public void orWhileYouWearVyreNobleClothing()
	{
		List<PathStep> path = route(MINE, NORTH_EAST_MEIYERDITCH, HIDEOUT_FOUND, QuestState.NOT_STARTED, VYRE_NOBLE_CLOTHING);
		assertTrue(path != null && leapsTo(path, NORTH_EAST_MEIYERDITCH));
		assertNull(route(MINE, NORTH_EAST_MEIYERDITCH, HIDEOUT_FOUND, QuestState.NOT_STARTED, NOTHING));
	}

	@Test
	public void theMineIsNotAShortcutIntoMeiyerditchWithoutAWayOut()
	{
		List<PathStep> path = route(BANK_VYREWATCH, NORTH_EAST_MEIYERDITCH, HIDEOUT_FOUND, QuestState.NOT_STARTED, NOTHING);
		assertFalse(path != null && leapsTo(path, MINE));
	}

	/** Whether some edge of the path is a transport landing on the tile. */
	private static boolean leapsTo(List<PathStep> path, int tile)
	{
		for (int i = 1; i < path.size(); i++)
		{
			if (path.get(i).getPackedPosition() == tile)
			{
				for (Transport transport : EdgeTransports.forEdge(CONFIG, path.get(i - 1), path.get(i)))
				{
					if (transport.getDestination() == tile)
					{
						return true;
					}
				}
			}
		}
		return false;
	}

	private static PathfinderConfig CONFIG;

	/** The owned-mode path with the given Darkness of Hallowvale progress, quest state and bag, or null when unreachable. */
	private static List<PathStep> route(int from, int to, int hallowvale, QuestState quests, Item[] carried)
	{
		final Thread clientThread = Thread.currentThread();
		ItemContainer inventory = Mockito.mock(ItemContainer.class);
		Mockito.when(inventory.getItems()).thenReturn(carried);
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
						return (int) args[0] == VarbitID.MYQ3_MAIN_QUEST ? hallowvale : 0;
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
				return "calculationCutoff".equals(name) ? 120 : "currencyThreshold".equals(name) ? 100000 : 0;
			}
			if (type == TeleportationItem.class)
			{
				return TeleportationItem.INVENTORY;
			}
			return HybridPageFillTest.defaultValue(type);
		});
		PathfinderConfig owned = new TestPathfinderConfig(client, config, quests, false, true);
		owned.refresh();
		CONFIG = owned;
		CollisionMap map = new CollisionMap(SplitFlagMap.fromResources());
		Pathfinder pathfinder = new Pathfinder(owned, from, Destinations.walkableTargets(map, to));
		pathfinder.run();
		return pathfinder.getResult().isReached() ? pathfinder.getPath() : null;
	}
}
