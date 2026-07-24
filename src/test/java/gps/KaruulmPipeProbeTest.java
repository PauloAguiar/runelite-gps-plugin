package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.Mockito.when;

/**
 * Dev probe (enable with -DkaruulmPipeProbe=true): prints which tiles around each end of the
 * Mysterious pipe (88 Agility, id 34655) are walkable, to pick standing/landing tiles for the
 * agility_shortcuts.tsv rows without a field capture. Cache placements: (1316,10214,0) and
 * (1346,10231,0).
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class KaruulmPipeProbeTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	@Test
	public void printWalkableNeighbours()
	{
		Assume.assumeTrue(Boolean.getBoolean("karuulmPipeProbe"));
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		CollisionMap map = planning.getMap();
		int[][] pipes = {{1316, 10214}, {1346, 10231}};
		for (int[] pipe : pipes)
		{
			System.out.println("=== pipe at " + pipe[0] + "," + pipe[1] + ",0 ===");
			for (int dy = 2; dy >= -2; dy--)
			{
				StringBuilder line = new StringBuilder();
				for (int dx = -2; dx <= 2; dx++)
				{
					int x = pipe[0] + dx;
					int y = pipe[1] + dy;
					boolean blocked = map.isBlocked(x, y, 0);
					if (dx == 0 && dy == 0)
					{
						line.append(blocked ? " [X]" : " [.]");
					}
					else
					{
						line.append(blocked ? "  X " : "  . ");
					}
				}
				System.out.println("y=" + (pipe[1] + dy) + line);
			}
		}
	}
}
