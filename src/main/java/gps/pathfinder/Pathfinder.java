package gps.pathfinder;

import java.util.List;
import java.util.Set;

import lombok.Getter;
import gps.PrimitiveIntList;
import gps.WorldPointUtil;
import gps.leagues.LeagueModeState;

public class Pathfinder implements Runnable
{
	private final PathfinderStats stats;
	@Getter
	private final int start;
	@Getter
	private final Set<Integer> targets;
	private final PathfinderConfig config;
	private final CollisionMap map;
	private final boolean targetInWilderness;
	private final boolean targetInBlockedRegion;
	private final Runnable completionCallback;
	// Nodes are stored structure-of-arrays style: each node is an int id into the graph, instead of
	// an object per explored tile. This keeps a whole search to a handful of arrays (issue #491).
	private final NodeGraph graph;
	// Capacities should be enough to store all nodes without requiring the queue to grow
	// They were found by checking the max queue size
	private final IntDeque boundary = new IntDeque(4096);
	private final IntMinHeap pending;
	// A* mode (heuristic attached): the ordering key is g + a consistent heuristic instead of g.
	private final boolean astar;
	// Heap mode: every node goes through a cost-ordered queue and visited-marking moves from
	// enqueue to dequeue (the first DEQUEUE of a state is via its cheapest parent; the first
	// ENQUEUE isn't necessarily). Engaged whenever the ordering can be non-uniform: a heuristic is
	// attached (A*), or ANY transport/teleport is usable — a popped transport feeds tiles back
	// into the FIFO at an arbitrary cost behind entries with larger costs, after which FIFO order
	// is no longer cost order and enqueue-marking claims tiles via non-cheapest parents (returning
	// non-minimal routes). The FIFO fast-path remains only for genuinely uniform-cost searches,
	// where its layer assumption actually holds.
	private final boolean heapMode;
	// The ordered queue for UNINFORMED heap-mode searches: a Dial's bucket queue keyed by g, whose
	// O(1) push/pop keeps the map-wide floods (nearest-X) at FIFO-era speed — the binary heap's
	// log n per node doubled their cpu. A* searches keep the heap: their key (g + h) can jump by
	// the heuristic delta, and the field heuristic already keeps them small.
	private final IntBucketQueue buckets;
	// Duplicate-enqueue pruner for the same searches: settle-dedup re-enqueues each tile from
	// every settled neighbour (~3-4x node volume); recording the best tentative cost per state
	// skips non-improving duplicates before their nodes are created. Improving duplicates still
	// go through, so costs stay exact.
	private final TentativeCosts tentative;
	private final VisitedTiles visited;
	@Getter
	private volatile boolean done = false;
	private volatile boolean cancelled = false;
	// Read by the render thread during the search to draw the partial path; written by the worker.
	private volatile int bestLastNode = NodeGraph.NO_NODE;
	// The path the render thread builds progressively while the search runs.
	private List<PathStep> pathSteps = List.of();
	private boolean pathNeedsUpdate = false;
	// Built once on the worker thread when the search finishes, then served to the render thread so
	// it never walks the node chain (which is released) after the search is done.
	private volatile List<PathStep> finalPath = null;
	// Accumulated cost of the path to bestLastNode, captured before the node graph is released so
	// alternative-route ranking has a total cost without re-walking the (released) chain.
	private volatile int finalCost = -1;
	private volatile int closestReachedPoint = WorldPointUtil.UNDEFINED;
	private int bestRemainingDistance = Integer.MAX_VALUE;
	private int bestTravelledDistance = Integer.MAX_VALUE;
	private int bestX = Integer.MAX_VALUE;
	private int bestY = Integer.MAX_VALUE;
	private int reachedTarget = WorldPointUtil.UNDEFINED;
	private PathTerminationReason terminationReason;
	// The targets as a plain array: the closest-tile tracking compares every target, and iterating
	// the boxed set there was measurable with large target sets ("nearest bank" has ~150).
	private final int[] targetArray;
	// Hard cost ceiling: nodes above it are never expanded. Used by the alternative-routes engine to
	// bound every search at the walk-only cost (routes costlier than walking are never shown), which
	// keeps a useless seed teleport from flooding the map. MAX_VALUE = uncapped.
	private final int costCap;
	// Closest-tile tracking is O(targets) per node, so during the search it only runs on every
	// UNREACHABLE_TRACK_INTERVAL-th tile (enough for the cutoff extension and the progressively
	// rendered partial path); the exact best tile is recovered in one post-pass when the search
	// ends without reaching a target.
	private static final int UNREACHABLE_TRACK_INTERVAL = 16;
	// Test-only invariant check: in heap mode the settled orderCost sequence must never decrease
	// (Dijkstra/consistent-A* property). A decrease means the heuristic overestimated somewhere
	// (inadmissibility) or an ordering-only term crept into the key — the failure modes behind
	// returned-but-not-minimal routes. Off in production; the optimality tests switch it on.
	public static volatile boolean validateSettleOrder = false;
	private int lastSettledOrderCost = Integer.MIN_VALUE;
	private int unreachableTrackCounter = 0;
	/**
	 * Teleportation transports are updated when this changes.
	 * Can be either:
	 * 0 = all teleports can be used (e.g. Chronicle)
	 * 20 = most teleports can be used (e.g. Varrock Teleport)
	 * 30 = some teleports can be used (e.g. Amulet of Glory)
	 * 31 = no teleports can be used
	 */
	private int wildernessLevel;

