package gps.pathfinder;

import static net.runelite.api.Constants.REGION_SIZE;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import gps.PrimitiveIntHashMap;
import gps.WorldPointUtil;
import gps.transport.Transport;

/**
 * A target-rooted distance field: for every tile, a LOWER BOUND of the cost to reach the nearest
 * target — the per-generation preprocessing behind the near-exact A* heuristic. Built by one
 * multi-source reverse Dijkstra from the target set over:
 * <ul>
 * <li>walking edges (symmetric, mirrored from {@link CollisionMap}'s traversability rules with a
 * superset bias — anything uncertain is treated as walkable, which can only shorten distances and
 * therefore keeps the bound admissible), and</li>
 * <li>origin-bound transports reversed EXACTLY via a destination-keyed index (approximating
 * reverse edges with forward ones would overestimate across one-way transports — a jump-down
 * shortcut next to the target — and break admissibility).</li>
 * </ul>
 * Origin-less (global) teleports are deliberately NOT flooded: their reverse edge would reach
 * every tile at once, which is precisely the heuristic's constant floor —
 * {@link SearchHeuristic#buildWithField} computes it per search from the search's own usable
 * teleports, so per-search exclusions raise the floor exactly when searches get expensive.
 * <p>
 * The field is built from the generation's BASE availability (no per-search exclusions). Excluding
 * transports only raises true costs, so the field stays a valid lower bound for every search of
 * the generation. Unflooded tiles (reverse-unreachable from the targets) report
 * {@link #UNREACHED}; the heuristic maps them to its floor, which the consistency argument covers
 * because a forward transport edge into the flooded region implies its reversed edge existed in
 * the flood (so its origin would have been flooded) — EXCEPT teleport/transport landings on
 * blocked tiles, which the walking flood cannot step onto; {@link #patchBlockedLandings} values
 * those from their step-off neighbours after the flood (leaving them at the floor overestimated
 * the remaining cost, burying genuinely cheap routes through such landings).
 */
public final class DistanceField
{
	public static final int UNREACHED = Integer.MAX_VALUE;
	private static final short EMPTY = -1;
	// Distances are stored as shorts; anything longer than this is indistinguishable from
	// unreached for heuristic purposes (searches never run that far).
	private static final int MAX_DISTANCE = Short.MAX_VALUE - 1;
	// Extra flood distance past the bare floor * multiple horizon: covers the small gap between the
	// floor (a lower bound built from field values) and the best route's true cost, plus the +-1s of
	// blocked-landing patching. A pure speed knob — correctness never depends on the horizon (an
	// unflooded tile's true remaining provably exceeds the horizon, so reporting the horizon as its
	// heuristic is always admissible); overshooting just floods a little more.
	private static final int HORIZON_SLACK = 64;
	// The eight neighbour offsets (four cardinals, then four diagonals), shared by expandWalking and
	// patchBlockedLandings. Static so the per-tile flood never re-allocates them.
	private static final int[] DX = {-1, 1, 0, 0, -1, 1, -1, 1};
	private static final int[] DY = {0, 0, -1, 1, -1, -1, 1, 1};

	private final SplitFlagMap.RegionExtent regionExtents;
	private final int widthInclusive;
	private final short[][] regions;
	private final CollisionMap map;
	// Reused scratch for expandWalking's neighbour walkability. The flood is single-threaded per
	// DistanceField instance, so one buffer avoids a boolean[8] allocation on every settled tile —
	// which, across a whole-map flood, was the dominant allocation in build().
	private final boolean[] traversable = new boolean[8];
	// Every tile whose true field distance is <= this value is guaranteed flooded (the flood settles
	// in cost order and only stops past it), so an unflooded tile's remaining cost provably exceeds
	// it — the admissible fallback SearchHeuristic uses for unflooded tiles. MAX_VALUE = full flood
	// (unflooded means genuinely reverse-unreachable, e.g. an island).
	private int floodHorizon = Integer.MAX_VALUE;

