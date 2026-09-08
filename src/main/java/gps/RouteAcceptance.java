package gps;

import gps.transport.Transport;
import gps.transport.TransportType;
import java.util.List;
import java.util.function.Predicate;

/**
 * The one copy of the page acceptance rule (plan step L1). The exclusion chain, the seed pass and
 * the tail-diversity pass each decide whether a found route joins the page and which route makes
 * room for it; before this class each of them carried its own copy of the closeness rule, the
 * hybrid cost band with its page-fill ceiling, and the eviction loop, with small drifts between
 * the copies. Everything here is a pure function of the candidate and the page, so each rule is
 * testable on its own (see RouteAcceptanceTest); the passes keep the wiring and the logging.
 */
final class RouteAcceptance
{
	// A super-cheap best route must not strangle the cost band: with the best route at cost 9 (a
	// direct teleport), best x 3 = 27 hid every teleport+short-walk combination (a 28-cost quetzal
	// whistle route) behind "more". The band prices off max(best, this), so the default band is
	// never tighter than ~100 units (~30s of travel) and still grows with every "more" press.
	static final int MIN_BEST_FOR_BAND = 35;
	// The band/cliff gates don't apply until this many routes are on the page: one direct teleport
	// far cheaper than everything else otherwise made a one-entry page with no alternatives. The
	// chain search's own sanity cap (2x the band) still bounds how far these fill routes may cost.
	static final int MIN_PAGE_ROUTES = 4;
	// Past the band a route is still shown while its cost stays within this gap (or an eighth of
	// the reference, whichever is larger) of max(band, costliest accepted route).
	static final int PAGE_FILL_MIN_GAP = 10;
	// For an unreachable target every route ends at the closest reachable area; later routes are
	// only accepted while they get equally close, within this many tiles.
	static final int CLOSEST_DISTANCE_TOLERANCE = 10;
	/** How close a same-kind teleport must land to a flight's destination to make the hop pointless. */
	private static final int SHARED_DESTINATION_RADIUS = 10;

	private RouteAcceptance()
	{
	}

	/** The display band: {@code max(best, MIN_BEST_FOR_BAND) * multiple}. The page must not be empty. */
	static long band(List<RouteOption> routes, int multiple)
	{
		return (long) Math.max(routes.get(0).getTotalCost(), MIN_BEST_FOR_BAND) * multiple;
	}

	/**
	 * The page-fill acceptance ceiling: past the display band a route is still shown while its cost
	 * stays within a modest gap of {@code max(band, costliest accepted route)}. Referencing the band
	 * (not just the last route) matters when a cluster STARTS just past the band edge: routes at
	 * 32/86 with a 105 band and the next cluster at 106: measured from 86 the gap is 20 (a stop),
	 * measured from the band it is 1 (the cluster the band edge landed in). The ceiling ratchets as
	 * fill routes are accepted, so it follows a dense cluster and stops at the first real cliff.
	 */
	static long pageFillCeiling(int bestCost, int maxAcceptedCost, int multiple)
	{
		long ref = Math.max((long) Math.max(bestCost, MIN_BEST_FOR_BAND) * multiple, maxAcceptedCost);
		return ref + Math.max(PAGE_FILL_MIN_GAP, ref / 8);
	}

	/** The costliest accepted route's cost (walk-only entries included: they bound the page too). */
	static int maxAcceptedCost(List<RouteOption> routes)
	{
		int max = 0;
		for (RouteOption route : routes)
		{
			max = Math.max(max, route.getTotalCost());
		}
		return max;
	}

	/**
	 * Tightens a search's cost cap to {@code best route cost * multiple} once a route is found, so
	 * searches for far-worse alternatives don't flood the map. Routes are added in non-decreasing
	 * cost order, so the first is the cheapest. No effect before the first route, when
	 * {@code multiple <= 0} (uncapped), or when the product exceeds {@code cap}.
	 */
	static int cappedByBestCost(int cap, List<RouteOption> routes, int multiple)
	{
		if (routes.isEmpty() || multiple <= 0)
		{
			return cap;
		}
		long byBest = band(routes, multiple);
		return byBest < cap ? (int) byBest : cap;
	}

	/**
	 * Whether a candidate at {@code cost} lies beyond what this page shows. The hybrid band edge:
	 * routes inside the display band are always accepted; past it the page keeps filling while the
	 * cost stays within the page-fill ceiling, so a cost cluster straddling the band edge is shown
	 * whole, but a genuine cliff still ends the page. A minimum page overrides the cliff: one
	 * super-cheap route otherwise produced a single-entry page with no alternatives at all. With
	 * {@code limit} the page being full past the band is a stop as well (the chain cannot evict);
	 * the passes that evict pass {@link Integer#MAX_VALUE}.
	 */
	static boolean beyondBand(int cost, List<RouteOption> routes, int multiple, int limit)
	{
		if (multiple <= 0 || routes.size() < MIN_PAGE_ROUTES || cost <= band(routes, multiple))
		{
			return false;
		}
		return routes.size() >= limit
			|| cost > pageFillCeiling(routes.get(0).getTotalCost(), maxAcceptedCost(routes), multiple);
	}

