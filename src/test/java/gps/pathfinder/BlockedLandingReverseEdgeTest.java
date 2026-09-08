package gps.pathfinder;

import gps.ShortestPathConfig;
import gps.TeleportationItem;
import gps.WorldPointUtil;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Plan step N7: the Moss Giant Island rope swing lands on a BLOCKED tile (2704,3209). The reverse
 * flood never steps onto blocked tiles unless they host a transport origin, so the landing was
 * only valued by the post-flood patch, after the loop that would have followed its reverse edge
 * into the rope's origin (2709,3209) had ended. Everything behind the rope read as unreachable:
 * the field declared a reachable island target provably unreachable (the escape menu then
 * replaced the real page), and for any target behind a blocked jetty or platform the heuristic
 * sent the far side to its floor, overestimating and burying routes through it.
 */
@RunWith(MockitoJUnitRunner.class)
public class BlockedLandingReverseEdgeTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	private static final int ISLAND = WorldPointUtil.packWorldPoint(2705, 3205, 0);
	private static final int ROPE_ORIGIN = WorldPointUtil.packWorldPoint(2709, 3209, 0);
	private static final int ROPE_LANDING = WorldPointUtil.packWorldPoint(2704, 3209, 0);
	private static final int BRIMHAVEN_DOCK = WorldPointUtil.packWorldPoint(2772, 3225, 0);

	@Test
	public void reverseEdgesOutOfABlockedLandingAreFollowed()
	{
		when(config.calculationCutoff()).thenReturn(120);
		lenient().when(config.currencyThreshold()).thenReturn(10000000);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.NONE);
		lenient().when(config.useAgilityShortcuts()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(i -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		PathfinderConfig pathConfig = new TestPathfinderConfig(client, config, QuestState.FINISHED, true, true);
		pathConfig.refresh();
		assertTrue("premise: the rope lands on a blocked tile", pathConfig.getMap().isBlocked(2704, 3209, 0));

		DistanceField field = DistanceField.build(pathConfig, Set.of(ISLAND));
		int landing = field.distance(ROPE_LANDING);
		int origin = field.distance(ROPE_ORIGIN);
		int dock = field.distance(BRIMHAVEN_DOCK);
		System.out.println("BLOCKEDLANDING landing=" + landing + " origin=" + origin + " dock=" + dock);
		assertTrue("the landing itself is valued", landing != DistanceField.UNREACHED);
		assertTrue("the rope's origin must be reached through the landing's reverse edge", origin != DistanceField.UNREACHED);
		assertTrue("the mainland behind the rope must be flooded", dock != DistanceField.UNREACHED);

		// Admissibility: the field is a lower bound on the forward cost from the dock.
		Pathfinder pathfinder = new Pathfinder(pathConfig, BRIMHAVEN_DOCK, Set.of(ISLAND), Integer.MAX_VALUE, null);
		pathfinder.run();
		assertTrue("premise: the island is reachable on foot from the dock", pathfinder.getResult().isReached());
		int forward = pathfinder.getResult().getTotalCost();
		System.out.println("BLOCKEDLANDING forward from dock=" + forward);
		assertTrue("field " + dock + " must not exceed the forward cost " + forward, dock <= forward);
	}
}
