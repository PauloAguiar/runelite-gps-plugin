package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathStep;
import gps.pathfinder.Pathfinder;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.SplitFlagMap;
import gps.pathfinder.TestPathfinderConfig;
import java.lang.reflect.Proxy;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.QuestState;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;

/**
 * Dorgesh-Kaan, upper level (capture gps-capture-20261008-214129): from 2713,5281 to the stairs
 * at 2722,5255 the route climbed down at 2722,5272, walked the ground floor and climbed back up
 * two tiles short of the target, instead of the walkway on the same level. On the tick count
 * alone that is right, 27 cost units against 30, because a staircase is one tick in the data and
 * in every measured sample. What the ticks miss is the click: each object interaction stops the
 * run and costs the player a moment. The "Object transport modifier" prices that at one tick
 * (two run tiles) per stairs, ladder or mapped door, which tips a knife edge like this one to
 * the walkway and leaves any real shortcut alone.
 */
public class DorgeshKaanStairsTest
{
	private static final int START = WorldPointUtil.packWorldPoint(2713, 5281, 1);
	private static final int TARGET = WorldPointUtil.packWorldPoint(2722, 5255, 1);

	@Test
	public void theWalkwayWinsOnceEachClimbCostsItsClick()
	{
		assertEquals("default modifier: stay on the upper level", 0, planeChanges(2));
		assertEquals("modifier off: the stairs win by a tick and a half", 2, planeChanges(0));
	}

	/** Plane changes on the owned-mode route with the object transport modifier at {@code costTransports}. */
	private static int planeChanges(int costTransports)
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
				return "calculationCutoff".equals(name) ? 120 : "costTransports".equals(name) ? costTransports : 0;
			}
			if (type == TeleportationItem.class)
			{
				return TeleportationItem.INVENTORY;
			}
			return HybridPageFillTest.defaultValue(type);
		});
		PathfinderConfig owned = new TestPathfinderConfig(client, config, QuestState.FINISHED, true, true);
		owned.refresh();
		CollisionMap map = new CollisionMap(SplitFlagMap.fromResources());
		Pathfinder pathfinder = new Pathfinder(owned, START, Destinations.walkableTargets(map, TARGET));
		pathfinder.run();
		List<PathStep> path = pathfinder.getPath();
		int changes = 0;
		for (int i = 1; i < path.size(); i++)
		{
			if (WorldPointUtil.unpackWorldPlane(path.get(i).getPackedPosition()) != WorldPointUtil.unpackWorldPlane(path.get(i - 1).getPackedPosition()))
			{
				changes++;
			}
		}
		return changes;
	}
}
