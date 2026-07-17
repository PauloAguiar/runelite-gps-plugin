package gps.pathfinder;

import gps.PrimitiveIntList;
import gps.WorldPointUtil;
import gps.transport.Transport;

public class CollisionMap
{
	// Enum.values() makes copies every time which hurts performance in the hotpath
	private static final OrdinalDirection[] ORDINAL_VALUES = OrdinalDirection.values();

	private final SplitFlagMap collisionData;
	// This is only safe if pathfinding is single-threaded. Holds the ids of the neighbour nodes
	// appended to the NodeGraph during the most recent getNeighbors call.
	private final PrimitiveIntList neighbors = new PrimitiveIntList(16);
	private final boolean[] traversable = new boolean[8];

	public CollisionMap(SplitFlagMap collisionData)
	{
		this.collisionData = collisionData;
	}

	private static int packedPointFromOrdinal(int startPacked, OrdinalDirection direction)
	{
		final int x = WorldPointUtil.unpackWorldX(startPacked);
		final int y = WorldPointUtil.unpackWorldY(startPacked);
		final int plane = WorldPointUtil.unpackWorldPlane(startPacked);
		return WorldPointUtil.packWorldPoint(x + direction.x, y + direction.y, plane);
	}

	public byte getRegionPlaneCounts(int regionIndex)
	{
		return collisionData.getRegionPlaneCounts(regionIndex);
	}

	private boolean get(int x, int y, int z, int flag)
	{
		return collisionData.get(x, y, z, flag);
	}

	public boolean n(int x, int y, int z)
	{
		return get(x, y, z, 0);
	}

	public boolean s(int x, int y, int z)
	{
		return n(x, y - 1, z);
	}

	public boolean e(int x, int y, int z)
	{
		return get(x, y, z, 1);
	}

	public boolean w(int x, int y, int z)
	{
		return e(x - 1, y, z);
	}

	private boolean ne(int x, int y, int z)
	{
		return n(x, y, z) && e(x, y + 1, z) && e(x, y, z) && n(x + 1, y, z);
	}

	private boolean nw(int x, int y, int z)
	{
		return n(x, y, z) && w(x, y + 1, z) && w(x, y, z) && n(x - 1, y, z);
	}

	private boolean se(int x, int y, int z)
	{
		return s(x, y, z) && e(x, y - 1, z) && e(x, y, z) && s(x + 1, y, z);
	}

	private boolean sw(int x, int y, int z)
	{
		return s(x, y, z) && w(x, y - 1, z) && w(x, y, z) && s(x - 1, y, z);
	}

	public boolean isBlocked(int x, int y, int z)
	{
		return !n(x, y, z) && !s(x, y, z) && !e(x, y, z) && !w(x, y, z);
	}

	public PrimitiveIntList getNeighbors(int node, VisitedTiles visited, PathfinderConfig config, int wildernessLevel, boolean targetInWilderness, NodeGraph graph)
	{
		return getNeighbors(node, visited, config, wildernessLevel, targetInWilderness, graph, null);
	}

	/**
	 * {@code tentative} (nullable) is the duplicate-enqueue pruner for settle-dedup searches: a
	 * neighbour whose candidate cost does not improve on an already-queued arrival at the same
	 * (tile, bank) state is skipped before its node is even created.
	 */
	public PrimitiveIntList getNeighbors(int node, VisitedTiles visited, PathfinderConfig config, int wildernessLevel, boolean targetInWilderness, NodeGraph graph, TentativeCosts tentative)
	{
		if (graph.isTile(node))
		{
			return getTileNeighbors(node, visited, config, wildernessLevel, graph, tentative);
		}
		else
		{
			return getAbstractNodeNeighbors(node, visited, config, targetInWilderness, graph);
		}
	}

