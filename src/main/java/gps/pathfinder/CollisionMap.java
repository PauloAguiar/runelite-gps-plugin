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
	//      * A transition into banked state, if the current tile is a bank.
	//      * Transition into abstract global teleport nodes, if we haven't tried that yet.
	private PrimitiveIntList getTileNeighbors(int node, VisitedTiles visited, PathfinderConfig config, int wildernessLevel, NodeGraph graph, TentativeCosts tentative)
	{
		final int packedPosition = graph.packedPosition(node);
		final int x = WorldPointUtil.unpackWorldX(packedPosition);
		final int y = WorldPointUtil.unpackWorldY(packedPosition);
		final int z = WorldPointUtil.unpackWorldPlane(packedPosition);

		neighbors.clear();

		// Either we have already visited a bank, if the current tile is a bank switch into the bankVisited state for the
		// rest of the path.
		boolean alreadyBanked = graph.bankVisited(node);
		boolean pathBankVisited = alreadyBanked
			|| (config.isBankPathEnabled() && config.bankAccessible(packedPosition));
		// Charge the bank-pickup penalty once, on every edge leaving the tile where the path first
		// enters the banked state, so banking only wins when it saves more than that many tiles overall.
		int bankEntryCost = (!alreadyBanked && pathBankVisited) ? config.getBankPickupCost() : 0;

		// Firstly check if there are any transports or teleports which are applicable from the current tile.
		Transport[] transports = config.getTransportsPacked(pathBankVisited).getOrDefault(packedPosition, TransportAvailability.EMPTY_TRANSPORTS);
		for (Transport transport : transports)
		{
			boolean delayedVisit = transport.getType().sharesDestinationsWith() != null;
			// Do not consider a transport if we have already visited its target tile.
			// For transports that share destinations with a teleport, skip this check
			// so both can compete in the priority queue (delayed visit).
			if (!delayedVisit && visited.get(transport.getDestination(), pathBankVisited))
			{
				continue;
			}
			// NB: Do not need to check for wilderness level for transports, since transports have specific origin tile.
			// The whistle differential is REAL cost inside getAdditionalTransportCost, so a chain
			// (whistle -> landing site A -> fly to B) prices itself above the direct whistle-to-B
			// teleport with no special inheritance.
			final int transportTravelTime = CostUnits.fromTicks(transport.getDuration());
			final int transportAdditionalCost = config.getAdditionalTransportCost(transport) + bankEntryCost;
			if (tentative != null && tentative.shouldPrune(transport.getDestination(), pathBankVisited,
				graph.cost(node) + Math.max(0, transportTravelTime + transportAdditionalCost)))
			{
				continue;
			}
			neighbors.add(graph.createTransport(
				transport.getDestination(),
				node,
				transportTravelTime,
				transportAdditionalCost,
				pathBankVisited,
				delayedVisit));
		}

		// Global teleports are only considered from an abstract node, so each
		// wilderness/bank state expands them once.
		AbstractNodeKind abstractKind = AbstractNodeKind.fromWildernessLevel(wildernessLevel);
		if (!visited.getAbstract(abstractKind, pathBankVisited))
		{
			neighbors.add(graph.createAbstract(abstractKind, node, pathBankVisited, bankEntryCost));
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

		// One walking step costs 1 (Chebyshev-adjacent) plus the one-off bank-entry surcharge.
		final int walkStepCost = graph.cost(node) + Math.max(0, 1 + bankEntryCost);
		for (int i = 0; i < traversable.length; i++)
		{
			OrdinalDirection d = ORDINAL_VALUES[i];
			int neighborPacked = packedPointFromOrdinal(packedPosition, d);
			if (visited.get(neighborPacked, pathBankVisited))
			{
				continue;
			}

			if (traversable[i])
			{
				if (tentative != null && tentative.shouldPrune(neighborPacked, pathBankVisited, walkStepCost))
				{
					continue;
				}
				neighbors.add(graph.createTile(neighborPacked, node, pathBankVisited, bankEntryCost));
			}
			else if (Math.abs(d.x + d.y) == 1 && isBlocked(x + d.x, y + d.y, z))
			{
				// The transport starts from a blocked adjacent tile, e.g. fairy ring
				// Only checks non-teleport transports (includes portals and levers, but not
				// items and spells)
				Transport[] neighborTransports = config.getTransportsPacked(pathBankVisited).getOrDefault(neighborPacked,
					TransportAvailability.EMPTY_TRANSPORTS);
				for (Transport transport : neighborTransports)
				{
					if (transport.getOrigin() == Transport.UNDEFINED_ORIGIN
						|| !(transport.isUsableAtWildernessLevel(wildernessLevel))
						|| visited.get(transport.getOrigin(), pathBankVisited))
					{
						continue;
					}
					if (tentative != null && tentative.shouldPrune(transport.getOrigin(), pathBankVisited,
						graph.cost(node) + Math.max(0, WorldPointUtil.distanceBetween(packedPosition, transport.getOrigin()) + bankEntryCost)))
					{
						continue;
					}
					neighbors.add(graph.createTile(transport.getOrigin(), node, pathBankVisited, bankEntryCost));
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
			boolean delayedVisit = transport.getType().sharesDestinationsWith() != null;
			if (!delayedVisit && visited.get(transport.getDestination(), bankVisited))
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
				bankVisited,
				delayedVisit));
		}
		return neighbors;
	}
}
