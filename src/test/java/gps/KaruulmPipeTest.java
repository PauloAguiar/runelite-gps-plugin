package gps;

import gps.pathfinder.Pathfinder;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.PathStep;
import gps.pathfinder.TestPathfinderConfig;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The Mysterious pipe (88 Agility, id 34655) shortcuts the Karuulm Slayer Dungeon's hydra
 * chamber straight to the Alchemical Hydra corridor. The Agility plugin knew it; GPS data
 * didn't. Standing/landing tiles are cache-derived (~geometry): the pipe object tiles
 * (1316,10214) and (1346,10231) are blocked, so the rows use their open-side neighbours.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class KaruulmPipeTest
{
	private static final int NEAR_HYDRA = WorldPointUtil.packWorldPoint(1346, 10232, 0);
	private static final int NEAR_HALL = WorldPointUtil.packWorldPoint(1315, 10214, 0);

	@Mock
	Client client;
	private final TestShortestPathConfig config = new TestShortestPathConfig();

	/**
	 * The LIVE config (not copyForPlanning) — planning mode bypasses per-player unlock gates,
	 * skill levels included, which would defeat the 88 Agility assertions.
	 */
	private PathfinderConfig liveAt(int agilityLevel)
	{
		config.setCalculationCutoffValue(120);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(agilityLevel);
		PathfinderConfig live = new TestPathfinderConfig(client, config);
		live.refresh();
		return live;
	}

	private static boolean usesPipe(List<PathStep> steps, int from, int to)
	{
		for (int i = 0; i + 1 < steps.size(); i++)
		{
			if (steps.get(i).getPackedPosition() == from
				&& steps.get(i + 1).getPackedPosition() == to)
			{
				return true;
			}
		}
		return false;
	}

	@Test
	public void with88AgilityThePipeCarriesBothDirections()
	{
		PathfinderConfig planning = liveAt(99);
		Pathfinder toHall = new Pathfinder(planning, NEAR_HYDRA, Set.of(NEAR_HALL));
		toHall.run();
		assertTrue(toHall.getResult().isReached());
		assertTrue("hydra-side to hall must squeeze through the pipe",
			usesPipe(toHall.getResult().getPathSteps(), NEAR_HYDRA, NEAR_HALL));

		Pathfinder toHydra = new Pathfinder(planning, NEAR_HALL, Set.of(NEAR_HYDRA));
		toHydra.run();
		assertTrue(toHydra.getResult().isReached());
		assertTrue("hall to hydra-side must squeeze through the pipe",
			usesPipe(toHydra.getResult().getPathSteps(), NEAR_HALL, NEAR_HYDRA));
	}

	@Test
	public void below88AgilityThePipeIsClosed()
	{
		PathfinderConfig planning = liveAt(87);
		Pathfinder toHall = new Pathfinder(planning, NEAR_HYDRA, Set.of(NEAR_HALL));
		toHall.run();
		assertFalse("at 87 Agility the pipe edge must not be taken",
			usesPipe(toHall.getResult().getPathSteps(), NEAR_HYDRA, NEAR_HALL));
	}
}
