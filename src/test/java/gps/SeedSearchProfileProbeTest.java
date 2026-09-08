package gps;

import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.client.callback.ClientThread;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Gated probe (-Dgps.seedProfile=true): prints every search record of a few typical generations
 * so the value of a tightening seed cost ceiling can be judged on data (plan step N5 decision).
 */
@RunWith(MockitoJUnitRunner.class)
public class SeedSearchProfileProbeTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;
	@Mock
	ClientThread clientThread;

	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int VARROCK = WorldPointUtil.packWorldPoint(3212, 3422, 0);
	private static final int ARDOUGNE = WorldPointUtil.packWorldPoint(2662, 3305, 0);
	private static final int FELDIP_HILLS = WorldPointUtil.packWorldPoint(2500, 2960, 0);
	private static final int RAFT_DECK = WorldPointUtil.packWorldPoint(3253, 3180, 0);

	@Test
	public void printSeedProfiles() throws Exception
	{
		Assume.assumeTrue(Boolean.getBoolean("gps.seedProfile"));
		when(config.calculationCutoff()).thenReturn(120);
		when(config.currencyThreshold()).thenReturn(10000000);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.ALL);
		lenient().when(config.useFairyRings()).thenReturn(true);
		lenient().when(config.useGnomeGliders()).thenReturn(true);
		lenient().when(config.useTeleportationSpells()).thenReturn(true);
		lenient().when(config.useSpiritTrees()).thenReturn(true);
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
		try
		{
			profile(service, "Lumbridge->Varrock ALL", LUMBRIDGE, VARROCK, AlternativeRoutesMode.ALL_EVERYTHING);
			profile(service, "Lumbridge->Ardougne ALL", LUMBRIDGE, ARDOUGNE, AlternativeRoutesMode.ALL_EVERYTHING);
			profile(service, "Feldip->Varrock ALL", FELDIP_HILLS, VARROCK, AlternativeRoutesMode.ALL_EVERYTHING);
			profile(service, "Lumbridge->raft deck (unreachable) ALL", LUMBRIDGE, RAFT_DECK, AlternativeRoutesMode.ALL_EVERYTHING);
			profile(service, "Lumbridge->Ardougne OWNED", LUMBRIDGE, ARDOUGNE, AlternativeRoutesMode.OWNED_INVENTORY);
		}
		finally
		{
			service.shutdown();
		}
	}

	private static void profile(AlternativeRoutesService service, String title, int start, int target,
		AlternativeRoutesMode mode) throws Exception
	{
		CountDownLatch latch = new CountDownLatch(1);
		service.generate(start, Set.of(target), Set.of(), mode, 10, 3, false,
			(routes, catalog, unavailable, done) ->
			{
				if (done)
				{
					latch.countDown();
				}
			});
		assertTrue("generation must finish", latch.await(180, TimeUnit.SECONDS));
		List<AlternativeRoutesService.SearchRecord> records = service.getLastSearchRecords();
		long[] timing = service.getLastTimingSummary();
		long seedNodes = 0;
		long otherNodes = 0;
		int seeds = 0;
		StringBuilder out = new StringBuilder();
		out.append("SEEDPROFILE ").append(title).append(": wall=").append(timing[0]).append("ms searchCpu=")
			.append(timing[3]).append("ms searches=").append(timing[4]).append(" field=").append(timing[5]).append("ms\n");
		for (AlternativeRoutesService.SearchRecord r : records)
		{
			boolean seed = r.label.startsWith("seed:");
			if (seed)
			{
				seeds++;
				seedNodes += Math.max(0, r.nodesChecked);
			}
			else
			{
				otherNodes += Math.max(0, r.nodesChecked);
			}
			out.append("  ").append(r.label).append(" cpu=").append(r.cpuMs).append("ms nodes=").append(r.nodesChecked)
				.append(" cost=").append(r.resultCost).append(" reached=").append(r.reached).append(" term=")
				.append(r.termination).append(" capped=").append(r.capped).append(" astar=").append(r.astar).append('\n');
		}
		out.append("  seeds=").append(seeds).append(" seedNodes=").append(seedNodes).append(" otherNodes=").append(otherNodes);
		System.out.println(out);
	}
}
