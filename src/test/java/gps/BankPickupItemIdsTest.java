package gps;

import gps.pathfinder.PathStep;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.BankPickupRequirements;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.QuestState;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The bank pickup names what the rest of the route needs from the bank, and now also says which
 * bank slots hold it, for the bank highlight: the ids of the items actually in the bank, so a
 * Games necklace(7) lights up even though the phrase names the canonical necklace(8).
 */
public class BankPickupItemIdsTest
{
	private static final int CAMULET_LANDING = WorldPointUtil.packWorldPoint(3106, 9315, 2);
	private static final int BURTHORPE = WorldPointUtil.packWorldPoint(2899, 3553, 0);

	@Test
	public void thePickupListsTheBankSlotsOfEveryItemTheRouteStillNeeds()
	{
		Client client = client();
		ShortestPathConfig config = Mockito.mock(ShortestPathConfig.class, invocation ->
		{
			String name = invocation.getMethod().getName();
			Class<?> type = invocation.getMethod().getReturnType();
			if (type == boolean.class)
			{
				return !"avoidWilderness".equals(name);
			}
			if (type == int.class)
			{
				return "calculationCutoff".equals(name) ? 120 : 0;
			}
			if (type == TeleportationItem.class)
			{
				return TeleportationItem.INVENTORY_AND_BANK;
			}
			return HybridPageFillTest.defaultValue(type);
		});
		PathfinderConfig owned = new TestPathfinderConfig(client, config, QuestState.FINISHED, true, true);
		Item[] bank = {new Item(ItemID.CAMULET, 1), new Item(ItemID.NECKLACE_OF_MINIGAMES_7, 1)};
		owned.setBankSnapshot(bank);
		owned.refresh();

		Set<Integer> banks = owned.getDestinations("bank");
		int bankTile = banks.iterator().next();
		List<PathStep> path = List.of(
			new PathStep(bankTile, false, 0),
			new PathStep(CAMULET_LANDING, true, 4),
			new PathStep(BURTHORPE, true, 8));

		BankPickupRequirements.Pickup pickup = BankPickupRequirements.compute(client, bank, owned, banks, path, 0);

		assertEquals("one phrase per item step", 2, pickup.phrases.size());
		assertEquals("the phrase names the canonical necklace", List.of("1 Item " + ItemID.CAMULET, "1 Item " + ItemID.NECKLACE_OF_MINIGAMES_8),
			pickup.phrases);
		assertEquals("the slots to light: the Camulet and the necklace the bank actually holds",
			Set.of(ItemID.CAMULET, ItemID.NECKLACE_OF_MINIGAMES_7), pickup.itemIds);
		assertFalse("the canonical necklace is a name, not a slot", pickup.itemIds.contains(ItemID.NECKLACE_OF_MINIGAMES_8));
	}

	@Test
	public void offTheBankTileThereIsNothingToPickUp()
	{
		Client client = client();
		PathfinderConfig owned = new TestPathfinderConfig(client, Mockito.mock(ShortestPathConfig.class));
		Item[] bank = {new Item(ItemID.CAMULET, 1)};
		List<PathStep> path = List.of(new PathStep(CAMULET_LANDING, true, 0), new PathStep(BURTHORPE, true, 4));
		BankPickupRequirements.Pickup pickup = BankPickupRequirements.compute(client, bank, owned, Set.of(), path, 0);
		assertTrue(pickup.phrases.isEmpty());
		assertTrue(pickup.itemIds.isEmpty());
	}

	/** A logged-in client with nothing on the player; item names read "Item <id>". */
	private static Client client()
	{
		final Thread clientThread = Thread.currentThread();
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getGameState":
						return GameState.LOGGED_IN;
					case "getClientThread":
						return clientThread;
					case "getBoostedSkillLevel":
						return 99;
					case "getItemDefinition":
						ItemComposition item = Mockito.mock(ItemComposition.class);
						Mockito.when(item.getName()).thenReturn("Item " + args[0]);
						return item;
					default:
						return HybridPageFillTest.defaultValue(method.getReturnType());
				}
			});
	}
}
