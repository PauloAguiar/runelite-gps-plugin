package shortestpath.pathfinder;

import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;
import shortestpath.ShortestPathConfig;
import shortestpath.WorldPointUtil;

/**
 * Invariants of the target-rooted distance field: targets are zero, values lower-bound real
 * search costs (admissibility — the property the A* heuristic's exactness rests on), and
 * reverse-unreachable places stay unreached.
 */
@RunWith(MockitoJUnitRunner.class)
public class DistanceFieldTest
{
	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int VARROCK = WorldPointUtil.packWorldPoint(3213, 3424, 0);
	private static final int ENTRANA = WorldPointUtil.packWorldPoint(2830, 3335, 0);

	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	@Before
	public void before()
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
	}

	private PathfinderConfig walkOnlyConfig()
	{
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		planning.rebuildAvailabilityWithExclusions(planning.getMethodCatalog());
		return planning;
	}

	@Test
	public void fieldLowerBoundsTheRealWalkCostAndIsZeroAtTargets()
	{
		PathfinderConfig config = walkOnlyConfig();
		DistanceField field = DistanceField.build(config, Set.of(VARROCK));

		assertEquals("A target tile must be at distance zero", 0, field.distance(VARROCK));

		Pathfinder walk = new Pathfinder(config, LUMBRIDGE, Set.of(VARROCK), null, Integer.MAX_VALUE, null);
		walk.run();
		int walkCost = walk.getResult().getTotalCost();
		int fieldAtStart = field.distance(LUMBRIDGE);
		assertTrue("Field must be reached at the start tile", fieldAtStart != DistanceField.UNREACHED);
		assertTrue("Field must lower-bound the real walk cost (" + fieldAtStart + " vs " + walkCost + ")",
			fieldAtStart <= walkCost);
		assertTrue("Field must be a meaningful distance, not zero", fieldAtStart > 100);

		// Admissibility along the actual route: at every step, the field may never exceed the
		// cost still ahead of that step (this is exactly the property A* exactness rests on).
		List<PathStep> path = walk.getResult().getPathSteps();
		for (int i = 0; i < path.size(); i++)
		{
			int remaining = walkCost - (i == 0 ? 0 : pathCostUpTo(path, i));
			int h = field.distance(path.get(i).getPackedPosition());
			assertTrue("Field overestimates at step " + i + " (" + h + " > " + remaining + ")",
				h == DistanceField.UNREACHED || h <= remaining);
		}
	}

	/** Walking cost accumulated up to step index (walk-only paths move 1 tile per step). */
	private static int pathCostUpTo(List<PathStep> path, int index)
	{
		int cost = 0;
		for (int i = 1; i <= index; i++)
		{
			cost += Math.max(1, WorldPointUtil.distanceBetween(
				path.get(i - 1).getPackedPosition(), path.get(i).getPackedPosition()));
		}
		return cost;
	}

	@Test
	public void reverseUnreachableIslandStaysUnreached()
	{
		// Entrana is boat-only; with every method excluded nothing walks there, so the island must
		// not be flooded from a mainland target — its heuristic falls back to the floor.
		DistanceField field = DistanceField.build(walkOnlyConfig(), Set.of(VARROCK));
		assertEquals("The island must be reverse-unreachable",
			DistanceField.UNREACHED, field.distance(ENTRANA));
	}

	@Test
	public void multiSourceFieldTakesTheNearestTarget()
	{
		PathfinderConfig config = walkOnlyConfig();
		DistanceField single = DistanceField.build(config, Set.of(VARROCK));
		DistanceField multi = DistanceField.build(config, Set.of(VARROCK, LUMBRIDGE));
		assertEquals("A tile that IS a target must be zero in the multi-source field",
			0, multi.distance(LUMBRIDGE));
		assertTrue("Multi-source distances can only shrink",
			multi.distance(WorldPointUtil.packWorldPoint(3250, 3300, 0))
				<= single.distance(WorldPointUtil.packWorldPoint(3250, 3300, 0)));
	}
}
