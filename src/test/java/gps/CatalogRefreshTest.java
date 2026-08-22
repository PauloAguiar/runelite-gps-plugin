package gps;

import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.TransportType;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * Issue #5: the catalog's usable count and per-method reasons were a per-generation snapshot,
 * so picking up an item changed nothing until the next route computation. The service's
 * catalog-only refresh must re-classify from the live inventory — the Cowbell amulet is
 * "missing item" with an empty inventory and usable once it is carried — without generating
 * a single route.
 */
@RunWith(MockitoJUnitRunner.class)
public class CatalogRefreshTest
{
	private static final int COWBELL_DESTINATION = WorldPointUtil.packWorldPoint(3259, 3277, 0);
	private static final int COWBELL_AMULET = 33104;

	@Mock
	Client client;
	@Mock
	ItemContainer inventory;
	@Mock
	ShortestPathConfig config;
	@Mock
	ClientThread clientThread;

	private AlternativeRoutesService service;

	@Before
	public void before()
	{
		when(config.calculationCutoff()).thenReturn(30);
		when(config.currencyThreshold()).thenReturn(10000000);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		// Whichever thread asks is the client thread: the service refreshes on its executor,
		// the test on its own thread, and the config guards client-thread-only reads.
		when(client.getClientThread()).thenAnswer(i -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.NONE);
		doReturn(new Item[0]).when(inventory).getItems();
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
		doAnswer(invocation ->
		{
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		service = new AlternativeRoutesService(clientThread, planning);
	}

	@After
	public void after()
	{
		service.shutdown();
	}

	private Map<TeleportMethod, MethodAvailability> refresh() throws Exception
	{
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<Map<TeleportMethod, MethodAvailability>> out = new AtomicReference<>();
		service.refreshCatalog(AlternativeRoutesMode.OWNED_INVENTORY, (catalog, unavailable) ->
		{
			out.set(unavailable);
			latch.countDown();
		});
		assertTrue("catalog refresh must finish", latch.await(60, TimeUnit.SECONDS));
		return out.get();
	}

	private static TeleportMethod cowbell(Map<TeleportMethod, MethodAvailability> unavailable)
	{
		return unavailable.keySet().stream()
			.filter(m -> m.getType() == TransportType.TELEPORTATION_ITEM
				&& m.getDestination() == COWBELL_DESTINATION)
			.findFirst().orElse(null);
	}

	@Test
	public void pickingUpTheItemFlipsTheCatalogWithoutAGeneration() throws Exception
	{
		Map<TeleportMethod, MethodAvailability> before = refresh();
		TeleportMethod method = cowbell(before);
		assertTrue("empty inventory: the Cowbell amulet must be listed as not usable", method != null);
		assertEquals(MethodAvailability.MISSING_ITEM, before.get(method));

		doReturn(new Item[]{new Item(COWBELL_AMULET, 1)}).when(inventory).getItems();
		Map<TeleportMethod, MethodAvailability> after = refresh();
		assertTrue("carrying the amulet: the catalog refresh must drop it from the unavailable"
			+ " map without any route generation, still flagged " + after.get(method),
			!after.containsKey(method));
	}
}
