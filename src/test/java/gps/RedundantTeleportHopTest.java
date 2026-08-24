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
import net.runelite.client.callback.ClientThread;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Capture 20260823-213943: six of ten routes were "Quetzal whistle to some site, quetzal back to
 * Civitas, charter to Port Tyras" — pointless variants of the direct "Whistle to Civitas, charter"
 * (the whistle already lands at any built site). The service filters such candidates: a teleport
 * immediately followed by a flight of the network sharing its destinations, landing where the
 * teleport could already go, never reaches the route list.
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
		TeleportMethod whistleToQuetzacalli = new TeleportMethod(
			TransportType.QUETZAL_WHISTLE, "Quetzal whistle: Quetzacalli Gorge", QUETZACALLI_LANDING);
		TeleportMethod quetzalToCivitas = new TeleportMethod(
			TransportType.QUETZAL, "Civitas illa Fortis", CIVITAS_LANDING);
		TeleportMethod primioToVarrock = new TeleportMethod(
			TransportType.QUETZAL, "Primio: Varrock", PRIMIO_VARROCK_LANDING);
		TeleportMethod charter = new TeleportMethod(
			TransportType.CHARTER_SHIP, "Port Tyras", WorldPointUtil.packWorldPoint(2142, 3122, 0));

		assertTrue("whistle -> quetzal back to a whistle-reachable site is the hop to drop",
			AlternativeRoutesService.hasRedundantTeleportHop(planning,
				List.of(whistleToQuetzacalli, quetzalToCivitas, charter)));
		assertFalse("Varrock has no whistle landing: the Primio flight is a REAL leg",
			AlternativeRoutesService.hasRedundantTeleportHop(planning,
				List.of(whistleToQuetzacalli, primioToVarrock)));
		assertFalse("unrelated method pairs are untouched",
			AlternativeRoutesService.hasRedundantTeleportHop(planning,
				List.of(quetzalToCivitas, charter)));
	}

	/** The capture's exact query: no emitted route may carry a redundant whistle hop. */
	@Test
	public void capturedQueryNoLongerEmitsWhistleHopVariants() throws Exception
	{
		PathfinderConfig planning = planning(config());
		ClientThread ct = Mockito.mock(ClientThread.class, Mockito.withSettings().stubOnly());
		Mockito.doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(ct).invokeLater(Mockito.any(Runnable.class));
		AlternativeRoutesService service = new AlternativeRoutesService(ct, planning);
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(WorldPointUtil.packWorldPoint(1453, 3173, 0),
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
		// This harness starts 16 tiles from The Teomat landing, so walking to the local quetzal
		// outranks casting the whistle: routes need not START with a whistle here — the guarantee
		// is that no emitted route carries the pointless whistle-then-quetzal-back hop.
		boolean reached = false;
		for (RouteOption route : routes)
		{
			assertFalse("no route may keep a redundant whistle hop: " + route.getMethods(),
				AlternativeRoutesService.hasRedundantTeleportHop(planning, route.getMethods()));
			reached |= route.isReached();
		}
		assertTrue("the target stays reachable with the junk variants filtered", reached);
	}
}
