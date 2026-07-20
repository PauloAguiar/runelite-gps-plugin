package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.SplitFlagMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Sanity budget for the progress tracker's walk-distance flood on the REAL collision map: it
 * runs once per player-tile change (memoized in the overlay), so it must be comfortably
 * sub-millisecond. Fails only if it regresses catastrophically; prints the measured average.
 */
public class RouteProgressBenchTest
{
	@Test
	public void walkDistanceFloodIsSubMillisecond()
	{
		CollisionMap map = new CollisionMap(SplitFlagMap.fromResources());
		// Anchors near open ground; the scan below snaps each to a genuinely unblocked tile
		// (hand-picked tiles kept landing inside GE fences and castle walls).
		int[][] anchors = {{3164, 3487, 0}, {2465, 9650, 0}, {3222, 3218, 0}};
		int[] spots = new int[anchors.length];
		for (int a = 0; a < anchors.length; a++)
		{
			outer:
			for (int dx = 0; dx < 6; dx++)
			{
				for (int dy = 0; dy < 6; dy++)
				{
					if (!map.isBlocked(anchors[a][0] + dx, anchors[a][1] + dy, anchors[a][2]))
					{
						spots[a] = WorldPointUtil.packWorldPoint(
							anchors[a][0] + dx, anchors[a][1] + dy, anchors[a][2]);
						break outer;
					}
				}
			}
			assertTrue("no open tile near anchor " + a, spots[a] != 0);
		}
		// Warm-up (JIT + page-in).
		for (int i = 0; i < 200; i++)
		{
			RouteProgress.walkDistances(map, spots[i % spots.length], RouteProgress.REACH_RADIUS);
		}
		int iterations = 600;
		long start = System.nanoTime();
		int sink = 0;
		for (int i = 0; i < iterations; i++)
		{
			Map<Integer, Integer> walk =
				RouteProgress.walkDistances(map, spots[i % spots.length], RouteProgress.REACH_RADIUS);
			sink += walk.size();
		}
		long nanos = System.nanoTime() - start;
		double microsPerFlood = nanos / 1000.0 / iterations;
		System.out.println("walkDistances avg: " + String.format("%.1f", microsPerFlood)
			+ " us/flood (tiles per flood avg " + (sink / iterations) + ")");
		// Budget: one flood per tile moved. 2 ms would already be absurd; typical should be far less.
		assertTrue("walk flood too slow: " + microsPerFlood + " us", microsPerFlood < 2000);
	}
}
