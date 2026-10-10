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
import java.util.Map;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * A memoirs teleport exists once its torn page is ADDED to the book, not once the quest that
 * awards the page is done: the rows gated on the quests' reward flags, so a player with all five
 * quests done and two pages in the book (field captures 2026-10-04 to -08: rewards all 1, pages
 * HOSIDIUS and PISCARILIUS 1, the other three 0, 39 charges) was told to use the three pages
 * still in the bank. Each row now gates on its page's varbit.
 */
public class KharedstsMemoirsTest
{
	private static final int KOUREND_CASTLE = WorldPointUtil.packWorldPoint(1640, 3673, 0);
	private static final int HOSIDIUS_LANDING = WorldPointUtil.packWorldPoint(1714, 3611, 0);
	private static final int SHAYZIEN_LANDING = WorldPointUtil.packWorldPoint(1478, 3576, 0);

	/** The reporting player's book: every quest done, two pages added, charges to spare. */
	private static final Map<Integer, Integer> TWO_PAGES = Map.of(
		VarbitID.HOSIDIUSQUEST_REWARD, 1, VarbitID.PISCQUEST_REWARD, 1, VarbitID.SHAYZIENQUEST_REWARD, 1,
		VarbitID.LOVAQUEST_REWARD, 1, VarbitID.ARCQUEST_REWARD, 1,
		VarbitID.HOSIDIUS_PAGE, 1, VarbitID.PISCARILIUS_PAGE, 1,
		VarbitID.VEOS_MEMOIR_CHARGES, 39);

	@Test
	public void anAddedPageTeleports()
	{
		assertEquals("Kharedst's memoirs: Lunch by the Lancalliums", firstTeleport(HOSIDIUS_LANDING));
	}

	@Test
	public void aPageStillOutOfTheBookDoesNot()
	{
		assertNotEquals("Kharedst's memoirs: History and Hearsay", firstTeleport(SHAYZIEN_LANDING));
	}

	/** The display info of the first teleport on the owned-mode route from Kourend Castle, or "walked". */
	private static String firstTeleport(int target)
	{
		final Thread clientThread = Thread.currentThread();
		ItemContainer inventory = Mockito.mock(ItemContainer.class);
		Mockito.when(inventory.getItems()).thenReturn(new Item[]{new Item(ItemID.VEOS_KHAREDSTS_MEMOIRS, 1)});
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
						return TWO_PAGES.getOrDefault((int) args[0], 0);
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
				return TeleportationItem.INVENTORY;
			}
			return HybridPageFillTest.defaultValue(type);
		});
		PathfinderConfig owned = new TestPathfinderConfig(client, config, QuestState.FINISHED, false, true);
		owned.refresh();
		CollisionMap map = new CollisionMap(SplitFlagMap.fromResources());
		Pathfinder pathfinder = new Pathfinder(owned, KOUREND_CASTLE, Destinations.walkableTargets(map, target));
		pathfinder.run();
		List<PathStep> path = pathfinder.getPath();
		for (int i = 1; i < path.size(); i++)
		{
			for (Transport transport : EdgeTransports.forEdge(owned, path.get(i - 1), path.get(i)))
			{
				if (transport.getType() != null && transport.getType().isTeleport())
				{
					return transport.getDisplayInfo();
				}
			}
		}
		return "walked";
	}
}
