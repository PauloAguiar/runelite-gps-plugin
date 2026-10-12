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
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Andras rows players from Port Phasmatys to Slepe for 10,000 coins, or for free once the one-off
 * fee of a million has been paid (varbit 10150, ANDRAS_PERM_UNLOCK), and from Slepe back for
 * free (wiki). The data charged 10,000 both ways and knew nothing of the fee (player report,
 * 2026-10-11).
 */
public class AndrasBoatTest
{
	private static final int ECTO_DOCK = WorldPointUtil.packWorldPoint(3671, 3544, 0);
	private static final int SLEPE_DOCK = WorldPointUtil.packWorldPoint(3661, 3278, 0);
	private static final Item[] NOTHING = {};
	private static final Item[] FARE = {new Item(ItemID.COINS, 10000)};

	@Test
	public void toSlepeCostsTenThousandUnlessTheFeeWasPaid()
	{
		assertTrue("the fare on you", rows(ECTO_DOCK, SLEPE_DOCK, FARE, 0));
		assertFalse("no coins, no fee paid", rows(ECTO_DOCK, SLEPE_DOCK, NOTHING, 0));
		assertTrue("the fee paid: free", rows(ECTO_DOCK, SLEPE_DOCK, NOTHING, 1));
	}

	@Test
	public void backToPortPhasmatysIsFree()
	{
		assertTrue(rows(SLEPE_DOCK, ECTO_DOCK, NOTHING, 0));
	}

	/** Whether the owned-mode route between the docks is Andras's boat, with the given coins and fee state. */
	private static boolean rows(int from, int to, Item[] carried, int feePaid)
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
						return (int) args[0] == VarbitID.ANDRAS_PERM_UNLOCK ? feePaid : 0;
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
		PathfinderConfig owned = new TestPathfinderConfig(client, config, QuestState.FINISHED, false, true);
		owned.refresh();
		CollisionMap map = new CollisionMap(SplitFlagMap.fromResources());
		Pathfinder pathfinder = new Pathfinder(owned, from, Destinations.walkableTargets(map, to));
		pathfinder.run();
		List<PathStep> path = pathfinder.getPath();
		for (int i = 1; i < path.size(); i++)
		{
			for (Transport transport : EdgeTransports.forEdge(owned, path.get(i - 1), path.get(i)))
			{
				if (TransportType.BOAT.equals(transport.getType()))
				{
					return true;
				}
			}
		}
		return false;
	}
}
