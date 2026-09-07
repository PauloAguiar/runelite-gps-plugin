package gps.transport;

import gps.ShortestPathConfig;
import gps.TeleportationItem;
import gps.WorldPointUtil;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Capture 20260907-114507: the crew charged half the row's fare. Cabin Fever halves every
 * charter fare and the Ring of charos (a) halves again (a quarter with both); the rows carry base
 * fares and PathfinderConfig scales both the possession check and the displayed fare.
 */
@RunWith(MockitoJUnitRunner.class)
public class CharterFareDiscountTest
{
	private static final int PORT_SARIM_CREW = WorldPointUtil.packWorldPoint(3038, 3192, 0);
	private static final int PORT_TYRAS_DECK = WorldPointUtil.packWorldPoint(2142, 3125, 1);
	private static final int BASE_FARE = 3200;

	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;
	@Mock
	ItemContainer inventory;
	@Mock
	ItemContainer equipment;

	@Before
	public void stubs()
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(config.currencyThreshold()).thenReturn(10000000);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.INVENTORY);
		lenient().when(config.useCharterShips()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(i -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
		lenient().when(client.getItemContainer(InventoryID.WORN)).thenReturn(equipment);
		lenient().when(equipment.getItems()).thenReturn(new Item[0]);
	}

	private Transport portSarimToTyras(PathfinderConfig pathConfig)
	{
		Transport[] fromCrew = pathConfig.getTransportsPacked(false).get(PORT_SARIM_CREW);
		if (fromCrew != null)
		{
			for (Transport transport : fromCrew)
			{
				if (transport.getDestination() == PORT_TYRAS_DECK && TransportType.CHARTER_SHIP.equals(transport.getType()))
				{
					return transport;
				}
			}
		}
		return null;
	}

	@Test
	public void cabinFeverHalvesTheFare()
	{
		when(inventory.getItems()).thenReturn(new Item[]{new Item(ItemID.COINS, BASE_FARE / 2)});
		PathfinderConfig done = new TestPathfinderConfig(client, config, QuestState.FINISHED, true, true);
		done.refresh();
		Transport charter = portSarimToTyras(done);
		assertTrue("half the base fare boards after Cabin Fever", charter != null);
		assertEquals(50, done.getCharterFarePercent());
		assertEquals("the label shows what the crew charges", BASE_FARE / 2, done.effectiveCoinFare(charter));
	}

	@Test
	public void withoutCabinFeverTheBaseFareApplies()
	{
		when(inventory.getItems()).thenReturn(new Item[]{new Item(ItemID.COINS, BASE_FARE / 2)});
		PathfinderConfig notDone = new TestPathfinderConfig(client, config, QuestState.NOT_STARTED, true, true);
		notDone.refresh();
		assertEquals(100, notDone.getCharterFarePercent());
		assertFalse("half the base fare does not board without the discount", portSarimToTyras(notDone) != null);
	}

	@Test
	public void ringOfCharosStacksToAQuarter()
	{
		when(inventory.getItems()).thenReturn(new Item[]{new Item(ItemID.COINS, BASE_FARE / 4)});
		when(equipment.getItems()).thenReturn(new Item[]{new Item(ItemID.RING_OF_CHAROS_UNLOCKED, 1)});
		when(equipment.contains(ItemID.RING_OF_CHAROS_UNLOCKED)).thenReturn(true);
		PathfinderConfig done = new TestPathfinderConfig(client, config, QuestState.FINISHED, true, true);
		done.refresh();
		assertEquals(25, done.getCharterFarePercent());
		assertTrue("a quarter of the base fare boards with both discounts", portSarimToTyras(done) != null);
	}
}
