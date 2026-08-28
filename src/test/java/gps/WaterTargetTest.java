package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import java.lang.reflect.Proxy;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertTrue;

/**
 * Capture 20260827-220818: a pin on open water was expanded to its nearest LAND ring only, so
 * the sailing route to the actual spot could never compete - the search walked to the shore.
 * A sailable pin keeps its water tile in the target set alongside the land ring: aboard, the
 * sea legs settle it exactly; on foot, the shore still serves.
 */
public class WaterTargetTest
{
	@Test
	public void sailablePinKeepsItsWaterTileAmongTheTargets()
	{
		final Thread ct = Thread.currentThread();
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getGameState": return GameState.LOGGED_IN;
					case "getClientThread": return ct;
					case "getBoostedSkillLevel": return 99;
					default: return HybridPageFillTest.defaultValue(method.getReturnType());
				}
			});
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class, Mockito.withSettings().stubOnly());
		Mockito.when(cfg.calculationCutoff()).thenReturn(30);
		PathfinderConfig planning = new TestPathfinderConfig(client, cfg).copyForPlanning();
		planning.refresh();
		CollisionMap map = planning.getMap();

		// Mid-ocean pin (Bay of Sarim label): sailable, far from land - the water tile IS the target.
		int oceanPin = WorldPointUtil.packWorldPoint(3070, 3177, 0);
		assertTrue("the fixture pin must be sailable water", SailingSea.isSailable(oceanPin));
		assertTrue("an ocean pin's water tile must stay a target (the boat route's settle point)",
			Destinations.walkableTargets(map, oceanPin).contains(oceanPin));

		// Near-shore water: find a sailable tile within ring distance of Port Sarim's dock, and
		// expect BOTH the water tile (boat) and a land ring (on foot) among its targets.
		int nearShore = -1;
		outer:
		for (int r = 1; r <= 6; r++)
		{
			for (int dx = -r; dx <= r; dx++)
			{
				for (int dy = -r; dy <= r; dy++)
				{
					int tile = WorldPointUtil.packWorldPoint(3038 + dx, 3192 + dy, 0);
					if (SailingSea.isSailable(tile))
					{
						nearShore = tile;
						break outer;
					}
				}
			}
		}
		assertTrue("a sailable tile near the Port Sarim dock must exist", nearShore != -1);
		Set<Integer> targets = Destinations.walkableTargets(map, nearShore);
		assertTrue("the water tile itself must stay a target", targets.contains(nearShore));
		assertTrue("the land ring must also serve the pin on foot: " + targets.size(),
			targets.size() > 1);
	}
}
