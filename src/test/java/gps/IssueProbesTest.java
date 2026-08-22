package gps;

import gps.pathfinder.Pathfinder;
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

/** Issue probes (-Dgps.issueProbe=true): #21 Kharazi walkability, #15 Brittle Isle generation time. */
public class IssueProbesTest
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

	@Test
	public void kharaziWalkOnly()
	{
		Assume.assumeTrue(Boolean.getBoolean("gps.issueProbe"));
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class, Mockito.withSettings().stubOnly());
		Mockito.when(cfg.calculationCutoff()).thenReturn(60);
		PathfinderConfig config = new TestPathfinderConfig(client(), cfg).copyForPlanning();
		config.refresh();
		Pathfinder pathfinder = new Pathfinder(config,
			WorldPointUtil.packWorldPoint(2825, 2997, 0),
			Set.of(WorldPointUtil.packWorldPoint(2950, 2902, 0)));
		pathfinder.run();
		List<gps.pathfinder.PathStep> path = pathfinder.getPath();
		int transports = 0;
		for (int i = 1; i < path.size(); i++)
		{
			if (WorldPointUtil.distanceBetween(path.get(i - 1).getPackedPosition(),
				path.get(i).getPackedPosition()) > 1)
			{
				transports++;
			}
		}
		System.out.println("PROBE #21 kharazi reached=" + pathfinder.getResult().isReached()
			+ " steps=" + path.size() + " jumps(transports)=" + transports);
	}

	@Test
	public void kharaziInteriorWalkOnly()
	{
		Assume.assumeTrue(Boolean.getBoolean("gps.issueProbe"));
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class, Mockito.withSettings().stubOnly());
		Mockito.when(cfg.calculationCutoff()).thenReturn(60);
		PathfinderConfig config = new TestPathfinderConfig(client(), cfg).copyForPlanning();
		config.refresh();
		// Issue 21's second snapshot: 2925,2944 -> 2950,2902 came back as a 95-cost WALK with no methods.
		Pathfinder pathfinder = new Pathfinder(config,
			WorldPointUtil.packWorldPoint(2925, 2944, 0),
			Set.of(WorldPointUtil.packWorldPoint(2950, 2902, 0)));
		pathfinder.run();
		List<gps.pathfinder.PathStep> path = pathfinder.getPath();
		int jumps = 0;
		StringBuilder jumpAt = new StringBuilder();
		for (int i = 1; i < path.size(); i++)
		{
			int a = path.get(i - 1).getPackedPosition();
			int b = path.get(i).getPackedPosition();
			if (WorldPointUtil.distanceBetween(a, b) > 1)
			{
				jumps++;
				jumpAt.append(' ').append(WorldPointUtil.unpackWorldX(a)).append(',').append(WorldPointUtil.unpackWorldY(a));
			}
		}
		System.out.println("PROBE #21b interior reached=" + pathfinder.getResult().isReached()
			+ " steps=" + path.size() + " jumps=" + jumps + jumpAt);
	}

	/** Collision-only BFS (no transports at all): can the interior be WALKED? */
	@Test
	public void kharaziInteriorCollisionOnly()
	{
		Assume.assumeTrue(Boolean.getBoolean("gps.issueProbe"));
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class, Mockito.withSettings().stubOnly());
		Mockito.when(cfg.calculationCutoff()).thenReturn(60);
		PathfinderConfig config = new TestPathfinderConfig(client(), cfg).copyForPlanning();
		config.refresh();
		gps.pathfinder.CollisionMap map = config.getMap();
		int start = WorldPointUtil.packWorldPoint(2925, 2944, 0);
		int goal = WorldPointUtil.packWorldPoint(2950, 2902, 0);
		java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
		java.util.HashSet<Integer> seen = new java.util.HashSet<>();
		queue.add(start);
		seen.add(start);
		boolean reached = false;
		while (!queue.isEmpty() && seen.size() < 200000)
		{
			int at = queue.poll();
			if (at == goal)
			{
				reached = true;
				break;
			}
			int x = WorldPointUtil.unpackWorldX(at);
			int y = WorldPointUtil.unpackWorldY(at);
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					int next = WorldPointUtil.packWorldPoint(x + dx, y + dy, 0);
					if ((dx != 0 || dy != 0) && map.canStep(at, next) && seen.add(next))
					{
						queue.add(next);
					}
				}
			}
		}
		System.out.println("PROBE #21c collision-only walk reached=" + reached + " flooded=" + seen.size());
	}

	@Test
	public void brittleIsleGenerationTime() throws Exception
	{
		Assume.assumeTrue(Boolean.getBoolean("gps.issueProbe"));
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class, Mockito.withSettings().stubOnly());
		Mockito.when(cfg.calculationCutoff()).thenReturn(120);
		Mockito.when(cfg.useSailing()).thenReturn(true);
		PathfinderConfig config = new TestPathfinderConfig(client(), cfg).copyForPlanning();
		config.refresh();
		ClientThread ct = Mockito.mock(ClientThread.class, Mockito.withSettings().stubOnly());
		Mockito.doAnswer(i -> { ((Runnable) i.getArgument(0)).run(); return null; })
			.when(ct).invokeLater(Mockito.any(Runnable.class));
		AlternativeRoutesService service = new AlternativeRoutesService(ct, config);
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		long start = System.nanoTime();
		service.generate(WorldPointUtil.packWorldPoint(2936, 3281, 0),
			Set.of(WorldPointUtil.packWorldPoint(1947, 4067, 0)), Set.of(),
			AlternativeRoutesMode.ALL_EVERYTHING, 10, 3, false,
			(routes, catalog, unavailable, done) -> { if (done) { out.set(routes); latch.countDown(); } });
		boolean finished = latch.await(180, TimeUnit.SECONDS);
		long ms = (System.nanoTime() - start) / 1_000_000;
		System.out.println("PROBE #15 brittle isle finished=" + finished + " in " + ms + " ms, routes="
			+ (out.get() == null ? -1 : out.get().size())
			+ (out.get() != null && !out.get().isEmpty() ? " best=" + out.get().get(0).getTotalCost() + " " + out.get().get(0).getMethods() : ""));
		service.shutdown();
	}
}
