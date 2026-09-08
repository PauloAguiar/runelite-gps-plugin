package gps;

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

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Plan step N1. A "nearest bank" query hands the generator a target set spanning the whole
 * map; DistanceField.buildIfCompact refused to build a field for anything wider than 256 tiles,
 * so every search of the generation ran blind: the cost cap degenerates to a plain g-ball and
 * each search floods everything inside it (the review measured ~1M nodes and ~400 ms per blind
 * search against ~1 ms guided). The field is built once per generation and bounds its own flood,
 * so a wide target set must get one too.
 */
@RunWith(MockitoJUnitRunner.class)
public class MultiTargetFieldTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;
	@Mock
	ClientThread clientThread;

	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);

	private static final int FELDIP_HILLS = WorldPointUtil.packWorldPoint(2500, 2960, 0);

	@Test
	public void nearestBankGenerationRunsGuided() throws Exception
	{
		runNearestBank(LUMBRIDGE, TeleportationItem.ALL, AlternativeRoutesMode.ALL_EVERYTHING);
	}

	/** The expensive shape: the nearest bank is far, and every blind search floods to the cap. */
	@Test
	public void farNearestBankRunsGuidedToo() throws Exception
	{
		runNearestBank(FELDIP_HILLS, TeleportationItem.NONE, AlternativeRoutesMode.OWNED_INVENTORY);
	}

	private void runNearestBank(int start, TeleportationItem items, AlternativeRoutesMode mode) throws Exception
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(config.currencyThreshold()).thenReturn(10000000);
		when(config.useTeleportationItems()).thenReturn(items);
		lenient().when(config.useFairyRings()).thenReturn(true);
		lenient().when(config.useGnomeGliders()).thenReturn(true);
		lenient().when(config.useTeleportationSpells()).thenReturn(items != TeleportationItem.NONE);
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
		Set<Integer> banks = planning.getDestinations("bank");
		assertTrue("premise: the bank set spans the map", banks.size() > 100);

		AlternativeRoutesService service = new AlternativeRoutesService(clientThread, planning);
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(start, banks, Set.of(), mode, 10, 3, false,
			(routes, catalog, unavailable, done) ->
			{
				if (done)
				{
					out.set(routes);
					latch.countDown();
				}
			});
		assertTrue("generation must finish", latch.await(180, TimeUnit.SECONDS));
		List<AlternativeRoutesService.SearchRecord> records = service.getLastSearchRecords();
		service.shutdown();

		assertTrue("routes expected", out.get() != null && !out.get().isEmpty());
		assertTrue("searches expected", !records.isEmpty());
		int guided = 0;
		int worstNodes = 0;
		long totalNodes = 0;
		StringBuilder blind = new StringBuilder();
		for (AlternativeRoutesService.SearchRecord record : records)
		{
			if (record.astar)
			{
				guided++;
			}
			else
			{
				blind.append("\n  ").append(record.label).append(" nodes=").append(record.nodesChecked);
			}
			worstNodes = Math.max(worstNodes, record.nodesChecked);
			totalNodes += record.nodesChecked;
		}
		assertTrue("at least 90% of a nearest-bank generation's searches must be field-guided, blind ones:"
			+ blind, guided * 10 >= records.size() * 9);
		assertTrue("no single search may flood the map (worst " + worstNodes + " nodes)", worstNodes < 500_000);
		assertTrue("the whole generation must stay under 2M nodes (was ~1M per search): " + totalNodes,
			totalNodes < 2_000_000);
		System.out.println("MULTITARGET " + WorldPointUtil.unpackWorldPoint(start) + " searches=" + records.size()
			+ " guided=" + guided + " worstNodes=" + worstNodes + " totalNodes=" + totalNodes);
	}
}
