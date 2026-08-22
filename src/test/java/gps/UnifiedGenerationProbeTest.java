package gps;

import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import org.junit.Assume;
import org.junit.Test;
import org.mockito.Mockito;

/** Probe (-Dgps.unifyProbe=true): panel-closed (limit 1) vs panel-open (limit 10) generation time and best route. */
public class UnifiedGenerationProbeTest
{
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

	private static ShortestPathConfig config()
	{
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class, Mockito.withSettings().stubOnly());
		Mockito.when(cfg.calculationCutoff()).thenReturn(120);
		Mockito.when(cfg.currencyThreshold()).thenReturn(10000000);
		Mockito.when(cfg.useTeleportationItems()).thenReturn(TeleportationItem.INVENTORY);
		for (String name : new String[]{"useAgilityShortcuts", "useGrappleShortcuts", "useBoats", "useCanoes",
			"useCharterShips", "useShips", "useSailing", "useFairyRings", "useGnomeGliders", "useHotAirBalloons",
			"useMagicCarpets", "useMagicMushtrees", "useMinecarts", "useMountainGuides", "useQuetzals",
			"useSpiritTrees", "useTeleportationLevers", "useTeleportationPortals", "useTeleportationSpells",
			"useTeleportationMinigames", "useWildernessObelisks", "usePoh", "usePohFairyRing", "usePohSpiritTree",
			"useTeleportationPortalsPoh", "usePohMountedItems", "usePohObelisk"})
		{
			try
			{
				Mockito.when((Boolean) ShortestPathConfig.class.getMethod(name).invoke(cfg)).thenReturn(true);
			}
			catch (Exception e)
			{
				throw new AssertionError(name, e);
			}
		}
		return cfg;
	}

	private static String run(AlternativeRoutesMode mode, int limit, int[] from, int[] to) throws Exception
	{
		PathfinderConfig config = new TestPathfinderConfig(client(), config()).copyForPlanning();
		config.refresh();
		ClientThread ct = Mockito.mock(ClientThread.class, Mockito.withSettings().stubOnly());
		Mockito.doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(ct).invokeLater(Mockito.any(Runnable.class));
		AlternativeRoutesService service = new AlternativeRoutesService(ct, config);
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		AtomicReference<Long> firstMs = new AtomicReference<>(-1L);
		long start = System.nanoTime();
		service.generate(WorldPointUtil.packWorldPoint(from[0], from[1], 0),
			Set.of(WorldPointUtil.packWorldPoint(to[0], to[1], 0)), Set.of(), mode, limit, 3, false,
			(routes, catalog, unavailable, done) ->
			{
				if (!routes.isEmpty() && firstMs.get() < 0)
				{
					firstMs.set((System.nanoTime() - start) / 1_000_000);
				}
				if (done)
				{
					out.set(routes);
					latch.countDown();
				}
			});
		latch.await(180, TimeUnit.SECONDS);
		long ms = (System.nanoTime() - start) / 1_000_000;
		service.shutdown();
		List<RouteOption> routes = out.get();
		RouteOption best = routes == null || routes.isEmpty() ? null : routes.get(0);
		return String.format("%-16s limit=%2d  wall=%5d ms  first=%5d ms  routes=%2d  best=%s",
			mode, limit, ms, firstMs.get(), routes == null ? -1 : routes.size(),
			best == null ? "-" : best.getTotalCost() + " " + best.getMethods());
	}

	@Test
	public void closedVersusOpen() throws Exception
	{
		Assume.assumeTrue(Boolean.getBoolean("gps.unifyProbe"));
		int[][][] pairs = {
			{{3222, 3218}, {3164, 3487}}, // Lumbridge -> GE
			{{2726, 3486}, {2950, 2902}}, // Camelot -> Kharazi
			{{3164, 3487}, {2662, 3305}}, // GE -> Ardougne
			{{3213, 3424}, {3259, 3277}}, // Varrock -> Cowbell
			{{3087, 3493}, {2660, 3657}}, // Edgeville -> Rellekka
			{{2936, 3281}, {1947, 4067}}, // Brimhaven -> Brittle Isle (sailing)
		};
		StringBuilder report = new StringBuilder("\nPROBE unify\n");
		for (int[][] pair : pairs)
		{
			report.append(String.format("%d,%d -> %d,%d%n", pair[0][0], pair[0][1], pair[1][0], pair[1][1]));
			for (AlternativeRoutesMode mode : new AlternativeRoutesMode[]{AlternativeRoutesMode.OWNED_INVENTORY, AlternativeRoutesMode.ALL_EVERYTHING})
			{
				for (int limit : new int[]{1, 10})
				{
					report.append("   ").append(run(mode, limit, pair[0], pair[1])).append('\n');
				}
			}
		}
		System.out.println(report);
	}
}
