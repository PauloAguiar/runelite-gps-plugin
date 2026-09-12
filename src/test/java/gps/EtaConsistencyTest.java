package gps;

import gps.pathfinder.CostUnits;
import gps.pathfinder.PathStep;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Plan step N9 (issue #4: "ETA in overlay and route list don't match"). The route card showed
 * the search's total cost in seconds while the directions overlay summed its own per-step tick
 * estimates (walking legs rounded per leg, per-edge distance capped, no cost modifiers), so the
 * two numbers for the same route disagreed. Path steps now carry the search's cumulative cost,
 * the overlay's remaining-time table is derived from it exactly per index, and the card keeps
 * the total: one number, by construction.
 */
@RunWith(MockitoJUnitRunner.class)
public class EtaConsistencyTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;
	@Mock
	ClientThread clientThread;

	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int VARROCK = WorldPointUtil.packWorldPoint(3212, 3422, 0);

	private static int cardSeconds(RouteOption route)
	{
		return (int) Math.ceil(RouteListView.routeEtaUnits(route) * CostUnits.SECONDS_PER_UNIT);
	}

	private static int overlaySeconds(double ticks)
	{
		return (int) Math.ceil(ticks * RouteDirections.SECONDS_PER_TICK);
	}

	@Test
	public void overlayTableComesFromThePathCosts()
	{
		List<PathStep> path = List.of(
			new PathStep(WorldPointUtil.packWorldPoint(3200, 3200, 0), false, 0),
			new PathStep(WorldPointUtil.packWorldPoint(3201, 3200, 0), false, 3),
			new PathStep(WorldPointUtil.packWorldPoint(3202, 3200, 0), false, 10),
			new PathStep(WorldPointUtil.packWorldPoint(3300, 3300, 0), false, 24));
		RouteOption route = new RouteOption(path, List.of(), List.of(), List.of(), 24, 24, true, Set.of(), List.of(), 0);

		double[] remaining = RouteDirectionsOverlay.buildRemainingTicks(route, List.of());
		assertEquals(4, remaining.length);
		assertEquals("whole journey in ticks: 24 units / 2", 12.0, remaining[0], 1e-9);
		assertEquals("(24 - 10) / 2", 7.0, remaining[2], 1e-9);
		assertEquals(0.0, remaining[3], 1e-9);
		assertEquals("card and overlay agree on the whole journey", cardSeconds(route), overlaySeconds(remaining[0]));
	}

	@Test
	public void generatedRoutesAgreeEverywhere() throws Exception
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(config.currencyThreshold()).thenReturn(10000000);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.ALL);
		lenient().when(config.useFairyRings()).thenReturn(true);
		lenient().when(config.useTeleportationSpells()).thenReturn(true);
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
		List<RouteOption> routes;
		try
		{
			CountDownLatch latch = new CountDownLatch(1);
			AtomicReference<List<RouteOption>> out = new AtomicReference<>();
			service.generate(LUMBRIDGE, Set.of(VARROCK), Set.of(), AlternativeRoutesMode.ALL_EVERYTHING, 10, 3, false,
				(r, catalog, unavailable, done) ->
				{
					if (done)
					{
						out.set(r);
						latch.countDown();
					}
				});
			assertTrue("generation must finish", latch.await(180, TimeUnit.SECONDS));
			routes = out.get();
		}
		finally
		{
			service.shutdown();
		}
		assertTrue("routes expected", routes != null && routes.size() >= 3);
		for (RouteOption route : routes)
		{
			List<PathStep> path = route.getPath();
			assertTrue("every path step carries its cumulative cost", path.stream().allMatch(s -> s.getCost() >= 0));
			assertEquals("the route's total cost is the last step's cumulative cost: " + route.getMethods(),
				route.getTotalCost(), path.get(path.size() - 1).getCost());
			double[] remaining = RouteDirectionsOverlay.buildRemainingTicks(route, List.of());
			assertEquals("card and overlay agree at the start: " + route.getMethods(),
				cardSeconds(route), overlaySeconds(remaining[0]));
			for (int i = 1; i < remaining.length; i++)
			{
				assertTrue("remaining time never increases along the path", remaining[i] <= remaining[i - 1] + 1e-9);
			}
		}
	}
}
