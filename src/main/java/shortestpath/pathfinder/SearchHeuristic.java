package shortestpath.pathfinder;

import shortestpath.WorldPointUtil;
import shortestpath.transport.Transport;

/**
 * A consistent (and therefore admissible) A* heuristic for one search: a lower bound on the
 * remaining cost from any node to the target set, so the search prefers nodes heading toward the
 * target instead of flooding a Dijkstra disc in every direction. Guarantees identical costs to the
 * uninformed search — only the exploration order (and size) changes.
 * <p>
 * Backed by a per-generation {@link DistanceField} (one multi-source reverse flood from the target
 * set over walking edges and reversed origin-bound transports): {@code h(n) = min(field(n), floor)}
 * where {@code floor} is the cheapest jump this search's own usable origin-less teleports offer —
 * {@code max(0, duration + configured weight) + field(landing)}. The floor is what keeps global
 * teleports from breaking admissibility (a node 2000 tiles away is 8 units from the target if a
 * teleport lands there), and computing it per search from the search's post-exclusion availability
 * makes the heuristic STRONGER exactly when searches get expensive: excluding the good teleports
 * raises the floor.
 * <p>
 * Consistency argument (h(a) &le; edge(a,b) + h(b) for every edge kind): walking and origin-bound
 * transport edges satisfy the field's own shortest-path triangle inequality (their reversed edges
 * are inside the flood); a global-teleport edge from the abstract hub to a landing satisfies
 * {@code floor <= effCost + field(landing)} by the floor's definition; abstract hub nodes take
 * {@code h = floor}, which every tile's h is already &le;. Unflooded (reverse-unreachable) tiles
 * also take the floor — reaching a target from them requires a global teleport, which is exactly
 * what the floor bounds. Consistent h means the first settle of a state is optimal — the engine
 * needs no reopening. There is no weak regime to gate against: a flat field-heuristic implies a
 * genuinely cheap route, and therefore a small search anyway.
 */
public final class SearchHeuristic
{
	/**
	 * Widest target-set span the distance field is built for: a map-wide "nearest X" set would
	 * flood everything for searches that are already cheap, and h ~ 0 everywhere regardless.
	 */
	static final int MAX_TARGET_SPAN = 256;
	// The floor with no usable teleport at all (e.g. the walk-only search). Kept far below
	// Integer.MAX_VALUE so orderCost = cost + h can never overflow.
	static final int UNBOUNDED_FLOOR = 1 << 20;

	private final DistanceField field;
	private final int floor;

	private SearchHeuristic(DistanceField field, int floor)
	{
		this.field = field;
		this.floor = floor;
	}

	/**
	 * The heuristic value for a node position; {@link WorldPointUtil#UNDEFINED} (abstract
	 * global-teleport hub nodes) and unflooded tiles get the floor.
	 */
	public int of(int packedPosition)
	{
		if (packedPosition == WorldPointUtil.UNDEFINED)
		{
			return floor;
		}
		final int distance = field.distance(packedPosition);
		return distance >= floor ? floor : distance;
	}

	int floor()
	{
		return floor;
	}

	/**
	 * Builds the heuristic for one search over the given config's CURRENT (post-exclusion)
	 * availability, or null when there is no field (map-wide target sets — the caller falls back
	 * to the uninformed search).
	 */
	public static SearchHeuristic buildWithField(PathfinderConfig config, DistanceField field)
	{
		if (field == null)
		{
			return null;
		}
		int floor = UNBOUNDED_FLOOR;
		// Both bank states: a path may flip into the banked state mid-search, so the floor must
		// cover every teleport either state can use (a superset only lowers the floor = safe).
		for (boolean bankVisited : new boolean[]{false, true})
		{
			for (Transport teleport : config.getUsableTeleports(bankVisited))
			{
				final int destination = teleport.getDestination();
				if (destination == WorldPointUtil.UNDEFINED)
				{
					continue;
				}
				final int landingDistance = field.distance(destination);
				if (landingDistance == DistanceField.UNREACHED)
				{
					continue;
				}
				final int effectiveCost = Math.max(0,
					CostUnits.fromTicks(teleport.getDuration()) + config.getAdditionalTransportCost(teleport));
				floor = Math.min(floor, effectiveCost + landingDistance);
			}
		}
		return new SearchHeuristic(field, floor);
	}
}
