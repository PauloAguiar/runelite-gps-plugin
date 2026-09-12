package gps;

import com.google.gson.Gson;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The item-dependency gate (issues #23/#24 follow-up): inventory changes only mark the teleport
 * catalog dirty when they touch an item some transport requirement can actually read. Bulk
 * skilling traffic - logs, ore, food, loot - never schedules a refresh at all.
 */
@RunWith(MockitoJUnitRunner.class)
public class RoutingItemDependenciesTest
{
	private static RoutingItemDependencies deps;

	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	@BeforeClass
	public static void buildIndex()
	{
		Client client = mock(Client.class);
		ShortestPathConfig config = mock(ShortestPathConfig.class);
		deps = new TestPathfinderConfig(client, config).getRoutingItemDependencies();
	}

	private static ItemContainer container(Item... items)
	{
		ItemContainer container = mock(ItemContainer.class);
		when(container.getItems()).thenReturn(items);
		return container;
	}

	@Test
	public void bulkSkillingItemsAreIrrelevantTransportItemsAreNot()
	{
		assertTrue("the index must not be empty", deps.size() > 100);
		// Logs are NOT in this list: hot-air balloon flights burn them, and the index knows.
		assertTrue("logs gate balloon flights", deps.isRelevant(ItemID.LOGS));
		assertFalse("raw shrimps gate no transport", deps.isRelevant(ItemID.RAW_SHRIMP));
		assertFalse("iron ore gates no transport", deps.isRelevant(ItemID.IRON_ORE));
		assertTrue("dramen staff gates fairy rings", deps.isRelevant(ItemID.DRAMEN_STAFF));
		assertTrue("coins gate charters and fares", deps.isRelevant(ItemID.COINS));
		assertTrue("a rune pouch unlocks its runes for spell requirements",
			deps.isRelevant(ItemID.BH_RUNE_POUCH));
	}

	@Test
	public void fingerprintIgnoresIrrelevantTraffic()
	{
		ItemContainer worn = container();
		long before = deps.fingerprint(
			container(new Item(ItemID.RING_OF_DUELING_8, 1), new Item(ItemID.LOGS, 5)), worn);
		long after = deps.fingerprint(
			container(new Item(ItemID.RING_OF_DUELING_8, 1), new Item(ItemID.LOGS, 500),
				new Item(ItemID.RAW_SHRIMP, 20)), worn);
		assertEquals("chopping 495 more logs changes nothing a transport reads", before, after);
	}

	@Test
	public void fingerprintTracksRelevantChanges()
	{
		ItemContainer worn = container();
		long ringOnly = deps.fingerprint(container(new Item(ItemID.RING_OF_DUELING_8, 1)), worn);
		long ringAndNecklace = deps.fingerprint(
			container(new Item(ItemID.RING_OF_DUELING_8, 1), new Item(ItemID.NECKLACE_OF_MINIGAMES_8, 1)), worn);
		long bare = deps.fingerprint(container(), worn);
		assertNotEquals("gaining a games necklace matters", ringOnly, ringAndNecklace);
		assertNotEquals("losing the dueling ring matters", ringOnly, bare);
	}

	@Test
	public void wieldedVersusCarriedStaffDiffers()
	{
		long carried = deps.fingerprint(container(new Item(ItemID.DRAMEN_STAFF, 1)), container());
		long wielded = deps.fingerprint(container(), container(new Item(ItemID.DRAMEN_STAFF, 1)));
		assertNotEquals("possession checks credit wielded and carried differently", carried, wielded);
	}

	@Test
	public void currencyOnlyMattersAtFareThresholds()
	{
		ItemContainer worn = container();
		long none = deps.fingerprint(container(), worn);
		long some = deps.fingerprint(container(new Item(ItemID.COINS, 100)), worn);
		assertNotEquals("first coins cross the presence threshold", none, some);

		long rich = deps.fingerprint(container(new Item(ItemID.COINS, 1_000_000)), worn);
		long richer = deps.fingerprint(container(new Item(ItemID.COINS, 1_000_001)), worn);
		assertEquals("a coin pickup above every fare crosses no threshold", rich, richer);
	}

	/** End to end through the controller's item hook: only relevant changes arm the dirty flag. */
	@Test
	public void inventoryEventsWithoutRelevantChangesStayClean()
	{
		PathfinderConfig pathConfig = new TestPathfinderConfig(client, config);
		ShortestPathPlugin plugin = mock(ShortestPathPlugin.class);
		ConfigManager configManager = mock(ConfigManager.class);
		ChoiceStore choices = new ChoiceStore(() -> configManager, Gson::new, "gps");
		RouteController routes = new RouteController(plugin, new RouteSession(), new MethodExclusions(choices, () -> { }),
			choices, new PluginMessageBridge(plugin, () -> null));

		ItemContainer worn = container();
		ItemContainer inv = container(new Item(ItemID.RING_OF_DUELING_8, 1),
			new Item(ItemID.RAW_SHRIMP, 3), new Item(ItemID.LOGS, 5));
		// First event baselines the fingerprint (dirty once, panel-gated downstream anyway).
		assertTrue(routes.itemsChanged(pathConfig, inv, worn));
		assertTrue(routes.isCatalogDirty());

		// Fishing shrimp: the routing-relevant slice is unchanged, no refresh is ever scheduled.
		ItemContainer moreShrimp = container(new Item(ItemID.RING_OF_DUELING_8, 1),
			new Item(ItemID.RAW_SHRIMP, 22), new Item(ItemID.LOGS, 5));
		assertFalse("bulk skilling traffic must not dirty the catalog", routes.itemsChanged(pathConfig, moreShrimp, worn));

		// Equipping the dueling ring: relevant slice changed, the flag arms.
		ItemContainer invAfterEquip = container(new Item(ItemID.RAW_SHRIMP, 22), new Item(ItemID.LOGS, 5));
		ItemContainer wornAfterEquip = container(new Item(ItemID.RING_OF_DUELING_8, 1));
		assertTrue("a teleport item moving matters", routes.itemsChanged(pathConfig, invAfterEquip, wornAfterEquip));
		assertTrue("without a dependency index yet, every change counts", routes.itemsChanged(null, inv, worn));
	}

}
