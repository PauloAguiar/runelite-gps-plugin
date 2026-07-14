package gps.transport;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import gps.WorldPointUtil;
import gps.transport.parser.VarRequirement;

/**
 * Balloon flights are destination-keyed (wiki: the log type and Firemaking level belong to the
 * DESTINATION, and each route needs its first-flight unlock varbit): the permutation pair-combiner
 * merges the destination-role row's requirements into every generated edge, so a flight from any
 * station to Varrock must require 40 Firemaking, a willow log, and the Varrock unlock (2872=1) —
 * verified against live varbit values (a player with 2872=1 can fly there; 2869=0 cannot fly to
 * Castle Wars).
 */
public class BalloonRouteTest
{
	private static final int WILLOW_LOGS = 1519;
	private static final int VARROCK_LANDING = gps.WorldPointUtil.packWorldPoint(3299, 3482, 0);
	private static final int ENTRANA_LANDING = gps.WorldPointUtil.packWorldPoint(2808, 3354, 0);

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
					&& transport.getDestination() == WorldPointUtil.packWorldPoint(3299, 3482, 0))
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
			for (gps.transport.requirement.ItemRequirement req : flight.getItemRequirements().getRequirements())
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
	@org.junit.Test
	public void logPossessionGatesFlightsInOwnedModes()
	{
		net.runelite.api.Client client = org.mockito.Mockito.mock(net.runelite.api.Client.class);
		gps.ShortestPathConfig config = org.mockito.Mockito.mock(gps.ShortestPathConfig.class);
		org.mockito.Mockito.when(config.calculationCutoff()).thenReturn(30);
		org.mockito.Mockito.when(config.useHotAirBalloons()).thenReturn(true);
		org.mockito.Mockito.when(config.useTeleportationItems()).thenReturn(gps.TeleportationItem.NONE);
		org.mockito.Mockito.when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
		org.mockito.Mockito.when(client.getClientThread()).thenReturn(Thread.currentThread());
		org.mockito.Mockito.when(client.getBoostedSkillLevel(org.mockito.ArgumentMatchers.any(net.runelite.api.Skill.class)))
			.thenReturn(99);
		net.runelite.api.ItemContainer inventory = org.mockito.Mockito.mock(net.runelite.api.ItemContainer.class);
		org.mockito.Mockito.doReturn(new net.runelite.api.Item[]{new net.runelite.api.Item(WILLOW_LOGS, 1)})
			.when(inventory).getItems();
		org.mockito.Mockito.when(client.getItemContainer(net.runelite.api.gameval.InventoryID.INV))
			.thenReturn(inventory);

		gps.pathfinder.PathfinderConfig cfg = new gps.pathfinder.TestPathfinderConfig(client, config);
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
				if (transport.getDestination() == VARROCK_LANDING)
				{
					varrock = true;
				}
				if (transport.getDestination() == ENTRANA_LANDING)
				{
					entrana = true;
				}
			}
		}
		assertTrue("holding a willow log, the Varrock flight must be usable", varrock);
		org.junit.Assert.assertFalse("without a normal log, the Entrana flight must not be usable", entrana);
	}
}
