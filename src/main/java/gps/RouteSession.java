package gps;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * The alternative-routes session (plan step L9, out of the plugin class): the page of routes for
 * the current destination, which one is selected, which one the overlays draw, whether a
 * generation is in flight, what the last generation ran from and with, and the "more routes"
 * budget. Owned by the client thread; the volatile fields are read by the overlays and the panel.
 * The plugin keeps everything with a side effect (panel refreshes, the journey timer, plugin
 * messages, persistence); every decision here is a pure function of this state.
 */
final class RouteSession
{
	// The cost cap for a generation, as a multiple of the best route's cost: only routes up to this
	// many times the cheapest are computed (a cheap teleport otherwise floods the map searching for
	// far-worse alternatives). "Show more" raises it; reset to the default on a new destination.
	static final int DEFAULT_COST_MULTIPLE = 3;
	static final int COST_MULTIPLE_STEP = 3;

	private volatile List<RouteOption> routes = new ArrayList<>();
	private volatile RouteOption selected;
	// The route the overlays draw, committed ONLY when a generation settles (its "done" update),
	// never mid-stream: while alternatives are still generating and re-ranking, the overlays hold
	// this instead of flipping through the streaming top result. Null for a fresh destination,
	// so the overlay stays clear until the routes settle.
	private volatile RouteOption committed;
	private volatile boolean inFlight;
	// Whether the last generation left routes unshown (the cost cap or the route-count budget).
	private volatile boolean moreLikely;
	// Start, targets and limit the alternatives were last generated from, reused by exclusion,
	// mode and show-more edits so they re-run against the same destination.
	private volatile int lastStart = WorldPointUtil.UNDEFINED;
	private volatile Set<Integer> lastTargets = Set.of();
	private volatile int lastLimit;
	private int limit = AlternativeRoutesService.MAX_ROUTES;
	private int costMultiple = DEFAULT_COST_MULTIPLE;

	List<RouteOption> routes()
	{
		return routes;
	}

	RouteOption selected()
	{
		return selected;
	}

	boolean inFlight()
	{
		return inFlight;
	}

	int lastStart()
	{
		return lastStart;
	}

	Set<Integer> lastTargets()
	{
		return lastTargets;
	}

	int lastLimit()
	{
		return lastLimit;
	}

	int limit()
	{
		return limit;
	}

	int costMultiple()
	{
		return costMultiple;
	}

	boolean canLoadMore()
	{
		return moreLikely;
	}

	void setLimit(int limit)
	{
		this.limit = limit;
	}

	/** A fresh destination starts at the default cost band; "show more" widens it from there. */
	void resetCostMultiple()
	{
		costMultiple = DEFAULT_COST_MULTIPLE;
	}

	void resetBudget(int defaultLimit)
	{
		costMultiple = DEFAULT_COST_MULTIPLE;
		limit = defaultLimit;
	}

	/** Drops the pick so a fresh generation's best takes over rather than the overlay clinging to the old line. */
	void clearSelection()
	{
		selected = null;
	}

	/**
	 * A generation begins for {@code targets} (empty: the catalog alone). A NEW destination clears
	 * the committed route so the overlay stays blank until the fresh routes settle; regenerating
	 * the SAME destination (off-route recalc, method toggle, "more") keeps it, so the overlay holds
	 * the current route steadily rather than blinking blank. The page empties either way.
	 */
	void begin(int start, Set<Integer> targets)
	{
		Set<Integer> ends = targets == null ? Set.of() : Set.copyOf(targets);
		if (!ends.equals(lastTargets))
		{
			committed = null;
		}
		lastStart = start;
		lastTargets = ends;
		lastLimit = limit;
		routes = new ArrayList<>();
		moreLikely = false;
		inFlight = !ends.isEmpty();
	}

	/** A streaming update: the page so far, in effective order. The selection and the committed route stay. */
	void stream(List<RouteOption> ordered)
	{
		routes = ordered;
	}

