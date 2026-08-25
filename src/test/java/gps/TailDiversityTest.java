package gps;

import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.TransportType;
import java.util.HashSet;
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
import net.runelite.api.gameval.VarbitID;
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
 * Capture 20260824-183101 replayed: with the Dramen staff only in the bank, every route was
 * "some bank-adjacent teleport, bank, Salve graveyard tablet, fairy ring D L Q" — ten first-leg
 * variants of one tail, while "bank, Ardougne cloak to the Kandarin monastery, its fairy ring"
 * could never appear: the chain's exclusions accumulate on PRIMARIES, and the cloak variant
 * shares its primary with a kept route. The tail-diversity pass runs a fresh search with the
 * shared tail method excluded (primaries restored) and gives the cloak variant a slot.
 */
@RunWith(MockitoJUnitRunner.class)
public class TailDiversityTest
{
	private static final int START = WorldPointUtil.packWorldPoint(2206, 3156, 0);
	private static final int TARGET = WorldPointUtil.packWorldPoint(3350, 2966, 0);
	private static final int SALVE_GRAVEYARD_TABLET = 19619;

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
	public void dominatedPageGainsADifferentMiddleLeg() throws Exception
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(config.currencyThreshold()).thenReturn(10000000);
		lenient().when(config.costBankPickup()).thenReturn(15);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.INVENTORY);
		lenient().when(config.useFairyRings()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(i -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		// The fairy network's quest overlay (routing-level disableUnless) and the cloak's
		// monastery unlock; the Lumbridge elite diary stays 0 so the ring NEEDS the banked staff.
		lenient().when(client.getVarbitValue(VarbitID.FAIRY2_QUEENCURE_QUEST)).thenReturn(100);
		lenient().when(client.getVarbitValue(4458)).thenReturn(1);
		doReturn(new Item[]{
			new Item(SALVE_GRAVEYARD_TABLET, 10),
			new Item(ItemID.ARDY_CAPE_HARD, 1),
			new Item(ItemID.NECKLACE_OF_MINIGAMES_8, 1)}).when(inventory).getItems();
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
		doAnswer(invocation ->
		{
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));
		doReturn(new Item[]{new Item(ItemID.DRAMEN_STAFF, 1)}).when(bank).getItems();

		TestPathfinderConfig base = new TestPathfinderConfig(client, config);
		base.bank = bank;
		base.setBankSnapshot(new Item[]{new Item(ItemID.DRAMEN_STAFF, 1)});
		PathfinderConfig planning = base.copyForPlanning();
		planning.refresh();
		AlternativeRoutesService service = new AlternativeRoutesService(clientThread, planning);
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(START, Set.of(TARGET), Set.of(), AlternativeRoutesMode.OWNED_WITH_BANK,
			10, 3, false,
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
		Set<String> tails = new HashSet<>();
		boolean cloakVariant = false;
		StringBuilder dump = new StringBuilder();
		for (RouteOption route : routes)
		{
			dump.append("\n  cost ").append(route.getTotalCost()).append(' ').append(route.getMethods());
			List<TeleportMethod> methods = route.getMethods();
			if (methods.size() >= 2)
			{
				StringBuilder tail = new StringBuilder();
				for (int i = 1; i < methods.size(); i++)
				{
					tail.append(methods.get(i)).append('|');
				}
				tails.add(tail.toString());
			}
			for (TeleportMethod method : methods)
			{
				cloakVariant |= TransportType.TELEPORTATION_ITEM.equals(method.getType())
					&& method.getDisplayInfo() != null
					&& method.getDisplayInfo().contains("Kandarin Monastery");
			}
		}
		assertTrue("the page must carry at least two distinct method tails; routes:" + dump,
			tails.size() >= 2);
		assertTrue("the Ardougne cloak middle-leg variant must surface; routes:" + dump, cloakVariant);
	}
}
