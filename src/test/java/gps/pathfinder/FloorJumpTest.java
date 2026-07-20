package gps.pathfinder;

import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import gps.ShortestPathConfig;
import gps.WorldPointUtil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Multi-floor stairs with Top-/Bottom-floor menu options jump past the intermediate floors in one
 * click. The data prices a jump at floors-1 ticks (the upstream Lighthouse convention), so the
 * router must strictly prefer it over chaining the per-floor climbs — one step in the directions
 * instead of three.
 */
@RunWith(MockitoJUnitRunner.class)
public class FloorJumpTest
{
	// The Grand Tree ladder column at (2466, 3494): planes 0..3, glider level on top.
	private static final int GRAND_TREE_BASE = WorldPointUtil.packWorldPoint(2466, 3494, 0);
	private static final int GRAND_TREE_TOP = WorldPointUtil.packWorldPoint(2466, 3494, 3);
	// Lumbridge Castle north staircase: ground floor to the bank level.
	private static final int LUMBRIDGE_BASE = WorldPointUtil.packWorldPoint(3205, 3228, 0);
	private static final int LUMBRIDGE_TOP = WorldPointUtil.packWorldPoint(3206, 3229, 2);

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

	private PathfinderConfig everythingConfig()
	{
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		return planning;
	}

	@Test
	public void grandTreeTopFloorJumpBeatsChainedClimbs()
	{
		Pathfinder pathfinder = new Pathfinder(everythingConfig(), GRAND_TREE_BASE, Set.of(GRAND_TREE_TOP));
		pathfinder.run();
		assertTrue("route should reach the glider level", pathfinder.getResult().isReached());
		// Jump: 2 ticks = 4 cost units. The chained climbs cost at least 3 ticks (3 x 1-tick floor).
		assertEquals("Top-Floor jump (2 ticks) should be the optimal route",
			gps.pathfinder.CostUnits.fromTicks(2), pathfinder.getResult().getTotalCost());
		// One edge: base -> top, no intermediate-floor tiles on the path.
		assertEquals(2, pathfinder.getResult().getPathSteps().size());
	}

	@Test
	public void grandTreeBottomFloorJumpFromTheGliderLevel()
	{
		Pathfinder pathfinder = new Pathfinder(everythingConfig(), GRAND_TREE_TOP, Set.of(GRAND_TREE_BASE));
		pathfinder.run();
		assertTrue(pathfinder.getResult().isReached());
		assertEquals(gps.pathfinder.CostUnits.fromTicks(2), pathfinder.getResult().getTotalCost());
		assertEquals(2, pathfinder.getResult().getPathSteps().size());
	}

	@Test
	public void lumbridgeCastleTopFloorReachesTheBankLevelInOneStep()
	{
		Pathfinder pathfinder = new Pathfinder(everythingConfig(), LUMBRIDGE_BASE, Set.of(LUMBRIDGE_TOP));
		pathfinder.run();
		assertTrue(pathfinder.getResult().isReached());
		assertEquals(gps.pathfinder.CostUnits.fromTicks(1), pathfinder.getResult().getTotalCost());
		assertEquals(2, pathfinder.getResult().getPathSteps().size());
	}

	@Test
	public void fairytaleHideoutTowerJumpsAndMidFloorBothWork()
	{
		// Cache-derived rows (the Lighthouse's identical twin staircase): ground -> top in one
		// 1-tick step, and the middle floor stays reachable via the per-floor rows.
		int base = WorldPointUtil.packWorldPoint(2444, 4600, 0);
		int top = WorldPointUtil.packWorldPoint(2441, 4601, 2);
		int mid = WorldPointUtil.packWorldPoint(2441, 4601, 1);
		Pathfinder jump = new Pathfinder(everythingConfig(), base, Set.of(top));
		jump.run();
		assertTrue(jump.getResult().isReached());
		assertEquals(gps.pathfinder.CostUnits.fromTicks(1), jump.getResult().getTotalCost());
		assertEquals(2, jump.getResult().getPathSteps().size());
		Pathfinder toMid = new Pathfinder(everythingConfig(), base, Set.of(mid));
		toMid.run();
		assertTrue("middle floor must stay reachable via per-floor rows", toMid.getResult().isReached());
	}

	@Test
	public void aldarinTowerJumpsAndMidFloorBothWork()
	{
		int base = WorldPointUtil.packWorldPoint(1649, 3091, 0);
		int top = WorldPointUtil.packWorldPoint(1649, 3091, 2);
		int mid = WorldPointUtil.packWorldPoint(1649, 3091, 1);
		Pathfinder jump = new Pathfinder(everythingConfig(), base, Set.of(top));
		jump.run();
		assertTrue(jump.getResult().isReached());
		assertEquals(gps.pathfinder.CostUnits.fromTicks(1), jump.getResult().getTotalCost());
		assertEquals(2, jump.getResult().getPathSteps().size());
		Pathfinder down = new Pathfinder(everythingConfig(), top, Set.of(base));
		down.run();
		assertTrue(down.getResult().isReached());
		assertEquals(gps.pathfinder.CostUnits.fromTicks(1), down.getResult().getTotalCost());
		Pathfinder toMid = new Pathfinder(everythingConfig(), base, Set.of(mid));
		toMid.run();
		assertTrue(toMid.getResult().isReached());
	}
}
