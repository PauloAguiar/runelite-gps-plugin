package gps;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Track QUALITY as numbers, not eyeballs: for the field capture 212843 leg (the Pandemonium's
 * ship berth to open water at 2882,2872), report how many straight legs the helm must steer
 * and the closest the track ever comes to a blocked tile — then hold both to the bounds the
 * standoff/smoothing machinery promises. Run with -i to see the report in the test log.
 */
public class SeaTrackQualityTest
{
	private static final int PANDEMONIUM_BERTH_LAND = WorldPointUtil.packWorldPoint(3069, 2986, 0);
	private static final int CAPTURE_PIN = WorldPointUtil.packWorldPoint(2882, 2872, 0);
	/** Endpoint approaches may legally close in (port water); the open-sea body may not. */
	private static final int ENDPOINT_SLACK_POINTS = 3;

	@Test
	public void captureLegIsFewStraightLegsWithClearance()
	{
		int[] track = SailingSea.seaPathBlocking(PANDEMONIUM_BERTH_LAND, CAPTURE_PIN);
		assertTrue("track must exist", track != null && track.length > 2);

		// Real helm changes via the windowed corner detector — per-point bearings count the
		// integer stair-stepping of densified straight chords as phantom turns.
		int straightLegs = SailingSea.trackCorners(track).size() - 1;

		int minClearance = Integer.MAX_VALUE;
		int clearanceAtX = -1;
		int clearanceAtY = -1;
		for (int w = ENDPOINT_SLACK_POINTS; w < track.length - ENDPOINT_SLACK_POINTS; w++)
		{
			int x = WorldPointUtil.unpackWorldX(track[w]);
			int y = WorldPointUtil.unpackWorldY(track[w]);
			int clearance = clearanceAt(x, y);
			if (clearance < minClearance)
			{
				minClearance = clearance;
				clearanceAtX = x;
				clearanceAtY = y;
			}
		}

		System.out.println("TRACK QUALITY " + 3069 + "," + 2986 + " -> 2882,2872: "
			+ straightLegs + " straight leg(s) over " + track.length + " points; minimum "
			+ minClearance + " tile(s) of clearance (tightest at " + clearanceAtX + ","
			+ clearanceAtY + ")");

		assertTrue("a mostly open-water leg should need few helm changes, got " + straightLegs,
			straightLegs <= 10);
		assertTrue("the open-sea body of the track must keep the standoff, got "
				+ minClearance + " tile(s) at " + clearanceAtX + "," + clearanceAtY,
			minClearance >= 2);
	}

	/** Chebyshev distance from (x, y) to the nearest non-sailable tile, capped at 6. */
	private static int clearanceAt(int x, int y)
	{
		for (int radius = 1; radius <= 6; radius++)
		{
			for (int dx = -radius; dx <= radius; dx++)
			{
				for (int dy = -radius; dy <= radius; dy++)
				{
					if (Math.max(Math.abs(dx), Math.abs(dy)) == radius
						&& !SailingSea.isSailable(WorldPointUtil.packWorldPoint(x + dx, y + dy, 0)))
					{
						return radius;
					}
				}
			}
		}
		return 6;
	}

}
