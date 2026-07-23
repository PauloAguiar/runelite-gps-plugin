package gps;

import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.Assume;
import org.junit.Test;
import org.mockito.Mockito;
import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathStep;
import gps.pathfinder.Pathfinder;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.SplitFlagMap;
import gps.pathfinder.TestPathfinderConfig;

/**
 * Debug probe for the Keldagrim east-side stairs (gps-capture-20260721-212441):
 * dumps plane-1 walkability around the crossing and pathfinds from the stairs
 * landing tile to the captured targets, printing any non-walk jumps. Gated by
 * -DkeldaProbe=true.
 */
public class KeldagrimStairsProbeTest
{
	@Test
	public void probe()
	{
		Assume.assumeTrue(Boolean.getBoolean("keldaProbe"));

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
		ShortestPathConfig config = Mockito.mock(ShortestPathConfig.class, invocation ->
		{
			String name = invocation.getMethod().getName();
			Class<?> type = invocation.getMethod().getReturnType();
			if (type == boolean.class)
			{
				return !"avoidWilderness".equals(name) && !"enableSeasonalTransports".equals(name);
			}
			if (type == int.class)
			{
				return "calculationCutoff".equals(name) ? 120 : 0;
			}
			if (type == TeleportationItem.class)
			{
				return TeleportationItem.ALL;
			}
			if (type == JewelleryBoxTier.class)
			{
				return JewelleryBoxTier.ORNATE;
			}
			return HybridPageFillTest.defaultValue(type);
		});

		CollisionMap map = new CollisionMap(SplitFlagMap.fromResources());

		for (int plane = 0; plane <= 1; plane++)
		{
			System.out.println("=== walkability plane " + plane + " (x 2855-2900, '.'=open '#'=blocked) ===");
			System.out.println("      " + "         2         2         2         2      ".trim());
			for (int y = 10214; y >= 10184; y--)
			{
				StringBuilder sb = new StringBuilder();
				for (int x = 2855; x <= 2900; x++)
				{
					boolean open = map.n(x, y, plane) || map.s(x, y, plane) || map.e(x, y, plane) || map.w(x, y, plane);
					sb.append(open ? '.' : '#');
				}
				System.out.println(y + " " + sb);
			}
		}

		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();

		Set<Integer> targets = new LinkedHashSet<>();
		targets.add(WorldPointUtil.packWorldPoint(2893, 10212, 0));
		targets.add(WorldPointUtil.packWorldPoint(2892, 10212, 0));
		targets.add(WorldPointUtil.packWorldPoint(2891, 10212, 0));
		targets.add(WorldPointUtil.packWorldPoint(2893, 10210, 0));
		targets.add(WorldPointUtil.packWorldPoint(2893, 10211, 0));
		targets.add(WorldPointUtil.packWorldPoint(2892, 10211, 0));

		int[][] starts = {
			{2865, 10188, 0},   // captured route start (sanity: should walk via plane 1)
			{2862, 10188, 1},   // transport-data stairs landing tile
			{2862, 10187, 1},
			{2863, 10188, 1},
			{2863, 10187, 1},
			{2865, 10190, 0},   // player tile at capture time
		};

		for (int[] s : starts)
		{
			int start = WorldPointUtil.packWorldPoint(s[0], s[1], s[2]);
			Pathfinder pathfinder = new Pathfinder(planning, start, targets);
			pathfinder.run();
			List<PathStep> path = pathfinder.getPath();
			System.out.println("=== from (" + s[0] + "," + s[1] + "," + s[2] + ") reached="
				+ pathfinder.getResult().isReached() + " steps=" + path.size());
			int prev = start;
			for (int i = 0; i < path.size(); i++)
			{
				int cur = path.get(i).getPackedPosition();
				int d = WorldPointUtil.distanceBetween(prev, cur);
				if (i < 4 || d > 1)
				{
					System.out.println("  [" + i + "] " + WorldPointUtil.unpackWorldX(cur) + ","
						+ WorldPointUtil.unpackWorldY(cur) + "," + WorldPointUtil.unpackWorldPlane(cur)
						+ (d > 1 ? "   <-- JUMP (transport/teleport)" : ""));
				}
				prev = cur;
			}
		}
	}
}
