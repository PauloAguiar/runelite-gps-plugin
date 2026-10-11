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
import net.runelite.api.QuestState;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * An axe stored at a canoe station is shared by every station and need not be withdrawn (wiki),
 * so with the "Axe stored at a canoe station" flag set a canoe needs no axe on the player: the
 * reporting player had stored theirs and was still sent to the bank for one (capture
 * gps-capture-20261011-123503).
 */
public class CanoeStoredAxeTest
{
	private static final int EDGEVILLE_STATION = WorldPointUtil.packWorldPoint(3132, 3510, 0);
	private static final int LUMBRIDGE_LANDING = WorldPointUtil.packWorldPoint(3240, 3242, 0);

	@Test
	public void aStoredAxePaddlesWithoutOneInTheBag()
	{
		Transport canoe = firstCanoe(true);
		assertNotNull("the canoe is the way", canoe);
		assertEquals("Lumbridge", canoe.getDisplayInfo());
	}

	@Test
	public void withoutTheFlagAnAxeIsStillNeeded()
	{
		assertNull(firstCanoe(false));
	}

	/** The route's first canoe from the Edgeville station to the Lumbridge landing with nothing carried, or null. */
	private static Transport firstCanoe(boolean axeStored)
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
				return "canoeAxeStored".equals(name) ? axeStored : !"avoidWilderness".equals(name);
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
		PathfinderConfig owned = new TestPathfinderConfig(client, config, QuestState.FINISHED, true, true);
		owned.refresh();
		CollisionMap map = new CollisionMap(SplitFlagMap.fromResources());
		Pathfinder pathfinder = new Pathfinder(owned, EDGEVILLE_STATION, Destinations.walkableTargets(map, LUMBRIDGE_LANDING));
		pathfinder.run();
		List<PathStep> path = pathfinder.getPath();
		for (int i = 1; i < path.size(); i++)
		{
			for (Transport transport : EdgeTransports.forEdge(owned, path.get(i - 1), path.get(i)))
			{
				if (TransportType.CANOE.equals(transport.getType()))
				{
					return transport;
				}
			}
		}
		return null;
	}
}
