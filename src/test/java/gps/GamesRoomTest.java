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
 * The Burthorpe Games Room minigame teleport lands IN the games room zone (field-verified
 * 2208,4939,0 — the 2015 destination switch gave the outdoor tile to the games necklace,
 * and our row still had the old spot). The landing is only useful if the zone connects
 * back out: down-stairs to the room, up-stairs to Burthorpe castle.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class GamesRoomTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	@Test
	public void theMinigameLandingWalksOutToBurthorpe()
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		Pathfinder pathfinder = new Pathfinder(planning,
			WorldPointUtil.packWorldPoint(2208, 4939, 0),
			Set.of(WorldPointUtil.packWorldPoint(2899, 3553, 0)));
		pathfinder.run();
		assertTrue("the games room landing must reach Burthorpe via the stairs",
			pathfinder.getResult().isReached());
	}
}
