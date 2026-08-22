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
 * The rule: a method row never leaves the catalog for a structural reason — house off, a
 * jewellery-box tier not built, mounts off — it stays listed with a lock and a reason. The one
 * exception is seasonal content, which only exists on seasonal worlds.
 */
@RunWith(MockitoJUnitRunner.class)
public class CatalogNeverShrinksTest
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
		when(config.pohMountGlory()).thenReturn(true);
		when(config.pohMountXerics()).thenReturn(true);
		when(config.pohMountDigsite()).thenReturn(true);
		when(config.pohMountMythical()).thenReturn(true);
		when(config.pohJewelleryBoxTier()).thenReturn(JewelleryBoxTier.ORNATE);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(i -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
	}

	private PathfinderConfig planning()
	{
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		return planning;
	}

	private static TeleportMethod first(Map<TeleportMethod, MethodAvailability> availability, TransportType type,
		String labelPrefix)
	{
		for (TeleportMethod method : availability.keySet())
		{
			if (method.getType() == type && (labelPrefix == null || method.label().startsWith(labelPrefix)))
			{
				return method;
			}
		}
		return null;
	}

	@Test
	public void houseOffLocksItsMethodsInsteadOfRemovingThem()
	{
		int withHouse = planning().getMethodCatalog().size();
		when(config.usePoh()).thenReturn(false);
		PathfinderConfig off = planning();
		assertEquals("the catalog must not shrink when the house is switched off",
			withHouse, off.getMethodCatalog().size());
		Map<TeleportMethod, MethodAvailability> availability = off.getMethodAvailability();
		TeleportMethod box = first(availability, TransportType.TELEPORTATION_BOX, null);
		assertTrue("a house method is still listed", box != null);
		assertEquals(MethodAvailability.LOCKED, availability.get(box));
		assertEquals("House is off (House section)", off.getMethodAvailabilityDetail().get(box));
	}

	@Test
	public void boxTierLocksHigherTierDestinations()
	{
		when(config.pohJewelleryBoxTier()).thenReturn(JewelleryBoxTier.NONE);
		PathfinderConfig none = planning();
		Map<TeleportMethod, MethodAvailability> availability = none.getMethodAvailability();
		boolean lockedBox = false;
		for (Map.Entry<TeleportMethod, MethodAvailability> entry : availability.entrySet())
		{
			String label = entry.getKey().label();
			boolean mount = label.startsWith("Xeric's talisman:") || label.startsWith("Amulet of glory:")
				|| label.startsWith("Digsite pendant:") || label.startsWith("Mythical cape:");
			if (entry.getKey().getType() == TransportType.TELEPORTATION_BOX && !mount
				&& entry.getValue() == MethodAvailability.LOCKED)
			{
				lockedBox = true;
				assertEquals("Not built at your jewellery box tier (House section)",
					none.getMethodAvailabilityDetail().get(entry.getKey()));
			}
		}
		assertTrue("with no box built its destinations stay listed, locked", lockedBox);
	}

	@Test
	public void seasonalContentIsTheOneException()
	{
		when(config.useSeasonalTransports()).thenReturn(false);
		assertFalse("seasonal rows must NOT be listed off a seasonal world",
			planning().getMethodCatalog().stream().anyMatch(m -> m.getType() == TransportType.SEASONAL_TRANSPORTS));
	}
}
