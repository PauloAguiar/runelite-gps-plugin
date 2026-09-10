package gps;

import gps.transport.TransportType;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Plan step L9: the alternative-routes session, out of the plugin class. The page, the pick, the
 * committed display route, the in-flight flag, the last generation's start, targets and limit,
 * and the "more" budget, with the decisions that used to be spread over eleven plugin fields:
 * a fresh destination shows nothing until its routes settle, a same-destination regeneration
 * keeps the current route on screen, a pick survives a regeneration when an equivalent route
 * exists (matching what is LEFT of the plan) unless it was never started and something far
 * better appeared, a stale page for another destination is never shown, and "more" widens the
 * band and the count up to the cap.
 */
public class RouteSessionTest
{
	private static final TeleportMethod SPELL = new TeleportMethod(TransportType.TELEPORTATION_SPELL, "Varrock Teleport", 1);
	private static final TeleportMethod RING = new TeleportMethod(TransportType.FAIRY_RING, "A K S", 2);
	private static final TeleportMethod CART = new TeleportMethod(TransportType.MINECART, "Keldagrim", 3);
	private static final Set<Integer> T1 = Set.of(WorldPointUtil.packWorldPoint(3212, 3422, 0));
	private static final Set<Integer> T2 = Set.of(WorldPointUtil.packWorldPoint(2965, 3380, 0));

	private static RouteOption route(int cost, List<TeleportMethod> methods, List<Integer> edges)
	{
		return new RouteOption(List.of(), methods, edges, List.of(), cost, cost, true, Set.of(), List.of(), 0);
	}

	private static RouteOption route(int cost, TeleportMethod... methods)
	{
		return route(cost, List.of(methods), List.of());
	}

	@Test
	public void aFreshDestinationShowsNothingUntilItsRoutesSettle()
	{
		RouteSession session = new RouteSession();
		session.setLimit(10);
		session.begin(1, T1);
		assertTrue(session.inFlight());
		assertNull(session.displayed(T1));

		RouteOption a = route(100, SPELL);
		session.stream(List.of(a));
		assertNull("a streaming front-runner is never drawn", session.displayed(T1));

		RouteOption b = route(150, RING);
		session.settle(List.of(a, b), true, 250, () -> 0);
		assertFalse(session.inFlight());
		assertSame("settled: the best route", a, session.displayed(T1));
		assertTrue("routes were left unshown and the budget is below the cap", session.canLoadMore());
	}

	@Test
	public void selectionTogglesAndFallsBackToTheBest()
	{
		RouteSession session = new RouteSession();
		session.begin(1, T1);
		RouteOption a = route(100, SPELL);
		RouteOption b = route(150, RING);
		session.settle(List.of(a, b), false, 250, () -> 0);
		assertTrue(session.select(1));
		assertSame(b, session.displayed(T1));
		assertTrue("clicking the shown route hides it", session.select(1));
		assertSame("the best route is the fallback", a, session.displayed(T1));
		assertFalse("out of range", session.select(9));
		assertFalse("nothing was left unshown", session.canLoadMore());
	}

	@Test
	public void aSameDestinationRegenerationKeepsTheDisplayAndRematchesThePick()
	{
		RouteSession session = new RouteSession();
		session.begin(1, T1);
		RouteOption a = route(100, SPELL);
		RouteOption b = route(150, RING);
		session.settle(List.of(a, b), false, 250, () -> 0);
		session.select(1);

		session.begin(2, T1);
		assertTrue(session.inFlight());
		assertSame("the picked route stays on screen while the fresh page computes", b, session.displayed(T1));

		RouteOption a2 = route(90, SPELL);
		RouteOption b2 = route(160, RING);
		session.settle(List.of(a2, b2), false, 250, () -> 0);
		assertSame("the equivalent route stays selected, even at another cost", b2, session.selected());
		assertSame(b2, session.displayed(T1));
	}

