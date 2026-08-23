package gps;

import gps.pathfinder.PathStep;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.Transport;
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

/** Probe (-Dgps.apeProbe=true): what the Grand Tree -> Ape Atoll routes carry as methods vs plain steps. */
public class ApeAtollProbeTest
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
	public void grandTreeToApeAtoll() throws Exception
	{
		Assume.assumeTrue(Boolean.getBoolean("gps.apeProbe"));
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class, Mockito.withSettings().stubOnly());
		Mockito.when(cfg.calculationCutoff()).thenReturn(120);
		Mockito.when(cfg.currencyThreshold()).thenReturn(10000000);
		Mockito.when(cfg.useTeleportationItems()).thenReturn(TeleportationItem.INVENTORY);
		for (String name : new String[]{"useBoats", "useShips", "useCharterShips", "useGnomeGliders", "useFairyRings",
			"useSpiritTrees", "useTeleportationSpells", "useAgilityShortcuts", "useTeleportationPortals"})
		{
			Mockito.when((Boolean) ShortestPathConfig.class.getMethod(name).invoke(cfg)).thenReturn(true);
		}
		PathfinderConfig config = new TestPathfinderConfig(client(), cfg).copyForPlanning();
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
		service.generate(WorldPointUtil.packWorldPoint(2465, 3496, 0),
			Set.of(WorldPointUtil.packWorldPoint(2803, 2706, 0)), Set.of(),
			AlternativeRoutesMode.ALL_EVERYTHING, 10, 3, false,
			(routes, catalog, unavailable, done) ->
			{
				if (done)
				{
					out.set(routes);
					latch.countDown();
				}
			});
		latch.await(120, TimeUnit.SECONDS);
		StringBuilder sb = new StringBuilder("\nPROBE ape atoll\n");
		for (RouteOption route : out.get())
		{
			sb.append(String.format("cost %d walkOnly=%s methods=%s%n", route.getTotalCost(), route.isWalkOnly(), route.getMethods()));
			List<PathStep> path = route.getPath();
			for (int i = 1; i < path.size(); i++)
			{
				int a = path.get(i - 1).getPackedPosition(), b = path.get(i).getPackedPosition();
				if (WorldPointUtil.distanceBetween(a, b) > 1 || WorldPointUtil.unpackWorldPlane(a) != WorldPointUtil.unpackWorldPlane(b))
				{
					List<Transport> rows = config.transportsOnEdge(a, b);
					sb.append(String.format("   jump@%d %d,%d,%d -> %d,%d,%d : %s%n", i,
						WorldPointUtil.unpackWorldX(a), WorldPointUtil.unpackWorldY(a), WorldPointUtil.unpackWorldPlane(a),
						WorldPointUtil.unpackWorldX(b), WorldPointUtil.unpackWorldY(b), WorldPointUtil.unpackWorldPlane(b),
						rows.stream().map(t -> t.getType() + "/" + t.getObjectInfo() + "/" + t.getDisplayInfo()).collect(java.util.stream.Collectors.toList())));
				}
			}
		}
		System.out.println(sb);
		service.shutdown();
	}
}
