package gps.pathfinder;

import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;
import gps.Destinations;
import gps.ShortestPathConfig;
import gps.WorldPointUtil;

/**
 * Covers the walkable-ring expansion for arbitrary single-tile targets (map pins, Quest Helper
 * NPC tiles): a pin on an unwalkable tile must become a reachable target set instead of sending
 * every search into full-map exhaustion — the in-game capture that motivated this showed 11
 * exhausted searches (8.2s) for a pin on Draynor Manor furniture.
 */
@RunWith(MockitoJUnitRunner.class)
public class WalkableTargetsTest
{
	// An unwalkable furniture tile inside Draynor Manor, two tiles from the captured pin that
	// motivated the feature (the original tile opened with the 2026-07 collision fixes).
	private static final int MANOR_PIN = WorldPointUtil.packWorldPoint(3093, 3359, 0);
	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);

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

	private PathfinderConfig planningConfig()
	{
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		return planning;
	}

	@Test
	public void walkableTileStaysExact()
	{
		CollisionMap map = planningConfig().getMap();
		assertEquals("A walkable tile must stay an exact single target",
			Set.of(LUMBRIDGE), Destinations.walkableTargets(map, LUMBRIDGE));
	}

	@Test
	public void blockedPinExpandsToTheNearestWalkableRing()
	{
		CollisionMap map = planningConfig().getMap();
		assertTrue("Precondition: the pin tile is unwalkable",
			map.isBlocked(WorldPointUtil.unpackWorldX(MANOR_PIN),
				WorldPointUtil.unpackWorldY(MANOR_PIN), WorldPointUtil.unpackWorldPlane(MANOR_PIN)));

		Set<Integer> targets = Destinations.walkableTargets(map, MANOR_PIN);
		assertTrue("Expansion must add walkable ring tiles", targets.size() > 1);
		assertTrue("The original tile stays in the set (harmless, keeps identity)",
			targets.contains(MANOR_PIN));
		for (int target : targets)
		{
			if (target == MANOR_PIN)
			{
				continue;
			}
			assertFalse("Every added ring tile must be walkable",
				map.isBlocked(WorldPointUtil.unpackWorldX(target),
					WorldPointUtil.unpackWorldY(target), WorldPointUtil.unpackWorldPlane(target)));
		}
	}

	@Test
	public void expandedPinIsActuallyReachedBySearches()
	{
		// The end-to-end guarantee: targeting the expanded set REACHES (search terminates at a
		// ring tile) instead of exhausting the map for a closest-tile fallback.
		PathfinderConfig config = planningConfig();
		Set<Integer> targets = Destinations.walkableTargets(config.getMap(), MANOR_PIN);
		Pathfinder pathfinder = new Pathfinder(config, LUMBRIDGE, targets, Integer.MAX_VALUE, null);
		pathfinder.run();
		assertTrue("The expanded pin must be reached", pathfinder.getResult().isReached());
	}
}
