package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.Pathfinder;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.Mockito.when;

/**
 * Dev probe (-DkebosMineProbe=true) for gps-capture-20260724-180802: route from the Kebos
 * Lowlands shore (1235,3656) to the mine cave (1211,3648) walked 66 tiles through the south
 * instead of cutting west. Prints the pathfinder's path, an edge-aware BFS distance (fences
 * are EDGE flags, invisible to tile-walkability grids), and where the direct corridor is
 * actually severed.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class KebosMineProbeTest
{
	private static final int START = WorldPointUtil.packWorldPoint(1235, 3656, 0);
	private static final int TARGET = WorldPointUtil.packWorldPoint(1211, 3648, 0);

	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	@Test
	public void probe()
	{
		Assume.assumeTrue(Boolean.getBoolean("kebosMineProbe"));
		when(config.calculationCutoff()).thenReturn(120);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		CollisionMap map = planning.getMap();

		Pathfinder pathfinder = new Pathfinder(planning, START, Set.of(TARGET));
		pathfinder.run();
		System.out.println("pathfinder cost=" + pathfinder.getResult().getTotalCost()
			+ " steps=" + pathfinder.getResult().getPathSteps().size());

		// Edge-aware BFS over the local box: the true walk distance with fences honoured.
		Map<Integer, Integer> dist = new HashMap<>();
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		dist.put(START, 0);
		queue.add(START);
		while (!queue.isEmpty())
		{
			int at = queue.poll();
			int x = WorldPointUtil.unpackWorldX(at);
			int y = WorldPointUtil.unpackWorldY(at);
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					if (dx == 0 && dy == 0)
					{
						continue;
					}
					int nx = x + dx;
					int ny = y + dy;
					if (nx < 1195 || nx > 1245 || ny < 3620 || ny > 3665)
					{
						continue;
					}
					int next = WorldPointUtil.packWorldPoint(nx, ny, 0);
					if (dist.containsKey(next) || !map.canStep(at, next))
					{
						continue;
					}
					dist.put(next, dist.get(at) + 1);
					queue.add(next);
				}
			}
		}
		System.out.println("edge-aware BFS distance to target: " + dist.getOrDefault(TARGET, -1));

		// The BFS wavefront as a grid: distance mod 10 per tile ('#' unreachable-or-blocked).
		// The frontier between low numbers (near start) and high/blank shows the barrier shape.
		System.out.println("     " + "0123456789".repeat(5).substring(0, 1245 - 1198 + 1));
		for (int y = 3660; y >= 3628; y--)
		{
			StringBuilder line = new StringBuilder(y + " ");
			for (int x = 1198; x <= 1245; x++)
			{
				Integer distance = dist.get(WorldPointUtil.packWorldPoint(x, y, 0));
				line.append(distance == null ? '#' : (char) ('0' + distance % 10));
			}
			System.out.println(line);
		}
	}
}
