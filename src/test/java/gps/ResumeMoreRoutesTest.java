package gps;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;

/**
 * "+ more routes" (same query, wider limit/multiple) must CONTINUE the previous generation: the
 * already-found routes appear unchanged in the very first update (the panel never blanks), the
 * widened run only appends, and the outcome matches a from-scratch run at the wide settings.
 */
@RunWith(MockitoJUnitRunner.class)
public class ResumeMoreRoutesTest
{
	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int BARROWS = WorldPointUtil.packWorldPoint(3566, 3291, 0);

	private AlternativeRoutesService service;
	private PathfinderConfig config;
	private ClientThread clientThread;

	static Object defaultValue(Class<?> t)
	{
		if (t == int.class || t == short.class || t == byte.class)
		{
			return 0;
		}
		if (t == long.class)
		{
			return 0L;
		}
		if (t == boolean.class)
		{
			return false;
		}
		if (t == double.class)
		{
			return 0.0;
		}
		if (t == float.class)
		{
			return 0f;
		}
		if (t == char.class)
		{
			return (char) 0;
		}
		return null;
	}

	@Before
	public void before()
	{
		final Thread ct = Thread.currentThread();
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getGameState":
						return GameState.LOGGED_IN;
					case "getClientThread":
						return ct;
					case "getBoostedSkillLevel":
						return 99;
					default:
						return defaultValue(method.getReturnType());
				}
			});
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class, Mockito.withSettings().stubOnly());
		Mockito.when(cfg.calculationCutoff()).thenReturn(120);
		config = new TestPathfinderConfig(client, cfg).copyForPlanning();
		config.refresh();
		clientThread = Mockito.mock(ClientThread.class, Mockito.withSettings().stubOnly());
		Mockito.doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(Mockito.any(Runnable.class));
		service = new AlternativeRoutesService(clientThread, config);
	}

	@After
	public void after()
	{
		service.shutdown();
	}

	private List<RouteOption> generate(AlternativeRoutesService svc, int limit, int multiple,
		List<List<Integer>> streamedCosts) throws Exception
	{
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		svc.generate(LUMBRIDGE, Set.of(BARROWS), Set.of(), AlternativeRoutesMode.ALL_EVERYTHING,
			limit, multiple, false,
			(routes, catalog, unavailable, done) ->
			{
				if (streamedCosts != null)
				{
					List<Integer> costs = new ArrayList<>();
					for (RouteOption r : routes)
					{
						costs.add(r.getTotalCost());
					}
					streamedCosts.add(costs);
				}
				if (done)
				{
					out.set(routes);
					latch.countDown();
				}
			});
		assertTrue("generation must finish", latch.await(60, TimeUnit.SECONDS));
		return out.get();
	}

	private static List<Integer> costs(List<RouteOption> routes)
	{
		List<Integer> costs = new ArrayList<>();
		for (RouteOption r : routes)
		{
			costs.add(r.getTotalCost());
		}
		return costs;
	}

	@Test
	public void wideningResumesAndNeverBlanksThePage() throws Exception
	{
		List<RouteOption> first = generate(service, 10, 3, null);
		assertNotNull(first);
		assertFalse("the narrow run must find routes", first.isEmpty());
		List<Integer> firstCosts = costs(first);

		// Widen ("+"): same start/targets/mode/exclusions, bigger limit and multiple.
		List<List<Integer>> streamed = new CopyOnWriteArrayList<>();
		List<RouteOption> widened = generate(service, 20, 6, streamed);
		assertNotNull(widened);

		// Continuity: every update (including the first) carries at least the narrow run's routes,
		// unchanged and in order — the page never blanks and never loses what it showed.
		assertFalse("the widened run must stream updates", streamed.isEmpty());
		for (List<Integer> update : streamed)
		{
			assertTrue("an update lost routes (page blanked): " + update,
				update.size() >= firstCosts.size());
			assertEquals("the previously shown routes must stay, unchanged and in order",
				firstCosts, update.subList(0, firstCosts.size()));
		}
		assertTrue("widening must add routes past the narrow page (got "
			+ widened.size() + " vs " + first.size() + ")", widened.size() > first.size());

		// Parity: a from-scratch service at the wide settings finds the same number of routes, and
		// the resumed run is never worse slot-for-slot. (Exact multiset equality is too strict: the
		// parallel seed pass fills the marginal last slots in completion order, so the two runs can
		// legitimately differ there — the resumed run keeping a cheaper marginal route is fine.)
		AlternativeRoutesService fresh = new AlternativeRoutesService(clientThread, config);
		try
		{
			List<RouteOption> scratch = generate(fresh, 20, 6, null);
			List<Integer> a = costs(widened);
			List<Integer> b = costs(scratch);
			a.sort(Integer::compareTo);
			b.sort(Integer::compareTo);
			assertEquals("resumed and from-scratch runs must find the same number of routes",
				b.size(), a.size());
			for (int i = 0; i < a.size(); i++)
			{
				assertTrue("resumed run must never be worse than from-scratch at slot " + i
					+ " (" + a.get(i) + " vs " + b.get(i) + ")", a.get(i) <= b.get(i));
			}
		}
		finally
		{
			fresh.shutdown();
		}
	}

	/**
	 * Resuming from a TINY prior generation must not silently drop routes. A panel-hidden generation
	 * computes only the primary route (limit 1), and its seed pass records signatures for routes it
	 * can't fit; the full generation that follows (panel opens / Quest Helper sets a target) resumes
	 * from it. If those burned signatures carried over, the wide run rejected cheap routes it found
	 * — the user capture where a Quest-Helper target showed 6 routes (missing the 2nd-cheapest) while
	 * a plain refresh showed 10. It must match a from-scratch wide run.
	 */
	@Test
	public void resumingFromATinyGenerationLosesNoRoutes() throws Exception
	{
		List<RouteOption> tiny = generate(service, 1, 3, null);
		assertNotNull(tiny);
		assertEquals("the panel-hidden run shows only the primary route", 1, tiny.size());

		List<RouteOption> resumed = generate(service, 10, 3, null);
		assertNotNull(resumed);

		AlternativeRoutesService fresh = new AlternativeRoutesService(clientThread, config);
		try
		{
			List<RouteOption> scratch = generate(fresh, 10, 3, null);
			List<Integer> a = costs(resumed);
			List<Integer> b = costs(scratch);
			a.sort(Integer::compareTo);
			b.sort(Integer::compareTo);
			assertEquals("resuming from a limit-1 generation must find as many routes as from scratch"
				+ " (resumed " + a + " vs scratch " + b + ")", b.size(), a.size());
			for (int i = 0; i < a.size(); i++)
			{
				assertTrue("resumed run must never be worse than from-scratch at slot " + i
					+ " (" + a.get(i) + " vs " + b.get(i) + ")", a.get(i) <= b.get(i));
			}
		}
		finally
		{
			fresh.shutdown();
		}
	}
}