	// Get neighbours for a walkable tile:
	//      * Neighbouring tiles we can walk to
	//      * An OPTIONAL same-tile transition into the banked state, if the current tile is a bank
	//        (priced at the bank-pickup cost — the un-banked path continues in parallel).
	//      * Transition into abstract global teleport nodes, if we haven't tried that yet.
	private PrimitiveIntList getTileNeighbors(int node, VisitedTiles visited, PathfinderConfig config, int wildernessLevel, NodeGraph graph, TentativeCosts tentative)
	{
		final int packedPosition = graph.packedPosition(node);
		final int x = WorldPointUtil.unpackWorldX(packedPosition);
		final int y = WorldPointUtil.unpackWorldY(packedPosition);
		final int z = WorldPointUtil.unpackWorldPlane(packedPosition);

		neighbors.clear();

		// Banking is a CHOICE, not a consequence of standing near a booth: from an un-banked node
		// on a bank-accessible tile, offer a same-tile transition into the banked state priced at
		// the pickup cost — every other edge continues un-banked, free of the surcharge. (The flip
		// used to be FORCED on every path touching a bank tile, which charged routes that never
		// withdraw anything — e.g. every route starting at the GE — cancelling the surcharge out
		// of the route ranking, and made plain walking detour around bank plazas to dodge it.)
		final boolean bankVisited = graph.bankVisited(node);
		if (!bankVisited && config.isBankPathEnabled() && config.bankAccessible(packedPosition)
			&& !visited.get(packedPosition, true)
			&& (tentative == null || !tentative.shouldPrune(packedPosition, true,
				graph.cost(node) + config.getBankPickupCost())))
		{
			neighbors.add(graph.createTile(packedPosition, node, true, config.getBankPickupCost()));
		}

		// Firstly check if there are any transports or teleports which are applicable from the current tile.
		Transport[] transports = config.getTransportsPacked(bankVisited).getOrDefault(packedPosition, TransportAvailability.EMPTY_TRANSPORTS);
		for (Transport transport : transports)
		{
			// A transport to an already-SETTLED destination is pointless (settled = final under
			// cost-ordered settling); same-destination competitors race in the queue otherwise.
			if (visited.get(transport.getDestination(), bankVisited))
			{
				continue;
			}
			// NB: Do not need to check for wilderness level for transports, since transports have specific origin tile.
			// The whistle differential is REAL cost inside getAdditionalTransportCost, so a chain
			// (whistle -> landing site A -> fly to B) prices itself above the direct whistle-to-B
			// teleport with no special inheritance.
			final int transportTravelTime = CostUnits.fromTicks(transport.getDuration());
			final int transportAdditionalCost = config.getAdditionalTransportCost(transport);
			if (tentative != null && tentative.shouldPrune(transport.getDestination(), bankVisited,
				graph.cost(node) + Math.max(0, transportTravelTime + transportAdditionalCost)))
			{
				continue;
			}
			neighbors.add(graph.createTransport(
				transport.getDestination(),
				node,
				transportTravelTime,
				transportAdditionalCost,
				bankVisited));
		}

		// Global teleports are only considered from an abstract node, so each
		// wilderness/bank state expands them once.
		AbstractNodeKind abstractKind = AbstractNodeKind.fromWildernessLevel(wildernessLevel);
		if (!visited.getAbstract(abstractKind, bankVisited))
		{
			neighbors.add(graph.createAbstract(abstractKind, node, bankVisited, 0));
		}

		// Then add tiles which we can walk to, which go into the FIFO boundary queue.
		if (isBlocked(x, y, z))
		{
			boolean westBlocked = isBlocked(x - 1, y, z);
			boolean eastBlocked = isBlocked(x + 1, y, z);
			boolean southBlocked = isBlocked(x, y - 1, z);
			boolean northBlocked = isBlocked(x, y + 1, z);
			boolean southWestBlocked = isBlocked(x - 1, y - 1, z);
			boolean southEastBlocked = isBlocked(x + 1, y - 1, z);
			boolean northWestBlocked = isBlocked(x - 1, y + 1, z);
			boolean northEastBlocked = isBlocked(x + 1, y + 1, z);
			traversable[0] = !westBlocked;
			traversable[1] = !eastBlocked;
			traversable[2] = !southBlocked;
			traversable[3] = !northBlocked;
			traversable[4] = !southWestBlocked && !westBlocked && !southBlocked;
			traversable[5] = !southEastBlocked && !eastBlocked && !southBlocked;
			traversable[6] = !northWestBlocked && !westBlocked && !northBlocked;
			traversable[7] = !northEastBlocked && !eastBlocked && !northBlocked;
		}
		else
		{
			traversable[0] = w(x, y, z);
			traversable[1] = e(x, y, z);
			traversable[2] = s(x, y, z);
			traversable[3] = n(x, y, z);
			traversable[4] = sw(x, y, z);
			traversable[5] = se(x, y, z);
			traversable[6] = nw(x, y, z);
			traversable[7] = ne(x, y, z);
		}

		// One walking step costs 1 (Chebyshev-adjacent).
		final int walkStepCost = graph.cost(node) + 1;
		for (int i = 0; i < traversable.length; i++)
		{
			OrdinalDirection d = ORDINAL_VALUES[i];
			int neighborPacked = packedPointFromOrdinal(packedPosition, d);
			if (visited.get(neighborPacked, bankVisited))
			{
				continue;
			}

			if (traversable[i])
			{
				if (tentative != null && tentative.shouldPrune(neighborPacked, bankVisited, walkStepCost))
				{
					continue;
				}
				neighbors.add(graph.createTile(neighborPacked, node, bankVisited, 0));
			}
			else if (Math.abs(d.x + d.y) == 1 && isBlocked(x + d.x, y + d.y, z))
			{
				// The transport starts from a blocked adjacent tile, e.g. fairy ring
				// Only checks non-teleport transports (includes portals and levers, but not
				// items and spells)
				Transport[] neighborTransports = config.getTransportsPacked(bankVisited).getOrDefault(neighborPacked,
					TransportAvailability.EMPTY_TRANSPORTS);
				for (Transport transport : neighborTransports)
				{
					// Remapped transports (POH interiors re-homed onto the landing by MAP KEY) keep
					// their original origin field; stepping onto it would jump the path into the
					// house template. Only true blocked-tile transports (origin == the map key,
					// e.g. fairy rings) may be entered this way.
					if (transport.getOrigin() != neighborPacked
						|| !(transport.isUsableAtWildernessLevel(wildernessLevel))
						|| visited.get(transport.getOrigin(), bankVisited))
					{
						continue;
					}
					if (tentative != null && tentative.shouldPrune(transport.getOrigin(), bankVisited,
						graph.cost(node) + WorldPointUtil.distanceBetween(packedPosition, transport.getOrigin())))
					{
						continue;
					}
					neighbors.add(graph.createTile(transport.getOrigin(), node, bankVisited, 0));
				}
			}
		}

		return neighbors;
	}

	// The only abstract nodes are currently for global teleports
	private PrimitiveIntList getAbstractNodeNeighbors(int node, VisitedTiles visited, PathfinderConfig config,
		boolean targetInWilderness, NodeGraph graph)
	{
		neighbors.clear();
		int sourceTile = graph.getClosestTilePosition(node);
		boolean bankVisited = graph.bankVisited(node);
		int maxWildernessLevel = graph.abstractKind(node).maxWildernessLevel();
		for (Transport transport : config.getUsableTeleports(bankVisited))
		{
			if (visited.get(transport.getDestination(), bankVisited))
			{
				continue;
			}
			if (!transport.isUsableAtWildernessLevel(maxWildernessLevel))
			{
				continue;
			}
			if (config.avoidWilderness(sourceTile, transport.getDestination(), targetInWilderness))
			{
				continue;
			}
			neighbors.add(graph.createTransport(
				transport.getDestination(),
				node,
				CostUnits.fromTicks(transport.getDuration()),
				config.getAdditionalTransportCost(transport),
				bankVisited));
		}
		return neighbors;
	}
}
