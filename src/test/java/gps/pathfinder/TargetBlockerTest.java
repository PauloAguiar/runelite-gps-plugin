package gps.pathfinder;

import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

/**
 * When a search stops one gate short of the target, GPS names the obstacle and its missing
 * requirement. The probe is the Piscatoris Fishing Colony: its Colony gate (2343-2344, 3662-3663)
 * is locked behind Swan Song, so a player without the quest, standing just south of it, is told
 * exactly that instead of a bare "can't reach".
 */
@RunWith(MockitoJUnitRunner.class)
public class TargetBlockerTest
{
	// Just south of the Colony gate — the tile a route would reach before the locked gate.
	private static final int SOUTH_OF_GATE = WorldPointUtil.packWorldPoint(2343, 3660, 0);
	// Inside the colony, north of the gate (a walkable tile — 2340,3688 is blocked).
	private static final Set<Integer> COLONY_TARGET = Set.of(WorldPointUtil.packWorldPoint(2340, 3687, 0));

	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	@Before
	public void before()
	{
		when(config.calculationCutoff()).thenReturn(30);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
	}

	private PathfinderConfig configWithSwanSong(QuestState swanSong)
	{
		PathfinderConfig cfg = new TestPathfinderConfig(client, config, swanSong, true, true).copyForPlanning();
		cfg.setPlanningMode(false);
		cfg.setBypassItemPossession(false);
		cfg.refresh();
		return cfg;
	}

	@Test
	public void namesTheSwanSongColonyGate()
	{
		String blocker = configWithSwanSong(QuestState.NOT_STARTED)
			.describeTargetBlocker(SOUTH_OF_GATE, COLONY_TARGET);
		assertNotNull("a locked gate near the frontier must be reported", blocker);
		assertTrue("the gate must be named: " + blocker, blocker.contains("Colony gate"));
		assertTrue("the missing quest must be named: " + blocker, blocker.contains("Swan Song"));
	}

	@Test
	public void noBlockerWhenTheQuestIsDone()
	{
		assertNull("with Swan Song complete the gate opens — no blocker to report",
			configWithSwanSong(QuestState.FINISHED).describeTargetBlocker(SOUTH_OF_GATE, COLONY_TARGET));
	}

	/**
	 * The precise path trace: a walk-through-gate route found with the gate open (Swan Song
	 * "done"), then diffed against a no-quest config, must name the exact gate on the path.
	 */
	@Test
	public void blockersAlongPathNamesGatesOnTheWalkedRoute()
	{
		// Bypass config: gate open, teleports excluded, so the search follows the physical walk.
		PathfinderConfig bypass = configWithSwanSong(QuestState.FINISHED);
		Set<gps.TeleportMethod> methods = bypass.getMethodCatalog();
		bypass.setExcludedMethods(methods);
		bypass.rebuildAvailabilityWithExclusions(methods);
		Pathfinder pf = new Pathfinder(bypass, SOUTH_OF_GATE, COLONY_TARGET);
		pf.run();
		assertTrue("the gates-open walk must reach the colony interior", pf.getResult().isReached());

		List<String> blockers = configWithSwanSong(QuestState.NOT_STARTED).blockersAlongPath(pf.getPath());
		assertTrue("the walked route must name the Swan Song colony gate: " + blockers,
			blockers.stream().anyMatch(b -> b.contains("Colony gate") && b.contains("Swan Song")));
	}
}
