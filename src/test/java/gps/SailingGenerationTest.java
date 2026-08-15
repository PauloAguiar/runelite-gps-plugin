package gps;

import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.TransportType;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertTrue;

/**
 * SERVICE-level sailing generation: the layer every August field finding lived in while the
 * unit tests stayed green. Seeds, chains, display caps and dedupe compose here — so these
 * tests assert ROUTE-SET PROPERTIES for aboard starts, not single-search reachability:
 * the closest-port option surviving the display cap (finding 4), multi-port + post-landing
 * teleport diversity with abandonment forbidden (finding 5), and the direct sail beating the
 * disembark/re-embark farce for a close water pin (the Cairn Isle capture).
 */
@RunWith(MockitoJUnitRunner.class)
public class SailingGenerationTest
{
	private static final int PANDEMONIUM_WATER = WorldPointUtil.packWorldPoint(3093, 2981, 0);
	private static final int VARROCK = WorldPointUtil.packWorldPoint(3212, 3433, 0);
	private static final int NEAR_CAIRN_WATER = WorldPointUtil.packWorldPoint(2744, 2989, 0);
	private static final int CAIRN_PIN = WorldPointUtil.packWorldPoint(2692, 2941, 0);
	/** Land on a sailing-only island: every route must sail, so boarding rules are visible. */
	private static final int DOGNOSE_PIN = WorldPointUtil.packWorldPoint(3048, 2648, 0);
	/** Grimstone's mooring land tile — the far north-east ocean, sailing-only and far beyond
	 * the wet flood's settle horizon: only matrix-composed aboard legs reach it directly. */
	private static final int GRIMSTONE_PIN = WorldPointUtil.packWorldPoint(2927, 4055, 0);
	private static final int PORT_ID_PANDEMONIUM = 1;
	/** Port Sarim (id 0) — a MAINLAND berth: walkable from Varrock, so OWNED-mode
	 * scenarios can actually reach the boat with an empty mocked inventory. */
	private static final int PORT_ID_PORT_SARIM = 0;
	private static final int PORT_ID_WINTUMBER = 46;

	private AlternativeRoutesService service;

	private List<RouteOption> generate(int start, int target, boolean abandon) throws Exception
	{
		return generate(start, target, abandon, true, -1, true);
	}

	/**
	 * @param summon   the "Assume Summon Boat spell" toggle
	 * @param boatPort SAILING_BOAT_1's port id (boat owned and moored there), or -1 for no
	 *                 boat seen — the pre-detection world every earlier test lives in
	 * @param aboard   whether the start is aboard the boat (SAILING_BOARDED_BOAT)
	 */
	private List<RouteOption> generate(int start, int target, boolean abandon, boolean summon,
		int boatPort, boolean aboard) throws Exception
	{
		return generate(start, target, abandon, summon, boatPort, aboard,
			AlternativeRoutesMode.ALL_EVERYTHING);
	}

	/** The refreshed planning-copy config all these tests search with. */
	private PathfinderConfig buildConfig(boolean summon, int boatPort, boolean aboard)
		throws Exception
	{
		return buildConfig(true, summon, boatPort, aboard);
	}

