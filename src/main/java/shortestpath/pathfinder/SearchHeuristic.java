package shortestpath.pathfinder;

import java.util.Set;
import shortestpath.PrimitiveIntHashMap;
import shortestpath.WorldPointUtil;
import shortestpath.transport.Transport;

/**
 * A consistent (and therefore admissible) A* heuristic for one search: a lower bound on the
 * remaining cost from any node to the target set, so the search can prefer nodes heading toward
 * the target instead of flooding a Dijkstra disc in every direction. Guarantees identical costs
 * to the uninformed search — only the exploration order (and size) changes.
 * <p>
 * Shape: {@code h(n) = min(chebyshev2D(n, targetBox), floor)} where {@code targetBox} is the 2D
 * bounding box of all target tiles and {@code floor} is the cheapest possible "jump" toward that
 * box. The floor is what keeps teleports from breaking admissibility: without it, a node 2000
 * tiles away would get h = 2000 even though a teleport could cover that for 8 units.
 * <p>
 * The floor is the minimum, over the transports this search can actually use, of
 * {@code max(0, duration + configured weight) + chebyshev2D(landing, targetBox)} — restricted to
 * transports that are true <em>shortcuts</em>:
 * <ul>
 * <li>Origin-less (global) teleports — castable from anywhere, always included.</li>
 * <li>Origin-bound transports whose 2D displacement exceeds their cost (spirit trees, gliders,
 * long agility shortcuts). A ladder or door moves less than it costs, and the walking term
 * already lower-bounds any path through it (triangle inequality: reaching its origin costs at
 * least the origin's own chebyshev distance), so it cannot constrain the floor. This matters:
 * a ladder right at the target would otherwise collapse the floor to ~2 and neuter the
 * heuristic for the whole map.</li>
 * </ul>
 * Consistency argument (h(a) &le; edge(a,b) + h(b) for every edge kind): walking moves 1 tile per
 * unit so the box distance changes by at most the edge cost; a non-shortcut transport edge is
 * covered by the triangle inequality above; a shortcut transport edge T satisfies
 * {@code floor <= effCost(T) + chebBox(dest(T))} by the floor's own definition; and abstract
 * (global-teleport hub) nodes take {@code h = floor}, which every tile's h is already &le; and
 * every teleport landing's term is already &ge;. Consistent h means the first settle of a state
 * is optimal — the engine needs no reopening.
 */
public final class SearchHeuristic
{
	/**
	 * Engagement gates: below this floor the heuristic is nearly flat, so A* pays its heap and
	 * pop-dedup overhead for no guidance (measured worst case: a floor-8 banked route ran ~2x
	 * slower). Floors just above it are already worth it — when the cheapest teleport corridor is
	 * the answer, the floor lands near the final cost and the search collapses to that corridor
	 * (measured: 188k -> 282 nodes on a floor-33 teleport trip). A target box wider than the span
	 * gate (a "nearest X" query across the map) makes h ~ 0 everywhere, same story.
	 * {@link #build(PathfinderConfig, Set)} returns null in both cases so callers fall back to the
	 * uninformed search.
	 */
	static final int MIN_USEFUL_FLOOR = 16;
	static final int MAX_TARGET_SPAN = 256;
	// The floor with no usable shortcut at all (e.g. the walk-only search). Kept far below
	// Integer.MAX_VALUE so orderCost = cost + h can never overflow.
	static final int UNBOUNDED_FLOOR = 1 << 20;

	private final int minX;
	private final int maxX;
	private final int minY;
	private final int maxY;
	private final int floor;
	// Field-backed mode: h comes from the target-rooted distance field instead of the box
	// distance — near-exact guidance (true walking + origin-bound-transport distances).
	private final DistanceField field;

	private SearchHeuristic(int minX, int maxX, int minY, int maxY, int floor)
	{
		this.minX = minX;
		this.maxX = maxX;
		this.minY = minY;
		this.maxY = maxY;
		this.floor = floor;
		this.field = null;
	}

	private SearchHeuristic(DistanceField field, int floor)
	{
		this.minX = 0;
		this.maxX = 0;
		this.minY = 0;
		this.maxY = 0;
		this.floor = floor;
		this.field = field;
	}

	/**
	 * The heuristic value for a node position; {@link WorldPointUtil#UNDEFINED} (abstract
	 * global-teleport hub nodes) gets the floor. In box mode plane is deliberately ignored:
	 * walking never changes plane and every plane change is a transport, both covered by the 2D
	 * bound. In field mode unflooded (reverse-unreachable) tiles also get the floor — reaching a
	 * target from them requires a global teleport, which is exactly what the floor bounds.
	 */
	public int of(int packedPosition)
	{
		if (packedPosition == WorldPointUtil.UNDEFINED)
		{
			return floor;
		}
		if (field != null)
		{
			final int distance = field.distance(packedPosition);
			return distance >= floor ? floor : distance;
		}
		final int x = WorldPointUtil.unpackWorldX(packedPosition);
		final int y = WorldPointUtil.unpackWorldY(packedPosition);
		final int dx = x < minX ? (minX - x) : (x > maxX ? x - maxX : 0);
		final int dy = y < minY ? (minY - y) : (y > maxY ? y - maxY : 0);
		return Math.min(Math.max(dx, dy), floor);
	}

	int floor()
	{
		return floor;
	}

