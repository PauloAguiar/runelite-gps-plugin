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
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The boat between Burgh de Rott and the south wall of Meiyerditch is the one fixed and pushed
 * out in Darkness of Hallowvale: game cache object 12945 shows the boat (17955) once varbit 2587
 * (MYQ3_SEA_BOAT_VISIBLE) is 1, and 17961 waits on the Meiyerditch rocks. Icyene Graveyard joins
 * its menu in Sins of the Father (varbit 7255 past 41, Quest Helper) and the graveyard's own boat
 * (multiloc 39520, 38089) only appears from 44. The rows were gated on Sins of the Father alone,
 * so a player fresh out of Darkness of Hallowvale had no boat (player question, 2026-10-11).
 */
public class BurghDeRottBoatTest
{
	private static final int BURGH_DE_ROTT_DOCK = WorldPointUtil.packWorldPoint(3525, 3170, 0);
	private static final int MEIYERDITCH_WALL = WorldPointUtil.packWorldPoint(3601, 3163, 1);
	private static final int ICYENE_GRAVEYARD = WorldPointUtil.packWorldPoint(3684, 3176, 0);

	@Test
	public void theHallowvaleBoatCrossesBothWays()
	{
		assertTrue("pushed out, Sins of the Father untouched", boat(BURGH_DE_ROTT_DOCK, MEIYERDITCH_WALL, 1, 0));
		assertTrue(boat(MEIYERDITCH_WALL, BURGH_DE_ROTT_DOCK, 1, 0));
	}

	@Test
	public void noBoatBeforeItIsPushedOut()
	{
		assertFalse(boat(BURGH_DE_ROTT_DOCK, MEIYERDITCH_WALL, 0, 0));
		assertFalse("Sins of the Father alone does not put it in the water", boat(BURGH_DE_ROTT_DOCK, MEIYERDITCH_WALL, 0, 42));
	}

	@Test
	public void icyeneGraveyardJoinsTheMenuInSinsOfTheFather()
	{
		assertFalse(boat(BURGH_DE_ROTT_DOCK, ICYENE_GRAVEYARD, 1, 41));
		assertTrue(boat(BURGH_DE_ROTT_DOCK, ICYENE_GRAVEYARD, 1, 42));
	}

	@Test
	public void theGraveyardBoatAppearsAtFortyFour()
	{
		assertFalse(boat(ICYENE_GRAVEYARD, BURGH_DE_ROTT_DOCK, 1, 43));
		assertTrue(boat(ICYENE_GRAVEYARD, BURGH_DE_ROTT_DOCK, 1, 44));
	}

	/** Whether the owned-mode route between the two tiles boards a boat, with the boat-pushed and Sins of the Father varbits given. */
	private static boolean boat(int from, int to, int pushed, int sins)
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
					case "getVarbitValue":
						return (int) args[0] == VarbitID.MYQ3_SEA_BOAT_VISIBLE ? pushed : (int) args[0] == VarbitID.MYQ5 ? sins : 0;
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
		if (!pathfinder.getResult().isReached())
		{
			return false;
		}
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
