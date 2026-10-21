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
 * Agility shortcuts RuneLite's own table lists that neither our data nor upstream Shortest Path
 * carried (audit 2026-10-21): the Crabclaw Caves rocks, the Deep Wilderness Dungeon crevice, the
 * Motherlode Mine dark tunnels, the Revenant Caves pillar jumps and the Brimhaven Dungeon vine.
 * Object positions from the game cache, stand and landing tiles from the collision map (provisional
 * until captured). Each crosses at its level and not one below.
 */
public class MissingShortcutsTest
{
	@Test
	public void crabclawCavesRocks()
	{
		crosses("Climb Rocks 31697", 18, at(1685, 9801, 0), at(1691, 9801, 0), at(1687, 9801, 0));
	}

	@Test
	public void deepWildernessDungeonCrevice()
	{
		crosses("Use Crevice 19043", 46, at(3046, 10325, 0), at(3049, 10337, 0), at(3046, 10326, 0));
		crosses("Use Crevice 19043", 46, at(3049, 10337, 0), at(3046, 10325, 0), at(3048, 10336, 0));
	}

	@Test
	public void motherlodeMineDarkTunnels()
	{
		crosses("Enter Dark tunnel 10047", 54, at(3758, 5670, 0), at(3766, 5671, 0), at(3759, 5670, 0));
		crosses("Enter Dark tunnel 10047", 54, at(3745, 5639, 0), at(3745, 5647, 0), at(3745, 5640, 0));
	}

	@Test
	public void revenantCavesPillars()
	{
		crosses("Jump-to Pillar 31561", 65, at(3220, 10083, 0), at(3220, 10089, 0), at(3220, 10084, 0));
		crosses("Jump-to Pillar 31561", 75, at(3197, 10136, 0), at(3203, 10136, 0), at(3198, 10136, 0));
		crosses("Jump-to Pillar 31561", 75, at(3199, 10196, 0), at(3205, 10196, 0), at(3200, 10196, 0));
		crosses("Jump-to Pillar 31561", 75, at(3180, 10206, 0), at(3180, 10212, 0), at(3180, 10207, 0));
		crosses("Jump-to Pillar 31561", 89, at(3238, 10145, 0), at(3244, 10145, 0), at(3239, 10145, 0));
	}

	@Test
	public void brimhavenDungeonVine()
	{
		crosses("Climb Vine 26880", 87, at(2674, 9583, 0), at(2668, 9583, 2), at(2673, 9583, 0));
		crosses("Climb Vine 26882", 87, at(2668, 9583, 2), at(2674, 9583, 0), at(2670, 9583, 2));
	}

	private static int at(int x, int y, int plane)
	{
		return WorldPointUtil.packWorldPoint(x, y, plane);
	}

	/** The route takes the shortcut from its stand tile at its level and does not one level below. */
	private static void crosses(String action, int level, int from, int to, int stand)
	{
		List<PathStep> able = route(from, to, level);
		assertTrue(action + " at " + level, able != null && uses(able, action, stand));
		List<PathStep> unable = route(from, to, level - 1);
		assertFalse(action + " at " + (level - 1), unable != null && uses(unable, action, stand));
	}

	private static PathfinderConfig config;

	private static boolean uses(List<PathStep> path, String action, int stand)
	{
		for (int i = 1; i < path.size(); i++)
		{
			for (Transport transport : EdgeTransports.forEdge(config, path.get(i - 1), path.get(i)))
			{
				if (action.equals(transport.getObjectInfo()) && transport.getOrigin() == stand)
				{
					return true;
				}
			}
		}
		return false;
	}

	/** The owned-mode path with an empty bag at the given boosted level, or null when unreachable. */
	private static List<PathStep> route(int from, int to, int level)
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
						return level;
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
		config = owned;
		CollisionMap map = new CollisionMap(SplitFlagMap.fromResources());
		Pathfinder pathfinder = new Pathfinder(owned, from, Destinations.walkableTargets(map, to));
		pathfinder.run();
		return pathfinder.getResult().isReached() ? pathfinder.getPath() : null;
	}
}
