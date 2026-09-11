package gps;

import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertTrue;

/**
 * The overlay's route across a REAL generation (real service, real threads), sampled at high
 * frequency from a render-like thread: for a fresh destination the overlay must show nothing
 * while the generation streams and then the settled best — one appearance, no flicker through
 * the re-sorted list.
 */
public class ProvisionalDisplayLiveTest
{
	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3221, 3219, 0);
	private static final int GRAND_EXCHANGE = WorldPointUtil.packWorldPoint(3164, 3487, 0);
	/** Capture 20260822-163446: Lumbridge -> Civitas illa Fortis, 10 chain routes + seeds + a 383 ms walk. */
	private static final int CIVITAS = WorldPointUtil.packWorldPoint(1687, 3141, 0);

	private static Client client()
	{
		final Thread ct = Thread.currentThread();
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getGameState": return GameState.LOGGED_IN;
					case "getClientThread": return ct;
					case "getBoostedSkillLevel": return 99;
					default: return HybridPageFillTest.defaultValue(method.getReturnType());
				}
			});
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

	/** The plugin fields that moved onto the DestinationController (plan step L36), by their old names. */
	private static final java.util.Map<String, String> DESTINATION_FIELDS = java.util.Map.of(
		"pathTargets", "targets", "pathStart", "start", "altRoundTrip", "roundTrip", "targetSource", "source");

	/** The object and field name to reflect on for {@code field}: the session or a controller for a moved field. */
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
		if (DESTINATION_FIELDS.containsKey(field))
		{
			return new Object[]{fieldOf((ShortestPathPlugin) plugin, "destination"), DESTINATION_FIELDS.get(field)};
		}
		return new Object[]{plugin, field};
	}

	private static Object fieldOf(ShortestPathPlugin plugin, String field) throws Exception
	{
		Field f = ShortestPathPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		return f.get(plugin);
	}

	private static void set(Object target, String field, Object value) throws Exception
	{
		Object[] at = resolve(target, field);
		Field f = at[0].getClass().getDeclaredField((String) at[1]);
		f.setAccessible(true);
		f.set(at[0], value);
	}

	private static Object get(Object target, String field) throws Exception
	{
		Object[] at = resolve(target, field);
		Field f = at[0].getClass().getDeclaredField((String) at[1]);
		f.setAccessible(true);
		return f.get(at[0]);
	}

	private static List<String> sampleGeneration(int start, int target, AlternativeRoutesMode mode) throws Exception
	{
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class, Mockito.withSettings().stubOnly());
		Mockito.when(cfg.calculationCutoff()).thenReturn(120);
		Mockito.when(cfg.currencyThreshold()).thenReturn(10000000);
		Mockito.when(cfg.useTeleportationItems()).thenReturn(TeleportationItem.INVENTORY);
		for (String name : new String[]{"useTeleportationSpells", "useFairyRings", "useSpiritTrees", "useShips",
			"useMinecarts", "useCharterShips", "useGnomeGliders", "useQuetzals", "useTeleportationMinigames",
			"useTeleportationPortals", "useAgilityShortcuts", "useBoats", "useMagicCarpets", "useCanoes",
			"useTeleportationLevers", "useMountainGuides", "useHotAirBalloons", "useMagicMushtrees", "useSailing"})
		{
			Mockito.when((Boolean) ShortestPathConfig.class.getMethod(name).invoke(cfg)).thenReturn(true);
		}
		Mockito.when(cfg.defaultRouteCount()).thenReturn(10);
		PathfinderConfig planning = new TestPathfinderConfig(client(), cfg).copyForPlanning();
		planning.refresh();
		// The service refreshes on "the client thread": the test thread here, via a direct-run mock.
		ClientThread ct = Mockito.mock(ClientThread.class, Mockito.withSettings().stubOnly());
		Mockito.doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(ct).invokeLater(Mockito.any(Runnable.class));
		AlternativeRoutesService service = new AlternativeRoutesService(ct, planning);

		ShortestPathPlugin plugin = new ShortestPathPlugin();
		set(plugin, "routeDirectionsOverlay", Mockito.mock(RouteDirectionsOverlay.class));
		set(plugin, "altRoutesService", service);
		set(plugin, "config", cfg);
		set(plugin, "pathTargets", Set.of(target));
		set(plugin, "routeLimit", 10);
		set(plugin, "routesMode", mode);

		// Sample what the overlay would draw, as fast as a render loop and then some.
		List<String> trace = new ArrayList<>();
		Thread sampler = new Thread(() ->
		{
			RouteOption last = null;
			boolean started = false;
			boolean sawFinding = false;
			long until = System.currentTimeMillis() + 30000;
			while (System.currentTimeMillis() < until)
			{
				try
				{
					boolean inFlight = (boolean) get(plugin, "altGenerationInFlight");
					started |= inFlight;
					RouteOption now = plugin.getDisplayedRoute();
					// The initial empty state is only "seen" once the generation is in flight.
					if (!sawFinding && now == null && plugin.isFindingRoute())
					{
						sawFinding = true;
						synchronized (trace)
						{
							trace.add("null [finding] [in-flight]");
						}
					}
					if (now != last)
					{
						synchronized (trace)
						{
							trace.add((now == null ? "null" : now.getTotalCost() + " " + now.getMethods())
								+ (plugin.isFindingRoute() ? " [finding]" : "")
								+ (inFlight ? " [in-flight]" : ""));
						}
						last = now;
					}
					if (started && !inFlight)
					{
						break;
					}
					Thread.sleep(0, 200_000);
				}
				catch (Exception e)
				{
					throw new RuntimeException(e);
				}
			}
		});
		sampler.start();

		((RouteController) fieldOf(plugin, "routes")).trigger(start, new HashSet<>(Set.of(target)));
		sampler.join(40000);
		service.shutdown();
		synchronized (trace)
		{
			return new ArrayList<>(trace);
		}
	}

	private static void assertOneAppearance(List<String> trace)
	{
		int routeChanges = 0;
		for (String t : trace)
		{
			if (!t.startsWith("null"))
			{
				routeChanges++;
			}
		}
		assertTrue("the overlay must stay empty while finding and then show the settled route once; saw " + trace,
			routeChanges == 1);
		assertTrue("the finding state must be visible before the list settles; saw " + trace,
			trace.stream().anyMatch(t -> t.startsWith("null") && t.contains("[finding]")));
	}

	@Test
	public void lumbridgeToGrandExchange() throws Exception
	{
		assertOneAppearance(sampleGeneration(LUMBRIDGE, GRAND_EXCHANGE, AlternativeRoutesMode.OWNED_INVENTORY));
	}

	@Test
	public void lumbridgeToCivitasEverything() throws Exception
	{
		assertOneAppearance(sampleGeneration(LUMBRIDGE, CIVITAS, AlternativeRoutesMode.ALL_EVERYTHING));
	}
}
