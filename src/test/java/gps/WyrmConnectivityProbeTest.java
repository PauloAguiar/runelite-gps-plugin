package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.Mockito.when;

/** Is the Wyrmcraig landing (2562,2289) walk-connected to the island interior? */
@RunWith(MockitoJUnitRunner.Silent.class)
public class WyrmConnectivityProbeTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	@Test
	public void probe()
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		CollisionMap map = planning.getMap();
		int landing = WorldPointUtil.packWorldPoint(2575, 2291, 0);
		int pin = WorldPointUtil.packWorldPoint(2606, 2277, 0);
		Set<Integer> seen = new HashSet<>();
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		seen.add(landing);
		queue.add(landing);
		while (!queue.isEmpty())
		{
			int at = queue.poll();
			int x = WorldPointUtil.unpackWorldX(at);
			int y = WorldPointUtil.unpackWorldY(at);
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					int next = WorldPointUtil.packWorldPoint(x + dx, y + dy, 0);
					if ((dx != 0 || dy != 0) && !seen.contains(next) && map.canStep(at, next))
					{
						seen.add(next);
						queue.add(next);
					}
				}
			}
		}
		System.out.println("landing component: " + seen.size() + " tiles; contains pin: "
			+ seen.contains(pin));
		System.out.println("pin blocked: " + map.isBlocked(2606, 2277, 0)
			+ "; landing blocked: " + map.isBlocked(2562, 2289, 0));
	}
}