	public Pathfinder(PathfinderConfig config, int start, Set<Integer> targets, Runnable completionCallback)
	{
		this(config, start, targets, completionCallback, Integer.MAX_VALUE);
	}

	public Pathfinder(PathfinderConfig config, int start, Set<Integer> targets, Runnable completionCallback, int costCap)
	{
		this(config, start, targets, completionCallback, costCap, null);
	}

	public Pathfinder(PathfinderConfig config, int start, Set<Integer> targets, Runnable completionCallback,
		int costCap, SearchHeuristic heuristic)
	{
		stats = new PathfinderStats();
		this.config = config;
		this.map = config.getMap();
		this.start = start;
		this.targets = targets;
		this.completionCallback = completionCallback;
		this.costCap = costCap;
		this.astar = heuristic != null;
		this.heapMode = astar || anyTransportsUsable(config);
		this.buckets = (heapMode && !astar) ? new IntBucketQueue() : null;
		this.tentative = buckets != null ? new TentativeCosts(map) : null;
		this.graph = new NodeGraph(1 << 14, heuristic);
		this.pending = new IntMinHeap(graph, astar ? 4096 : 256);
		visited = new VisitedTiles(map);
		targetInWilderness = WildernessChecker.isInWilderness(targets);
		targetInBlockedRegion = anyInBlockedRegion(config.getLeagueModeState(), targets);
		wildernessLevel = 31;
		targetArray = new int[targets.size()];
		int i = 0;
		for (int target : targets)
		{
			targetArray[i++] = target;
		}
	}

	/** Whether this search runs with an A* heuristic (identical costs, smaller exploration). */
	public boolean isAstar()
	{
		return astar;
	}

	/** Whether any transport or teleport is usable — the trigger for full heap ordering. */
	private static boolean anyTransportsUsable(PathfinderConfig config)
	{
		return config.getTransportsPacked(false).size() > 0
			|| config.getTransportsPacked(true).size() > 0
			|| config.getUsableTeleports(false).length > 0
			|| config.getUsableTeleports(true).length > 0;
	}

