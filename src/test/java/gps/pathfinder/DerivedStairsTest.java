package gps.pathfinder;

import gps.ShortestPathConfig;
import gps.WorldPointUtil;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Spot checks over the cache-derived staircase/ladder rows (scripts/derive_stairs.py): a
 * building ladder chain derived with two-way reciprocity must route up, up again, and back
 * down, entirely through derived rows.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class DerivedStairsTest
{
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

	private void assertRoute(int fromX, int fromY, int fromP, int toX, int toY, int toP, int maxTicks)
	{
		Pathfinder pathfinder = new Pathfinder(everythingConfig(),
			WorldPointUtil.packWorldPoint(fromX, fromY, fromP),
			Set.of(WorldPointUtil.packWorldPoint(toX, toY, toP)));
		pathfinder.run();
		assertTrue(fromX + "," + fromY + "," + fromP + " -> " + toX + "," + toY + "," + toP,
			pathfinder.getResult().isReached());
		assertTrue("cost " + pathfinder.getResult().getTotalCost(),
			pathfinder.getResult().getTotalCost() <= gps.pathfinder.CostUnits.fromTicks(maxTicks));
	}

	@Test
	public void derivedLadderChainRoutesUpAndDown()
	{
		// Ladder pair 17148/17149: ground to first floor, first to second, and back down —
		// every edge exists purely via derivation.
		// Ladder climbs UP are calibrated at 4 ticks from 18 field samples (down: 2) — the
		// one family where the flat 2-tick guess was measurably wrong.
		assertRoute(3014, 3519, 0, 3015, 3518, 1, 4);
		assertRoute(3016, 3518, 1, 3015, 3519, 2, 5);
		assertRoute(3015, 3519, 2, 3014, 3519, 0, 6);
	}

	@Test
	public void derivedStaircaseDescends()
	{
		assertRoute(3012, 3515, 1, 3011, 3514, 0, 2);
	}
}
