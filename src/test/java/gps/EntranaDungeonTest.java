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
import net.runelite.api.QuestState;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * The Entrana Dungeon had no way in or out in the data: a player on the island asking for the
 * Dramen tree (capture gps-capture-20261008-221132, from 2831,3345) was flown to Taverley and
 * walked to the sealed Taverley side of the same map region, "as close as possible". The ladder
 * at 2820,3374 goes down to 2822,9774 (audit capture 2026-10-08, one tick), and the Magic door at
 * the dungeon's east end is the only way out, a teleport into the Wilderness at 3250,3772 (wiki).
 */
public class EntranaDungeonTest
{
	private static final int ON_ENTRANA = WorldPointUtil.packWorldPoint(2831, 3345, 0);
	private static final int DRAMEN_TREE = WorldPointUtil.packWorldPoint(2861, 9737, 0);
	private static final int DOOR_LANDING = WorldPointUtil.packWorldPoint(3250, 3772, 0);

	@Test
	public void theLadderOnTheIslandLeadsToTheDramenTree()
	{
		List<PathStep> path = path(ON_ENTRANA, DRAMEN_TREE);
		assertNotNull("the tree is reachable", path);
		assertEquals("Climb-down Ladder 2408", firstObject(path));
	}

	/** The ladder is climbed from any of its four sides (player, 2026-10-08); the capture saw the south one. */
	@Test
	public void theLadderIsClimbedFromAllFourSides()
	{
		path(ON_ENTRANA, DRAMEN_TREE);
		PathStep landing = new PathStep(WorldPointUtil.packWorldPoint(2822, 9774, 0), false);
		for (int[] side : new int[][]{{2820, 3373}, {2820, 3375}, {2819, 3374}, {2821, 3374}})
		{
			PathStep stand = new PathStep(WorldPointUtil.packWorldPoint(side[0], side[1], 0), false);
			assertEquals("from " + side[0] + "," + side[1], 1, EdgeTransports.forEdge(owned, stand, landing).size());
		}
	}

	@Test
	public void theMagicDoorIsTheWayOutIntoTheWilderness()
	{
		// A free home teleport always gets a player out, so the door is tested on its own landing:
		// nothing else reaches that Wilderness tile in a single step (the test config does not avoid it).
		List<PathStep> path = path(DRAMEN_TREE, DOOR_LANDING);
		assertNotNull(path);
		assertEquals("Open Magic door 2407", firstObject(path));
		int landed = -1;
		for (int i = 1; i < path.size() && landed < 0; i++)
		{
			if (WorldPointUtil.distanceBetween2D(path.get(i - 1).getPackedPosition(), path.get(i).getPackedPosition()) > 1)
			{
				landed = path.get(i).getPackedPosition();
			}
		}
		assertEquals(DOOR_LANDING, landed);
	}

	private static PathfinderConfig owned;

	private static String firstObject(List<PathStep> path)
	{
		for (int i = 1; i < path.size(); i++)
		{
			for (Transport transport : EdgeTransports.forEdge(owned, path.get(i - 1), path.get(i)))
			{
				return transport.getObjectInfo();
			}
		}
		return "walked";
	}

	/** The owned-mode path, or null when the target is unreachable. */
	private static List<PathStep> path(int start, int target)
	{
		final Thread clientThread = Thread.currentThread();
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
		owned = new TestPathfinderConfig(client, config, QuestState.FINISHED, true, true);
		owned.refresh();
		CollisionMap map = new CollisionMap(SplitFlagMap.fromResources());
		Pathfinder pathfinder = new Pathfinder(owned, start, Destinations.walkableTargets(map, target));
		pathfinder.run();
		return pathfinder.getResult().isReached() ? pathfinder.getPath() : null;
	}
}
