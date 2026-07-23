package gps;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;
import gps.pathfinder.Pathfinder;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;

/**
 * Validates the hand-curated destinations (destinations-curated.tsv): every training entry —
 * agility courses, skilling spots — must be REACHABLE on the real collision map in
 * everything-mode, through the same walkable-ring targeting the search box uses. A mistyped
 * coordinate or wrong plane shows up here as an unreachable destination instead of shipping as a
 * search entry that routes nowhere.
 */
@RunWith(MockitoJUnitRunner.class)
public class CuratedDestinationsTest
{
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

	@Test
	public void everyCuratedTrainingDestinationIsReachable()
	{
		List<Destinations.Entry> training = new ArrayList<>();
		for (Destinations.Entry entry : Destinations.resourceEntries())
		{
			if ("training".equals(entry.category))
			{
				training.add(entry);
			}
		}
		assertFalse("curated training destinations must exist", training.isEmpty());

		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();

		List<String> unreachable = new ArrayList<>();
		for (Destinations.Entry entry : training)
		{
			Set<Integer> targets = Destinations.walkableTargets(planning.getMap(), entry.packedPosition);
			Pathfinder pathfinder = new Pathfinder(planning, LUMBRIDGE, targets);
			pathfinder.run();
			if (!pathfinder.getResult().isReached())
			{
				unreachable.add(entry.name + " at "
					+ WorldPointUtil.unpackWorldPoint(entry.packedPosition));
			}
		}
		assertTrue("Unreachable curated destinations (mistyped coordinates?): " + unreachable,
			unreachable.isEmpty());
	}

	@Test
	public void ibansTemplePinExistsOnAWalkableTile()
	{
		// The Underground Pass temple (wiki infobox 2136,4648,1 — that tile is the Well of the
		// Damned structure, so the pin sits one tile west on open floor). Full reachability from
		// the overworld is NOT asserted yet: the maze-to-temple connectors and the level
		// transitions are still being captured — when they land, extend this to a Pathfinder
		// assert from the deep-section entrance.
		Destinations.Entry temple = null;
		for (Destinations.Entry entry : Destinations.resourceEntries())
		{
			if ("Iban's Temple".equals(entry.name))
			{
				temple = entry;
			}
		}
		assertTrue("Iban's Temple must be a curated destination", temple != null);
		assertTrue("pin must sit at 2135,4648,1",
			temple.packedPosition == WorldPointUtil.packWorldPoint(2135, 4648, 1));

		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		assertFalse("pin tile must be walkable (not the well structure itself)",
			planning.getMap().isBlocked(2135, 4648, 1));
		assertFalse("search-box targeting must find walkable tiles around the pin",
			Destinations.walkableTargets(planning.getMap(), temple.packedPosition).isEmpty());
	}
}
