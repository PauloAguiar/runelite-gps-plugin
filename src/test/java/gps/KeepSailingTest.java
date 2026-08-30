package gps;

import gps.pathfinder.PathStep;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.TransportType;
import java.lang.reflect.Field;
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

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Capture 20260829-204334: aboard, mid ocean-task, every offered route abandoned the boat at the
 * nearest mooring and teleport-chained across the continent - the tick math favors it, and the
 * cost band culled every keep-sailing option off the page entirely. At the helm the cheapest
 * pure-sail route is now a protected baseline (the walk route's sibling), and pure-sail routes
 * rank first in the display order; the land chains stay listed below.
 */
@RunWith(MockitoJUnitRunner.class)
public class KeepSailingTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;
	@Mock
	ClientThread clientThread;

	private static RouteOption route(int cost, TeleportMethod... methods)
	{
		List<TeleportMethod> list = List.of(methods);
		List<Integer> edges = new java.util.ArrayList<>();
		List<Integer> durations = new java.util.ArrayList<>();
		for (int i = 0; i < list.size(); i++)
		{
			edges.add(i + 1);
			durations.add(1);
		}
		List<PathStep> path = List.of(new PathStep(WorldPointUtil.packWorldPoint(2746, 3216, 0), false),
			new PathStep(WorldPointUtil.packWorldPoint(2638, 3009, 0), false));
		return new RouteOption(path, list, edges, durations, cost, cost, true, Set.of(), List.of(0), 0);
	}

	@Test
	public void atTheHelmPureSailRanksFirstDespiteCost() throws Exception
	{
		ShortestPathPlugin plugin = new ShortestPathPlugin();
		PathfinderConfig pathConfig = mock(PathfinderConfig.class);
		when(pathConfig.isOnSailingBoat()).thenReturn(true);
		Field f = ShortestPathPlugin.class.getDeclaredField("pathfinderConfig");
		f.setAccessible(true);
		f.set(plugin, pathConfig);

		TeleportMethod sail = new TeleportMethod(TransportType.SAILING,
			"Disembark at Corsair Cove", WorldPointUtil.packWorldPoint(2589, 2851, 0));
		TeleportMethod glory = new TeleportMethod(TransportType.TELEPORTATION_ITEM,
			"Amulet of glory: Al Kharid", WorldPointUtil.packWorldPoint(3087, 3496, 0));
		RouteOption sailRoute = route(666, sail);
		RouteOption chain = route(182, sail, glory);

		List<RouteOption> ordered = plugin.sortByEffectiveOrder(List.of(chain, sailRoute));
		assertSame("at the helm the pure-sail route leads even at 3x the cost", sailRoute, ordered.get(0));

		// Ashore (or with the toggle off) plain effective cost decides.
		when(pathConfig.isOnSailingBoat()).thenReturn(false);
		ordered = plugin.sortByEffectiveOrder(List.of(sailRoute, chain));
		assertSame("ashore the cheaper chain leads", chain, ordered.get(0));
	}

	/** The capture's exact query: aboard west of Brimhaven, target on the Feldip coast. */
	@Test
	public void aboardGenerationAlwaysCarriesAPureSailRoute() throws Exception
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(config.currencyThreshold()).thenReturn(10000000);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.ALL);
		lenient().when(config.useSailing()).thenReturn(true);
		lenient().when(config.useGnomeGliders()).thenReturn(true);
		lenient().when(config.useFairyRings()).thenReturn(true);
		lenient().when(config.useTeleportationItems()).thenReturn(TeleportationItem.ALL);
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
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(WorldPointUtil.packWorldPoint(2746, 3216, 0),
			Set.of(WorldPointUtil.packWorldPoint(2638, 3009, 0)), Set.of(),
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
		assertTrue("a pure-sail route must survive the band at the helm: " + describe(routes),
			routes.stream().anyMatch(r -> r.isPureSail() && r.isReached()));
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
