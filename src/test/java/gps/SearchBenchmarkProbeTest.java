package gps;

import gps.pathfinder.DistanceField;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.Pathfinder;
import gps.pathfinder.PathfinderResult;
import gps.pathfinder.SearchHeuristic;
import gps.pathfinder.TestPathfinderConfig;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.QuestState;
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
 * Baseline numbers for the search engine on this machine: startup (data load), refresh, the
 * target-directed heuristic build, and representative searches with and without the heuristic,
 * including the worst case - an unreachable target that floods the whole reachable world.
 * -Dgps.searchBench=true. Numbers are printed as BENCH lines; nothing is asserted.
 */
@RunWith(MockitoJUnitRunner.class)
public class SearchBenchmarkProbeTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int GRAND_EXCHANGE = WorldPointUtil.packWorldPoint(3165, 3487, 0);
	private static final int ARDOUGNE = WorldPointUtil.packWorldPoint(2662, 3305, 0);
	private static final int PRIFDDINAS = WorldPointUtil.packWorldPoint(3257, 6094, 0);
	/** The Broken Raft deck: open tiles ringed by the river - never reachable on foot. */
	private static final int LUM_RAFT = WorldPointUtil.packWorldPoint(3253, 3180, 0);

	@Test
	public void bench()
	{
		Assume.assumeTrue(Boolean.getBoolean("gps.searchBench"));
		when(config.calculationCutoff()).thenReturn(120);
		lenient().when(config.currencyThreshold()).thenReturn(10000000);
		lenient().when(config.useAgilityShortcuts()).thenReturn(true);
		lenient().when(config.useFairyRings()).thenReturn(true);
		lenient().when(config.useGnomeGliders()).thenReturn(true);
		lenient().when(config.useSpiritTrees()).thenReturn(true);
		lenient().when(config.useTeleportationSpells()).thenReturn(true);
		lenient().when(config.useSailing()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(i -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);

		long t0 = System.nanoTime();
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.NONE);
		PathfinderConfig walkConfig = new TestPathfinderConfig(client, config, QuestState.FINISHED, true, true);
		long load = ms(t0);
		t0 = System.nanoTime();
		walkConfig.refresh();
		System.out.println("BENCH startup: data load " + load + " ms, main-config refresh " + ms(t0) + " ms");

		when(config.useTeleportationItems()).thenReturn(TeleportationItem.ALL);
		PathfinderConfig allConfig = new TestPathfinderConfig(client, config, QuestState.FINISHED, true, true);
		allConfig.refresh();
		PathfinderConfig planning = allConfig.copyForPlanning();
		t0 = System.nanoTime();
		planning.refresh();
		System.out.println("BENCH planning-copy refresh " + ms(t0) + " ms");

		// Warm the JIT once on a mid-size search before timing.
		search("warmup", walkConfig, LUMBRIDGE, GRAND_EXCHANGE, false);

		search("walk Lumbridge->GE, no heuristic", walkConfig, LUMBRIDGE, GRAND_EXCHANGE, false);
		search("walk Lumbridge->GE, heuristic", walkConfig, LUMBRIDGE, GRAND_EXCHANGE, true);
		search("walk Lumbridge->Ardougne, no heuristic", walkConfig, LUMBRIDGE, ARDOUGNE, false);
		search("walk Lumbridge->Ardougne, heuristic", walkConfig, LUMBRIDGE, ARDOUGNE, true);
		search("ALL teleports Lumbridge->Ardougne, no heuristic", allConfig, LUMBRIDGE, ARDOUGNE, false);
		search("ALL teleports Lumbridge->Ardougne, heuristic", allConfig, LUMBRIDGE, ARDOUGNE, true);
		search("ALL teleports Lumbridge->Prifddinas, heuristic", allConfig, LUMBRIDGE, PRIFDDINAS, true);
		search("UNREACHABLE raft, walk, no heuristic (full flood)", walkConfig, LUMBRIDGE, LUM_RAFT, false);
		search("UNREACHABLE raft, ALL teleports, heuristic (full flood)", allConfig, LUMBRIDGE, LUM_RAFT, true);
	}

	private static void search(String label, PathfinderConfig config, int start, int target, boolean heuristic)
	{
		long t0 = System.nanoTime();
		SearchHeuristic h = null;
		long fieldMs = 0;
		if (heuristic)
		{
			DistanceField field = DistanceField.build(config, Set.of(target));
			fieldMs = ms(t0);
			h = SearchHeuristic.buildWithField(config, field, start);
			t0 = System.nanoTime();
		}
		Pathfinder pathfinder = new Pathfinder(config, start, Set.of(target), Integer.MAX_VALUE, h);
		pathfinder.run();
		long searchMs = ms(t0);
		PathfinderResult r = pathfinder.getResult();
		System.out.println("BENCH " + label + ": " + searchMs + " ms"
			+ (heuristic ? " (+field " + fieldMs + " ms)" : "")
			+ " nodes=" + r.getNodesChecked() + " transports=" + r.getTransportsChecked()
			+ " reached=" + r.isReached() + " cost=" + r.getTotalCost()
			+ " term=" + r.getTerminationReason()
			+ " ns/node=" + (r.getNodesChecked() > 0 ? (searchMs * 1_000_000L / r.getNodesChecked()) : 0));
	}

	private static long ms(long since)
	{
		return (System.nanoTime() - since) / 1_000_000;
	}
}
