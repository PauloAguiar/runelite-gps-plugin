package gps;

import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Capture 20260827-191635: south of Ardougne with the fare only in the bank, all ten routes were
 * "teleport to some bank, Ardougne Teleport back, Brimhaven ship" - prefix twins of one tail -
 * while walking to the adjacent south bank sat at rank 11. A saturated tail (TAIL_DOMINANCE kept
 * routes) now rejects further prefix twins, so the walk-to-bank continuation makes the page.
 */
@RunWith(MockitoJUnitRunner.class)
public class SameTailPrefixCapTest
{
	private static final int START = WorldPointUtil.packWorldPoint(2664, 3286, 0);
	private static final int MOSS_GIANT_ISLAND = WorldPointUtil.packWorldPoint(2705, 3205, 0);

	@Mock
	Client client;
	@Mock
	ItemContainer inventory;
	@Mock
	ItemContainer bank;
	@Mock
	ShortestPathConfig config;
	@Mock
	ClientThread clientThread;

	@Test
	public void saturatedTailLeavesRoomForTheWalkToBankRoute() throws Exception
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(config.currencyThreshold()).thenReturn(10000000);
		lenient().when(config.costBankPickup()).thenReturn(15);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.INVENTORY_NON_CONSUMABLE);
		lenient().when(config.useShips()).thenReturn(true);
		lenient().when(config.useAgilityShortcuts()).thenReturn(true);
		lenient().when(config.useTeleportationSpells()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(i -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		// Runes for every standard teleport: plenty of bank-adjacent prefixes to spawn twins.
		doReturn(new Item[]{
			new Item(ItemID.AIRRUNE, 1000), new Item(ItemID.WATERRUNE, 1000),
			new Item(ItemID.EARTHRUNE, 1000), new Item(ItemID.FIRERUNE, 1000),
			new Item(ItemID.LAWRUNE, 1000)}).when(inventory).getItems();
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
		doAnswer(invocation ->
		{
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		Item[] bankItems = {new Item(ItemID.COINS, 10000)};
		doReturn(bankItems).when(bank).getItems();
		TestPathfinderConfig base = new TestPathfinderConfig(client, config);
		base.bank = bank;
		base.setBankSnapshot(bankItems);
		PathfinderConfig planning = base.copyForPlanning();
		planning.refresh();
		AlternativeRoutesService service = new AlternativeRoutesService(clientThread, planning);
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(START, Set.of(MOSS_GIANT_ISLAND), Set.of(),
			AlternativeRoutesMode.OWNED_WITH_BANK, 10, 3, false,
			(routes, catalog, unavailable, done) ->
			{
				if (done)
				{
					out.set(routes);
					latch.countDown();
				}
			});
		assertTrue("generation must finish", latch.await(120, TimeUnit.SECONDS));
		service.shutdown();
		List<RouteOption> routes = out.get();
		assertTrue("routes expected", routes != null && !routes.isEmpty());

		Map<String, Integer> tails = new HashMap<>();
		boolean walkToBankShip = false;
		StringBuilder dump = new StringBuilder();
		for (RouteOption route : routes)
		{
			List<TeleportMethod> methods = route.getMethods();
			dump.append("\n  cost ").append(route.getTotalCost()).append(' ').append(methods);
			if (methods.size() >= 2)
			{
				String tail = methods.subList(1, methods.size()).toString();
				tails.merge(tail, 1, Integer::sum);
			}
			// The adjacent-bank continuation: no teleport prefix, just the ship (via the bank).
			walkToBankShip |= route.isViaBank() && methods.size() == 1
				&& methods.get(0).getType() == gps.transport.TransportType.SHIP;
		}
		for (Map.Entry<String, Integer> entry : tails.entrySet())
		{
			assertTrue("a saturated tail must stop collecting prefix twins: "
				+ entry.getKey() + " x" + entry.getValue() + dump, entry.getValue() <= 3);
		}
		assertTrue("the walk-to-the-adjacent-bank ship route must make the page:" + dump, walkToBankShip);
	}
}
