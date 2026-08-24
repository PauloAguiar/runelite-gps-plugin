package gps;

import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.Transport;
import gps.transport.TransportType;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Captures 20260823-213943 and -215617: most routes were "Quetzal whistle to some site, quetzal
 * back to Civitas, charter to Port Tyras" — pointless variants of the direct "Whistle to Civitas,
 * charter" (the whistle already lands at any built site), one per whistle destination the
 * exclusion chain tried. The filter's baseline is captured before the chain's own exclusion
 * rebuilds: the chain removes the direct whistle right before generating its hop variants, so a
 * live-availability check found nothing (the second capture, on the first cut of this filter).
 */
public class RedundantTeleportHopTest
{
	private static final int CIVITAS_LANDING = WorldPointUtil.packWorldPoint(1697, 3140, 0);
	private static final int QUETZACALLI_LANDING = WorldPointUtil.packWorldPoint(1510, 3222, 0);
	private static final int PRIMIO_VARROCK_LANDING = WorldPointUtil.packWorldPoint(3280, 3412, 0);

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

	private static PathfinderConfig planning(ShortestPathConfig cfg)
	{
		PathfinderConfig planning = new TestPathfinderConfig(client(), cfg).copyForPlanning();
		planning.refresh();
		return planning;
	}

	private static ShortestPathConfig config()
	{
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class, Mockito.withSettings().stubOnly());
		Mockito.when(cfg.calculationCutoff()).thenReturn(120);
		Mockito.when(cfg.currencyThreshold()).thenReturn(10000000);
		Mockito.when(cfg.useTeleportationItems()).thenReturn(TeleportationItem.ALL);
		Mockito.when(cfg.useQuetzals()).thenReturn(true);
		Mockito.when(cfg.useCharterShips()).thenReturn(true);
		Mockito.when(cfg.useTeleportationMinigames()).thenReturn(true);
		Mockito.when(cfg.useShips()).thenReturn(true);
		return cfg;
	}

	@Test
	public void whistleThenQuetzalBackIsRedundantButPrimioIsNot()
	{
		PathfinderConfig planning = planning(config());
		List<Transport> baseline = AlternativeRoutesService.teleportHopBaseline(planning, Set.of());
		assertTrue("the baseline must hold the whistle teleports", !baseline.isEmpty());
		TeleportMethod whistleToQuetzacalli = new TeleportMethod(
			TransportType.QUETZAL_WHISTLE, "Quetzal whistle: Quetzacalli Gorge", QUETZACALLI_LANDING);
		TeleportMethod whistleToCivitas = new TeleportMethod(
			TransportType.QUETZAL_WHISTLE, "Quetzal whistle: Civitas illa Fortis", CIVITAS_LANDING);
		TeleportMethod quetzalToCivitas = new TeleportMethod(
			TransportType.QUETZAL, "Civitas illa Fortis", CIVITAS_LANDING);
		TeleportMethod primioToVarrock = new TeleportMethod(
			TransportType.QUETZAL, "Primio: Varrock", PRIMIO_VARROCK_LANDING);
		TeleportMethod charter = new TeleportMethod(
			TransportType.CHARTER_SHIP, "Port Tyras", WorldPointUtil.packWorldPoint(2142, 3122, 0));

		assertTrue("whistle -> quetzal back to a whistle-reachable site is the hop to drop",
			AlternativeRoutesService.hasRedundantTeleportHop(baseline,
				List.of(whistleToQuetzacalli, quetzalToCivitas, charter)));
		assertFalse("Varrock has no whistle landing: the Primio flight is a REAL leg",
			AlternativeRoutesService.hasRedundantTeleportHop(baseline,
				List.of(whistleToQuetzacalli, primioToVarrock)));
		assertFalse("unrelated method pairs are untouched",
			AlternativeRoutesService.hasRedundantTeleportHop(baseline,
				List.of(quetzalToCivitas, charter)));

		// A USER exclusion of the direct whistle makes its hop variants legitimate alternatives.
		List<Transport> withoutCivitas =
			AlternativeRoutesService.teleportHopBaseline(planning, Set.of(whistleToCivitas));
		assertFalse("with the direct whistle excluded by the user, the hop is a real route",
			AlternativeRoutesService.hasRedundantTeleportHop(withoutCivitas,
				List.of(whistleToQuetzacalli, quetzalToCivitas, charter)));
	}

	/**
	 * Capture 20260823-215617's exact query (Grand Exchange -> Zul-Andra): from the GE the whistle
	 * is the cheapest entry to Varlamore, so the chain excludes it and, before the baseline fix,
	 * emitted one whistle-hop variant per landing site. No emitted route may carry the hop.
	 */
	@Test
	public void capturedQueryNoLongerEmitsWhistleHopVariants() throws Exception
	{
		PathfinderConfig planning = planning(config());
		List<Transport> baseline = AlternativeRoutesService.teleportHopBaseline(planning, Set.of());
		ClientThread ct = Mockito.mock(ClientThread.class, Mockito.withSettings().stubOnly());
		Mockito.doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(ct).invokeLater(Mockito.any(Runnable.class));
		AlternativeRoutesService service = new AlternativeRoutesService(ct, planning);
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(WorldPointUtil.packWorldPoint(3162, 3486, 0),
			Set.of(WorldPointUtil.packWorldPoint(2207, 3159, 0)), Set.of(),
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
		service.shutdown();
		List<RouteOption> routes = out.get();
		assertTrue("routes expected", routes != null && !routes.isEmpty());
		boolean reached = false;
		for (RouteOption route : routes)
		{
			assertFalse("no route may keep a redundant whistle hop: " + route.getMethods(),
				AlternativeRoutesService.hasRedundantTeleportHop(baseline, route.getMethods()));
			reached |= route.isReached();
		}
		assertTrue("the target stays reachable with the junk variants filtered", reached);
	}
}