	private PathfinderConfig buildConfig(boolean abandon, boolean summon, int boatPort,
		boolean aboard) throws Exception
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getGameState":
						return GameState.LOGGED_IN;
					case "getClientThread":
						// Whatever thread asks IS the client thread: the mocked ClientThread
						// runs invokeLater inline on the service worker, and the per-mode
						// refreshTransports must not no-op behind its thread guard there.
						return Thread.currentThread();
					case "getBoostedSkillLevel":
						return 99;
					case "getVarbitValue":
						int id = (int) args[0];
						if (id == VarbitID.SAILING_BOARDED_BOAT)
						{
							return aboard ? 1 : 0;
						}
						if (id == VarbitID.SAILING_BOAT_1_OWNED)
						{
							return boatPort >= 0 ? 1 : 0;
						}
						if (id == VarbitID.SAILING_BOAT_1_PORT)
						{
							return Math.max(boatPort, 0);
						}
						return 0;
					default:
						return HybridPageFillTest.defaultValue(method.getReturnType());
				}
			});
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class,
			Mockito.withSettings().stubOnly());
		Mockito.when(cfg.calculationCutoff()).thenReturn(120);
		Mockito.when(cfg.useSailing()).thenReturn(true);
		Mockito.when(cfg.sailingTeleportAbandon()).thenReturn(abandon);
		Mockito.when(cfg.sailingAssumeSummon()).thenReturn(summon);
		PathfinderConfig config = new TestPathfinderConfig(client, cfg).copyForPlanning();
		config.refresh();
		return config;
	}

	private List<RouteOption> generate(int start, int target, boolean abandon, boolean summon,
		int boatPort, boolean aboard, AlternativeRoutesMode mode) throws Exception
	{
		PathfinderConfig config = buildConfig(abandon, summon, boatPort, aboard);
		ClientThread ct = Mockito.mock(ClientThread.class, Mockito.withSettings().stubOnly());
		Mockito.doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(ct).invokeLater(Mockito.any(Runnable.class));
		service = new AlternativeRoutesService(ct, config);
		return runGeneration(start, target, mode);
	}

	/** One generation on the CURRENT service — lets a test run several against one service. */
	private List<RouteOption> runGeneration(int start, int target, AlternativeRoutesMode mode)
		throws Exception
	{
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(start, Set.of(target), Set.of(),
			mode, 10, 3, false,
			(routes, catalog, unavailable, done) ->
			{
				if (done)
				{
					out.set(routes);
					latch.countDown();
				}
			});
		assertTrue("generation must finish", latch.await(120, TimeUnit.SECONDS));
		List<RouteOption> routes = out.get();
		assertRouteShapes(routes);
		return routes;
	}

	private static ClientThread inlineClientThread()
	{
		ClientThread ct = Mockito.mock(ClientThread.class, Mockito.withSettings().stubOnly());
		Mockito.doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(ct).invokeLater(Mockito.any(Runnable.class));
		return ct;
	}

	/**
	 * Capture 211433: flip Summon Boat off, refresh — the Khazard water pin routed to
	 * "closest point" Brimhaven; a second refresh fixed it. The sea legs were synthesized
	 * from the PREVIOUS generation's snapshot, so the first post-flip generation had
	 * neither near-mooring legs (gated off) nor the berth must-include (not yet visible).
	 * One service, two generations, the toggle flipped between: the first post-flip
	 * generation must already reach.
	 */
	@Test
	public void summonFlipTakesEffectOnTheNextGeneration() throws Exception
	{
		final boolean[] summon = {true};
		final Thread clientThread = Thread.currentThread();
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getGameState":
						return GameState.LOGGED_IN;
					case "getClientThread":
						return clientThread;
					case "getBoostedSkillLevel":
						return 99;
					case "getVarbitValue":
						int id = (int) args[0];
						if (id == VarbitID.SAILING_BOAT_1_OWNED)
						{
							return 1;
						}
						if (id == VarbitID.SAILING_BOAT_1_PORT)
						{
							return PORT_ID_PANDEMONIUM;
						}
						return 0;
					default:
						return HybridPageFillTest.defaultValue(method.getReturnType());
				}
			});
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class,
			Mockito.withSettings().stubOnly());
		Mockito.when(cfg.calculationCutoff()).thenReturn(120);
		Mockito.when(cfg.useSailing()).thenReturn(true);
		// Lenient: a LAND start never consults the abandon toggle.
		Mockito.lenient().when(cfg.sailingTeleportAbandon()).thenReturn(true);
		Mockito.when(cfg.sailingAssumeSummon()).thenAnswer(i -> summon[0]);
		PathfinderConfig config = new TestPathfinderConfig(client, cfg).copyForPlanning();
		config.refresh();
		service = new AlternativeRoutesService(inlineClientThread(), config);

		int ardougne = WorldPointUtil.packWorldPoint(2610, 3222, 0);
		int khazardWater = WorldPointUtil.packWorldPoint(2693, 3152, 0);
		assertTrue("summon-on generation must reach the water pin",
			anyReaches(runGeneration(ardougne, khazardWater, AlternativeRoutesMode.OWNED_INVENTORY)));
		summon[0] = false;
		assertTrue("the FIRST generation after flipping summon off must already reach"
				+ " (capture 211433: it took a second refresh)",
			anyReaches(runGeneration(ardougne, khazardWater, AlternativeRoutesMode.OWNED_INVENTORY)));
	}

	private static boolean anyReaches(List<RouteOption> routes)
	{
		for (RouteOption route : routes)
		{
			if (route.isReached())
			{
				return true;
			}
		}
		return false;
	}

	@After
	public void after()
	{
		if (service != null)
		{
			service.shutdown();
		}
	}

	/** Finding 7's lint: no route may disembark at a port and re-board at the same port.
	 * Plus capture 233931's: parking variants (disembark somewhere, then summon the boat
	 * away to a later embark) must be collapsed — one route per distinct continuation. */
	private static void assertRouteShapes(List<RouteOption> routes)
	{
		java.util.Set<String> parkingTails = new java.util.HashSet<>();
		for (RouteOption route : routes)
		{
			List<TeleportMethod> methods = route.getMethods();
			if (methods.size() < 2 || !isSailing(methods.get(0))
				|| methods.get(0).getDisplayInfo() == null
				|| !methods.get(0).getDisplayInfo().startsWith("Disembark at "))
			{
				continue;
			}
			boolean laterEmbark = false;
			StringBuilder tail = new StringBuilder();
			for (int i = 1; i < methods.size(); i++)
			{
				laterEmbark |= isSailing(methods.get(i)) && methods.get(i).getDisplayInfo() != null
					&& methods.get(i).getDisplayInfo().startsWith("Embark at ");
				tail.append(methods.get(i)).append('|');
			}
			if (laterEmbark)
			{
				assertTrue("parking variants must collapse (capture 233931): " + methods,
					parkingTails.add(tail.toString()));
			}
		}
		for (RouteOption route : routes)
		{
			List<TeleportMethod> methods = route.getMethods();
			for (int i = 0; i + 1 < methods.size(); i++)
			{
				String a = methods.get(i).getDisplayInfo();
				String b = methods.get(i + 1).getDisplayInfo();
				if (a != null && b != null && a.startsWith("Disembark at "))
				{
					String port = a.substring("Disembark at ".length());
					assertTrue("route disembarks and re-embarks at " + port + ": " + methods,
						!b.startsWith("Sailing: " + port + " →"));
				}
			}
		}
	}

	private static boolean isSailing(TeleportMethod method)
	{
		return TransportType.SAILING.equals(method.getType());
	}

	@Test
	public void abandonOnKeepsTheClosestPortOnTheCard() throws Exception
	{
		List<RouteOption> routes = generate(PANDEMONIUM_WATER, VARROCK, true);
		assertTrue("aboard generations must produce several routes, got " + routes.size(),
			routes.size() >= 3);
		boolean teleportFirst = false;
		boolean portFirst = false;
		for (RouteOption route : routes)
		{
			TeleportMethod first = route.getMethods().isEmpty() ? null : route.getMethods().get(0);
			if (first == null)
			{
				continue;
			}
			teleportFirst |= !isSailing(first);
			portFirst |= isSailing(first) && first.getDisplayInfo() != null
				&& first.getDisplayInfo().startsWith("Disembark");
		}
		assertTrue("with abandonment allowed, teleport-first routes must exist", teleportFirst);
		assertTrue("the closest-port disembark must survive the display cost cap (finding 4)",
			portFirst);
	}

	@Test
	public void abandonOffDiversifiesPortsAndContinuations() throws Exception
	{
		List<RouteOption> routes = generate(PANDEMONIUM_WATER, VARROCK, false);
		assertTrue("port-seeded generation must produce several routes, got " + routes.size(),
			routes.size() >= 3);
		Set<String> firstPorts = new java.util.HashSet<>();
		boolean teleportAfterLanding = false;
		for (RouteOption route : routes)
		{
			List<TeleportMethod> methods = route.getMethods();
			if (methods.isEmpty())
			{
				continue;
			}
			TeleportMethod first = methods.get(0);
			assertTrue("abandonment forbidden: no teleport-first routes, got " + methods,
				isSailing(first));
			firstPorts.add(first.getDisplayInfo());
			for (int i = 1; i < methods.size(); i++)
			{
				teleportAfterLanding |= methods.get(i).getType().isTeleport();
			}
		}
		assertTrue("distinct disembark ports must compete (finding 5), got " + firstPorts,
			firstPorts.size() >= 2);
		assertTrue("teleports must fire AFTER landing (position-scoped gate)",
			teleportAfterLanding);
	}

	/**
	 * Boat-location awareness in an OWNED mode (the gate is a possession, so the "All"
	 * family legitimately bypasses it): with the boat moored at the Pandemonium and Summon
	 * Boat NOT assumed, no route may board anywhere else.
	 */
	@Test
	public void summonOffBoardsOnlyWhereTheBoatIs() throws Exception
	{
		List<RouteOption> routes = generate(VARROCK, DOGNOSE_PIN, true, false,
			PORT_ID_PORT_SARIM, false, AlternativeRoutesMode.OWNED_INVENTORY);
		assertTrue("routes must exist", !routes.isEmpty());
		boolean sailed = false;
		for (RouteOption route : routes)
		{
			for (TeleportMethod method : route.getMethods())
			{
				String info = method.getDisplayInfo();
				if (info != null && info.startsWith("Sailing: "))
				{
					sailed = true;
					assertTrue("with the boat at the Bay of Sarim and no summon, a route boards"
							+ " elsewhere: " + info,
						info.startsWith("Sailing: Bay of Sarim"));
				}
			}
		}
		assertTrue("a sailing route via the boat's berth must exist", sailed);
	}

	/**
	 * Sailing on by default must not sell boatless accounts a boarding step: boat varbits
	 * read (logged in), NO boat owned, no summon — an OWNED-mode generation to a
	 * sailing-only island produces no sailing methods at all.
	 */
	@Test
	public void boatlessAccountsGetNoBoardingLegs() throws Exception
	{
		List<RouteOption> routes = generate(VARROCK, DOGNOSE_PIN, true, false,
			-1, false, AlternativeRoutesMode.OWNED_INVENTORY);
		for (RouteOption route : routes)
		{
			for (TeleportMethod method : route.getMethods())
			{
				assertTrue("boatless account was offered sailing: " + method.getDisplayInfo(),
					!isSailing(method));
			}
		}
	}

	/**
	 * Teleport to Boat at the availability level: the generated spell rows exist for every
	 * port, and ONLY the live berth's row survives the structural gate — asserted directly
	 * on the planning copy's usable teleports so route-card competition can't hide it.
	 */
	@Test
	public void teleportToBoatTargetsTheLiveBerth() throws Exception
	{
		PathfinderConfig config = buildConfig(false, PORT_ID_PANDEMONIUM, false);
		boolean berthRow = false;
		for (gps.transport.Transport teleport : config.getUsableTeleports(false))
		{
			String info = teleport.getDisplayInfo();
			if (info != null && info.startsWith("Teleport to Boat"))
			{
				assertTrue("only the live berth's teleport row may survive, got " + info,
					info.equals("Teleport to Boat — The Pandemonium"));
				berthRow = true;
			}
		}
		assertTrue("the berth's Teleport to Boat row must be usable", berthRow);
	}

	/**
	 * The toggle really disables the gate: the boat rots at far-north Wintumber, Summon Boat
	 * IS assumed, and a sailing-only pin still gets boarded at sensible nearby ports.
	 */
	@Test
	public void summonOnFreesEveryMooring() throws Exception
	{
		List<RouteOption> routes =
			generate(VARROCK, DOGNOSE_PIN, true, true, PORT_ID_WINTUMBER, false);
		assertTrue("routes must exist", !routes.isEmpty());
		boolean otherPort = false;
		for (RouteOption route : routes)
		{
			for (TeleportMethod method : route.getMethods())
			{
				String info = method.getDisplayInfo();
				otherPort |= info != null && info.startsWith("Sailing: ")
					&& !info.startsWith("Sailing: Wintumber Island");
			}
		}
		assertTrue("assuming Summon Boat, boardings must not be pinned to the boat's berth",
			otherPort);
	}

	/**
	 * Findings 6-7 at the service level: a far sailing-only pin generates cleanly — routes
	 * exist, and any disembark→re-board chain uses DIFFERENT ports (the same-port farce
	 * stays dead; assertRouteShapes lints every generation). The far continuous sail itself
	 * is pinned as a unit property in {@link DirectSeaLegTest} — for Grimstone the router
	 * rightly prefers the Weiss teleport + 30-tile hop over a 1,000-tile ocean crossing.
	 */
	@Test
	public void farSailingOnlyPinGeneratesCleanly() throws Exception
	{
		List<RouteOption> routes = generate(PANDEMONIUM_WATER, GRIMSTONE_PIN, true);
		assertTrue("routes must exist", !routes.isEmpty());
		boolean sailsToGrimstone = false;
		for (RouteOption route : routes)
		{
			for (TeleportMethod method : route.getMethods())
			{
				sailsToGrimstone |= isSailing(method) && method.getDisplayInfo() != null
					&& method.getDisplayInfo().contains("Grimstone");
			}
		}
		assertTrue("every path to the sailing-only rock ends under sail", sailsToGrimstone);
	}

	@Test
	public void closeWaterPinTakesTheDirectSail() throws Exception
	{
		List<RouteOption> routes = generate(NEAR_CAIRN_WATER, CAIRN_PIN, true);
		assertTrue("routes must exist", !routes.isEmpty());
		RouteOption best = routes.get(0);
		assertTrue("the direct sail must win for a 70-tile water pin (Cairn capture: a 182-cost"
				+ " disembark/re-embark detour displayed instead), got cost " + best.getTotalCost()
				+ " methods " + best.getMethods(),
			best.getTotalCost() < 80 && best.getMethods().size() == 1
				&& isSailing(best.getMethods().get(0)));
	}
}