	static boolean beyondBand(int cost, List<RouteOption> routes, int multiple)
	{
		return beyondBand(cost, routes, multiple, Integer.MAX_VALUE);
	}

	/**
	 * Closeness. With the best route reaching the target ({@code bestRemaining == 0}) an unreached
	 * result is not "the closest reachable area": it is a search truncated by the cost cap a few
	 * tiles short of the goal, and showing it painted a phantom "unreachable" route. With an
	 * unreachable target every route ends at the closest reachable area, and later ones are
	 * accepted only while they get about as close. Before any route ({@code bestRemaining < 0})
	 * nothing is too far; the caller records the first as the best.
	 */
	static boolean tooFar(boolean reached, int remaining, int bestRemaining)
	{
		if (!reached && bestRemaining == 0)
		{
			return true;
		}
		return bestRemaining >= 0 && remaining > bestRemaining + CLOSEST_DISTANCE_TOLERANCE;
	}

	/**
	 * The route to make room with when the page is full: the costliest one the predicate allows,
	 * strictly costlier than {@code costlierThan} (pass -1 to allow any cost), never the sole
	 * port-first route (the "park the boat properly" promise is evict-proof like the walk
	 * baseline). -1 when nothing qualifies, in which case the candidate is dropped instead.
	 */
	static int evictionIndex(List<RouteOption> routes, int costlierThan, Predicate<RouteOption> evictable)
	{
		int sole = solePortFirstIndex(routes);
		int evict = -1;
		int maxCost = costlierThan;
		for (int r = 0; r < routes.size(); r++)
		{
			RouteOption route = routes.get(r);
			if (r != sole && evictable.test(route) && route.getTotalCost() > maxCost)
			{
				maxCost = route.getTotalCost();
				evict = r;
			}
		}
		return evict;
	}

	/** Whether the route starts by disembarking at a port. */
	static boolean portFirst(RouteOption route)
	{
		List<TeleportMethod> methods = route.getMethods();
		return !methods.isEmpty()
			&& TransportType.SAILING.equals(methods.get(0).getType())
			&& methods.get(0).getDisplayInfo() != null
			&& methods.get(0).getDisplayInfo().startsWith("Disembark");
	}

	/** The index of the only port-first route on the page, or -1 when there is none or several. */
	static int solePortFirstIndex(List<RouteOption> routes)
	{
		int found = -1;
		for (int r = 0; r < routes.size(); r++)
		{
			if (portFirst(routes.get(r)))
			{
				if (found >= 0)
				{
					return -1;
				}
				found = r;
			}
		}
		return found;
	}

	/** Whether any shown route already starts by disembarking at a port. */
	static boolean hasPortFirstRoute(List<RouteOption> routes)
	{
		for (RouteOption route : routes)
		{
			if (portFirst(route))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * A kept route nested inside the candidate: the candidate is a detour that ends with exactly a
	 * kept route's methods (a bank trip before the same teleport chain counts only against a kept
	 * route that also banks). Such a candidate adds nothing the page does not already say.
	 */
	static boolean nestsAKeptRoute(List<TeleportMethod> candidate, boolean candidateViaBank,
		List<RouteOption> kept)
	{
		for (RouteOption route : kept)
		{
			List<TeleportMethod> base = route.getMethods();
			if (base.isEmpty() || candidate.size() <= base.size()
				|| (candidateViaBank && !route.isViaBank()))
			{
				continue;
			}
			if (candidate.subList(candidate.size() - base.size(), candidate.size()).equals(base))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * A whistle-hop variant of a direct teleport: a teleport followed by a flight of the kind that
	 * shares its destinations, when a same-kind teleport in the baseline already lands within
	 * {@link #SHARED_DESTINATION_RADIUS} of the flight's destination. The hop only exists because
	 * the chain excluded the direct teleport, so it is filtered rather than shown.
	 */
	static boolean hasRedundantTeleportHop(List<Transport> baselineTeleports, List<TeleportMethod> methods)
	{
		for (int i = 0; i + 1 < methods.size(); i++)
		{
			TeleportMethod teleport = methods.get(i);
			TeleportMethod flight = methods.get(i + 1);
			if (teleport.getType() == null || flight.getType() == null
				|| !flight.getType().equals(teleport.getType().sharesDestinationsWith()))
			{
				continue;
			}
			for (Transport candidate : baselineTeleports)
			{
				if (teleport.getType().equals(candidate.getType())
					&& WorldPointUtil.distanceBetween(candidate.getDestination(), flight.getDestination())
						<= SHARED_DESTINATION_RADIUS)
				{
					return true;
				}
			}
		}
		return false;
	}
}
