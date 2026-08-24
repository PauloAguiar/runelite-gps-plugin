package gps;

import gps.MethodAvailability;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.TransportType;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Field capture 20260823-232830: a wielded (pretty) lava staff satisfies BOTH the earth and fire
 * rune needs of the Civitas illa Fortis Teleport — the game casts it — but the possession check
 * allowed a staff to be credited for only ONE requirement, so the spell route demanded a bank
 * withdraw. One combo staff now covers every element it provides; two DIFFERENT staves still do
 * not combine (only one is wielded per cast).
 */
@RunWith(MockitoJUnitRunner.class)
public class StaffComboPossessionTest
{
	@Mock
	Client client;
	@Mock
	ItemContainer inventory;
	@Mock
	ItemContainer equipment;
	@Mock
	ShortestPathConfig config;

	@Before
	public void before()
	{
		when(config.calculationCutoff()).thenReturn(30);
		when(config.currencyThreshold()).thenReturn(10000000);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.NONE);
		lenient().when(config.useTeleportationSpells()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(i -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
		lenient().when(client.getItemContainer(InventoryID.WORN)).thenReturn(equipment);
	}

	private MethodAvailability civitasSpell(Item[] inventoryItems, Item[] wornItems)
	{
		doReturn(inventoryItems).when(inventory).getItems();
		doReturn(wornItems).when(equipment).getItems();
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		Map<TeleportMethod, MethodAvailability> catalog = planning.getMethodAvailability();
		for (Map.Entry<TeleportMethod, MethodAvailability> entry : catalog.entrySet())
		{
			if (entry.getKey().getType() == TransportType.TELEPORTATION_SPELL
				&& "Civitas illa Fortis Teleport".equals(entry.getKey().getDisplayInfo()))
			{
				return entry.getValue();
			}
		}
		assertNotNull("the Civitas spell must be in the catalog", null);
		return null;
	}

	@Test
	public void aComboStaffCoversBothItsElements()
	{
		MethodAvailability status = civitasSpell(
			new Item[]{new Item(ItemID.LAWRUNE, 100)},
			new Item[]{new Item(ItemID.MYSTIC_LAVA_STAFF_PRETTY, 1)});
		assertEquals("a wielded pretty lava staff provides earth AND fire at once",
			MethodAvailability.AVAILABLE, status);
	}

	@Test
	public void thePlainOrVariantCountsToo()
	{
		MethodAvailability status = civitasSpell(
			new Item[]{new Item(ItemID.LAWRUNE, 100)},
			new Item[]{new Item(ItemID.LAVA_BATTLESTAFF_PRETTY, 1)});
		assertEquals(MethodAvailability.AVAILABLE, status);
	}

	@Test
	public void twoDifferentStavesDoNotCombine()
	{
		// A staff of earth worn plus a staff of fire in the pack: only one can be wielded for the
		// cast, so earth+fire must NOT both be credited.
		MethodAvailability status = civitasSpell(
			new Item[]{new Item(ItemID.LAWRUNE, 100), new Item(ItemID.STAFF_OF_FIRE, 1)},
			new Item[]{new Item(ItemID.STAFF_OF_EARTH, 1)});
		assertTrue("two separate staves must not satisfy a single cast: " + status,
			status != MethodAvailability.AVAILABLE);
	}
}
