package gps;

import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import java.util.List;
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
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Probe (-Dgps.civitasProbe=true): capture 20260823-215617's question — where the Civitas illa
 * Fortis Teleport spell route lands with the runes in the inventory vs only in the bank, on the
 * fixed (junk-filtered) build. GE -> Zul-Andra, Owned inventory+bank.
 */
@RunWith(MockitoJUnitRunner.class)
public class CivitasSpellProbeTest
{
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

	private void run(String label, Item[] inventoryItems, Item[] bankItems) throws Exception
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(config.currencyThreshold()).thenReturn(10000000);
		lenient().when(config.costBankPickup()).thenReturn(15);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.INVENTORY_NON_CONSUMABLE);
		lenient().when(config.useQuetzals()).thenReturn(true);
		lenient().when(config.useCharterShips()).thenReturn(true);
		lenient().when(config.useTeleportationSpells()).thenReturn(true);
		lenient().when(config.useTeleportationMinigames()).thenReturn(true);
		lenient().when(config.useShips()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(i -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		doReturn(inventoryItems).when(inventory).getItems();
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
		doAnswer(invocation ->
		{
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		doReturn(bankItems).when(bank).getItems();
		TestPathfinderConfig base = new TestPathfinderConfig(client, config);
		base.bank = bank;
		base.setBankSnapshot(bankItems);
		PathfinderConfig planning = base.copyForPlanning();
		planning.refresh();
		AlternativeRoutesService service = new AlternativeRoutesService(clientThread, planning);
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(WorldPointUtil.packWorldPoint(3162, 3486, 0),
			Set.of(WorldPointUtil.packWorldPoint(2207, 3159, 0)), Set.of(),
			AlternativeRoutesMode.OWNED_WITH_BANK, 10, 3, false,
			(routes, catalog, unavailable, done) ->
			{
				if (done)
				{
					out.set(routes);
					latch.countDown();
				}
			});
		latch.await(120, TimeUnit.SECONDS);
		service.shutdown();
		StringBuilder sb = new StringBuilder("\nPROBE civitas — " + label + "\n");
		planning.getMethodAvailability().entrySet().stream()
			.filter(e -> e.getKey().getDisplayInfo() != null
				&& e.getKey().getDisplayInfo().contains("Civitas illa Fortis Teleport"))
			.forEach(e -> sb.append("  catalog: ").append(e.getKey().label()).append(" = ").append(e.getValue())
				.append(" detail=").append(planning.getMethodAvailabilityDetail().get(e.getKey())).append("\n"));
		for (AlternativeRoutesService.SearchRecord record : service.getLastSearchRecords())
		{
			sb.append("  search: ").append(record.label).append(" cost=").append(record.resultCost)
				.append(" reached=").append(record.reached).append(" term=").append(record.termination)
				.append("\n");
		}
		for (RouteOption route : out.get())
		{
			sb.append(String.format("  cost %d viaBank=%s %s%n",
				route.getTotalCost(), route.isViaBank(), route.getMethods()));
		}
		System.out.println(sb);
	}

	@Test
	public void whereTheSpellLands() throws Exception
	{
		Assume.assumeTrue(Boolean.getBoolean("gps.civitasProbe"));
		Item whistle = new Item(ItemID.HG_QUETZALWHISTLE_BASIC, 1);
		Item coins = new Item(ItemID.COINS, 100000);
		Item earth = new Item(ItemID.EARTHRUNE, 100);
		Item fire = new Item(ItemID.FIRERUNE, 100);
		Item law = new Item(ItemID.LAWRUNE, 100);
		run("runes IN INVENTORY", new Item[]{whistle, coins, earth, fire, law}, new Item[0]);
		run("runes ONLY IN BANK", new Item[]{whistle, coins}, new Item[]{earth, fire, law});
		run("no runes anywhere", new Item[]{whistle, coins}, new Item[0]);
	}
}
