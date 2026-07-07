package shortestpath.pathfinder;

import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;
import shortestpath.ShortestPathConfig;
import shortestpath.TeleportationItem;
import shortestpath.WorldPointUtil;

/**
 * The A* correctness contract: with a consistent heuristic the search must produce EXACTLY the
 * same costs as the uninformed (Dijkstra) search — only the exploration order and explored-set
 * size may differ. Every scenario runs both ways on the real collision map and compares. The
 * heuristic is force-engaged (zero floor gate) so the saturated-floor cases — where ordering bugs
 * would hide — are exercised too.
 */
@RunWith(MockitoJUnitRunner.class)
public class AStarEquivalenceTest
{
	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int VARROCK = WorldPointUtil.packWorldPoint(3213, 3424, 0);
	private static final int FALADOR = WorldPointUtil.packWorldPoint(2965, 3380, 0);
	private static final int DRAYNOR = WorldPointUtil.packWorldPoint(3093, 3245, 0);
	private static final int BARROWS = WorldPointUtil.packWorldPoint(3566, 3291, 0);
	private static final int ENTRANA = WorldPointUtil.packWorldPoint(2830, 3335, 0);
	private static final int COWBELL_DESTINATION = WorldPointUtil.packWorldPoint(3259, 3277, 0);
	private static final int COWBELL_AMULET = 33104;

	@Mock
	Client client;
	@Mock
	net.runelite.api.ItemContainer bank;
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

	/** Runs one search and returns the pathfinder (search finished synchronously). */
	private static Pathfinder run(PathfinderConfig config, int start, Set<Integer> targets, SearchHeuristic heuristic)
	{
		Pathfinder pathfinder = new Pathfinder(config, start, targets, null, Integer.MAX_VALUE, heuristic);
		pathfinder.run();
		return pathfinder;
	}

	/** An Everything-mode planning copy (possession and unlocks bypassed), refreshed. */
	private PathfinderConfig everythingConfig()
	{
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		return planning;
	}

	/** A walk-only variant: every travel method excluded, plain connectors remain. */
	private PathfinderConfig walkOnlyConfig()
	{
		PathfinderConfig planning = everythingConfig();
		planning.rebuildAvailabilityWithExclusions(planning.getMethodCatalog());
		return planning;
	}

	/** Field-mode equivalence: same contract, with the near-exact distance-field heuristic. */
	private static int[] assertEquivalentWithField(PathfinderConfig config, int start, Set<Integer> targets)
	{
		Pathfinder dijkstra = run(config, start, targets, null);
		DistanceField distanceField = DistanceField.build(config, targets);
		SearchHeuristic heuristic = SearchHeuristic.buildWithField(config, distanceField);
		assertNotNull("Field heuristic must build", heuristic);
		Pathfinder astar = run(config, start, targets, heuristic);

		assertEquals("reached must match (field mode)",
			dijkstra.getResult().isReached(), astar.getResult().isReached());
		assertEquals("total cost must be identical (field mode)",
			dijkstra.getResult().getTotalCost(), astar.getResult().getTotalCost());
		System.out.println("field-equivalent: cost " + dijkstra.getResult().getTotalCost()
			+ ", nodes dijkstra=" + dijkstra.getStats().getNodesChecked()
			+ " astar=" + astar.getStats().getNodesChecked());
		return new int[]{
			dijkstra.getStats().getNodesChecked(),
			astar.getStats().getNodesChecked()};
	}

	@Test
	public void fieldModeWalkRouteIsIdenticalAndCollapses()
	{
		int[] nodes = assertEquivalentWithField(walkOnlyConfig(), LUMBRIDGE, Set.of(VARROCK));
		assertTrue("The field heuristic must collapse a directed walk to a corridor ("
				+ nodes[0] + " -> " + nodes[1] + ")",
			nodes[1] < nodes[0] / 20);
	}

