package gps.pathfinder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;
import gps.PrimitiveIntHashMap;
import gps.ShortestPathConfig;
import gps.WorldPointUtil;
import gps.transport.Transport;

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
	private static final int LUNAR_ISLE = WorldPointUtil.packWorldPoint(2110, 3915, 0);

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

		Pathfinder walk = new Pathfinder(config, LUMBRIDGE, Set.of(VARROCK), Integer.MAX_VALUE, null);
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
		// Lunar Isle is boat-only; with every method excluded nothing walks there, so the island must
		// not be flooded from a mainland target — its heuristic falls back to the floor. (Entrana was
		// the example until its dungeon's Magic door, a one-way walk out to the Wilderness, joined
		// the data: the island can reach the mainland on foot now, if not the other way round.)
		DistanceField field = DistanceField.build(walkOnlyConfig(), Set.of(VARROCK));
		assertEquals("The island must be reverse-unreachable",
			DistanceField.UNREACHED, field.distance(LUNAR_ISLE));
	}

	/**
	 * The reverse index must fold every (destination, origin) pair to a single min-cost entry — a
	 * transport not gated on banking is present in both bank-state availability maps, and distinct
	 * methods can share an origin and landing, so the raw data holds duplicate/redundant edges the
	 * index is expected to collapse. Verified by independently reducing the raw availability the same
	 * way and asserting the index matches exactly.
	 */
	@Test
	public void reverseIndexKeepsOneMinCostEntryPerOrigin()
	{
		PathfinderConfig cfg = new TestPathfinderConfig(client, config).copyForPlanning();
		cfg.refresh();

		Map<Integer, Map<Integer, Integer>> expected = new HashMap<>();
		for (boolean bankVisited : new boolean[]{false, true})
		{
			PrimitiveIntHashMap<Transport[]> transports = cfg.getTransportsPacked(bankVisited);
			for (int origin : transports.keys())
			{
				Transport[] set = transports.get(origin);
				if (set == null)
				{
					continue;
				}
				for (Transport t : set)
				{
					if (t.getDestination() == WorldPointUtil.UNDEFINED
						|| t.getOrigin() == WorldPointUtil.UNDEFINED)
					{
						continue;
					}
					int cost = Math.max(0, CostUnits.fromTicks(t.getDuration())
						+ cfg.getAdditionalTransportCost(t));
					expected.computeIfAbsent(t.getDestination(), k -> new HashMap<>())
						.merge(t.getOrigin(), cost, Math::min);
				}
			}
		}

		PrimitiveIntHashMap<Map<Integer, Integer>> index = DistanceField.buildReverseTransportIndex(cfg);
		assertFalse("The transport data must produce some reverse edges for this test to mean anything",
			index.size() == 0);
		int[] landings = index.keys();
		assertEquals("Every landing (and only those) must be indexed", expected.size(), landings.length);
		for (int landing : landings)
		{
			// A Map<origin, cost> can't hold a duplicate origin, and each value must be the minimum
			// cost across both bank states — exactly the reduction computed above.
			assertEquals("One min-cost entry per origin at landing " + landing,
				expected.get(landing), index.get(landing));
		}
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
