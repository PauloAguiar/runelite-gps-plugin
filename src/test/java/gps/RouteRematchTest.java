package gps;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import gps.pathfinder.PathStep;
import gps.transport.TransportType;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Selection stickiness across recalculations: the picked route survives re-ranking, survives the
 * player's own progress along it (methods already behind are consumed from the match), and only
 * falls back to the new best when no equivalent continuation exists. Exercised through the real
 * {@code onAlternativeRoutesUpdate} done-branch via reflection on a bare plugin instance.
 */
public class RouteRematchTest
{
	private static final TeleportMethod CLOAK =
		new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Ardougne cloak", WorldPointUtil.packWorldPoint(2606, 3230, 0));
	private static final TeleportMethod MINECART =
		new TeleportMethod(TransportType.MINECART, "Keldagrim minecart", WorldPointUtil.packWorldPoint(2908, 10170, 0));
	private static final TeleportMethod SPIRIT_TREE =
		new TeleportMethod(TransportType.SPIRIT_TREE, "Tree Gnome Village", WorldPointUtil.packWorldPoint(2542, 3170, 0));

	private ShortestPathPlugin plugin;
	private RouteDirectionsOverlay overlay;

	@Before
	public void before() throws Exception
	{
		plugin = new ShortestPathPlugin();
		overlay = Mockito.mock(RouteDirectionsOverlay.class);
		set("routeDirectionsOverlay", overlay);
		AlternativeRoutesService service = Mockito.mock(AlternativeRoutesService.class);
		Mockito.when(service.wasMoreLikely()).thenReturn(false);
		set("altRoutesService", service);
	}

	/** The plugin fields that moved onto the RouteSession (plan step L9), by their old names. */
	private static final java.util.Map<String, String> SESSION_FIELDS = java.util.Map.of(
		"alternativeRoutes", "routes", "selectedRoute", "selected", "committedDisplayRoute", "committed",
		"altGenerationInFlight", "inFlight", "moreRoutesLikely", "moreLikely", "lastAltStart", "lastStart",
		"lastAltTargets", "lastTargets", "lastAltLimit", "lastLimit", "routeLimit", "limit",
		"routeCostMultiple", "costMultiple");

	/** The plugin fields that moved onto the RouteController (plan step L35), by their old names. */
	private static final java.util.Map<String, String> ROUTES_FIELDS = java.util.Map.of(
		"altRoutesService", "service", "routesMode", "mode", "altPanelVisible", "panelVisible");

	/** The object and field name to reflect on for {@code field}: the session or the controller for a moved field. */
	private static Object[] resolve(Object plugin, String field) throws Exception
	{
		if (!(plugin instanceof ShortestPathPlugin))
		{
			return new Object[]{plugin, field};
		}
		if (SESSION_FIELDS.containsKey(field))
		{
			return new Object[]{fieldOf((ShortestPathPlugin) plugin, "session"), SESSION_FIELDS.get(field)};
		}
		if (ROUTES_FIELDS.containsKey(field))
		{
			return new Object[]{fieldOf((ShortestPathPlugin) plugin, "routes"), ROUTES_FIELDS.get(field)};
		}
		return new Object[]{plugin, field};
	}

	private static Object fieldOf(ShortestPathPlugin plugin, String field) throws Exception
	{
		Field f = ShortestPathPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		return f.get(plugin);
	}

	private void set(String field, Object value) throws Exception
	{
		Object[] at = resolve(plugin, field);
		Field f = at[0].getClass().getDeclaredField((String) at[1]);
		f.setAccessible(true);
		f.set(at[0], value);
	}

