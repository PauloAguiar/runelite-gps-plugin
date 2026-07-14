package gps.pathfinder;

import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;
import gps.ShortestPathConfig;
import gps.WorldPointUtil;
import gps.transport.Transport;
import gps.transport.TransportType;

/**
 * The search optimality contract, checked against {@link ReferenceDijkstra} (textbook Dijkstra
 * over the same edge expansion — the oracle): every production search, informed or not, must
 * return EXACTLY the oracle's minimal cost. This is what guarantees the panel's route 0 is the
 * true optimum regardless of the route count.
 * <p>
 * Battery notes: the Varlamore scenario reproduces a user capture where the primary search
 * returned a 177-cost route while a 101-cost route existed (surfaced only by the seed pass at a
 * higher route limit). The shared-destination scenario forces the quetzal whistle's differential
 * cost into play — with the differential in the ordering key only, the cheaper arrival loses the
 * settle race to a costlier competitor and is permanently discarded.
 */
@RunWith(MockitoJUnitRunner.class)
public class SearchOptimalityTest
{
	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int BARROWS = WorldPointUtil.packWorldPoint(3566, 3291, 0);
	private static final int GRAND_EXCHANGE = WorldPointUtil.packWorldPoint(3164, 3487, 0);
	private static final int SHILO_VILLAGE = WorldPointUtil.packWorldPoint(2852, 2954, 0);
	private static final int VARROCK = WorldPointUtil.packWorldPoint(3213, 3424, 0);
	private static final int FALADOR = WorldPointUtil.packWorldPoint(2965, 3380, 0);
	private static final int DRAYNOR = WorldPointUtil.packWorldPoint(3093, 3245, 0);

