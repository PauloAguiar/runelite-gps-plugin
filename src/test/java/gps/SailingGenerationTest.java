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

	private AlternativeRoutesService service;

	private List<RouteOption> generate(int start, int target, boolean abandon) throws Exception
	{
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
						// ABOARD: the aboard-legs and teleport gating both key off this.
						return (int) args[0] == VarbitID.SAILING_BOARDED_BOAT ? 1 : 0;
					default:
						return HybridPageFillTest.defaultValue(method.getReturnType());
				}
			});
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class,
			Mockito.withSettings().stubOnly());
		Mockito.when(cfg.calculationCutoff()).thenReturn(120);
		Mockito.when(cfg.useSailing()).thenReturn(true);
		Mockito.when(cfg.sailingTeleportAbandon()).thenReturn(abandon);
		PathfinderConfig config = new TestPathfinderConfig(client, cfg).copyForPlanning();
		config.refresh();
		ClientThread ct = Mockito.mock(ClientThread.class, Mockito.withSettings().stubOnly());
		Mockito.doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(ct).invokeLater(Mockito.any(Runnable.class));
		service = new AlternativeRoutesService(ct, config);

		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(start, Set.of(target), Set.of(),
			AlternativeRoutesMode.ALL_EVERYTHING, 10, 3, false,
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

	@After
	public void after()
	{
		if (service != null)
		{
			service.shutdown();
		}
	}

	/** Finding 7's lint: no route may disembark at a port and re-board at the same port. */
	private static void assertRouteShapes(List<RouteOption> routes)
	{
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
