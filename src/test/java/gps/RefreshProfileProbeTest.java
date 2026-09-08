package gps;

import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Gated probe (-Dgps.refreshCost=true): a poor man's sampling profiler for the planning refresh.
 * Samples the refreshing thread's stack every half millisecond across forty refreshes and prints
 * the hottest frames by self time (first frame inside gps.*) and by inclusive time, so the
 * harvest/compute split targets what actually costs the client thread.
 */
@RunWith(MockitoJUnitRunner.class)
public class RefreshProfileProbeTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	@Test
	public void profilePlanningRefresh() throws Exception
	{
		Assume.assumeTrue(Boolean.getBoolean("gps.refreshCost"));
		when(config.calculationCutoff()).thenReturn(120);
		lenient().when(config.currencyThreshold()).thenReturn(10000000);
		lenient().when(config.useTeleportationItems()).thenReturn(TeleportationItem.ALL);
		lenient().when(config.useFairyRings()).thenReturn(true);
		lenient().when(config.useGnomeGliders()).thenReturn(true);
		lenient().when(config.useTeleportationSpells()).thenReturn(true);
		lenient().when(config.useSailing()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(i -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);

		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		planning.refresh();

		final Thread target = Thread.currentThread();
		final AtomicBoolean sampling = new AtomicBoolean(true);
		final Map<String, Integer> self = new HashMap<>();
		final Map<String, Integer> inclusive = new HashMap<>();
		final int[] samples = {0};
		Thread sampler = new Thread(() ->
		{
			while (sampling.get())
			{
				StackTraceElement[] stack = target.getStackTrace();
				if (stack.length == 0)
				{
					continue;
				}
				samples[0]++;
				boolean selfDone = false;
				java.util.Set<String> seen = new java.util.HashSet<>();
				for (StackTraceElement frame : stack)
				{
					if (!frame.getClassName().startsWith("gps."))
					{
						continue;
					}
					String key = frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1)
						+ "." + frame.getMethodName();
					if (!selfDone)
					{
						self.merge(key + ":" + frame.getLineNumber(), 1, Integer::sum);
						selfDone = true;
					}
					if (seen.add(key))
					{
						inclusive.merge(key, 1, Integer::sum);
					}
				}
				try
				{
					Thread.sleep(0, 500_000);
				}
				catch (InterruptedException e)
				{
					return;
				}
			}
		});
		sampler.setDaemon(true);
		sampler.start();
		long start = System.nanoTime();
		for (int i = 0; i < 40; i++)
		{
			planning.refresh();
		}
		long elapsed = (System.nanoTime() - start) / 1_000_000;
		sampling.set(false);
		sampler.join(2000);

		StringBuilder out = new StringBuilder();
		out.append("REFRESHPROFILE 40 refreshes in ").append(elapsed).append(" ms, ").append(samples[0]).append(" samples\n");
		out.append("REFRESHPROFILE self (top frame in gps.*):\n");
		for (Map.Entry<String, Integer> e : top(self, 25))
		{
			out.append(String.format("  %5.1f%%  %s%n", 100.0 * e.getValue() / samples[0], e.getKey()));
		}
		out.append("REFRESHPROFILE inclusive:\n");
		for (Map.Entry<String, Integer> e : top(inclusive, 30))
		{
			out.append(String.format("  %5.1f%%  %s%n", 100.0 * e.getValue() / samples[0], e.getKey()));
		}
		System.out.println(out);
	}

	private static List<Map.Entry<String, Integer>> top(Map<String, Integer> counts, int limit)
	{
		List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
		entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		return entries.subList(0, Math.min(limit, entries.size()));
	}
}