	@SuppressWarnings("unchecked")
	private <T> T get(String field) throws Exception
	{
		Object[] at = resolve(plugin, field);
		Field f = at[0].getClass().getDeclaredField((String) at[1]);
		f.setAccessible(true);
		return (T) f.get(at[0]);
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

	/** Runs the controller's generation-update handler as the service would. */
	private void update(List<RouteOption> routes, boolean done) throws Exception
	{
		((RouteController) fieldOf(plugin, "routes")).onUpdate(routes, List.of(), Map.of(), done);
	}

	private void select(RouteOption route, int trackerProgress) throws Exception
	{
		set("selectedRoute", route);
		Mockito.when(overlay.reachedIndexFor(route)).thenReturn(trackerProgress);
	}

	@Test
	public void pickedRouteStaysSelectedWhenItsRankMoves() throws Exception
	{
		RouteOption picked = route(CLOAK);
		select(picked, 0);
		RouteOption newBest = route(SPIRIT_TREE);
		RouteOption equivalent = route(CLOAK); // same methods, different object, now ranked #2
		update(List.of(newBest, equivalent), true);
		assertSame("equivalent route keeps the selection despite the rank change",
			equivalent, this.<RouteOption>get("selectedRoute"));
		assertSame(equivalent, this.<RouteOption>get("committedDisplayRoute"));
	}

	@Test
	public void vanishedRouteFallsBackToTheNewBest() throws Exception
	{
		select(route(CLOAK), 0);
		RouteOption newBest = route(SPIRIT_TREE);
		update(List.of(newBest), true);
		assertNull("no equivalent -> selection cleared", this.<RouteOption>get("selectedRoute"));
		assertSame(newBest, this.<RouteOption>get("committedDisplayRoute"));
	}

	@Test
	public void bankDetourVariantIsNotTheSameRouteBeforeDeparture()
		throws Exception
	{
		select(route(false, List.of(10), CLOAK), 0);
		RouteOption bankVariant = route(true, List.of(10), CLOAK);
		update(List.of(bankVariant), true);
		assertNull("direct route must not silently become the bank-detour variant",
			this.<RouteOption>get("selectedRoute"));
	}

	@Test
	public void methodsAlreadyBehindAreConsumedFromTheMatch() throws Exception
	{
		// Picked minecart -> spirit tree; the tracker says the player is past the minecart edge
		// (index 10) but before the spirit tree (index 20).
		RouteOption picked = route(MINECART, SPIRIT_TREE);
		select(picked, 14);
		RouteOption continuation = route(SPIRIT_TREE); // fresh list re-plans only the remainder
		RouteOption other = route(CLOAK);
		update(List.of(other, continuation), true);
		assertSame("the continuation (remaining methods) keeps the selection",
			continuation, this.<RouteOption>get("selectedRoute"));
	}

	@Test
	public void bankNessIsIgnoredMidJourney() throws Exception
	{
		// Withdrew and departed: the picked route was via the bank, the continuation is direct.
		RouteOption picked = route(true, List.of(10, 20), MINECART, SPIRIT_TREE);
		select(picked, 14);
		RouteOption continuation = route(false, List.of(10), SPIRIT_TREE);
		update(List.of(continuation), true);
		assertSame(continuation, this.<RouteOption>get("selectedRoute"));
	}

	@Test
	public void walkRemainderMatchesOnlyWhenEveryMethodIsBehind() throws Exception
	{
		RouteOption picked = route(MINECART);
		RouteOption walk = route(); // no methods
		// Before the minecart: the walk route must NOT steal the selection.
		select(picked, 0);
		update(List.of(walk), true);
		assertNull("untouched selection must not match the plain-walk route",
			this.<RouteOption>get("selectedRoute"));
		// After the minecart (edge 10 crossed): the walk route IS the honest continuation.
		select(picked, 12);
		update(List.of(walk), true);
		assertSame(walk, this.<RouteOption>get("selectedRoute"));
	}

	@Test
	public void midStreamUpdatesDoNotDisturbTheSelection() throws Exception
	{
		RouteOption picked = route(CLOAK);
		select(picked, 0);
		// Streaming update (done=false) with no equivalent present yet: selection must survive
		// so the overlay keeps drawing the picked route until the generation settles.
		update(List.of(route(SPIRIT_TREE)), false);
		assertSame(picked, this.<RouteOption>get("selectedRoute"));
		// The equivalent arrives by the time the generation settles: selection transfers to it.
		RouteOption equivalent = route(CLOAK);
		update(List.of(route(SPIRIT_TREE), equivalent), true);
		assertSame(equivalent, this.<RouteOption>get("selectedRoute"));
	}

	@Test
	public void emptyFinalListClearsEverything() throws Exception
	{
		select(route(CLOAK), 0);
		update(List.of(), true);
		assertNull(this.<RouteOption>get("selectedRoute"));
		assertNull(this.<RouteOption>get("committedDisplayRoute"));
		assertEquals(Boolean.FALSE, this.<Boolean>get("moreRoutesLikely"));
	}
}
