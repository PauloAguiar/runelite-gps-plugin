package gps.pathfinder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;
import gps.ShortestPathConfig;
import gps.TeleportMethod;
import gps.WorldPointUtil;
import gps.transport.TransportType;

/**
 * Captain Barnaby's ships (Ardougne / Brimhaven / Rimmington) drop you on the boat DECK, from which
 * you cross a gangplank to disembark — so the ship transports must land on the deck tile, not the
 * post-gangplank land tile. Landing on land skipped the gangplank: the route couldn't be tracked
 * (and the overlay froze on "Use ...") until the player had already crossed it by hand.
 */
@RunWith(MockitoJUnitRunner.class)
public class BarnabyShipTest
{
	private static final int ARDOUGNE_DOCK = WorldPointUtil.packWorldPoint(2683, 3271, 0);
	private static final int BRIMHAVEN_DECK = WorldPointUtil.packWorldPoint(2775, 3234, 1);
	private static final int BRIMHAVEN_LAND = WorldPointUtil.packWorldPoint(2772, 3234, 0);
	private static final int ARDOUGNE_DECK = WorldPointUtil.packWorldPoint(2683, 3268, 1);
	private static final int ARDOUGNE_LAND = WorldPointUtil.packWorldPoint(2683, 3271, 0);
	private static final int RIMMINGTON_DECK = WorldPointUtil.packWorldPoint(2915, 3222, 1);
	private static final int RIMMINGTON_LAND = WorldPointUtil.packWorldPoint(2915, 3225, 0);

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
	public void ardougneToBrimhavenLandsOnTheDeckAndCrossesTheGangplank()
	{
		// Ships only: every method-type but SHIP is excluded (gangplanks are plain walking connectors,
		// not methods, so they stay). Brimhaven is an island, so the Barnaby ship is the only route.
		PathfinderConfig config = everythingConfig();
		Set<TeleportMethod> excludeNonShips = new HashSet<>();
		for (TeleportMethod method : config.getMethodCatalog())
		{
			if (method.getType() != TransportType.SHIP)
			{
				excludeNonShips.add(method);
			}
		}
		config.rebuildAvailabilityWithExclusions(excludeNonShips);

		Pathfinder pathfinder = new Pathfinder(config, ARDOUGNE_DOCK, Set.of(BRIMHAVEN_LAND));
		pathfinder.run();

		assertTrue("Ardougne -> Brimhaven must be reachable by the Barnaby ship",
			pathfinder.getResult().isReached());
		List<PathStep> path = pathfinder.getResult().getPathSteps();
		assertTrue("the route must land on the Brimhaven boat deck, then cross the gangplank — "
				+ "landing straight on land would skip the gangplank the player must actually cross",
			path.stream().anyMatch(s -> s.getPackedPosition() == BRIMHAVEN_DECK));
	}

	@Test
	public void everyBarnabyDeckCanDisembark()
	{
		// Each tile the ships now land on must have a way off (guards against a dead-end landing).
		assertDisembark(BRIMHAVEN_DECK, BRIMHAVEN_LAND);
		assertDisembark(ARDOUGNE_DECK, ARDOUGNE_LAND);
		assertDisembark(RIMMINGTON_DECK, RIMMINGTON_LAND);
	}

	private void assertDisembark(int deck, int land)
	{
		Pathfinder pathfinder = new Pathfinder(everythingConfig(), deck, Set.of(land));
		pathfinder.run();
		assertTrue("boat deck " + WorldPointUtil.unpackWorldPoint(deck) + " must disembark to land",
			pathfinder.getResult().isReached());
	}
}
