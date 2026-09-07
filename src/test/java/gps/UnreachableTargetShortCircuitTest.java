package gps;

import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.client.callback.ClientThread;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Capture 20260830-172137: a Quest Helper target on the Broken Raft deck (open tiles ringed by the
 * River Lum, no transport lands there) ran seven full-world floods of ~2M nodes each, every one
 * ending SEARCH_EXHAUSTED, before the generation settled on "walk as close as possible". The
 * distance field had proven the target unreachable in 2 ms. Now that verdict ends the generation
 * after the one closest-approach search.
 */
@RunWith(MockitoJUnitRunner.class)
public class UnreachableTargetShortCircuitTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;
	@Mock
	ClientThread clientThread;

	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int LUM_RAFT_DECK = WorldPointUtil.packWorldPoint(3253, 3180, 0);

	@Test
	public void provablyUnreachableTargetRunsOneSearch() throws Exception
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(config.currencyThreshold()).thenReturn(10000000);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.ALL);
		lenient().when(config.useFairyRings()).thenReturn(true);
		lenient().when(config.useGnomeGliders()).thenReturn(true);
		lenient().when(config.useTeleportationSpells()).thenReturn(true);
		lenient().when(config.useSailing()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(i -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		doAnswer(invocation ->
		{
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		AlternativeRoutesService service = new AlternativeRoutesService(clientThread, planning);
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(LUMBRIDGE, Set.of(LUM_RAFT_DECK), Set.of(),
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
		List<AlternativeRoutesService.SearchRecord> records = service.getLastSearchRecords();
		service.shutdown();

		List<RouteOption> routes = out.get();
		assertTrue("a closest-approach route is still offered", routes != null && !routes.isEmpty());
		for (RouteOption route : routes)
		{
			assertFalse("nothing can reach the raft deck", route.isReached());
		}
		assertTrue("a short escape menu, not a flood per chain slot plus seeds and a walk: "
			+ records.size() + " searches", records.size() <= AlternativeRoutesService.UNREACHABLE_ESCAPE_ROUTES + 1);
		assertFalse("nothing more can surface for an unreachable target", service.wasMoreLikely());
	}
}