	/** The bounded flood's horizon: a strict lower bound on any unflooded tile's distance. */
	int horizon()
	{
		return floodHorizon;
	}

	private DistanceField(CollisionMap map)
	{
		this.map = map;
		regionExtents = SplitFlagMap.getRegionExtents();
		widthInclusive = regionExtents.getWidth() + 1;
		final int heightInclusive = regionExtents.getHeight() + 1;
		regions = new short[widthInclusive * heightInclusive][];
	}

	/** The field value for a packed tile: cost lower bound to the nearest target, or UNREACHED. */
	public int distance(int packedPosition)
	{
		final int x = WorldPointUtil.unpackWorldX(packedPosition);
		final int y = WorldPointUtil.unpackWorldY(packedPosition);
		final int plane = WorldPointUtil.unpackWorldPlane(packedPosition);
		final int regionIndex = getRegionIndex(x / REGION_SIZE, y / REGION_SIZE);
		if (regionIndex < 0 || regionIndex >= regions.length)
		{
			return UNREACHED;
		}
		final short[] region = regions[regionIndex];
		if (region == null)
		{
			return UNREACHED;
		}
		final int index = tileIndex(x, y, plane, region.length);
		if (index < 0)
		{
			return UNREACHED;
		}
		final short value = region[index];
		return value == EMPTY ? UNREACHED : value;
	}

	/** Relaxes a tile to the given distance; true when it improved (and should be enqueued). */
	private boolean relax(int x, int y, int plane, int distance)
	{
		final int regionIndex = getRegionIndex(x / REGION_SIZE, y / REGION_SIZE);
		if (regionIndex < 0 || regionIndex >= regions.length)
		{
			return false;
		}
		short[] region = regions[regionIndex];
		if (region == null)
		{
			final byte planeCount = map.getRegionPlaneCounts(regionIndex);
			region = new short[planeCount * REGION_SIZE * REGION_SIZE];
			java.util.Arrays.fill(region, EMPTY);
			regions[regionIndex] = region;
		}
		final int index = tileIndex(x, y, plane, region.length);
		if (index < 0)
		{
			return false;
		}
		final short clamped = (short) Math.min(distance, MAX_DISTANCE);
		final short current = region[index];
		if (current != EMPTY && current <= clamped)
		{
			return false;
		}
		region[index] = clamped;
		return true;
	}

	private int getRegionIndex(int regionX, int regionY)
	{
		return (regionX - regionExtents.minX) + (regionY - regionExtents.minY) * widthInclusive;
	}

	private static int tileIndex(int x, int y, int plane, int regionLength)
	{
		final int index = (plane * REGION_SIZE + (y % REGION_SIZE)) * REGION_SIZE + (x % REGION_SIZE);
		return index < regionLength ? index : -1;
	}

