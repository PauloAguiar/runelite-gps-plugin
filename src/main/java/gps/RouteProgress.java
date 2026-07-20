package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathStep;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure route-progress selection: which path index the player has honestly reached. Extracted from
 * the directions overlay so the maze rules are unit-testable.
 * <p>
 * Straight-line proximity is wall-blind — standing one tile OUTSIDE a wall used to snap progress
 * onto an unreachable path tile, and maze corridors that double back within a few tiles teleported
 * the marker across walls. Eligibility is therefore based on WALKING distance (a small bounded BFS
 * through the collision map around the player): a candidate tile must be genuinely walkable-to in
 * roughly its straight-line distance, or it doesn't count. When no collision map is available yet,
 * the legacy straight-line rules apply unchanged.
 */
final class RouteProgress
{
	/** Max index drift per update for near-line matches (honest travel, not teleports). */
	static final int STEP_WINDOW = 8;
	/** Max straight-line distance for near-line matches. */
	static final int NEAR_DISTANCE = 10;
	/** Walking may exceed the straight line by this much before a match reads as through-a-wall. */
	static final int REACH_SLACK = 2;
	/** BFS radius: the largest walking distance any eligibility rule can accept. */
	static final int REACH_RADIUS = NEAR_DISTANCE + REACH_SLACK;

	private RouteProgress()
	{
	}

	/** A selection: the reached path index and the walking distance to it (for the ETA). */
	static final class Result
	{
		final int index;
		final int distance;

		Result(int index, int distance)
		{
			this.index = index;
			this.distance = distance;
		}
	}

	/**
	 * Walking distances (in steps, diagonals allowed) from the player to every tile within
	 * {@code radius} steps, honouring walls via {@link CollisionMap#canStep}. Null when no map is
	 * available (callers fall back to straight-line rules).
	 */
	static Map<Integer, Integer> walkDistances(CollisionMap map, int fromPacked, int radius)
	{
		if (map == null)
		{
			return null;
		}
		Map<Integer, Integer> distances = new HashMap<>();
		distances.put(fromPacked, 0);
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		queue.add(fromPacked);
		int x = WorldPointUtil.unpackWorldX(fromPacked);
		int y = WorldPointUtil.unpackWorldY(fromPacked);
		int plane = WorldPointUtil.unpackWorldPlane(fromPacked);
		while (!queue.isEmpty())
		{
			int tile = queue.poll();
			int steps = distances.get(tile);
			if (steps >= radius)
			{
				continue;
			}
			int tx = WorldPointUtil.unpackWorldX(tile);
			int ty = WorldPointUtil.unpackWorldY(tile);
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					if (dx == 0 && dy == 0)
					{
						continue;
					}
					int next = WorldPointUtil.packWorldPoint(tx + dx, ty + dy, plane);
					if (!distances.containsKey(next)
						&& Math.max(Math.abs(tx + dx - x), Math.abs(ty + dy - y)) <= radius
						&& map.canStep(tile, next))
					{
						distances.put(next, steps + 1);
						queue.add(next);
					}
				}
			}
		}
		return distances;
	}

	/**
	 * The path index the player has reached, or null to hold the previous estimate.
	 *
	 * @param path        the route's tiles
	 * @param reachedIndex the previous selection (index drift is measured from here)
	 * @param doorGate    first path index at/past an uncrossed door — only exact on-tile presence
	 *                    counts from there on
	 * @param returnGate  first index of a round trip's return leg while still outbound
	 *                    ({@code Integer.MAX_VALUE} when not applicable)
	 * @param playerPacked the player's tile
	 * @param walk        walking distances around the player, or null for legacy straight-line rules
	 */
	static Result select(List<PathStep> path, int reachedIndex, int doorGate, int returnGate,
		int playerPacked, Map<Integer, Integer> walk)
	{
		int best = -1;
		int bestDistance = Integer.MAX_VALUE;
		int bestOffset = Integer.MAX_VALUE;
		for (int i = 0; i < path.size(); i++)
		{
			if (i > returnGate)
			{
				break;
			}
			int packed = path.get(i).getPackedPosition();
			// distanceBetween is MAX_VALUE across planes, so other-plane tiles filter out here.
			int straight = WorldPointUtil.distanceBetween(packed, playerPacked);
			Integer walked = walk != null ? walk.get(packed) : null;
			boolean beyondDoor = i >= doorGate;
			boolean eligible;
			int metric;
			if (walk != null)
			{
				// Wall-aware rules: same tile always counts; "on path" needs a genuinely walkable
				// single step; near-line matches must be walkable in about their straight-line
				// distance, or the "closeness" is through a wall.
				if (beyondDoor)
				{
					eligible = straight == 0;
				}
				else if (walked != null && walked <= 1)
				{
					eligible = true;
				}
				else
				{
					eligible = walked != null
						&& Math.abs(i - reachedIndex) <= STEP_WINDOW
						&& straight <= NEAR_DISTANCE
						&& walked <= straight + REACH_SLACK;
				}
				metric = walked != null ? walked : straight;
			}
			else
			{
				// Legacy straight-line rules (no collision map yet).
				boolean incremental = !beyondDoor && Math.abs(i - reachedIndex) <= STEP_WINDOW
					&& straight <= NEAR_DISTANCE;
				boolean onPath = straight <= (beyondDoor ? 0 : 1);
				eligible = incremental || onPath;
				metric = straight;
			}
			if (!eligible)
			{
				continue;
			}
			int offset = Math.abs(i - reachedIndex);
			if (metric < bestDistance || (metric == bestDistance && offset < bestOffset))
			{
				best = i;
				bestDistance = metric;
				bestOffset = offset;
			}
		}
		return best < 0 ? null : new Result(best, bestDistance);
	}
}
