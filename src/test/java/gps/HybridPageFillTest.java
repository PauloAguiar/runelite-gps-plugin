package gps;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;

/**
 * The hybrid band edge: in-band routes are always shown, and past the band the page keeps filling
 * while the next route's cost stays within a modest gap of the acceptance region — so a first page
 * never cuts a dense cost cluster in half (the 105-shown/107-hidden capture that motivated this),
 * while a genuine cost cliff still ends the page.
 */
@RunWith(MockitoJUnitRunner.class)
public class HybridPageFillTest
{
	@Test
	public void ceilingFollowsAClusterAcrossTheBandEdge()
	{
		// The user capture: best 27, band 105, next cluster starts at 107 — 2 past the band edge.
		assertTrue("107 must be accepted (cluster straddles the band edge)",
			107 <= AlternativeRoutesService.pageFillCeiling(27, 105, 3));
		// The Barrows shape: best 32, band 105, costliest accepted 86, next cluster at 106 — the gap
		// from the last route is 20, but from the band edge it is 1.
		assertTrue("106 must be accepted (cluster starts just past the band)",
			106 <= AlternativeRoutesService.pageFillCeiling(32, 86, 3));
		// A genuine cliff is still a stop.
		assertTrue("a far outlier must not be dragged onto the page",
			400 > AlternativeRoutesService.pageFillCeiling(27, 112, 3));
		// The ceiling ratchets with accepted fill routes, following a dense cluster.
		assertTrue("the ceiling must ratchet as fill routes are accepted",
			AlternativeRoutesService.pageFillCeiling(27, 134, 3)
				> AlternativeRoutesService.pageFillCeiling(27, 107, 3));
	}

	// --- End-to-end on the real map -----------------------------------------------------------

	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int BARROWS = WorldPointUtil.packWorldPoint(3566, 3291, 0);

	private AlternativeRoutesService service;

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
						return defaultValue(method.getReturnType());
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

	@org.junit.After
	public void after()
	{
		service.shutdown();
	}

	/**
	 * Keldagrim station -> Giants' Foundry (user capture): the minigame teleport (46) is far cheaper
	 * than every alternative (157+), so the band/cliff gates alone produced a ONE-route page. The
	 * minimum page size must surface alternatives anyway, and none of them may ride the Keldagrim
	 * train to White Wolf Mountain and back as a "free" shortcut (the rides had no duration, so the
	 * search priced them at zero).
	 */
	@Test
	public void minimumPageSurvivesACheapBestRoute() throws Exception
	{
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(WorldPointUtil.packWorldPoint(2927, 10165, 0),
			Set.of(WorldPointUtil.packWorldPoint(3361, 3149, 0)), Set.of(),
			AlternativeRoutesMode.ALL_EVERYTHING, 10, 3, false,
			(routes, catalog, unavailable, done) ->
			{
				if (done)
				{
					out.set(routes);
					latch.countDown();
				}
			});
		assertTrue("generation must finish", latch.await(60, TimeUnit.SECONDS));
		List<RouteOption> routes = out.get();
		assertNotNull(routes);
		assertTrue("a cheap best route must not collapse the page to one entry (got "
			+ routes.size() + ")", routes.size() >= 4);
		for (RouteOption route : routes)
		{
			long trainRides = route.getMethods().stream()
				.filter(m -> gps.transport.TransportType.MINECART.equals(m.getType())
					&& m.label().contains("White Wolf Mountain"))
				.count();
			assertTrue("no route may detour via the White Wolf Mountain train from Keldagrim",
				trainRides == 0);
		}
	}

	/**
	 * Lumbridge -> Barrows in everything mode: four routes sit under the bare 105 band (32/32/86/86)
	 * and a straggler cluster sits just past its edge (118, 122) before a genuine cliff (the next
	 * route costs 176). The bare band cut mid-cluster and showed 4; the hybrid must show the whole
	 * cluster (6) and still stop at the cliff rather than dragging the 176+ outliers onto the page.
	 */
	@Test
	public void barrowsFirstPageCrossesTheBandEdgeAndStopsAtTheCliff() throws Exception
	{
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(LUMBRIDGE, Set.of(BARROWS), Set.of(), AlternativeRoutesMode.ALL_EVERYTHING,
			10, 3, false,
			(routes, catalog, unavailable, done) ->
			{
				if (done)
				{
					out.set(routes);
					latch.countDown();
				}
			});
		assertTrue("generation must finish", latch.await(60, TimeUnit.SECONDS));
		List<RouteOption> routes = out.get();
		assertNotNull(routes);
		assertTrue("the page must fill past the bare band's 4 routes, got " + routes.size(),
			routes.size() > 4);
		int best = routes.get(0).getTotalCost();
		long band = 3L * Math.max(best, 35);
		int costliest = 0;
		for (RouteOption route : routes)
		{
			costliest = Math.max(costliest, route.getTotalCost());
			assertTrue("route cost " + route.getTotalCost() + " must stay under the sanity ceiling",
				route.getTotalCost() <= 2 * band);
		}
		assertTrue("fill must actually cross the band edge (costliest " + costliest + " vs band " + band + ")",
			costliest > band);
		assertTrue("the page must stop at the cliff: nothing at or past the next cluster (176+)",
			costliest < 176);
	}
}
