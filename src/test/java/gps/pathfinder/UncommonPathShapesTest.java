package gps.pathfinder;

import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;
import gps.ShortestPathConfig;
import gps.WorldPointUtil;

/**
 * Uncommon route shapes, each checked against the {@link ReferenceDijkstra} oracle with both the
 * uninformed search and production A* (bounded distance field): an island reachable only by boat,
 * the same island with every method excluded (must honestly end at the coast, not fake a route),
 * a deep-wilderness target no teleport can reach directly, and a plane-change target behind a
 * staircase transport. These exercise the gating machinery (wilderness bands, boat-only reverse
 * transports, plane transports) that ordinary overland queries never touch.
 */
@RunWith(MockitoJUnitRunner.class)
public class UncommonPathShapesTest
{
	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	// Entrana: no walking connection, no teleports land there — the only way in is the Port Sarim
	// ferry (which lands on the ship's deck at plane 1, then the gangplank down).
	private static final int ENTRANA = WorldPointUtil.packWorldPoint(2830, 3335, 0);
	// The Wilderness Agility Course entrance, ~level 52: far deeper than any global teleport's band,
	// so a route must enter at a legal band (or via obelisks) and traverse from there.
	private static final int DEEP_WILDERNESS = WorldPointUtil.packWorldPoint(3004, 3937, 0);
	// The upper landing of a west-Varrock staircase (transports.tsv: 3159,3435,0 -> 3155,3435,1) —
	// a target only reachable through a plane-change transport.
	private static final int UPSTAIRS = WorldPointUtil.packWorldPoint(3155, 3435, 1);
	private static final int COST_MULTIPLE = 3;

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
		Pathfinder.validateSettleOrder = true;
	}

	@After
	public void after()
	{
		Pathfinder.validateSettleOrder = false;
	}

	private PathfinderConfig everythingConfig()
	{
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		return planning;
	}

	private PathfinderConfig walkOnlyConfig()
	{
		PathfinderConfig planning = everythingConfig();
		planning.rebuildAvailabilityWithExclusions(planning.getMethodCatalog());
		return planning;
	}

	/**
	 * Oracle parity for one query: the uninformed search and production A* (bounded field) must both
	 * agree with textbook Dijkstra on reachability and minimal cost. Returns the A* result for
	 * shape-specific assertions.
	 */
	private static PathfinderResult assertParity(PathfinderConfig cfg, int start, Set<Integer> targets)
	{
		ReferenceDijkstra.Result oracle = ReferenceDijkstra.search(cfg, start, targets);

		Pathfinder uninformed = new Pathfinder(cfg, start, targets);
		uninformed.run();
		assertEquals("uninformed reached must match the oracle",
			oracle.reached, uninformed.getResult().isReached());

		DistanceField field = DistanceField.build(cfg, targets, COST_MULTIPLE);
		SearchHeuristic heuristic = SearchHeuristic.buildWithField(cfg, field);
		assertNotNull(heuristic);
		Pathfinder astar = new Pathfinder(cfg, start, targets, Integer.MAX_VALUE, heuristic);
		astar.run();
		assertEquals("A* reached must match the oracle", oracle.reached, astar.getResult().isReached());

		if (oracle.reached)
		{
			assertEquals("uninformed cost must match the oracle",
				oracle.cost, uninformed.getResult().getTotalCost());
			assertEquals("A* cost must match the oracle",
				oracle.cost, astar.getResult().getTotalCost());
		}
		return astar.getResult();
	}

	@Test
	public void islandReachableOnlyByBoat()
	{
		PathfinderResult result = assertParity(everythingConfig(), LUMBRIDGE, Set.of(ENTRANA));
		assertTrue("Entrana must be reachable with boats available", result.isReached());
		// The route must genuinely cross water: some step lands on Entrana's side having been on the
		// mainland before it — i.e. a non-adjacent jump (the ferry) appears in the path.
		List<PathStep> path = result.getPathSteps();
		boolean jumped = false;
		for (int i = 1; i < path.size(); i++)
		{
			if (WorldPointUtil.distanceBetween(
				path.get(i - 1).getPackedPosition(), path.get(i).getPackedPosition()) > 20)
			{
				jumped = true;
				break;
			}
		}
		assertTrue("the route must include the ferry crossing (a non-adjacent transport edge)", jumped);
	}

	@Test
	public void islandHonestlyUnreachableWithEveryMethodExcluded()
	{
		PathfinderConfig cfg = walkOnlyConfig();
		PathfinderResult result = assertParity(cfg, LUMBRIDGE, Set.of(ENTRANA));
		assertFalse("with ships excluded nothing can reach Entrana", result.isReached());
		// The closest-tile path must honestly end on the mainland (near the coast), not on the island.
		List<PathStep> path = result.getPathSteps();
		assertFalse("a closest-tile path must still be produced", path.isEmpty());
		int end = path.get(path.size() - 1).getPackedPosition();
		assertTrue("the walk must end on the mainland side, not on Entrana",
			WorldPointUtil.distanceBetween(end, ENTRANA) > 20);
	}

	@Test
	public void deepWildernessTargetBeyondEveryTeleportBand()
	{
		PathfinderResult result = assertParity(everythingConfig(), LUMBRIDGE, Set.of(DEEP_WILDERNESS));
		assertTrue("deep wilderness must be reachable (teleport to a legal band, then traverse)",
			result.isReached());
		assertTrue("premise: the target lies in level-30+ wilderness",
			WildernessChecker.isInLevel30Wilderness(DEEP_WILDERNESS));
	}

	@Test
	public void startOnBlockedFairyRingPlatform()
	{
		// The Mount Karuulm (CIR) fairy ring's standing tile: transport ORIGINS on blocked tiles
		// are enterable and leavable only through the dedicated step-off rules — starting a search
		// there is the mirror image of the usual case and exercises the isBlocked branch of
		// getTileNeighbors. (Was the Karamja ring, whose platform turned out to be phantom
		// blocking and opened with the 2026-07 collision fixes.)
		final int ring = WorldPointUtil.packWorldPoint(1639, 3868, 0);
		PathfinderConfig cfg = everythingConfig();
		assertTrue("premise: the fairy ring standing tile is blocked in the collision map",
			cfg.getMap().isBlocked(1639, 3868, 0));
		PathfinderResult result = assertParity(cfg, ring, Set.of(LUMBRIDGE));
		assertTrue("a route off the ring platform must exist", result.isReached());
	}

	@Test
	public void blockedTransportLandingAsTarget()
	{
		// A transport LANDING on a blocked tile (a fairy ring platform, a jetty): forward searches
		// may step onto it only by taking the transport there; the reverse field values it via
		// patchBlockedLandings. Discovered from the data rather than hardcoded, so the test follows
		// the transport files.
		PathfinderConfig cfg = everythingConfig();
		int landing = WorldPointUtil.UNDEFINED;
		outer:
		for (boolean bankVisited : new boolean[]{false, true})
		{
			for (int origin : cfg.getTransportsPacked(bankVisited).keys())
			{
				for (gps.transport.Transport transport : cfg.getTransportsPacked(bankVisited).get(origin))
				{
					final int destination = transport.getDestination();
					if (destination == WorldPointUtil.UNDEFINED)
					{
						continue;
					}
					if (cfg.getMap().isBlocked(WorldPointUtil.unpackWorldX(destination),
						WorldPointUtil.unpackWorldY(destination), WorldPointUtil.unpackWorldPlane(destination)))
					{
						landing = destination;
						break outer;
					}
				}
			}
		}
		assertTrue("premise: the transport data must contain at least one blocked landing "
			+ "(the reason patchBlockedLandings exists)", landing != WorldPointUtil.UNDEFINED);
		PathfinderResult result = assertParity(cfg, LUMBRIDGE, Set.of(landing));
		assertTrue("the blocked landing must be reachable by taking the transport there: "
			+ WorldPointUtil.unpackWorldPoint(landing), result.isReached());
	}

	@Test
	public void undergroundTargetThroughTrapdoor()
	{
		// The Lumbridge castle cellar: underground regions connect to the surface only through
		// point transports (the kitchen trapdoor at 3210,3216 -> 3210,9616), so the route must
		// contain that jump — there is no walkable connection.
		final int cellar = WorldPointUtil.packWorldPoint(3210, 9616, 0);
		PathfinderResult result = assertParity(everythingConfig(), LUMBRIDGE, Set.of(cellar));
		assertTrue("the cellar must be reachable through the trapdoor", result.isReached());
		List<PathStep> path = result.getPathSteps();
		boolean jumped = false;
		for (int i = 1; i < path.size(); i++)
		{
			if (WorldPointUtil.distanceBetween(
				path.get(i - 1).getPackedPosition(), path.get(i).getPackedPosition()) > 20)
			{
				jumped = true;
				break;
			}
		}
		assertTrue("the route must include the trapdoor jump", jumped);
	}

	@Test
	public void midBandWildernessStart()
	{
		// Start at the level-27 obelisk platform: inside the wilderness but BELOW the level-30 band,
		// so the 20-30 abstract hub kind applies — teleports usable up to level 30 are legal right
		// away (unlike the 30+ escape case, which must walk out first).
		final int obelisk = WorldPointUtil.packWorldPoint(3035, 3732, 0);
		assertTrue("premise: in the wilderness", WildernessChecker.isInWilderness(obelisk));
		assertFalse("premise: below the level-30 band", WildernessChecker.isInLevel30Wilderness(obelisk));
		PathfinderResult result = assertParity(everythingConfig(), obelisk, Set.of(
			WorldPointUtil.packWorldPoint(3213, 3424, 0)));
		assertTrue("Varrock must be reachable from mid-band wilderness", result.isReached());
	}

	@Test
	public void startInsideTheTargetSet()
	{
		// Degenerate but real (e.g. "nearest bank" while standing in one): the search must finish
		// immediately with a zero-cost single-step path, not explore anything.
		PathfinderConfig cfg = everythingConfig();
		PathfinderResult result = assertParity(cfg, LUMBRIDGE, Set.of(LUMBRIDGE, ENTRANA));
		assertTrue(result.isReached());
		assertEquals("a start-is-target route costs nothing", 0, result.getTotalCost());
		assertEquals("the path is the single start tile", 1, result.getPathSteps().size());
		assertEquals(LUMBRIDGE, result.getPathSteps().get(0).getPackedPosition());
	}

	@Test
	public void planeChangeTargetBehindAStaircase()
	{
		PathfinderResult result = assertParity(everythingConfig(), LUMBRIDGE, Set.of(UPSTAIRS));
		assertTrue("the upstairs tile must be reachable via the staircase transport", result.isReached());
		// The path must actually change planes: it starts on plane 0 and ends on plane 1.
		List<PathStep> path = result.getPathSteps();
		assertEquals("path must start on the ground floor",
			0, WorldPointUtil.unpackWorldPlane(path.get(0).getPackedPosition()));
		assertEquals("path must end on the upper floor",
			1, WorldPointUtil.unpackWorldPlane(path.get(path.size() - 1).getPackedPosition()));
	}
}
