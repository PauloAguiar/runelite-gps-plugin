package gps;

import gps.pathfinder.PathStep;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TransportAvailability;
import gps.transport.Transport;
import gps.transport.TransportType;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The candidate transports for a rendered path edge (plan step L23, out of the plugin class),
 * re-derived from the current path state for display code. Path display is edge-based, not
 * node-based: the origin comes from the current step, the destination from the next, and the
 * applicable set may depend on whether the edge transitions into the banked state. Banking is
 * not its own path edge: the "becomes banked" change is conflated into the edge that reaches
 * the banked step, so neither step alone resolves the transports; the edge does. This is a
 * fallback for display and stays ambiguous when several valid transports share an origin and
 * destination under the same edge state; the structural fix would be explicit edges on
 * reconstructed paths.
 */
final class EdgeTransports
{
	private EdgeTransports()
	{
	}

	/**
	 * The transports that ride the edge from {@code current} to {@code next}: the local ones
	 * from the current tile plus the anywhere-teleports landing on the next, filtered while
	 * collecting because this runs per edge per frame. A same-plane adjacent edge is a plain
	 * walking step, so no teleport is hinted on it; a teleport whose type shares destinations
	 * with a local type (the quetzal whistle and the quetzal) is dropped when that local type is
	 * on the edge, or when the edge is within the shared type's radius (the path is walking to
	 * the landing site, not teleporting there).
	 */
	static Set<Transport> forEdge(PathfinderConfig config, PathStep current, PathStep next)
	{
		if (current == null || next == null)
		{
			return Set.of();
		}
		boolean bankVisited = current.isBankVisited() || next.isBankVisited();
		final int landing = next.getPackedPosition();
		Set<Transport> stepTransports = new HashSet<>();
		for (Transport transport : config.getTransportsPacked(bankVisited)
			.getOrDefault(current.getPackedPosition(), TransportAvailability.EMPTY_TRANSPORTS))
		{
			if (transport.getDestination() == landing)
			{
				stepTransports.add(transport);
			}
		}
		for (Transport transport : config.getUsableTeleports(bankVisited))
		{
			if (transport.getDestination() == landing)
			{
				stepTransports.add(transport);
			}
		}
		Set<TransportType> localTypes = EnumSet.noneOf(TransportType.class);
		for (Transport t : stepTransports)
		{
			if (t.getOrigin() != Transport.UNDEFINED_ORIGIN && t.getType() != null)
			{
				localTypes.add(t.getType());
			}
		}
		int edgeDistance = WorldPointUtil.distanceBetween2D(current.getPackedPosition(), next.getPackedPosition());
		boolean samePlane = WorldPointUtil.unpackWorldPlane(current.getPackedPosition())
			== WorldPointUtil.unpackWorldPlane(next.getPackedPosition());
		stepTransports.removeIf(t ->
		{
			if (t.getOrigin() != Transport.UNDEFINED_ORIGIN || t.getType() == null)
			{
				return false; // local transports stay
			}
			if (samePlane && edgeDistance <= 1)
			{
				return true; // a walking step across a teleport's landing tile
			}
			TransportType sharedType = t.getType().sharesDestinationsWith();
			if (sharedType == null)
			{
				return false;
			}
			return localTypes.contains(sharedType)
				|| (sharedType.getRadiusThreshold() != null && edgeDistance <= sharedType.getRadiusThreshold());
		});
		return stepTransports;
	}

	/** The step after {@code index}, or null at the end of the path. */
	static PathStep nextStep(List<PathStep> path, int index)
	{
		if (path == null || index < 0 || index + 1 >= path.size())
		{
			return null;
		}
		return path.get(index + 1);
	}
}
