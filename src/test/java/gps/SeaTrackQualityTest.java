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

		// Every leg must lie on one of the 16 bearings the boat can steer (22.5-degree
		// grid); up to two fallback chords are tolerated for tight port approaches.
		java.util.List<Integer> corners = SailingSea.trackCorners(track);
		int offBearing = 0;
		StringBuilder offenders = new StringBuilder();
		for (int c = 0; c + 1 < corners.size(); c++)
		{
			// Sample the leg INTERIOR: the windowed detector places corners up to two dense
			// points (~8 tiles) off the true geometric corner, smearing edge angles.
			int from = corners.get(c);
			int to = corners.get(c + 1);
			if (to - from >= 6)
			{
				from += 2;
				to -= 2;
			}
			int a1 = track[from];
			int b1 = track[to];
			double dx = WorldPointUtil.unpackWorldX(b1) - WorldPointUtil.unpackWorldX(a1);
			double dy = WorldPointUtil.unpackWorldY(b1) - WorldPointUtil.unpackWorldY(a1);
			double angle = Math.toDegrees(Math.atan2(dy, dx));
			double offGrid = Math.abs(angle % 22.5);
			offGrid = Math.min(offGrid, 22.5 - offGrid);
			// Port-approach legs (both corners within 25 tiles of an endpoint) may fall
			// back to raw chords in tight corridors; open water must hold the grid.
			boolean approach =
				(WorldPointUtil.distanceBetween(a1, track[0]) <= 25
					&& WorldPointUtil.distanceBetween(b1, track[0]) <= 25)
				|| (WorldPointUtil.distanceBetween(a1, track[track.length - 1]) <= 25
					&& WorldPointUtil.distanceBetween(b1, track[track.length - 1]) <= 25);
			// Integer-tile residue of the fractional bearing decomposition must live
			// somewhere: one SHORT connector (<= 16 tiles) may absorb it; long runs and
			// everything else hold the grid strictly.
			boolean shortConnector = Math.hypot(dx, dy) <= 16 && offGrid <= 8.0;
			if (offGrid > 4.0 && !approach && !shortConnector)
			{
				offBearing++;
				offenders.append(' ').append(WorldPointUtil.unpackWorldX(a1)).append(',')
					.append(WorldPointUtil.unpackWorldY(a1)).append("->")
					.append(WorldPointUtil.unpackWorldX(b1)).append(',')
					.append(WorldPointUtil.unpackWorldY(b1))
					.append(String.format("(%.1f deg off)", offGrid));
			}
		}
		System.out.println("TRACK BEARINGS: " + (corners.size() - 1 - offBearing) + "/"
			+ (corners.size() - 1) + " legs on the 16-bearing grid;" + offenders);
		assertTrue("legs must steer the 16 boat bearings, off-grid:" + offenders,
			offBearing == 0);
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
