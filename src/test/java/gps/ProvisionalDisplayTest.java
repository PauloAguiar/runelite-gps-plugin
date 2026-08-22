package gps;

import gps.pathfinder.PathStep;
import gps.transport.TransportType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The overlay's route while a generation streams: for a fresh destination NOTHING is shown — the
 * HUD is in its "Finding the best route" state — until the generation settles, when the best
 * route appears once. Front-runners streamed mid-generation never reach the overlay. A
 * same-destination regeneration keeps the committed route on screen throughout. Exercised through
 * the real trigger/update methods via reflection on a bare plugin instance (the RouteRematchTest
 * pattern).
 */
public class ProvisionalDisplayTest
{
	private static final int START = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int TARGET = WorldPointUtil.packWorldPoint(3164, 3487, 0);
	private static final TeleportMethod TABLET =
		new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Varrock tablet", TARGET);
	private static final TeleportMethod SPELL =
		new TeleportMethod(TransportType.TELEPORTATION_SPELL, "Varrock Teleport", TARGET);

	private ShortestPathPlugin plugin;

	@Before
	public void before() throws Exception
	{
		plugin = new ShortestPathPlugin();
		RouteDirectionsOverlay overlay = Mockito.mock(RouteDirectionsOverlay.class);
		set("routeDirectionsOverlay", overlay);
		AlternativeRoutesService service = Mockito.mock(AlternativeRoutesService.class);
		Mockito.when(service.wasMoreLikely()).thenReturn(false);
		set("altRoutesService", service);
		set("pathTargets", Set.of(TARGET));
		// The done-branch publishes to other plugins through the config (postTransports).
		set("config", Mockito.mock(ShortestPathConfig.class));
	}

	private void set(String field, Object value) throws Exception
	{
		Field f = ShortestPathPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	@SuppressWarnings("unchecked")
	private <T> T get(String field) throws Exception
	{
		Field f = ShortestPathPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		return (T) f.get(plugin);
	}

	private void trigger() throws Exception
	{
		Method m = ShortestPathPlugin.class.getDeclaredMethod("triggerAlternatives", int.class, Set.class);
		m.setAccessible(true);
		m.invoke(plugin, START, new HashSet<>(Set.of(TARGET)));
	}

	private void update(List<RouteOption> routes, boolean done) throws Exception
	{
		Method m = ShortestPathPlugin.class.getDeclaredMethod("onAlternativeRoutesUpdate",
			List.class, List.class, Map.class, boolean.class);
		m.setAccessible(true);
		m.invoke(plugin, routes, List.<TeleportMethod>of(), Map.<TeleportMethod, MethodAvailability>of(), done);
	}

	private static RouteOption route(int cost, TeleportMethod method)
	{
		List<PathStep> path = List.of(new PathStep(START, false), new PathStep(TARGET, false));
		return new RouteOption(path, List.of(method), List.of(1), List.of(1), cost, cost, true,
			Set.of(), List.of(0), 0);
	}

	@Test
	public void freshDestinationStaysClearUntilTheListSettles() throws Exception
	{
		RouteOption first = route(100, SPELL);
		RouteOption cheaper = route(50, TABLET);
		RouteOption middle = route(70, SPELL);

		trigger();
		assertTrue("a generation is in flight", (boolean) get("altGenerationInFlight"));
		assertNull("nothing on the overlay before the routes settle", plugin.getDisplayedRoute());
		assertTrue("the HUD is in its finding state", plugin.isFindingRoute());

		update(List.of(first), false);
		assertNull("the first route found is NOT shown — it may still change", plugin.getDisplayedRoute());
		assertTrue(plugin.isFindingRoute());
		update(List.of(cheaper, first), false);
		update(List.of(cheaper, middle, first), false);
		assertNull("front-runners mid-stream never reach the overlay", plugin.getDisplayedRoute());
		assertTrue(plugin.isFindingRoute());

		update(List.of(cheaper, middle, first), true);
		assertSame("settled: the final best appears, once", cheaper, plugin.getDisplayedRoute());
		assertFalse("finding state over", plugin.isFindingRoute());
		assertFalse((boolean) get("altGenerationInFlight"));
	}

	@Test
	public void sameDestinationRegenerationKeepsTheCommittedRoute() throws Exception
	{
		RouteOption settled = route(50, TABLET);
		RouteOption newer = route(40, SPELL);

		trigger();
		update(List.of(settled), false);
		update(List.of(settled), true);
		assertSame(settled, plugin.getDisplayedRoute());

		// Off-route recalc / method toggle: same targets, the overlay holds the committed route
		// steadily while the fresh list streams — no blank, no finding state.
		trigger();
		assertSame("committed route survives the regeneration start", settled, plugin.getDisplayedRoute());
		assertFalse("a held route is not a finding state", plugin.isFindingRoute());
		update(List.of(newer), false);
		assertSame("streaming does not swap the held route", settled, plugin.getDisplayedRoute());
		update(List.of(newer), true);
		assertSame("settles to the new best once", newer, plugin.getDisplayedRoute());
	}
}
