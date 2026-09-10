package gps.pathfinder;

import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;
import gps.JewelleryBoxTier;
import gps.ShortestPathConfig;
import gps.PlayerOwnedHouse;
import gps.WorldPointUtil;
import gps.transport.Transport;

/**
 * The POH model: the house is a zero-walk hub — every interior feature's transports are re-homed
 * onto the landing tile (1923,5709) — entered by house teleports/tabs/capes or the Home Portal at
 * the player's house location, and exited through the exit portal (both gated on the house-location
 * varbit). The master toggle removes ALL of it; the jewellery box filters by built tier.
 */
@RunWith(MockitoJUnitRunner.class)
public class PohGatingTest
{
	private static final int LANDING = WorldPointUtil.packWorldPoint(1923, 5709, 0);
	private static final int RIMMINGTON_PORTAL = WorldPointUtil.packWorldPoint(2953, 3224, 0);

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

	private PathfinderConfig planningConfig()
	{
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		return planning;
	}

	private static int landingTransportCount(PathfinderConfig cfg)
	{
		return cfg.getTransportsPacked(false)
			.getOrDefault(LANDING, TransportAvailability.EMPTY_TRANSPORTS).length;
	}

	@Test
	public void masterToggleRemovesEveryPohTransport()
	{
		// Default: usePoh is false — nothing POH-related may exist anywhere in the availability.
		PathfinderConfig cfg = planningConfig();
		assertEquals("no transports at the landing with POH disabled", 0, landingTransportCount(cfg));
		for (boolean bankVisited : new boolean[]{false, true})
		{
			for (Transport teleport : cfg.getUsableTeleports(bankVisited))
			{
				int d = teleport.getDestination();
				assertTrue("no teleport may land inside the POH with POH disabled: " + teleport,
					d == WorldPointUtil.UNDEFINED || !PlayerOwnedHouse.isInside(
						WorldPointUtil.unpackWorldX(d), WorldPointUtil.unpackWorldY(d)));
			}
		}
	}

	@Test
	public void enabledPohExposesTheLandingHubIncludingTheExitPortal()
	{
		when(config.usePoh()).thenReturn(true);
		PathfinderConfig cfg = planningConfig();
		assertTrue("the landing hub must carry the re-homed feature transports",
			landingTransportCount(cfg) > 50);

		// The exit portal: from the landing straight to the house location outside (varbits are
		// bypassed in planning mode, so all nine location variants are present; in play the
		// house-location varbit selects one).
		boolean exitFound = false;
		for (Transport transport : cfg.getTransportsPacked(false)
			.getOrDefault(LANDING, TransportAvailability.EMPTY_TRANSPORTS))
		{
			if (transport.getDestination() == RIMMINGTON_PORTAL)
			{
				exitFound = true;
				break;
			}
		}
		assertTrue("the exit portal to the house location must be usable from the landing", exitFound);

		// End to end: standing at the landing, the outside portal is one transport away.
		ReferenceDijkstra.Result out = ReferenceDijkstra.search(cfg, LANDING, Set.of(RIMMINGTON_PORTAL));
		assertTrue("exiting the house must reach the outside portal", out.reached);
		assertTrue("the exit is a single cheap hop, not a trek (cost " + out.cost + ")", out.cost <= 5);
	}

	@Test
	public void jewelleryBoxFiltersByTier()
	{
		when(config.usePoh()).thenReturn(true);
		when(config.pohJewelleryBoxTier()).thenReturn(JewelleryBoxTier.BASIC);
		int basic = countBoxDestinations(planningConfig());

		when(config.pohJewelleryBoxTier()).thenReturn(JewelleryBoxTier.ORNATE);
		int ornate = countBoxDestinations(planningConfig());

		when(config.pohJewelleryBoxTier()).thenReturn(JewelleryBoxTier.NONE);
		int none = countBoxDestinations(planningConfig());

		// Wiki: Basic = ring of dueling + games necklace (9 destinations); Fancy adds combat
		// bracelet + skills necklace (10); Ornate adds glory + ring of wealth (8). Tiers include
		// everything below them.
		assertEquals("Basic box: 9 destinations", 9, basic);
		assertEquals("Ornate box: all 27 destinations", 27, ornate);
		assertEquals("No box: no jewellery box destinations", 0, none);
	}

	private int countBoxDestinations(PathfinderConfig cfg)
	{
		int count = 0;
		for (Transport transport : cfg.getTransportsPacked(false)
			.getOrDefault(LANDING, TransportAvailability.EMPTY_TRANSPORTS))
		{
			String info = transport.getObjectInfo();
			if (info != null && info.contains("Jewellery Box"))
			{
				count++;
			}
		}
		return count;
	}
}