	@Test
	public void aNeverStartedPickYieldsToSomethingFarBetter()
	{
		RouteSession session = new RouteSession();
		session.begin(1, T1);
		RouteOption a = route(100, SPELL);
		RouteOption b = route(300, RING);
		session.settle(List.of(a, b), false, 250, () -> 0);
		session.select(1);

		session.begin(1, T1);
		RouteOption a2 = route(100, SPELL);
		RouteOption b2 = route(300, RING);
		session.settle(List.of(a2, b2), false, 250, () -> 0);
		assertNull("300 against a best of 100, standing at the start: not stability", session.selected());
		assertSame(a2, session.displayed(T1));

		// The same page, but the pick was under way (progress past the start): it is kept.
		session.select(1);
		session.begin(1, T1);
		session.settle(List.of(a2, b2), false, 250, () -> 7);
		assertSame(b2, session.selected());
	}

	@Test
	public void rematchMatchesWhatIsLeftOfThePlan()
	{
		RouteOption previous = route(200, List.of(SPELL, CART), List.of(3, 8));
		RouteOption remainder = route(120, CART);
		RouteOption whole = route(200, SPELL, CART);
		assertSame("nothing consumed: the full sequence", whole, RouteSession.rematch(previous, List.of(remainder, whole), 0));
		assertSame("the spell's edge is behind the player: the remainder", remainder,
			RouteSession.rematch(previous, List.of(remainder, whole), 5));
		assertNull(RouteSession.rematch(previous, List.of(route(50, RING)), 0));
	}

	@Test
	public void aNewDestinationClearsTheDisplayAndAStalePageIsNeverShown()
	{
		RouteSession session = new RouteSession();
		session.begin(1, T1);
		RouteOption a = route(100, SPELL);
		session.settle(List.of(a), false, 250, () -> 0);
		assertSame(a, session.displayed(T1));
		assertNull("the page was generated for T1", session.displayed(T2));
		assertNull(session.displayed(Set.of()));

		session.begin(1, T2);
		assertNull("a new destination draws nothing until it settles", session.displayed(T2));
	}

	@Test
	public void moreWidensTheBandAndTheCountUpToTheCap()
	{
		RouteSession session = new RouteSession();
		session.setLimit(10);
		assertFalse("no destination yet", session.widen(10, 250));
		session.begin(1, T1);
		session.settle(List.of(route(100, SPELL)), true, 250, () -> 0);
		assertTrue(session.widen(10, 250));
		assertEquals(6, session.costMultiple());
		assertEquals(20, session.limit());
		assertEquals("the budget the generation ran with, for the auto-compute check", 10, session.lastLimit());

		session.setLimit(250);
		session.begin(1, T1);
		session.settle(List.of(route(100, SPELL)), true, 250, () -> 0);
		assertFalse("at the cap nothing more can be asked for", session.canLoadMore());

		session.resetBudget(10);
		assertEquals(RouteSession.DEFAULT_COST_MULTIPLE, session.costMultiple());
		assertEquals(10, session.limit());
	}

	@Test
	public void resortAndAutoCompute()
	{
		RouteSession session = new RouteSession();
		session.begin(1, T1);
		RouteOption a = route(100, SPELL);
		RouteOption b = route(150, RING);
		session.settle(List.of(a, b), false, 250, () -> 0);
		session.resort(Comparator.comparingInt(RouteOption::getTotalCost).reversed());
		assertSame(b, session.routes().get(0));

		assertTrue(RouteSession.shouldAutoCompute(T1, Set.of(), 0, 10));
		assertTrue("the last run was allowed fewer routes than wanted now", RouteSession.shouldAutoCompute(T1, T1, 1, 10));
		assertFalse(RouteSession.shouldAutoCompute(T1, T1, 10, 10));
		assertFalse("no target", RouteSession.shouldAutoCompute(Set.of(), T1, 0, 10));
	}
}
