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
import net.runelite.api.gameval.VarPlayerID;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The Kharazi Jungle opens as soon as Legends' Quest is started: talking to Radimus Erkle and
 * taking his notes (wiki; Quest Helper's first quest step sends the player in to sketch it, at
 * varp 139 = 1). The bush and tree rows were gated on 139>49, the quest's closing stages, so a
 * player mid-quest with a machete was told the jungle could not be reached (capture
 * gps-capture-20261006-200838: a Quest Helper target at 2791,2917, three routes stopping at
 * 2789,2942). The vine keeps its late gate: the wiki wants the quest completed for it.
 */
public class KharaziJungleTest
{
	private static final int EDGE = WorldPointUtil.packWorldPoint(2789, 2942, 0);
	private static final int TARGET = WorldPointUtil.packWorldPoint(2791, 2917, 0);

	@Test
	public void theJungleOpensOnceTheQuestIsStarted()
	{
		assertEquals("Chop-down Jungle Bush", wayIn(new Item[]{new Item(ItemID.MACHETTE, 1)}, 1));
		assertEquals("and stays open after it", "Chop-down Jungle Bush", wayIn(new Item[]{new Item(ItemID.MACHETTE, 1)}, 75));
	}

	@Test
	public void notBeforeTheQuestAndNotWithoutAMachete()
	{
		assertNull("Radimus has not let the player in", wayIn(new Item[]{new Item(ItemID.MACHETTE, 1)}, 0));
		assertNull("nothing to cut the bushes with, and the vine wants the quest done", wayIn(new Item[0], 1));
		assertEquals("the quest done and 79 Agility: the vine, no machete needed", "Climb Vine", wayIn(new Item[0], 75));
	}

	/** The object of the first transport the owned-mode route uses to reach the target, or null when it cannot. */
	private static String wayIn(Item[] carried, int legendsProgress)
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
					case "getVarpValue":
						return (int) args[0] == VarPlayerID.LEGENDSQUEST ? legendsProgress : 0;
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
		// Varplayers apply; the quest state stays whatever the game reports (the rows gate on the varp).
		PathfinderConfig owned = new TestPathfinderConfig(client, config, QuestState.FINISHED, true, false);
		owned.refresh();
		CollisionMap map = new CollisionMap(SplitFlagMap.fromResources());
		Pathfinder pathfinder = new Pathfinder(owned, EDGE, Destinations.walkableTargets(map, TARGET));
		pathfinder.run();
		if (!pathfinder.getResult().isReached())
		{
			return null;
		}
		List<PathStep> path = pathfinder.getPath();
		for (int i = 1; i < path.size(); i++)
		{
			for (Transport transport : EdgeTransports.forEdge(owned, path.get(i - 1), path.get(i)))
			{
				String object = transport.getObjectInfo();
				return object.substring(0, object.lastIndexOf(' '));
			}
		}
		return "walked";
	}
}