	/** Direction changes along a path — the zigzag metric (a straight run changes direction once). */
	private static int directionChanges(java.util.List<PathStep> path)
	{
		int changes = 0;
		int lastDx = 99;
		int lastDy = 99;
		for (int i = 1; i < path.size(); i++)
		{
			int prev = path.get(i - 1).getPackedPosition();
			int cur = path.get(i).getPackedPosition();
			int dx = Integer.signum(WorldPointUtil.unpackWorldX(cur) - WorldPointUtil.unpackWorldX(prev));
			int dy = Integer.signum(WorldPointUtil.unpackWorldY(cur) - WorldPointUtil.unpackWorldY(prev));
			if (dx != lastDx || dy != lastDy)
			{
				changes++;
				lastDx = dx;
				lastDy = dy;
			}
		}
		return changes;
	}

	@Test
	public void fieldModePathShapeDoesNotZigzag()
	{
		// Equal-cost corridors make every tile share the same f value; without the heap's
		// creation-order tie-break those pops are arbitrary and the path zigzags between cardinal
		// and diagonal steps (in-game report: erratic drawn path vs the game's straight walk).
		// The A* path must stay in the same shape class as the FIFO search's.
		PathfinderConfig config = walkOnlyConfig();
		Pathfinder dijkstra = run(config, LUMBRIDGE, Set.of(VARROCK), null);
		DistanceField field = DistanceField.build(config, Set.of(VARROCK));
		Pathfinder astar = run(config, LUMBRIDGE, Set.of(VARROCK),
			SearchHeuristic.buildWithField(config, field));

		int dijkstraChanges = directionChanges(dijkstra.getResult().getPathSteps());
		int astarChanges = directionChanges(astar.getResult().getPathSteps());
		System.out.println("shape: direction changes dijkstra=" + dijkstraChanges + " astar=" + astarChanges
			+ " (path " + astar.getResult().getPathSteps().size() + " steps)");
		assertTrue("A* path must not zigzag (direction changes " + astarChanges
				+ " vs Dijkstra's " + dijkstraChanges + ")",
			astarChanges <= dijkstraChanges + 5);
	}

	@Test
	public void fieldModeTeleportRouteCostsAreIdentical()
	{
		assertEquivalentWithField(everythingConfig(), LUMBRIDGE, Set.of(BARROWS));
	}

	@Test
	public void fieldModeBankedRouteCostsAreIdentical()
	{
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.NONE);
		when(config.currencyThreshold()).thenReturn(10000000);
		doReturn(new Item[]{new Item(COWBELL_AMULET, 1)}).when(bank).getItems();
		when(client.getItemContainer(InventoryID.INV)).thenReturn(null);
		TestPathfinderConfig base = new TestPathfinderConfig(client, config);
		base.bank = bank;
		PathfinderConfig planning = base.copyForPlanning();
		planning.setPlanningMode(false);
		planning.setBypassItemPossession(false);
		planning.setConsiderBank(true);
		planning.refresh();

		assertEquivalentWithField(planning, VARROCK, Set.of(COWBELL_DESTINATION));
	}

	@Test
	public void fieldModeMultiTargetReachesTheSameCheapestTarget()
	{
		assertEquivalentWithField(walkOnlyConfig(), LUMBRIDGE, Set.of(VARROCK, FALADOR, DRAYNOR));
	}

	@Test
	public void fieldModeUnreachableTargetYieldsTheSameClosestTile()
	{
		PathfinderConfig config = walkOnlyConfig();
		Pathfinder dijkstra = run(config, LUMBRIDGE, Set.of(ENTRANA), null);
		DistanceField distanceField = DistanceField.build(config, Set.of(ENTRANA));
		SearchHeuristic heuristic = SearchHeuristic.buildWithField(config, distanceField);
		assertNotNull(heuristic);
		Pathfinder astar = run(config, LUMBRIDGE, Set.of(ENTRANA), heuristic);

		assertEquals("neither search may reach the island (field mode)",
			false, dijkstra.getResult().isReached() || astar.getResult().isReached());
		assertEquals("both must fall back to the same closest reachable tile (field mode)",
			dijkstra.getResult().getClosestReachedPoint(), astar.getResult().getClosestReachedPoint());
		assertEquals("and at the same cost (field mode)",
			dijkstra.getResult().getTotalCost(), astar.getResult().getTotalCost());
	}

}