	/**
	 * Builds the field for the given targets over the config's current (base) availability, or
	 * null when the targets span more than {@link SearchHeuristic#MAX_TARGET_SPAN} — a map-wide
	 * "nearest X" set floods everything for searches that are already cheap.
	 * <p>
	 * {@code costMultiple > 0} bounds the flood (see {@link #build(PathfinderConfig, Set, int)}):
	 * pass the generation's cost multiple so the flood stops once nothing beyond it can matter to
	 * any search of the generation. 0 floods the full map.
	 */
	public static DistanceField buildIfCompact(PathfinderConfig config, Set<Integer> targets, int costMultiple)
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
		if (Math.max(maxX - minX, maxY - minY) > SearchHeuristic.MAX_TARGET_SPAN)
		{
			return null;
		}
		return build(config, targets, costMultiple);
	}

	/** Builds the full-map field (tests use this directly). */
	public static DistanceField build(PathfinderConfig config, Set<Integer> targets)
	{
		return build(config, targets, 0);
	}

	/**
	 * Builds the field, optionally bounding the flood by the heuristic's own clamp: {@code h(n) =
	 * min(field(n), floor)}, so any value above the floor collapses to the floor, and every search is
	 * capped at {@code best * costMultiple} (≈ floor * costMultiple) — flooding past that band
	 * computes distances no consumer can distinguish from "far" (measured at 95%+ of the flood for
	 * teleport-rich configs). The floor is discovered DURING the flood: it is the cheapest
	 * {@code cast + field(landing)} over the base origin-free teleports, and since tiles settle in
	 * cost order, landings near the targets surface early; once the popped distance exceeds
	 * {@code floorSoFar * (costMultiple + 1) + HORIZON_SLACK} the flood stops itself.
	 * <p>
	 * Correctness does not depend on the horizon: the flood settles in cost order, so an unflooded
	 * tile's field distance — and therefore its true remaining cost, which the field lower-bounds —
	 * provably exceeds the recorded {@link #horizon()}. {@link SearchHeuristic} reports
	 * {@code min(horizon, floor)} for unflooded tiles, which is admissible and consistent
	 * unconditionally; an undersized horizon can only weaken the heuristic (slower searches), never
	 * change a result.
	 */
	static DistanceField build(PathfinderConfig config, Set<Integer> targets, int costMultiple)
	{
		final CollisionMap map = config.getMap();
		final DistanceField field = new DistanceField(map);
		final PrimitiveIntHashMap<Map<Integer, Integer>> reverseTransports = buildReverseTransportIndex(config);
		final VisitedTiles settled = new VisitedTiles(map);
		final IntDeque fifo = new IntDeque(4096);
		// Transport relaxations only (a few tens of thousands at most): boxed entries are fine.
		// Entry = (distance << 32) | (packed & 0xFFFFFFFF); natural ordering sorts by distance.
		final PriorityQueue<Long> heap = new PriorityQueue<>();
		// Bounded mode: cheapest cast cost per origin-free teleport landing, for in-flood floor
		// discovery. Null = unbounded (full flood).
		final PrimitiveIntHashMap<Integer> landingCasts =
			costMultiple > 0 ? buildTeleportLandingIndex(config) : null;
		int cheapestFloor = Integer.MAX_VALUE;
		long horizonTarget = Long.MAX_VALUE;

		for (int target : targets)
		{
			final int x = WorldPointUtil.unpackWorldX(target);
			final int y = WorldPointUtil.unpackWorldY(target);
			final int plane = WorldPointUtil.unpackWorldPlane(target);
			if (field.relax(x, y, plane, 0))
			{
				fifo.addLast(target);
			}
		}

		while (!fifo.isEmpty() || !heap.isEmpty())
		{
			int packed;
			if (!heap.isEmpty()
				&& (fifo.isEmpty() || (heap.peek() >>> 32) < field.distance(fifo.peekFirst())))
			{
				packed = (int) (long) heap.poll();
			}
			else
			{
				packed = fifo.pollFirst();
			}
			final int x = WorldPointUtil.unpackWorldX(packed);
			final int y = WorldPointUtil.unpackWorldY(packed);
			final int plane = WorldPointUtil.unpackWorldPlane(packed);
			if (!settled.set(x, y, plane, false))
			{
				continue;
			}
			final int distance = field.distance(packed);

			if (landingCasts != null)
			{
				// Everything below the popped distance is settled (cost-ordered), so once it passes
				// the horizon nothing else can matter: unflooded tiles provably lie beyond it.
				if (distance > horizonTarget)
				{
					field.floodHorizon = (int) Math.min(Integer.MAX_VALUE - 1L, horizonTarget);
					break;
				}
				final Integer cast = landingCasts.get(packed);
				if (cast != null && cast + distance < cheapestFloor)
				{
					cheapestFloor = cast + distance;
					horizonTarget = (long) cheapestFloor * costMultiple + cheapestFloor + HORIZON_SLACK;
				}
			}

			field.expandWalking(packed, x, y, plane, distance, fifo, settled, config);

			final Map<Integer, Integer> intoHere = reverseTransports.get(packed);
			if (intoHere != null)
			{
				for (Map.Entry<Integer, Integer> edge : intoHere.entrySet())
				{
					final int origin = edge.getKey();
					final int cost = edge.getValue();
					final int candidate = distance + cost;
					final int ox = WorldPointUtil.unpackWorldX(origin);
					final int oy = WorldPointUtil.unpackWorldY(origin);
					final int oplane = WorldPointUtil.unpackWorldPlane(origin);
					if (!settled.get(ox, oy, oplane, false) && field.relax(ox, oy, oplane, candidate))
					{
						heap.add(((long) candidate << 32) | (origin & 0xFFFFFFFFL));
					}
				}
			}
		}
		field.patchBlockedLandings(config, reverseTransports.keys());
		return field;
	}

	/**
	 * Values the landings the walking flood cannot reach: a teleport/transport destination on a
	 * BLOCKED tile (a quetzal platform, a jetty) is forward-occupiable — the player lands there and
	 * steps off — but the reverse flood never steps ONTO a blocked tile, so such landings stayed
	 * {@link #UNREACHED} and the heuristic sent them to its floor. That OVERESTIMATES the remaining
	 * cost from the landing (breaking admissibility: the search then buries routes through it), and
	 * inflates the floor itself, which skips unreached landings. Each such landing takes
	 * {@code min(field(step-off neighbour)) + 1}, mirroring the forward blocked-tile step-off rules
	 * exactly — a real forward edge, so the value stays a valid lower bound and h stays consistent.
	 * No propagation is needed: blocked tiles have no walk edges into other blocked tiles, so a
	 * patched value can never improve any other tile.
	 */
	private void patchBlockedLandings(PathfinderConfig config, int[] transportDestinations)
	{
		final Set<Integer> landings = new HashSet<>();
		for (int destination : transportDestinations)
		{
			landings.add(destination);
		}
		for (boolean bankVisited : new boolean[]{false, true})
		{
			for (Transport teleport : config.getUsableTeleports(bankVisited))
			{
				if (teleport.getDestination() != WorldPointUtil.UNDEFINED)
				{
					landings.add(teleport.getDestination());
				}
			}
		}
		for (int landing : landings)
		{
			final int x = WorldPointUtil.unpackWorldX(landing);
			final int y = WorldPointUtil.unpackWorldY(landing);
			final int plane = WorldPointUtil.unpackWorldPlane(landing);
			if (distance(landing) != UNREACHED || !map.isBlocked(x, y, plane))
			{
				continue;
			}
			// Forward step-off adjacency from a blocked tile (CollisionMap.getTileNeighbors'
			// isBlocked branch): any unblocked cardinal; diagonals need both flanking cardinals too.
			final boolean westBlocked = map.isBlocked(x - 1, y, plane);
			final boolean eastBlocked = map.isBlocked(x + 1, y, plane);
			final boolean southBlocked = map.isBlocked(x, y - 1, plane);
			final boolean northBlocked = map.isBlocked(x, y + 1, plane);
			final boolean[] stepOpen = {
				!westBlocked, !eastBlocked, !southBlocked, !northBlocked,
				!map.isBlocked(x - 1, y - 1, plane) && !westBlocked && !southBlocked,
				!map.isBlocked(x + 1, y - 1, plane) && !eastBlocked && !southBlocked,
				!map.isBlocked(x - 1, y + 1, plane) && !westBlocked && !northBlocked,
				!map.isBlocked(x + 1, y + 1, plane) && !eastBlocked && !northBlocked,
			};
			int best = UNREACHED;
			for (int i = 0; i < 8; i++)
			{
				if (!stepOpen[i])
				{
					continue;
				}
				final int neighbour = distance(WorldPointUtil.packWorldPoint(x + DX[i], y + DY[i], plane));
				if (neighbour != UNREACHED && neighbour + 1 < best)
				{
					best = neighbour + 1;
				}
			}
			if (best != UNREACHED)
			{
				relax(x, y, plane, best);
			}
		}
	}

	/**
	 * Relaxes the walking neighbours of a popped tile, mirroring {@link CollisionMap}'s forward
	 * traversability (walking is symmetric in the collision data). The blocked-tile branches mirror
	 * the forward rules for transport endpoints standing on blocked tiles (e.g. fairy ring
	 * platforms) with a superset bias — extra edges only shorten the field, which is safe.
	 */
	private void expandWalking(int packed, int x, int y, int plane, int distance,
		IntDeque fifo, VisitedTiles settled, PathfinderConfig config)
	{
		if (map.isBlocked(x, y, plane))
		{
			final boolean westBlocked = map.isBlocked(x - 1, y, plane);
			final boolean eastBlocked = map.isBlocked(x + 1, y, plane);
			final boolean southBlocked = map.isBlocked(x, y - 1, plane);
			final boolean northBlocked = map.isBlocked(x, y + 1, plane);
			traversable[0] = !westBlocked;
			traversable[1] = !eastBlocked;
			traversable[2] = !southBlocked;
			traversable[3] = !northBlocked;
			traversable[4] = !map.isBlocked(x - 1, y - 1, plane) && !westBlocked && !southBlocked;
			traversable[5] = !map.isBlocked(x + 1, y - 1, plane) && !eastBlocked && !southBlocked;
			traversable[6] = !map.isBlocked(x - 1, y + 1, plane) && !westBlocked && !northBlocked;
			traversable[7] = !map.isBlocked(x + 1, y + 1, plane) && !eastBlocked && !northBlocked;
		}
		else
		{
			// Diagonals composed exactly like CollisionMap's private sw/se/nw/ne: the diagonal is
			// walkable when all four half-edges around it are.
			final boolean w = map.w(x, y, plane);
			final boolean e = map.e(x, y, plane);
			final boolean s = map.s(x, y, plane);
			final boolean n = map.n(x, y, plane);
			traversable[0] = w;
			traversable[1] = e;
			traversable[2] = s;
			traversable[3] = n;
			traversable[4] = s && map.w(x, y - 1, plane) && w && map.s(x - 1, y, plane);
			traversable[5] = s && map.e(x, y - 1, plane) && e && map.s(x + 1, y, plane);
			traversable[6] = n && map.w(x, y + 1, plane) && w && map.n(x - 1, y, plane);
			traversable[7] = n && map.e(x, y + 1, plane) && e && map.n(x + 1, y, plane);
		}
		for (int i = 0; i < 8; i++)
		{
			final int nx = x + DX[i];
			final int ny = y + DY[i];
			boolean canStep = traversable[i];
			if (!canStep && Math.abs(DX[i] + DY[i]) == 1 && map.isBlocked(nx, ny, plane))
			{
				// Mirror of the forward rule that lets a path step onto a blocked tile hosting a
				// transport origin (fairy ring platform). Superset bias: any transport there counts.
				final int neighborPacked = WorldPointUtil.packWorldPoint(nx, ny, plane);
				canStep = config.getTransportsPacked(true)
					.getOrDefault(neighborPacked, TransportAvailability.EMPTY_TRANSPORTS).length > 0;
			}
			if (!canStep || settled.get(nx, ny, plane, false))
			{
				continue;
			}
			if (relax(nx, ny, plane, distance + 1))
			{
				fifo.addLast(WorldPointUtil.packWorldPoint(nx, ny, plane));
			}
		}
	}

	/**
	 * Destination-keyed index of every origin-bound transport in the config's base availability:
	 * {@code destination -> (origin -> cheapest cost to make that hop)}. Built over both bank states
	 * because a superset only shortens the field.
	 * <p>
	 * The inner map is keyed by origin and keeps the MINIMUM cost on purpose. The same origin lands
	 * on the same destination more than once here — a transport not gated on banking appears in both
	 * the no-bank and bank-visited availability, and two distinct methods can share an origin and
	 * landing — so a flat list would hold duplicate and redundant edges. The flood only ever relaxes
	 * an origin to the cheapest way it can reach the destination ({@code relax} keeps the minimum), so
	 * a pricier or duplicate edge could never improve the field. Folding them to one min-cost entry
	 * per origin as we build keeps the index minimal (and the flood's relax attempts non-redundant)
	 * without changing a single field value.
	 */
	/**
	 * Cheapest effective cast cost per origin-free teleport landing, over the config's base
	 * availability (both bank states) — the same teleport set {@link SearchHeuristic#buildWithField}
	 * scans, so the floor the bounded flood discovers matches the one the heuristic will use.
	 * Landings on blocked tiles never settle in the walk flood, so their casts simply never
	 * contribute — the floor stays a little high and the horizon a little wide, which is safe.
	 */
	private static PrimitiveIntHashMap<Integer> buildTeleportLandingIndex(PathfinderConfig config)
	{
		final PrimitiveIntHashMap<Integer> index = new PrimitiveIntHashMap<>(256);
		for (boolean bankVisited : new boolean[]{false, true})
		{
			for (Transport teleport : config.getUsableTeleports(bankVisited))
			{
				final int destination = teleport.getDestination();
				if (destination == WorldPointUtil.UNDEFINED)
				{
					continue;
				}
				final int cost = Math.max(0, CostUnits.fromTicks(teleport.getDuration())
					+ config.getAdditionalTransportCost(teleport));
				final Integer current = index.get(destination);
				if (current == null || cost < current)
				{
					index.put(destination, cost);
				}
			}
		}
		return index;
	}

	// Package-private for DistanceFieldTest's index-shape assertion. The OUTER map is keyed by a
	// primitive int (the destination): the flood looks it up for every settled tile, and a
	// Map<Integer, ...> would box that int into a throwaway Integer on each of the millions of tiles
	// — the second-biggest allocation in build() after the per-tile scratch arrays. The inner
	// origin -> min-cost map stays boxed: it is only iterated for the few thousand tiles that are
	// actually transport destinations, not per settled tile.
	static PrimitiveIntHashMap<Map<Integer, Integer>> buildReverseTransportIndex(PathfinderConfig config)
	{
		final PrimitiveIntHashMap<Map<Integer, Integer>> index = new PrimitiveIntHashMap<>(4096);
		for (boolean bankVisited : new boolean[]{false, true})
		{
			final PrimitiveIntHashMap<Transport[]> transports = config.getTransportsPacked(bankVisited);
			for (int origin : transports.keys())
			{
				final Transport[] set = transports.get(origin);
				if (set == null)
				{
					continue;
				}
				for (Transport transport : set)
				{
					final int destination = transport.getDestination();
					if (destination == WorldPointUtil.UNDEFINED)
					{
						continue;
					}
					final int cost = Math.max(0, CostUnits.fromTicks(transport.getDuration())
						+ config.getAdditionalTransportCost(transport));
					Map<Integer, Integer> byOrigin = index.get(destination);
					if (byOrigin == null)
					{
						byOrigin = new HashMap<>();
						index.put(destination, byOrigin);
					}
					// Keyed by the availability map's origin (the loop key), NOT transport.getOrigin():
					// the POH remap re-homes interior transports onto the landing tile by MAP KEY while
					// each Transport keeps its interior origin. Crediting the interior tile left the
					// landing unflooded, so the heuristic overestimated inside the POH (inadmissible)
					// and house-mediated best routes only surfaced after "+" (user capture pair).
					byOrigin.merge(origin, cost, Math::min);
				}
			}
		}
		return index;
	}
}
