package gps;

import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A page of alternatives took 2.8 s to compute for the Ruins of Tapoyauik (capture 2026-10-14):
 * the best route came from the first search in under a millisecond, the rest was four chain
 * searches for routes three times as costly. The exclusion chain now stops at a one-second
 * wall-clock budget per request once the best route is in hand (real time, so the wait is the
 * same on every machine); the page settles with what it has and "Search for more routes"
 * continues it with a second of its own. The suite runs with the budget off, so this test
 * arms it with a clock it controls.
 */
public class ChainBudgetTest
{
	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int VARROCK = WorldPointUtil.packWorldPoint(3213, 3424, 0);
	private static final long ONE_SECOND = 1_000_000_000L;

	private AlternativeRoutesService service;

	@Before
	public void setUp()
	{
		final Thread clientThread = Thread.currentThread();
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getGameState":
						return GameState.LOGGED_IN;
					case "getClientThread":
						return clientThread;
					case "getBoostedSkillLevel":
						return 99;
					default:
						return HybridPageFillTest.defaultValue(method.getReturnType());
				}
			});
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class, Mockito.withSettings().stubOnly());
		Mockito.when(cfg.calculationCutoff()).thenReturn(120);
		PathfinderConfig config = new TestPathfinderConfig(client, cfg).copyForPlanning();
		config.refresh();
		ClientThread ct = Mockito.mock(ClientThread.class, Mockito.withSettings().stubOnly());
		Mockito.doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(ct).invokeLater(Mockito.any(Runnable.class));
		service = new AlternativeRoutesService(ct, config);
	}

	@After
	public void tearDown()
	{
		service.shutdown();
	}

	@Test
	public void theChainStopsAtTheBudgetWithTheBestRouteInHand() throws Exception
	{
		List<RouteOption> full = generate();
		long fullChains = chainSearches();
		assertTrue("a chain of alternatives to cut (" + fullChains + ")", fullChains >= 3);

		// A clock that jumps two seconds on every read: the budget is spent at the first check.
		AtomicLong clock = new AtomicLong();
		service.setChainBudget(ONE_SECOND, () -> clock.getAndAdd(2 * ONE_SECOND));
		List<RouteOption> budgeted = generate();
		assertEquals("the first search always runs: it finds the best route", 1, chainSearches());
		assertEquals("the best route is never cut", full.get(0).getTotalCost(), budgeted.get(0).getTotalCost());
		assertTrue("the page can be continued", service.wasMoreLikely());

		// A clock that never moves: the budget never trips and the full chain runs again.
		service.setChainBudget(ONE_SECOND, () -> 0L);
		generate();
		assertEquals(fullChains, chainSearches());
	}

	private long chainSearches()
	{
		return service.getLastSearchRecords().stream().filter(r -> r.label.startsWith("chain#")).count();
	}

	private List<RouteOption> generate() throws InterruptedException
	{
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(LUMBRIDGE, Set.of(VARROCK), Set.of(), AlternativeRoutesMode.ALL_EVERYTHING, 10, 3, false,
			(routes, catalog, unavailable, done) ->
			{
				if (done)
				{
					out.set(routes);
					latch.countDown();
				}
			});
		assertTrue(latch.await(60, TimeUnit.SECONDS));
		assertNotNull(out.get());
		return out.get();
	}
}
