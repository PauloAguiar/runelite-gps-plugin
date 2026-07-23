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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The Underground Pass deep-section bridges derived from cache geometry: each 1x3 Cross bridge
 * is a single 5-tick edge between its collision-validated ends. The bridge at (2142,4562) is
 * the one the very first "maze-y path" field capture got stuck at.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class DerivedBridgesTest
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

	@Test
	public void firstCaptureBridgeIsOneFiveTickEdge()
	{
		Pathfinder pathfinder = new Pathfinder(everythingConfig(),
			WorldPointUtil.packWorldPoint(2142, 4561, 1),
			Set.of(WorldPointUtil.packWorldPoint(2142, 4565, 1)));
		pathfinder.run();
		assertTrue(pathfinder.getResult().isReached());
		assertEquals(CostUnits.fromTicks(5), pathfinder.getResult().getTotalCost());
		assertEquals(2, pathfinder.getResult().getPathSteps().size());
	}
}
