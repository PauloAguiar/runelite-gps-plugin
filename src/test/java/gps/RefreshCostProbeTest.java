package gps;

import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import java.util.Arrays;
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
 * Measures the client-thread slice of a planning-config refresh with the quest scripts already
 * memoized away (TestPathfinderConfig stubs them): what remains is the 13,922-row transport
 * loop, availability/classify builds, bank tiles and destinations - the residual per-occurrence
 * cost of a catalog refresh or generation. -Dgps.refreshCost=true.
 */
@RunWith(MockitoJUnitRunner.class)
public class RefreshCostProbeTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	@Test
	public void probe()
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
		PathfinderConfig owned = new TestPathfinderConfig(client, config);

		long[] planningMs = time(planning);
		long[] ownedMs = time(owned);
		System.out.println("REFRESHCOST planning-copy (catalog build) ms: " + Arrays.toString(planningMs));
		System.out.println("REFRESHCOST main-config ms: " + Arrays.toString(ownedMs));

		int before = org.mockito.Mockito.mockingDetails(client).getInvocations().size();
		planning.refresh();
		System.out.println("REFRESHCOST client-mock calls in one planning refresh: "
			+ (org.mockito.Mockito.mockingDetails(client).getInvocations().size() - before));
		for (String part : new String[]{"refreshTransports", "rebuildAccessibleBankTiles", "refreshDestinations"})
		{
			try
			{
				java.lang.reflect.Method m = PathfinderConfig.class.getDeclaredMethod(part);
				m.setAccessible(true);
				long[] samples = new long[8];
				for (int i = 0; i < samples.length; i++)
				{
					long start = System.nanoTime();
					m.invoke(planning);
					samples[i] = (System.nanoTime() - start) / 1_000_000;
				}
				Arrays.sort(samples);
				System.out.println("REFRESHCOST " + part + " ms: " + Arrays.toString(samples));
			}
			catch (Exception e)
			{
				System.out.println("REFRESHCOST " + part + " unmeasurable: " + e);
			}
		}
	}

	private static long[] time(PathfinderConfig pathConfig)
	{
		long[] samples = new long[12];
		for (int i = 0; i < samples.length; i++)
		{
			long start = System.nanoTime();
			pathConfig.refresh();
			samples[i] = (System.nanoTime() - start) / 1_000_000;
		}
		Arrays.sort(samples);
		return samples;
	}
}
