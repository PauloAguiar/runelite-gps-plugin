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

	/**
	 * Asserts A* result equivalence for the given prepared config, and returns the pair's node
	 * counts (dijkstra, astar) so callers can additionally assert the exploration shrank.
	 */
	private static int[] assertEquivalent(PathfinderConfig config, int start, Set<Integer> targets)
	{
		Pathfinder dijkstra = run(config, start, targets, null);
		SearchHeuristic heuristic = SearchHeuristic.build(config, targets, 0, Integer.MAX_VALUE);
		assertNotNull("Heuristic must build (force-engaged for the test)", heuristic);
		Pathfinder astar = run(config, start, targets, heuristic);

		PathfinderResult dijkstraResult = dijkstra.getResult();
		PathfinderResult astarResult = astar.getResult();
		assertEquals("reached must match", dijkstraResult.isReached(), astarResult.isReached());
		assertEquals("total cost must be identical (A* is exact with a consistent heuristic)",
			dijkstraResult.getTotalCost(), astarResult.getTotalCost());
		System.out.println("equivalent: cost " + dijkstraResult.getTotalCost()
			+ ", nodes dijkstra=" + dijkstra.getStats().getNodesChecked()
			+ " astar=" + astar.getStats().getNodesChecked()
			+ ", ms dijkstra=" + dijkstra.getStats().getElapsedTimeNanos() / 1_000_000
			+ " astar=" + astar.getStats().getElapsedTimeNanos() / 1_000_000);
		return new int[]{
			dijkstra.getStats().getNodesChecked(),
			astar.getStats().getNodesChecked()};
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

	@Test
	public void longWalkRouteIsIdenticalAndSmaller()
	{
		int[] nodes = assertEquivalent(walkOnlyConfig(), LUMBRIDGE, Set.of(VARROCK));
		assertTrue("A* must explore fewer nodes on a directed walk (" + nodes[0] + " -> " + nodes[1] + ")",
			nodes[1] < nodes[0]);
	}

	@Test
	public void teleportRouteCostsAreIdentical()
	{
		// Everything mode: global teleports, networks and shortcuts all participate, so this
		// exercises transport edges, abstract hub nodes and the saturated floor under A* ordering.
		assertEquivalent(everythingConfig(), LUMBRIDGE, Set.of(BARROWS));
	}

	@Test
	public void bankedRouteCostsAreIdentical()
	{
		// The banked-state mechanics (delayed visits, banked-dominates-unbanked marking) must hold
		// under f-ordering: a banked and unbanked node at the same tile share the same heuristic,
		// so their relative pop order is still by cost.
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

		assertEquivalent(planning, VARROCK, Set.of(COWBELL_DESTINATION));
	}

	@Test
	public void multiTargetReachesTheSameCheapestTarget()
	{
		// Multi-target: the heuristic is the distance to the targets' bounding box; the first
		// target settled must still be the globally cheapest one.
		assertEquivalent(walkOnlyConfig(), LUMBRIDGE, Set.of(VARROCK, FALADOR, DRAYNOR));
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

	@Test
	public void unreachableTargetYieldsTheSameClosestTile()
	{
		// An island target with walking only: both searches exhaust the reachable component (same
		// explored set, different order), so the tie-broken closest tile must be identical.
		PathfinderConfig config = walkOnlyConfig();
		Pathfinder dijkstra = run(config, LUMBRIDGE, Set.of(ENTRANA), null);
		SearchHeuristic heuristic = SearchHeuristic.build(config, Set.of(ENTRANA), 0, Integer.MAX_VALUE);
		assertNotNull(heuristic);
		Pathfinder astar = run(config, LUMBRIDGE, Set.of(ENTRANA), heuristic);

		assertEquals("neither search may reach the island",
			false, dijkstra.getResult().isReached() || astar.getResult().isReached());
		assertEquals("both must fall back to the same closest reachable tile",
			dijkstra.getResult().getClosestReachedPoint(), astar.getResult().getClosestReachedPoint());
		assertEquals("and at the same cost",
			dijkstra.getResult().getTotalCost(), astar.getResult().getTotalCost());
	}
}
