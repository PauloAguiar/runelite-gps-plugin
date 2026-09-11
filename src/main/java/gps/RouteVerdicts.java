package gps;

import gps.pathfinder.PathStep;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Judgements about a route (plan step L28, out of the plugin class): whether its endpoint is
 * close enough to the targets to count as reached, and where along it the player cannot
 * click-walk yet. Pure over the route, the targets and the configured tolerance.
 */
final class RouteVerdicts
{
	private RouteVerdicts()
	{
	}

	/**
	 * Whether a route that stopped short ends too far from every target to count as reached for
	 * colouring: a route that stops at the closest reachable tile (the exact target being an NPC
	 * or object spot) still counts while its endpoint is within {@code tolerance}.
	 */
	static boolean endTooFar(RouteOption route, Set<Integer> targets, int tolerance)
	{
		if (route.isReached())
		{
			return false;
		}
		List<PathStep> path = route.getPath();
		if (path == null || path.isEmpty() || targets.isEmpty())
		{
			return false;
		}
		return closestTargetDistance(path, targets) > tolerance;
	}

	/**
	 * Whether a route actually gets to the target: the exact tile, or, for object and other
	 * adjacent destinations that legitimately end beside the goal (a bank booth, an altar),
	 * within {@code tolerance} of it. False means the destination cannot be reached and the route
	 * only got to the closest reachable tile. Judged on the route's own endpoint, so a target
	 * reachable only by a teleport is not misreported.
	 */
	static boolean reachesTarget(RouteOption route, Set<Integer> targets, int tolerance)
	{
		if (route == null)
		{
			return false;
		}
		if (route.isReached())
		{
			return true;
		}
		List<PathStep> path = route.getPath();
		if (path == null || path.isEmpty() || targets.isEmpty())
		{
			return true; // not enough information to declare it unreachable
		}
		return closestTargetDistance(path, targets) <= tolerance;
	}

	private static int closestTargetDistance(List<PathStep> path, Set<Integer> targets)
	{
		int endPoint = path.get(path.size() - 1).getPackedPosition();
		int closest = Integer.MAX_VALUE;
		for (int target : targets)
		{
			closest = Math.min(closest, WorldPointUtil.distanceBetween(target, endPoint));
		}
		return closest;
	}

	/**
	 * The first path index the player cannot click-walk to yet: at or beyond the first obstacle
	 * ahead of {@code progress} they must interact with to cross (an agility shortcut, stairs, or
	 * a door not seen open, per {@code doorOpen}). The path from there is drawn blocked.
	 * {@link Integer#MAX_VALUE} when nothing ahead blocks.
	 */
	static int blockedFromIndex(List<PathStep> path, List<RouteDirections.Step> steps, int progress,
		Predicate<ClosedDoors.Door> doorOpen)
	{
		for (RouteDirections.Step step : steps)
		{
			if (!step.gatesWalk() || step.getEndIndex() <= progress)
			{
				continue;
			}
			if (step.isDoor())
			{
				ClosedDoors.Door door = ClosedDoors.doorBetween(
					path.get(step.getStartIndex()).getPackedPosition(),
					path.get(step.getEndIndex()).getPackedPosition());
				if (door == null || doorOpen.test(door))
				{
					continue;
				}
			}
			return step.getEndIndex();
		}
		return Integer.MAX_VALUE;
	}
}
