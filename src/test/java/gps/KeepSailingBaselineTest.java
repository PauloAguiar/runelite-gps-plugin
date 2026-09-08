package gps;

import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Plan step N10. KeepSailingTest failed intermittently: at the helm the pure-sail route is the
 * walk search's own result, appended last as the baseline, and the seed pass filled the page in
 * COMPLETION order. When ten cheaper port-then-teleport routes had already been accepted, the
 * append loop evicted the costliest unprotected route, which was the sail baseline itself (every
 * route is port-first, so the sole-port-first protection never applied). Completion order varies
 * with JIT and load, so the page sometimes filled before the baseline landed. The baseline just
 * appended is never the one evicted, and seed results are accepted in a deterministic order.
 */
@RunWith(MockitoJUnitRunner.class)
public class KeepSailingBaselineTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;
	@Mock
	ClientThread clientThread;

	private static final int HELM = WorldPointUtil.packWorldPoint(2746, 3216, 0);
	private static final int TARGET = WorldPointUtil.packWorldPoint(2638, 3009, 0);

	@Test
	public void theSailBaselineSurvivesAFullPageAndThePageIsDeterministic() throws Exception
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(config.currencyThreshold()).thenReturn(10000000);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.ALL);
		lenient().when(config.useSailing()).thenReturn(true);
		lenient().when(config.useGnomeGliders()).thenReturn(true);
		lenient().when(config.useFairyRings()).thenReturn(true);
		lenient().when(config.useTeleportationSpells()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(i -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		lenient().when(client.getVarbitValue(VarbitID.SAILING_BOARDED_BOAT)).thenReturn(1);
		doAnswer(invocation ->
		{
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		assertTrue("the harness must be at the helm", planning.isOnSailingBoat());
		AlternativeRoutesService service = new AlternativeRoutesService(clientThread, planning);
		List<String> pages = new ArrayList<>();
		try
		{
			// A limit of nine: more than nine cheaper port-then-teleport routes exist, so the
			// page is full before the sail baseline is appended, every time.
			for (int run = 1; run <= 3; run++)
			{
				List<RouteOption> routes = generate(service, 9);
				assertTrue("run " + run + ": a pure-sail route must survive a full page at the helm:" + describe(routes),
					routes.stream().anyMatch(r -> r.isPureSail() && r.isReached()));
				assertTrue("run " + run + ": the limit holds", routes.size() <= 9);
				pages.add(describe(routes));
			}
		}
		finally
		{
			service.shutdown();
		}
		assertEquals("the same query must produce the same page every time", pages.get(0), pages.get(1));
		assertEquals("the same query must produce the same page every time", pages.get(0), pages.get(2));
	}

	private static List<RouteOption> generate(AlternativeRoutesService service, int limit) throws Exception
	{
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(HELM, Set.of(TARGET), Set.of(), AlternativeRoutesMode.ALL_EVERYTHING, limit, 3, false,
			(routes, catalog, unavailable, done) ->
			{
				if (done)
				{
					out.set(routes);
					latch.countDown();
				}
			});
		assertTrue("generation must finish", latch.await(120, TimeUnit.SECONDS));
		assertTrue("routes expected", out.get() != null && !out.get().isEmpty());
		return out.get();
	}

	private static String describe(List<RouteOption> routes)
	{
		StringBuilder sb = new StringBuilder();
		for (RouteOption r : routes)
		{
			sb.append("\n  cost ").append(r.getTotalCost()).append(' ').append(r.getMethods());
		}
		return sb.toString();
	}
}