	/**
	 * Builds the field-backed (near-exact) heuristic for one search: h(n) = min(field(n), floor),
	 * with the floor computed from THIS search's usable origin-less teleports — per-search
	 * exclusions raise the floor exactly when the searches get expensive. Origin-bound transports
	 * are already inside the field (reversed), so no shortcut-vs-walk distinction is needed;
	 * consistency holds edge-by-edge with the same argument as the box mode, with the field's own
	 * shortest-path triangle inequality replacing the chebyshev one. Always engages: the field is
	 * as strong as the truth allows, so a flat h implies a genuinely cheap route (small disc).
	 */
	public static SearchHeuristic buildWithField(PathfinderConfig config, DistanceField field)
	{
		if (field == null)
		{
			return null;
		}
		int floor = UNBOUNDED_FLOOR;
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

	/**
	 * Builds the heuristic for a search over the given config's CURRENT transport availability
	 * (call after any exclusion rebuild), or null when it wouldn't pay for itself — see the gate
	 * constants. Correctness never depends on the gate: any returned heuristic is consistent.
	 * <p>
	 * {@code expectedCostFloor} is a known lower bound of the cost the search will find (0 =
	 * unknown): the previous route's cost for an exclusion-chain search (chain costs never
	 * decrease), or the straight-line start distance for a walk search. A* collapses the search
	 * when the heuristic floor is comparable to the final cost (the cheapest-teleport corridor IS
	 * the answer) and pays ~2x overhead when the cost far exceeds the floor (flat heuristic over a
	 * full Dijkstra disc, measured on the benchmark) — so engagement requires
	 * {@code floor >= 0.6 * expectedCostFloor}.
	 */
	public static SearchHeuristic build(PathfinderConfig config, Set<Integer> targets, int expectedCostFloor)
	{
		return build(config, targets, MIN_USEFUL_FLOOR, MAX_TARGET_SPAN, expectedCostFloor);
	}

	/**
	 * Gate-parameterized variant for tests (a zero {@code minFloor} forces engagement, exercising
	 * the saturated-floor cases where ordering bugs would hide).
	 */
	static SearchHeuristic build(PathfinderConfig config, Set<Integer> targets, int minFloor, int maxSpan)
	{
		return build(config, targets, minFloor, maxSpan, 0);
	}

	static SearchHeuristic build(PathfinderConfig config, Set<Integer> targets, int minFloor, int maxSpan,
		int expectedCostFloor)
	{
		if (targets == null || targets.isEmpty())
		{
			return null;
		}
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (int target : targets)
		{
			final int x = WorldPointUtil.unpackWorldX(target);
			final int y = WorldPointUtil.unpackWorldY(target);
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
			minY = Math.min(minY, y);
			maxY = Math.max(maxY, y);
		}
		if (Math.max(maxX - minX, maxY - minY) > maxSpan)
		{
			return null;
		}

		int floor = UNBOUNDED_FLOOR;
		// Both bank states: a path may flip into the banked state mid-search, so the floor must
		// cover every transport either state can use (a superset only lowers the floor = safe).
		for (boolean bankVisited : new boolean[]{false, true})
		{
			PrimitiveIntHashMap<Transport[]> transports = config.getTransportsPacked(bankVisited);
			for (int origin : transports.keys())
			{
				Transport[] set = transports.get(origin);
				if (set == null)
				{
					continue;
				}
				for (Transport transport : set)
				{
					floor = Math.min(floor, floorTerm(config, transport, minX, maxX, minY, maxY));
				}
			}
			for (Transport teleport : config.getUsableTeleports(bankVisited))
			{
				floor = Math.min(floor, floorTerm(config, teleport, minX, maxX, minY, maxY));
			}
		}
		if (floor < minFloor)
		{
			return null;
		}
		// Corridor test: engage only when the floor is at least ~60% of the known cost lower
		// bound. Below that the heuristic saturates long before the search finishes — nearly the
		// whole Dijkstra disc still gets explored, plus A*'s heap and pop-dedup overhead.
		if (expectedCostFloor > 0 && floor * 5L < expectedCostFloor * 3L)
		{
			return null;
		}
		return new SearchHeuristic(minX, maxX, minY, maxY, floor);
	}

	/**
	 * One transport's constraint on the floor, or "none" for non-shortcuts (see class javadoc).
	 */
	private static int floorTerm(PathfinderConfig config, Transport transport,
		int minX, int maxX, int minY, int maxY)
	{
		final int destination = transport.getDestination();
		if (destination == WorldPointUtil.UNDEFINED)
		{
			return UNBOUNDED_FLOOR;
		}
		final int effectiveCost = Math.max(0,
			CostUnits.fromTicks(transport.getDuration()) + config.getAdditionalTransportCost(transport));
		final int origin = transport.getOrigin();
		if (origin != WorldPointUtil.UNDEFINED
			&& WorldPointUtil.distanceBetween2D(origin, destination) <= effectiveCost)
		{
			// Not a shortcut: it moves no farther than it costs, so the walking bound covers it.
			return UNBOUNDED_FLOOR;
		}
		final int destX = WorldPointUtil.unpackWorldX(destination);
		final int destY = WorldPointUtil.unpackWorldY(destination);
		final int dx = destX < minX ? (minX - destX) : (destX > maxX ? destX - maxX : 0);
		final int dy = destY < minY ? (minY - destY) : (destY > maxY ? destY - maxY : 0);
		return effectiveCost + Math.max(dx, dy);
	}
}
