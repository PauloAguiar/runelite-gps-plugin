package gps.pathfinder;

import gps.JewelleryBoxTier;
import gps.MethodAvailability;
import gps.ShortestPathConfig;
import gps.TeleportMethod;
import gps.TeleportationItem;
import gps.transport.TransportType;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The house's mounted items are individual assumptions (they can't be scene-detected): each
 * mount has its own toggle under "Mounted items". An unticked mount stays IN the catalog —
 * nothing vanishes — but classifies LOCKED with a "not built" reason and drops out of routing,
 * exactly like any other unlock the player lacks.
 */
@RunWith(MockitoJUnitRunner.class)
public class PohMountSelectionTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	@Before
	public void before()
	{
		when(config.calculationCutoff()).thenReturn(30);
		when(config.currencyThreshold()).thenReturn(10000000);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.NONE);
		when(config.usePoh()).thenReturn(true);
		when(config.usePohMountedItems()).thenReturn(true);
		// Below Ornate, so the mounted glory is not folded into the box.
		when(config.pohJewelleryBoxTier()).thenReturn(JewelleryBoxTier.BASIC);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(i -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
	}

	private PathfinderConfig planning(boolean glory, boolean xerics, boolean digsite, boolean mythical)
	{
		when(config.pohMountGlory()).thenReturn(glory);
		when(config.pohMountXerics()).thenReturn(xerics);
		when(config.pohMountDigsite()).thenReturn(digsite);
		when(config.pohMountMythical()).thenReturn(mythical);
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		return planning;
	}

	private static TeleportMethod mount(Map<TeleportMethod, MethodAvailability> availability, String mount)
	{
		for (TeleportMethod method : availability.keySet())
		{
			if (method.getType() == TransportType.TELEPORTATION_BOX && method.label().startsWith(mount + ":"))
			{
				return method;
			}
		}
		return null;
	}

	@Test
	public void untickedMountStaysListedButLocked()
	{
		PathfinderConfig all = planning(true, true, true, true);
		Map<TeleportMethod, MethodAvailability> availability = all.getMethodAvailability();
		for (String name : new String[]{"Amulet of glory", "Xeric's talisman", "Digsite pendant", "Mythical cape"})
		{
			TeleportMethod method = mount(availability, name);
			assertTrue(name + " mount in the catalog", method != null);
			assertEquals(name + " usable when ticked", MethodAvailability.AVAILABLE, availability.get(method));
		}

		PathfinderConfig noXerics = planning(true, false, true, true);
		availability = noXerics.getMethodAvailability();
		TeleportMethod xerics = mount(availability, "Xeric's talisman");
		assertTrue("unticked Xeric's must STAY in the catalog", xerics != null);
		assertEquals("…but locked", MethodAvailability.LOCKED, availability.get(xerics));
		assertEquals("…with the reason the lock tooltip shows",
			"Not built in your house (House section)", noXerics.getMethodAvailabilityDetail().get(xerics));
		assertEquals("the other mounts stay usable", MethodAvailability.AVAILABLE,
			availability.get(mount(availability, "Amulet of glory")));
	}

	@Test
	public void untickedMountDropsOutOfOwnedRouting()
	{
		PathfinderConfig noXerics = planning(true, false, true, true);
		noXerics.setPlanningMode(false);
		noXerics.setBypassItemPossession(false);
		noXerics.refresh();
		boolean xericsEdge = false;
		boolean gloryEdge = false;
		// The mounts sit on two house tiles (glory/cape at 1960,5750; Xeric's/digsite at
		// 1886,5760) — scan every origin rather than hard-code either.
		gps.PrimitiveIntHashMap<gps.transport.Transport[]> byOrigin = noXerics.getTransportsPacked(false);
		for (int origin : byOrigin.keys())
		{
			gps.transport.Transport[] transports = byOrigin.get(origin);
			for (gps.transport.Transport transport : transports == null ? new gps.transport.Transport[0] : transports)
			{
				String info = transport.getObjectInfo();
				if (info != null && transport.getType() == TransportType.TELEPORTATION_BOX)
				{
					xericsEdge |= info.contains("Xeric's Talisman");
					gloryEdge |= info.contains("Amulet of Glory");
				}
			}
		}
		assertFalse("an unbuilt mount must not be a routable edge", xericsEdge);
		assertTrue("a built mount still is", gloryEdge);
	}
}
