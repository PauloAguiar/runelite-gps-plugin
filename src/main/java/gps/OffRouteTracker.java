package gps;

import gps.pathfinder.PathStep;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Off-route handling (plan step L10, out of the plugin class), in three bands of distance from
 * the path: on route (nothing), a warning band (the overlay shows a red "drifting off route"
 * message), and, on a MOVE that reaches the recalculate distance, a full recompute (or, if the
 * player prefers, cancelling the route). Recalculation fires only on movement so a stationary
 * far position (just teleported off-path) does not loop. With auto-recalculate off, GPS keeps
 * the original route and only ever warns. At the helm the bands stretch: a boat's wide turning
 * arcs swing off the decimated track farther than a walker drifts off a path. A boat cutscene or
 * a teleport landing carries the player far from the path in one leap; that is not drifting, so
 * a jump bigger than running arms a grace window that refreshes while the transport keeps moving
 * them and clears once they are back within the warning band. Client-thread owned; the warning
 * and the distance are read by the overlays.
 */
final class OffRouteTracker
{
	enum Verdict
	{
		ON_ROUTE,
		WARNING,
		RECALCULATE,
		CANCEL
	}

	// A single-tick displacement larger than running (2 tiles) means a transport is carrying the
	// player, not that they walked off route.
	static final int TRANSPORT_STEP_TILES = 3;
	// Ticks to keep suppressing after such a displacement (long enough to cover a boat cutscene).
	static final int TRANSPORT_GRACE_TICKS = 20;

	private int lastLocation = WorldPointUtil.packWorldPoint(0, 0, 0);
	private int graceTicks;
	private volatile int distance = -1;
	private volatile boolean warning;

	/** The distance from the path at the last tick, or -1 when unknown (no same-plane tile). */
	int distance()
	{
		return distance;
	}

	/** Whether the overlays should show the drifting-off-route warning. */
	boolean isWarning()
	{
		return warning;
	}

	/** Records where the player stands so the next tick is not read as a move from elsewhere. */
	void reset(int location)
	{
		lastLocation = location;
	}

	/**
	 * One game tick. {@code distanceFromPath} is consulted only when recalculation is enabled
	 * ({@code recalculateDistance >= 0}); it is the plugin's path scan. {@code warnDistance} is
	 * clamped into {@code [0, recalculateDistance]}. {@code aboard} stretches the bands (recalc x2,
	 * warn x3: field-tuned, 3x let the boat wander before a recalc rescued it, arcs fit inside 2x).
	 */
	Verdict tick(int location, IntSupplier distanceFromPath, int recalculateDistance, int warnDistance,
		boolean autoRecalculate, boolean cancelInstead, boolean aboard)
	{
		if (recalculateDistance < 0)
		{
			warning = false;
			return Verdict.ON_ROUTE;
		}
		int step = WorldPointUtil.distanceBetween(lastLocation, location);
		boolean moved = lastLocation != location;
		lastLocation = location;
		int d = distanceFromPath.getAsInt();
		distance = d;
		int recalc = recalculateDistance;
		int warn = Math.max(0, Math.min(warnDistance, recalc));
		if (aboard)
		{
			recalc *= 2;
			warn *= 3;
		}
		if (step > TRANSPORT_STEP_TILES)
		{
			graceTicks = TRANSPORT_GRACE_TICKS;
		}
		else if (graceTicks > 0)
		{
			graceTicks = (d >= 0 && d < warn) ? 0 : graceTicks - 1;
		}
		if (d < 0 || graceTicks > 0)
		{
			warning = false;
			return Verdict.ON_ROUTE;
		}
		if (moved && d >= recalc && autoRecalculate)
		{
			warning = false;
			return cancelInstead ? Verdict.CANCEL : Verdict.RECALCULATE;
		}
		warning = d >= warn;
		return warning ? Verdict.WARNING : Verdict.ON_ROUTE;
	}

	/**
	 * Chebyshev distance from {@code location} to the nearest tile of the displayed path, or -1
	 * without a path. Measured against the DISPLAYED route (the line the player follows), not a
	 * classic pathfinder path: when a search picked an alternative route those diverge, and
	 * measuring off the invisible one made off-route and recalculation misfire. A sailing leg
	 * contributes only its two endpoints to the path, so mid-sail the player would be hundreds
	 * of tiles off route by node distance: the legs' SEA TRACKS count too (cached waypoints);
	 * while a track is still computing the sailor is on route rather than measured against
	 * incomplete geometry.
	 */
	static int distanceFromPath(int location, List<PathStep> path, RouteOption displayed)
	{
		if (path == null || path.isEmpty())
		{
			return -1;
		}
		int best = Integer.MAX_VALUE;
		for (PathStep pathStep : path)
		{
			best = Math.min(best, WorldPointUtil.distanceBetween(location, pathStep.getPackedPosition()));
		}
		if (displayed != null && !displayed.sailingJumpDepartures().isEmpty() && SailingSea.isSailable(location))
		{
			for (int departure : displayed.sailingJumpDepartures())
			{
				if (departure < 0 || departure + 1 >= path.size())
				{
					continue;
				}
				int[] track = SailingSea.seaPath(path.get(departure).getPackedPosition(),
					path.get(departure + 1).getPackedPosition());
				if (track == null)
				{
					return 0;
				}
				for (int waypoint : track)
				{
					best = Math.min(best, WorldPointUtil.distanceBetween(location, waypoint));
				}
			}
		}
		return best;
	}
}