	private static boolean anyInBlockedRegion(LeagueModeState league, Set<Integer> packed)
	{
		if (!league.isSeasonal() || packed == null || packed.isEmpty())
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

	public Pathfinder(PathfinderConfig config, int start, Set<Integer> targets)
	{
		this(config, start, targets, null);
	}

	public void cancel()
	{
		cancelled = true;
	}

	public PathfinderStats getStats()
	{
		if (stats.started && stats.ended)
		{
			return stats;
		}

		// Don't give incomplete results
		return null;
	}

	public List<PathStep> getPath()
	{
		int lastNode = bestLastNode; // For thread safety, read bestLastNode once
		if (lastNode == NodeGraph.NO_NODE)
		{
			List<PathStep> finalised = finalPath;
			return finalised != null ? finalised : pathSteps;
		}

		// Once the search is finished the node graph is released, so serve the pre-built snapshot.
		if (done)
		{
			List<PathStep> finalised = finalPath;
			if (finalised != null)
			{
				return finalised;
			}
		}

		if (pathNeedsUpdate)
		{
			List<PathStep> walked = graph.getPathSteps(lastNode);
			// An empty result means the graph was released mid-walk; keep the last good path.
			if (!walked.isEmpty())
			{
				pathSteps = walked;
				pathNeedsUpdate = false;
			}
		}

		return pathSteps;
	}

	public PathfinderResult getResult()
	{
		PathfinderStats currentStats = getStats();
		if (currentStats == null)
		{
			return null;
		}

		List<PathStep> currentPath = getPath();
		boolean reached = reachedTarget != WorldPointUtil.UNDEFINED;
		int target = reached ? reachedTarget : (targets.isEmpty() ? WorldPointUtil.UNDEFINED : targets.iterator().next());
		// getStats() only returns non-null once the search has ended, so the snapshot is set.
		return new PathfinderResult(
			start,
			target,
			reached,
			currentPath,
			closestReachedPoint,
			finalCost,
			currentStats.getNodesChecked(),
			currentStats.getTransportsChecked(),
			currentStats.getElapsedTimeNanos(),
			terminationReason
		);
	}

	private void addNeighbors(int node, boolean nodeIsTile, int nodePacked)
	{
		PrimitiveIntList nodes = map.getNeighbors(node, visited, config, wildernessLevel, targetInWilderness, graph, tentative);
		final int count = nodes.size();
		for (int i = 0; i < count; i++)
		{
			int neighbor = nodes.get(i);
			// Each graph.xxx(id) re-indexes a backing array, so read each neighbour field once and
			// reuse the loop-invariant node fields passed in (the JIT cached these for free when nodes
			// were objects, but not when they are int ids into structure-of-arrays storage).
			final boolean neighborIsTile = graph.isTile(neighbor);
			if (nodeIsTile && neighborIsTile)
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

			final boolean neighborIsTransport = graph.isTransport(neighbor);
			// Heap mode: nothing is marked at enqueue (the pop dedups instead — see the run loop)
			// and every node is ordered through the cost-ordered queue — the g-keyed bucket queue
			// for uninformed searches, the (g+h)-keyed heap for A*. Stats are counted at settle
			// (pop) rather than here: enqueues include duplicates, so counting them would make the
			// explored-size numbers incomparable with the FIFO search.
			if (heapMode)
			{
				if (buckets != null)
				{
					buckets.add(neighbor, graph.cost(neighbor));
				}
				else
				{
					pending.add(neighbor);
				}
				continue;
			}
			// For delayed-visit nodes (shared destinations), don't mark as visited on enqueue.
			// They will be checked and marked when dequeued from pending.
			if (!(neighborIsTransport && graph.isDelayedVisit(neighbor)))
			{
				visited.set(neighbor, graph);
			}
			if (neighborIsTransport)
			{
				pending.add(neighbor);
				++stats.transportsChecked;
			}
			else
			{
				boundary.addLast(neighbor);
				++stats.nodesChecked;
			}
		}
	}

	/**
	 * Pathfinding to an unreachable target is slightly different from normal pathfinding.
	 * Straight-line movement before diagonal movement is no longer prioritized, because the
	 * original target is moved to the closest reachable tile. To avoid having to move the
	 * original target we instead do the following to favour the closest reachable tile:
	 * - 1) Pick the path with the minimum Euclidean distance (no need to use square root though)
	 * - 2) If a tie occurs, pick the path with minimum travelled distance
	 * - 3) If another tie occurs, pick the path with minimum x-coordinate
	 * - 4) If another tie occurs, pick the path with minimum y-coordinate
	 */
	private boolean updateBestPathWhenUnreachable(int node, int packedPosition)
	{
		boolean update = false;

		final int travelledDistance = graph.cost(node);
		for (int target : targetArray)
		{
			int remainingDistance = WorldPointUtil.distanceBetween(target, packedPosition, WorldPointUtil.EUCLIDEAN_SQUARED_DISTANCE_METRIC);
			int x = WorldPointUtil.unpackWorldX(packedPosition);
			int y = WorldPointUtil.unpackWorldY(packedPosition);
			if ((remainingDistance < bestRemainingDistance) ||
				(remainingDistance == bestRemainingDistance && travelledDistance < bestTravelledDistance) ||
				(remainingDistance == bestRemainingDistance && travelledDistance == bestTravelledDistance && x < bestX) ||
				(remainingDistance == bestRemainingDistance && travelledDistance == bestTravelledDistance && x == bestX && y < bestY))
			{
				bestRemainingDistance = remainingDistance;
				bestTravelledDistance = travelledDistance;
				bestX = x;
				bestY = y;
				bestLastNode = node;
				pathNeedsUpdate = true;
				update = true;
			}
		}

		return update;
	}

	/**
	 * Update wilderness level based on the current node position.
	 */
	private void updateWildernessLevel(int packedPosition)
	{
		if (wildernessLevel > 0)
		{
			// These are overlapping boundaries, so if the node isn't in level 30, it's in 0-29
			// likewise, if the node isn't in level 20, it's in 0-19
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
		}
	}

	@Override
	public void run()
	{
		stats.start();
		boundary.addFirst(graph.createStart(start));

		long cutoffDurationMillis = config.getCalculationCutoffMillis();
		long cutoffTimeMillis = System.currentTimeMillis() + cutoffDurationMillis;

		while (!cancelled && (!boundary.isEmpty() || !pending.isEmpty()
			|| (buckets != null && !buckets.isEmpty())))
		{
			int boundaryHead = boundary.peekFirst();

			int node;
			boolean ordered;
			if (buckets != null)
			{
				// Uninformed heap mode: the boundary only ever holds the start; everything else
				// comes cost-ordered from the bucket queue.
				ordered = boundaryHead == NodeGraph.NO_NODE;
				node = ordered ? buckets.poll() : boundary.pollFirst();
			}
			else
			{
				int pendingHead = pending.peek();
				ordered = pendingHead != NodeGraph.NO_NODE
					&& (boundaryHead == NodeGraph.NO_NODE || graph.cost(pendingHead) < graph.cost(boundaryHead));
				node = ordered ? pending.poll() : boundary.pollFirst();
			}
			if (ordered)
			{
				// For delayed-visit nodes, check if the destination was already reached by a
				// cheaper path while this node was queued. In heap mode EVERY node dedups here:
				// nothing is marked at enqueue, and cost ordering (plus a consistent heuristic in
				// A* mode) makes the first dequeue optimal.
				if (heapMode || graph.isDelayedVisit(node))
				{
					if (visited.get(node, graph))
					{
						continue;
					}
					visited.set(node, graph);
				}
				if (validateSettleOrder && heapMode)
				{
					final int settledKey = graph.orderCost(node);
					if (settledKey < lastSettledOrderCost)
					{
						throw new IllegalStateException("Settle order violated: orderCost " + settledKey
							+ " settled after " + lastSettledOrderCost
							+ " — inadmissible heuristic or polluted ordering key");
					}
					lastSettledOrderCost = settledKey;
				}
				// Heap mode counts distinct settled states (enqueues include duplicates); the FIFO
				// search counts at enqueue, where marking makes every count unique.
				if (heapMode)
				{
					if (graph.isTransport(node))
					{
						++stats.transportsChecked;
					}
					else
					{
						++stats.nodesChecked;
					}
				}
			}
			if (node == NodeGraph.NO_NODE)
			{
				continue;
			}
			// Cost cap: a node above the ceiling can't be on any path worth returning (edges are
			// non-negative), so don't expand it. With the cap at the walk-only cost this bounds the
			// whole search to the area walking could cover anyway.
			if (graph.cost(node) > costCap)
			{
				continue;
			}
			// Read the node's tile-ness and position once; every graph.xxx(id) re-indexes a backing
			// array, and these are used by several of the checks below.
			final boolean nodeIsTile = graph.isTile(node);
			final int nodePacked = nodeIsTile ? graph.packedPosition(node) : WorldPointUtil.UNDEFINED;
			if (nodeIsTile)
			{
				updateWildernessLevel(nodePacked);

				if (targets.contains(nodePacked))
				{
					bestLastNode = node;
					pathNeedsUpdate = true;
					reachedTarget = nodePacked;
					terminationReason = PathTerminationReason.TARGET_REACHED;
					break;
				}

				// Sampled: this is O(targets) per call — with a big target set ("nearest bank" has
				// ~150) running it on every tile dominated the whole search. Every 16th tile is
				// enough to keep extending the cutoff and to grow the progressively rendered
				// partial path; the exact closest tile is recovered in the post-pass below.
				if ((++unreachableTrackCounter & (UNREACHABLE_TRACK_INTERVAL - 1)) == 0
					&& updateBestPathWhenUnreachable(node, nodePacked))
				{
					cutoffTimeMillis = System.currentTimeMillis() + cutoffDurationMillis;
				}
			}

			if (System.currentTimeMillis() > cutoffTimeMillis)
			{
				terminationReason = PathTerminationReason.CUTOFF_REACHED;
				break;
			}

			addNeighbors(node, nodeIsTile, nodePacked);
		}

		if (cancelled)
		{
			terminationReason = PathTerminationReason.CANCELLED;
		}
		else if (terminationReason == null)
		{
			terminationReason = PathTerminationReason.SEARCH_EXHAUSTED;
		}

		// The search ended without reaching a target: recover the exact closest tile (same
		// tie-breaking as the sampled in-loop tracking) in one pass over the explored nodes —
		// paying O(nodes × targets) once, instead of on every expansion.
		if (!cancelled && reachedTarget == WorldPointUtil.UNDEFINED && targetArray.length > 0)
		{
			for (int id = 0; id < graph.size(); id++)
			{
				if (graph.isTile(id))
				{
					updateBestPathWhenUnreachable(id, graph.packedPosition(id));
				}
			}
		}

		// Materialise the final path and closest reached tile on the worker thread, publish them,
		// then release the large node graph. Once done is set the render thread serves finalPath
		// and never touches the released graph, so this is race-free with progressive rendering.
		int lastNode = bestLastNode;
		if (lastNode != NodeGraph.NO_NODE)
		{
			finalPath = graph.getPathSteps(lastNode);
			closestReachedPoint = graph.getClosestTilePosition(lastNode);
			finalCost = graph.cost(lastNode);
		}
		else
		{
			finalPath = pathSteps;
			closestReachedPoint = start;
			finalCost = 0;
		}

		done = !cancelled;

		boundary.clear();
		visited.clear();
		pending.clear();
		if (buckets != null)
		{
			buckets.clear();
		}
		if (tentative != null)
		{
			tentative.clear();
		}
		graph.release();

		stats.end(); // Include cleanup in stats to get the total cost of pathfinding

		if (completionCallback != null)
		{
			completionCallback.run();
		}
	}

	public static class PathfinderStats
	{
		@Getter
		private int nodesChecked = 0, transportsChecked = 0;
		private long startNanos, endNanos;
		private volatile boolean started = false, ended = false;

		public int getTotalNodesChecked()
		{
			return nodesChecked + transportsChecked;
		}

		public long getElapsedTimeNanos()
		{
			return endNanos - startNanos;
		}

		private void start()
		{
			started = true;
			nodesChecked = 0;
			transportsChecked = 0;
			startNanos = System.nanoTime();
		}

		private void end()
		{
			endNanos = System.nanoTime();
			ended = true;
		}
	}
}