	// The user capture (gps-capture-20260709-162344): Lumbridge courtyard to the Hunter Guild
	// entrance tiles in Varlamore.
	private static final int CAPTURE_START = WorldPointUtil.packWorldPoint(3219, 3219, 0);
	private static final Set<Integer> CAPTURE_TARGETS = Set.of(
		WorldPointUtil.packWorldPoint(1556, 3046, 0),
		WorldPointUtil.packWorldPoint(1555, 3045, 0),
		WorldPointUtil.packWorldPoint(1555, 3046, 0),
		WorldPointUtil.packWorldPoint(1554, 3046, 0));

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
		// Every production search in this suite also self-checks its settle order (a decrease
		// means an inadmissible heuristic or a polluted ordering key).
		Pathfinder.validateSettleOrder = true;
	}

	@org.junit.After
	public void after()
	{
		Pathfinder.validateSettleOrder = false;
	}

	/** An Everything-mode planning copy (possession and unlocks bypassed), refreshed. */
	private PathfinderConfig everythingConfig()
	{
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		return planning;
	}

	/**
	 * Runs the oracle plus both production searches (uninformed, and A* with the distance-field
	 * heuristic) and asserts every one returns the oracle's minimal cost.
	 */
	private static void assertOptimal(PathfinderConfig config, int start, Set<Integer> targets)
	{
		ReferenceDijkstra.Result oracle = ReferenceDijkstra.search(config, start, targets);

		Pathfinder uninformed = new Pathfinder(config, start, targets, Integer.MAX_VALUE, null);
		uninformed.run();

		DistanceField field = DistanceField.build(config, targets);
		SearchHeuristic heuristic = SearchHeuristic.buildWithField(config, field);
		assertNotNull("field heuristic must build", heuristic);
		Pathfinder astar = new Pathfinder(config, start, targets, Integer.MAX_VALUE, heuristic);
		astar.run();

		assertEquals("uninformed reached must match the oracle",
			oracle.reached, uninformed.getResult().isReached());
		assertEquals("A* reached must match the oracle",
			oracle.reached, astar.getResult().isReached());
		if (oracle.reached)
		{
			assertEquals("uninformed search must return the minimal cost",
				oracle.cost, uninformed.getResult().getTotalCost());
			assertEquals("A* search must return the minimal cost",
				oracle.cost, astar.getResult().getTotalCost());
		}
	}

	@Test
	public void captureQueryLumbridgeToHunterGuildIsOptimal()
	{
		assertOptimal(everythingConfig(), CAPTURE_START, CAPTURE_TARGETS);
	}

	@Test
	public void lumbridgeToBarrowsIsOptimal()
	{
		assertOptimal(everythingConfig(), LUMBRIDGE, Set.of(BARROWS));
	}

	@Test
	public void grandExchangeToShiloIsOptimal()
	{
		assertOptimal(everythingConfig(), GRAND_EXCHANGE, Set.of(SHILO_VILLAGE));
	}

	@Test
	public void multiTargetIsOptimal()
	{
		assertOptimal(everythingConfig(), LUMBRIDGE, Set.of(VARROCK, FALADOR, DRAYNOR));
	}

	/**
	 * Shared-destination competition under a non-zero whistle differential: the quetzal whistle
	 * teleport and a quetzal flight land on the SAME tile. Starting at the flight's origin makes
	 * the flight arrival cheap-and-early while the differential inflates the whistle's queue
	 * position — the exact configuration where an ordering-only cost term lets a costlier arrival
	 * settle the destination first and the cheaper one be discarded.
	 */
	@Test
	public void whistleDifferentialSharedDestinationIsOptimal()
	{
		when(config.costQuetzalWhistle()).thenReturn(100);
		PathfinderConfig cfg = everythingConfig();

		// Derive a concrete flight from the loaded data instead of hardcoding coordinates.
		Transport flight = null;
		for (int origin : cfg.getTransportsPacked(false).keys())
		{
			for (Transport transport : cfg.getTransportsPacked(false).get(origin))
			{
				if (TransportType.QUETZAL.equals(transport.getType())
					&& transport.getOrigin() != WorldPointUtil.UNDEFINED
					&& transport.getDestination() != WorldPointUtil.UNDEFINED)
				{
					flight = transport;
					break;
				}
			}
			if (flight != null)
			{
				break;
			}
		}
		assertNotNull("a quetzal flight must exist in the transport data", flight);
		assertTrue("the whistle must be usable so the shared-destination race exists",
			hasWhistle(cfg));

		assertOptimal(cfg, flight.getOrigin(), Set.of(flight.getDestination()));
	}

	private static boolean hasWhistle(PathfinderConfig cfg)
	{
		for (Transport teleport : cfg.getUsableTeleports(false))
		{
			if (TransportType.QUETZAL_WHISTLE.equals(teleport.getType()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Heuristic consistency sampler: h(a) &le; c(a,b) + h(b) over every edge kind the search can
	 * take from an occupiable tile — cardinal/diagonal walking in a box around the targets,
	 * origin-bound transports, and the global-teleport hub (whose h is the floor). Consistency is
	 * what makes the first settle optimal; a single violated edge is a route-burying bug.
	 */
	@Test
	public void heuristicIsConsistentAroundTheCaptureTargets()
	{
		PathfinderConfig cfg = everythingConfig();
		DistanceField field = DistanceField.build(cfg, CAPTURE_TARGETS);
		SearchHeuristic heuristic = SearchHeuristic.buildWithField(cfg, field);
		assertNotNull(heuristic);
		CollisionMap map = cfg.getMap();

		// Walking edges in a box around the capture targets (the region the heuristic steers).
		int violations = 0;
		for (int x = 1555 - 220; x <= 1555 + 220; x++)
		{
			for (int y = 3046 - 220; y <= 3046 + 220; y++)
			{
				if (map.isBlocked(x, y, 0))
				{
					// Non-landing blocked tiles are never occupied by the search; landings are
					// covered by the transport and hub checks below.
					continue;
				}
				final int a = WorldPointUtil.packWorldPoint(x, y, 0);
				final int ha = heuristic.of(a);
				// Cardinals; diagonals are compositions of them with equal edge cost 1, so the
				// cardinal triangle inequalities imply the diagonal ones within 1 either way —
				// check them directly anyway via the packed diagonal neighbours.
				final int[][] steps = {
					{x - 1, y}, {x + 1, y}, {x, y - 1}, {x, y + 1},
					{x - 1, y - 1}, {x + 1, y - 1}, {x - 1, y + 1}, {x + 1, y + 1}};
				final boolean[] open = {
					map.w(x, y, 0), map.e(x, y, 0), map.s(x, y, 0), map.n(x, y, 0),
					map.s(x, y, 0) && map.w(x, y - 1, 0) && map.w(x, y, 0) && map.s(x - 1, y, 0),
					map.s(x, y, 0) && map.e(x, y - 1, 0) && map.e(x, y, 0) && map.s(x + 1, y, 0),
					map.n(x, y, 0) && map.w(x, y + 1, 0) && map.w(x, y, 0) && map.n(x - 1, y, 0),
					map.n(x, y, 0) && map.e(x, y + 1, 0) && map.e(x, y, 0) && map.n(x + 1, y, 0)};
				for (int i = 0; i < steps.length; i++)
				{
					if (!open[i])
					{
						continue;
					}
					final int hb = heuristic.of(WorldPointUtil.packWorldPoint(steps[i][0], steps[i][1], 0));
					if (ha > 1 + hb)
					{
						violations++;
					}
				}
			}
		}
		assertEquals("walking-edge consistency violations", 0, violations);

		assertTransportEdgesConsistent(cfg, heuristic);
	}

	/**
	 * Transport-edge and teleport-hub consistency: h never overestimates across a ride or a cast.
	 * The origin is the availability MAP KEY — the tile the search actually uses the transport
	 * from — not transport.getOrigin(): the POH remap re-homes interior transports onto the
	 * landing by key only, and checking the stale interior origin is exactly how this sampler
	 * missed the inadmissible-heuristic-at-the-landing bug (capture pair 20260713-232224/232231).
	 */
	private static void assertTransportEdgesConsistent(PathfinderConfig cfg, SearchHeuristic heuristic)
	{
		for (boolean bankVisited : new boolean[]{false, true})
		{
			for (int origin : cfg.getTransportsPacked(bankVisited).keys())
			{
				for (Transport transport : cfg.getTransportsPacked(bankVisited).get(origin))
				{
					if (transport.getDestination() == WorldPointUtil.UNDEFINED)
					{
						continue;
					}
					final int edge = Math.max(0, CostUnits.fromTicks(transport.getDuration())
						+ cfg.getAdditionalTransportCost(transport));
					final int ha = heuristic.of(origin);
					final int hb = heuristic.of(transport.getDestination());
					assertTrue("transport edge " + transport + ": h(origin)=" + ha
						+ " > " + edge + " + h(dest)=" + hb, ha <= edge + hb);
				}
			}
			// The hub (h = floor) to every usable teleport landing.
			for (Transport teleport : cfg.getUsableTeleports(bankVisited))
			{
				if (teleport.getDestination() == WorldPointUtil.UNDEFINED)
				{
					continue;
				}
				final int edge = Math.max(0, CostUnits.fromTicks(teleport.getDuration())
					+ cfg.getAdditionalTransportCost(teleport));
				final int hb = heuristic.of(teleport.getDestination());
				assertTrue("hub -> " + teleport + ": floor=" + heuristic.floor()
					+ " > " + edge + " + h(landing)=" + hb, heuristic.floor() <= edge + hb);
			}
		}
	}

	// Keldagrim train station to the Ice Mountain -area target (capture pair
	// 20260713-232224/232231): the scenario whose optimum crosses the POH once the direct
	// teleports are excluded.
	private static final int KELDAGRIM_STATION = WorldPointUtil.packWorldPoint(2907, 10172, 0);
	private static final int ICE_MOUNTAIN_TARGET = WorldPointUtil.packWorldPoint(3009, 3487, 0);

	/** With the POH enabled, the heuristic must stay consistent across the remapped landing hub. */
	@Test
	public void heuristicIsConsistentWithThePohEnabled()
	{
		when(config.usePoh()).thenReturn(true);
		when(config.useTeleportationPortalsPoh()).thenReturn(true);
		PathfinderConfig cfg = everythingConfig();
		DistanceField field = DistanceField.build(cfg, Set.of(ICE_MOUNTAIN_TARGET));
		SearchHeuristic heuristic = SearchHeuristic.buildWithField(cfg, field);
		assertNotNull(heuristic);
		assertTransportEdgesConsistent(cfg, heuristic);
	}

	/**
	 * The optimum must survive crossing the POH: with the direct Lassar teleports excluded, the
	 * cheapest route enters the house and takes the Lassar Portal — the oracle and both production
	 * searches must agree. Pre-fix, A* returned the 67-cost Mind Altar route here.
	 */
	@Test
	public void pohMediatedOptimumIsOptimal()
	{
		when(config.usePoh()).thenReturn(true);
		when(config.useTeleportationPortalsPoh()).thenReturn(true);
		PathfinderConfig cfg = everythingConfig();
		Set<gps.TeleportMethod> excluded = new java.util.HashSet<>();
		for (gps.TeleportMethod method : cfg.getMethodCatalog())
		{
			if (method.label().equals("Lassar tablet") || method.label().equals("Lassar Teleport"))
			{
				excluded.add(method);
			}
		}
		assertTrue("the direct Lassar methods must exist in the catalog", excluded.size() == 2);
		cfg.setExcludedMethods(excluded);
		cfg.rebuildAvailabilityWithExclusions(excluded);
		assertOptimal(cfg, KELDAGRIM_STATION, Set.of(ICE_MOUNTAIN_TARGET));
	}
}
