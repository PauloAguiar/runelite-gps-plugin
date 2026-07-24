package gps;

import gps.pathfinder.Pathfinder;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Mount Karuulm's dungeon was a one-way island: the cave EXIT existed but the elevator DOWN
 * did not, so everything beneath the mountain — Kaal-Mej-San included — was unreachable. A
 * hub user hit this during A Kingdom Divided: Quest Helper targeted the Tasakaal and GPS
 * routed to "the most random area" (the closest reachable point). The Activate Elevator row
 * closes it; this pins the descent from the surface to the Tasakaal.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class KaruulmElevatorTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	@Test
	public void surfaceReachesTheTasakaalViaTheElevator()
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		int surface = WorldPointUtil.packWorldPoint(1311, 3805, 0);
		int[][] targets = {
			{1311, 10188, 0}, // elevator bottom
			{1310, 10206, 0}, // beside Kaal-Mej-San (the NPC tile itself is furniture-blocked)
		};
		for (int[] t : targets)
		{
			Pathfinder pathfinder = new Pathfinder(planning, surface,
				Set.of(WorldPointUtil.packWorldPoint(t[0], t[1], t[2])));
			pathfinder.run();
			assertTrue("must reach " + t[0] + "," + t[1] + "," + t[2],
				pathfinder.getResult().isReached());
		}
	}
}
