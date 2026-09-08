package gps;

import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import java.util.Arrays;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Plan step N5: the planning refresh runs on the client thread once per generation. A sampling
 * probe (RefreshProfileProbeTest) showed 63% of it re-reading the per-type travel toggles through
 * the live config interface for every one of the ~14,000 rows (a ConfigManager lookup each in the
 * client), and a direct varbit read on the classification's fairy ring branch. The toggles are
 * read once per refresh, the varbit through the per-pass memo, and item names through a cache that
 * survives refreshes (names never change), so the pass reads the config a bounded number of times.
 */
@RunWith(MockitoJUnitRunner.class)
public class PlanningRefreshCostTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	/** Config reads one planning refresh may make: every key once, with a margin for overrides. */
	static final int CONFIG_READS_BUDGET = 120;
	/** Client reads one planning refresh may make: quests, varbits, skills, containers, item names. */
	static final int CLIENT_READS_BUDGET = 1200;

	@Test
	public void planningRefreshReadsTheConfigOncePerKey()
	{
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

		int configBefore = Mockito.mockingDetails(config).getInvocations().size();
		int clientBefore = Mockito.mockingDetails(client).getInvocations().size();
		long[] samples = new long[8];
		for (int i = 0; i < samples.length; i++)
		{
			long start = System.nanoTime();
			planning.refresh();
			samples[i] = (System.nanoTime() - start) / 1_000_000;
		}
		int configReads = (Mockito.mockingDetails(config).getInvocations().size() - configBefore) / samples.length;
		int clientReads = (Mockito.mockingDetails(client).getInvocations().size() - clientBefore) / samples.length;
		Arrays.sort(samples);
		System.out.println("PLANNINGREFRESH config reads per refresh: " + configReads + ", client reads: " + clientReads
			+ ", ms: " + Arrays.toString(samples));

		assertTrue("a planning refresh read the config " + configReads + " times (budget " + CONFIG_READS_BUDGET + ")",
			configReads <= CONFIG_READS_BUDGET);
		assertTrue("a planning refresh read the client " + clientReads + " times (budget " + CLIENT_READS_BUDGET + ")",
			clientReads <= CLIENT_READS_BUDGET);
	}
}
