package gps;

import gps.transport.TransportType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Plan step L1: the page acceptance rule (closeness, the hybrid cost band with its page-fill
 * ceiling, and eviction when the page is full) was copied three times inside the generator (the
 * exclusion chain, the seed pass, the tail-diversity pass) with small drifts between the copies.
 * It is one set of pure functions now, pinned here; the generator's own tests keep the wiring.
 */
public class RouteAcceptanceTest
{
	private static final TeleportMethod TELEPORT = new TeleportMethod(TransportType.TELEPORTATION_SPELL, "Varrock Teleport", 1);
	private static final TeleportMethod PORT = new TeleportMethod(TransportType.SAILING, "Disembark at Port Khazard", 2);
	private static final TeleportMethod SAIL = new TeleportMethod(TransportType.SAILING, "Sail to the pin", 3);

	private static RouteOption route(int cost, TeleportMethod... methods)
	{
		return new RouteOption(List.of(), List.of(methods), List.of(), List.of(), cost, cost, true, Set.of(), List.of(), 0);
	}

	private static List<RouteOption> page(int... costs)
	{
		List<RouteOption> routes = new ArrayList<>();
		for (int cost : costs)
		{
			routes.add(route(cost, TELEPORT));
		}
		return routes;
	}

	@Test
	public void theBandOnlyAppliesOnceThePageHasAMinimum()
	{
		// Three routes: no band yet, whatever the cost.
		assertFalse(RouteAcceptance.beyondBand(10_000, page(10, 20, 30), 3));
		// Four routes, best 10: the band prices off max(best, 35) * 3 = 105.
		assertFalse("inside the band", RouteAcceptance.beyondBand(105, page(10, 20, 30, 40), 3));
		assertFalse("just past the band but within the page-fill gap of the band",
			RouteAcceptance.beyondBand(115, page(10, 20, 30, 40), 3));
		assertTrue("a cliff past the band", RouteAcceptance.beyondBand(200, page(10, 20, 30, 40), 3));
		assertFalse("no multiple, no band", RouteAcceptance.beyondBand(200, page(10, 20, 30, 40), 0));
	}

	@Test
	public void thePageFillCeilingRatchetsWithTheCostliestAcceptedRoute()
	{
		// Band 105, costliest accepted 150: the ceiling follows the cluster (150 + max(10, 150/8)).
		List<RouteOption> routes = page(10, 100, 120, 150);
		assertFalse(RouteAcceptance.beyondBand(165, routes, 3));
		assertTrue(RouteAcceptance.beyondBand(190, routes, 3));
		assertEquals(168L, RouteAcceptance.pageFillCeiling(10, 150, 3));
	}

	@Test
	public void theChainStopsAtTheLimitPastTheBandWhileSeedsMayEvict()
	{
		List<RouteOption> full = page(10, 20, 30, 40);
		// Past the band with the page at its limit: the chain has nothing to evict, so it stops.
		assertTrue(RouteAcceptance.beyondBand(110, full, 3, 4));
		// The seed form never stops on the limit: it can evict a costlier entry.
		assertFalse(RouteAcceptance.beyondBand(110, full, 3));
	}

	@Test
	public void closenessRules()
	{
		// The best route reached: an unreached result is a cap truncation, never a route.
		assertTrue(RouteAcceptance.tooFar(false, 3, 0));
		assertFalse(RouteAcceptance.tooFar(true, 0, 0));
		// No best yet: anything goes, the caller records it as the best.
		assertFalse(RouteAcceptance.tooFar(false, 500, -1));
		// An unreachable target: routes must end about as close as the best approach.
		assertFalse(RouteAcceptance.tooFar(false, 25, 20));
		assertTrue(RouteAcceptance.tooFar(false, 31, 20));
	}

	@Test
	public void evictionPicksTheCostliestEvictableRouteAndNeverTheSolePortFirstOne()
	{
		List<RouteOption> routes = new ArrayList<>();
		routes.add(route(20, TELEPORT));
		routes.add(route(400, PORT, TELEPORT));
		routes.add(route(300, TELEPORT));
		routes.add(route(50));
		// Any cost: the costliest evictable is index 2 (400 is the sole port-first route, 50 is walk-only).
		assertEquals(2, RouteAcceptance.evictionIndex(routes, -1, r -> !r.isWalkOnly()));
		// Only routes strictly costlier than the candidate qualify.
		assertEquals(-1, RouteAcceptance.evictionIndex(routes, 300, r -> !r.isWalkOnly()));
		assertEquals(2, RouteAcceptance.evictionIndex(routes, 299, r -> !r.isWalkOnly()));
		// With two port-first routes neither is protected.
		routes.add(route(450, PORT, SAIL));
		assertEquals(4, RouteAcceptance.evictionIndex(routes, -1, r -> !r.isWalkOnly()));
		// The predicate can protect anything else (the pure-sail baseline, a family).
		assertEquals(1, RouteAcceptance.evictionIndex(routes, -1, r -> !r.isWalkOnly() && !r.isPureSail()));
	}

	@Test
	public void portFirstHelpers()
	{
		List<RouteOption> routes = new ArrayList<>();
		routes.add(route(20, TELEPORT));
		assertFalse(RouteAcceptance.hasPortFirstRoute(routes));
		assertEquals(-1, RouteAcceptance.solePortFirstIndex(routes));
		routes.add(route(40, PORT));
		assertTrue(RouteAcceptance.hasPortFirstRoute(routes));
		assertEquals(1, RouteAcceptance.solePortFirstIndex(routes));
	}
}
