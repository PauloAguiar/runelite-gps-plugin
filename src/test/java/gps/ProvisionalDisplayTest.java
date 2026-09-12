package gps;

import com.google.gson.Gson;
import gps.pathfinder.PathStep;
import gps.transport.TransportType;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The overlay's route while a generation streams: for a fresh destination NOTHING is shown, the
 * HUD is in its "Finding the best route" state, until the generation settles, when the best
 * route appears once. Front-runners streamed mid-generation never reach the overlay. A
 * same-destination regeneration keeps the committed route on screen throughout. Exercised
 * through the controller's real trigger and update over a mocked plugin (review of 2026-09-12:
 * previously by reflection on a bare plugin instance).
 */
@RunWith(MockitoJUnitRunner.class)
public class ProvisionalDisplayTest
{
	private static final int START = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int TARGET = WorldPointUtil.packWorldPoint(3164, 3487, 0);
	private static final Set<Integer> TARGETS = Set.of(TARGET);
	private static final TeleportMethod TABLET =
		new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Varrock tablet", TARGET);
	private static final TeleportMethod SPELL =
		new TeleportMethod(TransportType.TELEPORTATION_SPELL, "Varrock Teleport", TARGET);

	@Mock
	ShortestPathPlugin plugin;
	@Mock
	ShortestPathConfig config;
	@Mock
	ConfigManager configManager;
	@Mock
	AlternativeRoutesService service;

	private RouteSession session;
	private RouteController routes;

	@Before
	public void before()
	{
		when(plugin.getGpsConfig()).thenReturn(config);
		when(config.defaultRouteCount()).thenReturn(10);
		// The effective order is the identity here: the lists arrive already ordered.
		when(plugin.sortByEffectiveOrder(any())).thenAnswer(i -> i.getArgument(0));
		ChoiceStore choices = new ChoiceStore(() -> configManager, Gson::new, "gps");
		session = new RouteSession();
		routes = new RouteController(plugin, session, new MethodExclusions(choices, () -> { }), choices,
			new PluginMessageBridge(plugin, () -> null));
		routes.start(service);
	}

	private void trigger()
	{
		routes.trigger(START, new HashSet<>(TARGETS));
	}

	private void update(List<RouteOption> list, boolean done)
	{
		routes.onUpdate(list, List.of(), Map.of(), done);
	}

	/** What the overlay draws for the destination. */
	private RouteOption displayed()
	{
		return session.displayed(TARGETS);
	}

	/** Whether the HUD is in its finding state for the destination. */
	private boolean finding()
	{
		return session.isFinding(TARGETS);
	}

	private static RouteOption route(int cost, TeleportMethod method)
	{
		List<PathStep> path = List.of(new PathStep(START, false), new PathStep(TARGET, false));
		return new RouteOption(path, List.of(method), List.of(1), List.of(1), cost, cost, true,
			Set.of(), List.of(0), 0);
	}

	@Test
	public void freshDestinationStaysClearUntilTheListSettles()
	{
		RouteOption first = route(100, SPELL);
		RouteOption cheaper = route(50, TABLET);
		RouteOption middle = route(70, SPELL);

		trigger();
		assertTrue("a generation is in flight", session.inFlight());
		assertNull("nothing on the overlay before the routes settle", displayed());
		assertTrue("the HUD is in its finding state", finding());

		update(List.of(first), false);
		assertNull("the first route found is NOT shown: it may still change", displayed());
		assertTrue(finding());
		update(List.of(cheaper, first), false);
		update(List.of(cheaper, middle, first), false);
		assertNull("front-runners mid-stream never reach the overlay", displayed());
		assertTrue(finding());

		update(List.of(cheaper, middle, first), true);
		assertSame("settled: the final best appears, once", cheaper, displayed());
		assertFalse("finding state over", finding());
		assertFalse(session.inFlight());
	}

	@Test
	public void sameDestinationRegenerationKeepsTheCommittedRoute()
	{
		RouteOption settled = route(50, TABLET);
		RouteOption newer = route(40, SPELL);

		trigger();
		update(List.of(settled), false);
		update(List.of(settled), true);
		assertSame(settled, displayed());

		// Off-route recalc / method toggle: same targets, the overlay holds the committed route
		// steadily while the fresh list streams: no blank, no finding state.
		trigger();
		assertSame("committed route survives the regeneration start", settled, displayed());
		assertFalse("a held route is not a finding state", finding());
		update(List.of(newer), false);
		assertSame("streaming does not swap the held route", settled, displayed());
		update(List.of(newer), true);
		assertSame("settles to the new best once", newer, displayed());
	}
}
