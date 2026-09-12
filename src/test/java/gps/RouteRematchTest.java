package gps;

import gps.pathfinder.PathStep;
import gps.transport.TransportType;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Selection stickiness across recalculations: the picked route survives re-ranking, survives the
 * player's own progress along it (methods already behind are consumed from the match), and only
 * falls back to the new best when no equivalent continuation exists. Exercised through the
 * session's settle, which is what the generation's done-branch calls (review of 2026-09-12:
 * previously through the plugin's private update handler by reflection).
 */
public class RouteRematchTest
{
	private static final TeleportMethod CLOAK =
		new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Ardougne cloak", WorldPointUtil.packWorldPoint(2606, 3230, 0));
	private static final TeleportMethod MINECART =
		new TeleportMethod(TransportType.MINECART, "Keldagrim minecart", WorldPointUtil.packWorldPoint(2908, 10170, 0));
	private static final TeleportMethod SPIRIT_TREE =
		new TeleportMethod(TransportType.SPIRIT_TREE, "Tree Gnome Village", WorldPointUtil.packWorldPoint(2542, 3170, 0));
	private static final Set<Integer> TARGETS = Set.of(WorldPointUtil.packWorldPoint(2542, 3170, 0));

	private RouteSession session;
	// What the directions tracker reports for the picked route.
	private int progress;

	@Before
	public void before()
	{
		session = new RouteSession();
		session.begin(WorldPointUtil.packWorldPoint(3222, 3218, 0), TARGETS);
	}

	private static RouteOption route(boolean viaBank, List<Integer> methodEdges, TeleportMethod... methods)
	{
		List<TeleportMethod> methodList = List.of(methods);
		Set<TeleportMethod> bankMethods = viaBank && methods.length > 0 ? Set.of(methods[0]) : Set.of();
		List<Integer> durations = methodList.stream().map(m -> 1).collect(java.util.stream.Collectors.toList());
		return new RouteOption(List.<PathStep>of(), methodList, methodEdges, durations,
			100, 100, true, bankMethods, List.of(), 0);
	}

	private static RouteOption route(TeleportMethod... methods)
	{
		List<Integer> edges = new java.util.ArrayList<>();
		for (int i = 0; i < methods.length; i++)
		{
			edges.add(10 * (i + 1)); // method edges at path indexes 10, 20, ...
		}
		return route(false, edges, methods);
	}

	/** Settles the fresh list as the generation's done-branch does, or streams it mid-generation. */
	private void update(List<RouteOption> routes, boolean done)
	{
		if (done)
		{
			session.settle(routes, false, AlternativeRoutesService.MAX_ROUTES_CAP, () -> progress);
		}
		else
		{
			session.stream(routes);
		}
	}

	/** The player picked {@code route}; the tracker says they are at path index {@code trackerProgress}. */
	private void select(RouteOption route, int trackerProgress)
	{
		session.stream(List.of(route));
		session.select(0);
		progress = trackerProgress;
	}

	/** The overlay's route after a settle: the pick, else the settled best. */
	private RouteOption committed()
	{
		return session.displayed(TARGETS);
	}

	@Test
	public void pickedRouteStaysSelectedWhenItsRankMoves()
	{
		RouteOption picked = route(CLOAK);
		select(picked, 0);
		RouteOption newBest = route(SPIRIT_TREE);
		RouteOption equivalent = route(CLOAK); // same methods, different object, now ranked #2
		update(List.of(newBest, equivalent), true);
		assertSame("equivalent route keeps the selection despite the rank change", equivalent, session.selected());
		assertSame(equivalent, committed());
	}

	@Test
	public void vanishedRouteFallsBackToTheNewBest()
	{
		select(route(CLOAK), 0);
		RouteOption newBest = route(SPIRIT_TREE);
		update(List.of(newBest), true);
		assertNull("no equivalent -> selection cleared", session.selected());
		assertSame(newBest, committed());
	}

	@Test
	public void bankDetourVariantIsNotTheSameRouteBeforeDeparture()
	{
		select(route(false, List.of(10), CLOAK), 0);
		RouteOption bankVariant = route(true, List.of(10), CLOAK);
		update(List.of(bankVariant), true);
		assertNull("direct route must not silently become the bank-detour variant", session.selected());
	}

	@Test
	public void methodsAlreadyBehindAreConsumedFromTheMatch()
	{
		// Picked minecart -> spirit tree; the tracker says the player is past the minecart edge
		// (index 10) but before the spirit tree (index 20).
		RouteOption picked = route(MINECART, SPIRIT_TREE);
		select(picked, 14);
		RouteOption continuation = route(SPIRIT_TREE); // fresh list re-plans only the remainder
		RouteOption other = route(CLOAK);
		update(List.of(other, continuation), true);
		assertSame("the continuation (remaining methods) keeps the selection", continuation, session.selected());
	}

	@Test
	public void bankNessIsIgnoredMidJourney()
	{
		// Withdrew and departed: the picked route was via the bank, the continuation is direct.
		RouteOption picked = route(true, List.of(10, 20), MINECART, SPIRIT_TREE);
		select(picked, 14);
		RouteOption continuation = route(false, List.of(10), SPIRIT_TREE);
		update(List.of(continuation), true);
		assertSame(continuation, session.selected());
	}

	@Test
	public void walkRemainderMatchesOnlyWhenEveryMethodIsBehind()
	{
		RouteOption picked = route(MINECART);
		RouteOption walk = route(); // no methods
		// Before the minecart: the walk route must NOT steal the selection.
		select(picked, 0);
		update(List.of(walk), true);
		assertNull("untouched selection must not match the plain-walk route", session.selected());
		// After the minecart (edge 10 crossed): the walk route IS the honest continuation.
		select(picked, 12);
		update(List.of(walk), true);
		assertSame(walk, session.selected());
	}

	@Test
	public void midStreamUpdatesDoNotDisturbTheSelection()
	{
		RouteOption picked = route(CLOAK);
		select(picked, 0);
		// Streaming update (done=false) with no equivalent present yet: selection must survive
		// so the overlay keeps drawing the picked route until the generation settles.
		update(List.of(route(SPIRIT_TREE)), false);
		assertSame(picked, session.selected());
		// The equivalent arrives by the time the generation settles: selection transfers to it.
		RouteOption equivalent = route(CLOAK);
		update(List.of(route(SPIRIT_TREE), equivalent), true);
		assertSame(equivalent, session.selected());
	}

	@Test
	public void emptyFinalListClearsEverything()
	{
		select(route(CLOAK), 0);
		update(List.of(), true);
		assertNull(session.selected());
		assertNull(committed());
		assertFalse(session.canLoadMore());
	}
}
