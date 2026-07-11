package gps.pathfinder;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Set;
import gps.PrimitiveIntList;
import gps.WorldPointUtil;
import gps.leagues.LeagueModeState;

/**
 * Textbook Dijkstra over the exact same edge expansion as {@link Pathfinder} — the optimality
 * ORACLE. Every node (tile, transport, abstract) goes through one min-heap keyed by pure
 * accumulated cost g; a state settles at its first dequeue and is never revisited; a target
 * counts only when POPPED (which, with non-negative edges, is the proof its cost is minimal).
 * No FIFO fast-path, no ordering-only cost terms, no heuristic — nothing that could trade
 * exactness for speed.
 * <p>
 * Used by the optimality tests and the benchmark's audit pass to catch any production search
 * returning a non-minimal cost. Deliberately simple and boxed — never run in the client hot path.
 */
public final class ReferenceDijkstra
{
	/** Safety valve: a bugged scenario must fail the audit, not hang it. */
	private static final long MAX_SETTLES = 20_000_000L;

	private ReferenceDijkstra()
	{
	}

	/** The oracle's verdict: whether a target was reached, the minimal cost, and its path. */
	public static final class Result
	{
		public final boolean reached;
		public final int cost;
		public final java.util.List<PathStep> path;

		Result(boolean reached, int cost, java.util.List<PathStep> path)
		{
			this.reached = reached;
			this.cost = cost;
			this.path = path;
		}
	}

	/**
	 * Runs the reference search on the config's CURRENT availability (callers refresh/rebuild
	 * exclusions first, exactly as they would for a production search).
	 */
	public static Result search(PathfinderConfig config, int start, Set<Integer> targets)
	{
		final CollisionMap map = config.getMap();
		final NodeGraph graph = new NodeGraph(1 << 14, null);
		final VisitedTiles settled = new VisitedTiles(map);
		// Ordering key: graph.cost only (node costs are write-once, so the comparator is stable).
		final PriorityQueue<Integer> heap = new PriorityQueue<>(Comparator.comparingInt(graph::cost));
		final boolean targetInWilderness = WildernessChecker.isInWilderness(targets);
		final boolean targetInBlockedRegion = anyInBlockedRegion(config.getLeagueModeState(), targets);
		int wildernessLevel = 31;

		heap.add(graph.createStart(start));
		long budget = MAX_SETTLES;
		while (!heap.isEmpty() && budget-- > 0)
		{
			final int node = heap.poll();
			// Settle-time dedup for EVERY node kind: duplicates stay in the heap (lazy deletion)
			// and are discarded here; the first dequeue of a state is its cheapest arrival.
			if (settled.get(node, graph))
			{
				continue;
			}
			settled.set(node, graph);

			final boolean nodeIsTile = graph.isTile(node);
			final int nodePacked = nodeIsTile ? graph.packedPosition(node) : WorldPointUtil.UNDEFINED;
			if (nodeIsTile)
			{
				wildernessLevel = updateWildernessLevel(wildernessLevel, nodePacked);
				if (targets.contains(nodePacked))
				{
					return new Result(true, graph.cost(node), graph.getPathSteps(node));
				}
			}

			final PrimitiveIntList neighbors =
				map.getNeighbors(node, settled, config, wildernessLevel, targetInWilderness, graph);
			final int count = neighbors.size();
			for (int i = 0; i < count; i++)
			{
				final int neighbor = neighbors.get(i);
				// Mirror Pathfinder.addNeighbors' tile-edge filters exactly.
				if (nodeIsTile && graph.isTile(neighbor))
				{
					final int neighborPacked = graph.packedPosition(neighbor);
					if (config.avoidWilderness(nodePacked, neighborPacked, targetInWilderness))
					{
						continue;
					}
					if (config.avoidBlockedRegion(nodePacked, neighborPacked, targetInBlockedRegion))
					{
						continue;
					}
				}
				heap.add(neighbor);
			}
		}
		return new Result(false, -1, java.util.List.of());
	}

	/** Mirrors {@link Pathfinder}'s wilderness-level narrowing on settled tiles. */
	private static int updateWildernessLevel(int wildernessLevel, int packedPosition)
	{
		if (wildernessLevel > 30 && !WildernessChecker.isInLevel30Wilderness(packedPosition))
		{
			wildernessLevel = 30;
		}
		if (wildernessLevel > 20 && !WildernessChecker.isInLevel20Wilderness(packedPosition))
		{
			wildernessLevel = 20;
		}
		if (wildernessLevel > 0 && !WildernessChecker.isInWilderness(packedPosition))
		{
			wildernessLevel = 0;
		}
		return wildernessLevel;
	}

	private static boolean anyInBlockedRegion(LeagueModeState league, Set<Integer> packed)
	{
		if (league == null || !league.isSeasonal() || packed == null || packed.isEmpty())
		{
			return false;
		}
		for (Integer point : packed)
		{
			if (league.isInBlockedRegion(point))
			{
				return true;
			}
		}
		return false;
	}
}
