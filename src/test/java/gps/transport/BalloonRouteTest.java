package gps.transport;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import gps.ShortestPathConfig;
import gps.TeleportationItem;
import gps.WorldPointUtil;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.parser.VarRequirement;
import gps.transport.requirement.ItemRequirement;

/**
 * Balloon flights are destination-keyed (wiki: the log type and Firemaking level belong to the
 * DESTINATION, and each route needs its first-flight unlock varbit): the permutation pair-combiner
 * merges the destination-role row's requirements into every generated edge, so a flight from any
 * station to Varrock must require 40 Firemaking, a willow log, and the Varrock unlock (2872=1) —
 * verified against live varbit values (a player with 2872=1 can fly there; 2869=0 cannot fly to
 * Castle Wars). The log can be paid from the inventory or from the balloon storage crates
 * (chat-parsed counts; see {@link gps.BalloonLogStorage}).
 */
public class BalloonRouteTest
{
	private static final int WILLOW_LOGS = 1519;
	private static final int VARROCK_LANDING = WorldPointUtil.packWorldPoint(3299, 3482, 0);
	private static final int ENTRANA_LANDING = WorldPointUtil.packWorldPoint(2808, 3354, 0);

	@Test
	public void flightsCarryTheDestinationsRequirements()
	{
		HashMap<Integer, Set<Transport>> all = TransportLoader.loadAllFromResources();
		// A Castle Wars basket tile; the edge to Varrock's landing (3299,3482).
		Transport flight = null;
		for (Set<Transport> transports : all.values())
		{
			for (Transport transport : transports)
			{
				if (TransportType.HOT_AIR_BALLOON.equals(transport.getType())
					&& transport.getDestination() == VARROCK_LANDING)
				{
					flight = transport;
					break;
				}
			}
			if (flight != null)
			{
				break;
			}
		}
		assertNotNull("a balloon flight to Varrock must exist", flight);

		// Firemaking 40 (destination-keyed).
		boolean firemaking40 = false;
		int[] skills = flight.getSkillLevels();
		// Skill array indexing follows Skill.ordinal(); scan for a 40 requirement.
		for (int level : skills)
		{
			if (level == 40)
			{
				firemaking40 = true;
				break;
			}
		}
		assertTrue("the Varrock flight must require level 40 (Firemaking)", firemaking40);

		// One willow log.
		boolean willow = false;
		if (flight.getItemRequirements() != null)
		{
			for (ItemRequirement req : flight.getItemRequirements().getRequirements())
			{
				if (req.getItemIds() != null)
				{
					for (int id : req.getItemIds())
					{
						if (id == WILLOW_LOGS)
						{
							willow = true;
							break;
						}
					}
				}
			}
		}
		assertTrue("the Varrock flight must require a willow log", willow);

		// The Varrock route unlock varbit (2872=1), verified against live player values.
		boolean unlockVarbit = false;
		for (VarRequirement var : flight.getVarbits())
		{
			if (var.getId() == 2872)
			{
				unlockVarbit = true;
				break;
			}
		}
		assertTrue("the Varrock flight must be gated on its route unlock varbit (2872)", unlockVarbit);

		// And the quest gate from the origin-role row survives the merge.
		Map<Integer, Integer> questState = new HashMap<>();
		questState.put(2867, 2);
		questState.put(2872, 1);
		boolean allPass = true;
		for (VarRequirement var : flight.getVarbits())
		{
			if (!var.check(questState))
			{
				allPass = false;
				break;
			}
		}
		assertTrue("an unlocked player (2867=2, 2872=1) must pass every varbit gate", allPass);
	}

	/**
	 * The possession check must BIND in owned modes: a player whose inventory holds only a willow
	 * log can fly to Varrock (its log) but not to Entrana (needs a normal log). Uses a
	 * possession-checked config (not a planning copy) with the balloon type enabled and varbit
	 * checks bypassed, so the log requirement is the only difference between the two flights.
	 */
	@Test
	public void logPossessionGatesFlightsInOwnedModes()
	{
		Client client = loggedInClient();
		ItemContainer inventory = mock(ItemContainer.class);
		doReturn(new Item[]{new Item(WILLOW_LOGS, 1)}).when(inventory).getItems();
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);

		boolean[] usable = flightUsability(client, balloonConfig());
		assertTrue("holding a willow log, the Varrock flight must be usable", usable[0]);
		assertFalse("without a normal log, the Entrana flight must not be usable", usable[1]);
	}

	/**
	 * The balloon log storage pays for flights without carrying logs: an empty inventory plus a
	 * chat-parsed stored willow count makes the Varrock flight usable, while Entrana (normal logs;
	 * that type's storage is empty) stays gated.
	 */
	@Test
	public void storedLogsCoverFlightsWithoutCarrying()
	{
		ShortestPathConfig config = balloonConfig();
		when(config.balloonStoredWillowLogs()).thenReturn(5);

		boolean[] usable = flightUsability(loggedInClient(), config);
		assertTrue("with willow logs in storage, the Varrock flight must be usable", usable[0]);
		assertFalse("with no normal log stored or carried, the Entrana flight must not be usable", usable[1]);
	}

	private static ShortestPathConfig balloonConfig()
	{
		ShortestPathConfig config = mock(ShortestPathConfig.class);
		when(config.calculationCutoff()).thenReturn(30);
		when(config.useHotAirBalloons()).thenReturn(true);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.NONE);
		return config;
	}

	private static Client loggedInClient()
	{
		Client client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		return client;
	}

	/** Whether the Varrock ([0]) and Entrana ([1]) flights are usable under the given mocks. */
	private static boolean[] flightUsability(Client client, ShortestPathConfig config)
	{
		PathfinderConfig cfg = new TestPathfinderConfig(client, config);
		cfg.refresh();

		boolean varrock = false;
		boolean entrana = false;
		for (int origin : cfg.getTransportsPacked(false).keys())
		{
			for (Transport transport : cfg.getTransportsPacked(false).get(origin))
			{
				if (!TransportType.HOT_AIR_BALLOON.equals(transport.getType()))
				{
					continue;
				}
				varrock |= transport.getDestination() == VARROCK_LANDING;
				entrana |= transport.getDestination() == ENTRANA_LANDING;
			}
		}
		return new boolean[]{varrock, entrana};
	}
}