	/**
	 * The generation's final update. A recalculation must not yank the player off the route they
	 * PICKED: when the fresh list holds an equivalent route it stays selected, even if its rank
	 * moved, unless the pick was never started ({@code progress} 0) and the fresh list found
	 * something far better (more than twice cheaper), in which case keeping it is not stability
	 * but clinging to a stale result. Otherwise the pick is dropped and the overlay settles to the
	 * new best. The committed route is settled BEFORE the in-flight flag clears, so the overlay
	 * adopts it in one step. "More" stays available while routes were left unshown and the
	 * route-count budget is below {@code cap}.
	 */
	void settle(List<RouteOption> ordered, boolean wasMoreLikely, int cap, IntSupplier progressOfSelected)
	{
		routes = ordered;
		moreLikely = !ordered.isEmpty() && wasMoreLikely && limit < cap;
		RouteOption rematched = null;
		if (selected != null)
		{
			int progress = progressOfSelected.getAsInt();
			rematched = rematch(selected, ordered, progress);
			if (rematched != null && !ordered.isEmpty() && rematched != ordered.get(0)
				&& progress == 0 && rematched.getTotalCost() > ordered.get(0).getTotalCost() * 2)
			{
				rematched = null;
			}
		}
		if (rematched != null)
		{
			selected = rematched;
			committed = rematched;
		}
		else
		{
			selected = null;
			committed = ordered.isEmpty() ? null : ordered.get(0);
		}
		inFlight = false;
	}

	/**
	 * The route the overlays draw for {@code currentTargets}: the pick when there is one; the
	 * committed route while a generation is in flight (null for a fresh destination: the HUD then
	 * says it is finding the route); else the best route, and only when the page was generated
	 * for these targets (a stale page for a previous destination is never shown).
	 */
	RouteOption displayed(Set<Integer> currentTargets)
	{
		RouteOption pick = selected;
		if (pick != null)
		{
			return pick;
		}
		if (inFlight)
		{
			return committed;
		}
		List<RouteOption> page = routes;
		if (page.isEmpty() || currentTargets.isEmpty() || !lastTargets.equals(currentTargets))
		{
			return null;
		}
		return page.get(0);
	}

	/** Toggles the pick at {@code index} (clicking the shown route hides it). False when out of range. */
	boolean select(int index)
	{
		List<RouteOption> page = routes;
		if (index < 0 || index >= page.size())
		{
			return false;
		}
		RouteOption route = page.get(index);
		selected = selected == route ? null : route;
		return true;
	}

	void resort(Comparator<RouteOption> order)
	{
		List<RouteOption> page = routes;
		if (!page.isEmpty())
		{
			List<RouteOption> sorted = new ArrayList<>(page);
			sorted.sort(order);
			routes = sorted;
		}
	}

	/**
	 * "More routes": grows both dimensions of the cap so genuinely more routes surface (widen the
	 * cost band and raise the route-count budget by another page, capped). False when there is no
	 * destination or the last generation left nothing unshown.
	 */
	boolean widen(int defaultLimit, int cap)
	{
		if (lastTargets.isEmpty() || !moreLikely)
		{
			return false;
		}
		costMultiple += COST_MULTIPLE_STEP;
		limit = Math.min(limit + defaultLimit, cap);
		return true;
	}

	/**
	 * Whether a new generation is needed: there is a target, and either it changed since the last
	 * generation or the last generation was allowed fewer routes than wanted now.
	 */
	static boolean shouldAutoCompute(Set<Integer> targets, Set<Integer> lastTargets, int lastLimit, int desiredLimit)
	{
		return !targets.isEmpty() && (!targets.equals(lastTargets) || lastLimit < desiredLimit);
	}

	/**
	 * The route in {@code routes} equivalent to {@code previous}, matching what is LEFT of the
	 * plan rather than its full history: methods whose edges the player has already crossed (per
	 * {@code progress}, the reached path index) are consumed, so after riding the minecart the
	 * equivalent route is the one continuing with the remaining methods. Bank-ness only
	 * distinguishes routes while nothing is consumed yet. Null when no equivalent exists.
	 */
	static RouteOption rematch(RouteOption previous, List<RouteOption> routes, int progress)
	{
		List<TeleportMethod> methods = previous.getMethods();
		List<Integer> edges = previous.getMethodEdgeIndexes();
		int consumed = 0;
		while (consumed < methods.size() && consumed < edges.size() && edges.get(consumed) <= progress)
		{
			consumed++;
		}
		List<TeleportMethod> remaining = methods.subList(consumed, methods.size());
		boolean checkBank = consumed == 0;
		for (RouteOption route : routes)
		{
			if ((!checkBank || route.isViaBank() == previous.isViaBank())
				&& route.getMethods().equals(remaining))
			{
				return route;
			}
		}
		return null;
	}

	/** A defensive copy of the targets, for callers that mutate. */
	Set<Integer> lastTargetsCopy()
	{
		return new HashSet<>(lastTargets);
	}
}
