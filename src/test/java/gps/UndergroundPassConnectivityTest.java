package gps;

import gps.pathfinder.Pathfinder;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
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
 * The Underground Pass field campaign's connectivity, locked in: from the West Ardougne street,
 * routing reaches level 1's far west and — through the captured Well 4004 — the DEEP BAND, all
 * across ~350 field-captured rows (rockslides, bridges, ledges, wells, the lever, the fire-arrow
 * bridge, the rope swing). Still open: the deep band's internal links (well landing -> bridge
 * maze -> Iban's Temple) — extend this test as those captures land; the temple is the finish
 * line (see also CuratedDestinationsTest's pin test).
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class UndergroundPassConnectivityTest
{
	private static final int OUTSIDE_ENTRANCE = WorldPointUtil.packWorldPoint(2436, 3315, 0);

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

	@Test
	public void streetToDeepBandRoutesAcrossTheCapturedPass()
	{
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		int[][] milestones = {
			{2495, 9716, 0}, // entrance chamber
			{2464, 9692, 0}, // past the rope swing
			{2419, 9674, 0}, // internal well, top
			{2343, 9622, 0}, // Well 4004: the level transition
			{2010, 4712, 1}, // the deep band
			// NOT yet a milestone: Iban's temple. The walk frontier from the well landing sits
			// at (2014,4710,1) — the swamp path obstacles between it and the bridge network at
			// x2126+ still need a field walk before the temple joins this chain.
		};
		for (int[] m : milestones)
		{
			Pathfinder pathfinder = new Pathfinder(planning, OUTSIDE_ENTRANCE,
				Set.of(WorldPointUtil.packWorldPoint(m[0], m[1], m[2])));
			pathfinder.run();
			assertTrue("must reach " + m[0] + "," + m[1] + "," + m[2],
				pathfinder.getResult().isReached());
		}
	}

	@Test
	public void templeDoorsTeleportIntoTheInstancedTemple()
	{
		// The inner sanctum doors are type-10 OBJECTS (3333/3334), invisible to the doors.tsv
		// registry — and they TELEPORT into an instanced temple (field-observed: standing at
		// 2145,4647 lands at template 2014,4711). The visible interior west of the doors is
		// scenery; destination-remaps.tsv snaps targets there to the door step.
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		Pathfinder pathfinder = new Pathfinder(planning,
			WorldPointUtil.packWorldPoint(2152, 4647, 1),
			Set.of(WorldPointUtil.packWorldPoint(2014, 4711, 1)));
		pathfinder.run();
		assertTrue("the instanced temple's template band must be reachable through the doors",
			pathfinder.getResult().isReached());
	}
}
