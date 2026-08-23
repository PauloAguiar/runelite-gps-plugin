package gps.pathfinder;

import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import gps.MethodAvailability;
import gps.ShortestPathConfig;
import gps.TeleportMethod;
import gps.transport.TransportType;

/**
 * The catalog's availability classification also records WHAT is missing, so the panel's lock
 * tooltip can say "Requires 40 Firemaking" or "Missing item: Willow logs" instead of a generic
 * category. The Varrock balloon flight is the probe: it carries a Firemaking level and a willow-log
 * requirement, and its unlock varbits are bypassed by the test config.
 */
public class MethodAvailabilityDetailTest
{
	private static final int WILLOW_LOGS = 1519;

	private PathfinderConfig planningConfig(int boostedLevel, boolean stubWillowName)
	{
		Client client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(boostedLevel);
		if (stubWillowName)
		{
			ItemComposition willow = mock(ItemComposition.class);
			when(willow.getName()).thenReturn("Willow logs");
			when(client.getItemDefinition(WILLOW_LOGS)).thenReturn(willow);
		}
		ShortestPathConfig config = mock(ShortestPathConfig.class);
		when(config.calculationCutoff()).thenReturn(30);
		// The classifier now locks switched-off travel options first; balloons are the subject here.
		lenient().when(config.useHotAirBalloons()).thenReturn(true);
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		return planning;
	}

	private static TeleportMethod varrockFlight(PathfinderConfig config)
	{
		for (TeleportMethod method : config.getMethodAvailability().keySet())
		{
			if (TransportType.HOT_AIR_BALLOON.equals(method.getType())
				&& "Varrock".equals(method.getDisplayInfo()))
			{
				return method;
			}
		}
		return null;
	}

	@Test
	public void missingLevelNamesTheSkillAndLevel()
	{
		PathfinderConfig config = planningConfig(1, false);
		TeleportMethod flight = varrockFlight(config);
		assertNotNull("the Varrock balloon flight must be in the catalog", flight);
		assertEquals(MethodAvailability.MISSING_LEVEL, config.getMethodAvailability().get(flight));
		assertEquals("Requires 40 Firemaking", config.getMethodAvailabilityDetail().get(flight));
	}

	@Test
	public void missingItemNamesTheItem()
	{
		PathfinderConfig config = planningConfig(99, true);
		TeleportMethod flight = varrockFlight(config);
		assertNotNull("the Varrock balloon flight must be in the catalog", flight);
		assertEquals(MethodAvailability.MISSING_ITEM, config.getMethodAvailability().get(flight));
		assertEquals("Missing item: Willow logs", config.getMethodAvailabilityDetail().get(flight));
	}

	@Test
	public void unnameableDetailsFallBackToNoEntry()
	{
		// Without an item definition source (the common test/mocked case), the status is still
		// classified but the detail map simply has no entry — the panel falls back to its generic
		// per-status wording.
		PathfinderConfig config = planningConfig(99, false);
		TeleportMethod flight = varrockFlight(config);
		assertNotNull(flight);
		assertEquals(MethodAvailability.MISSING_ITEM, config.getMethodAvailability().get(flight));
		Map<TeleportMethod, String> details = config.getMethodAvailabilityDetail();
		assertEquals(null, details.get(flight));
	}
}
