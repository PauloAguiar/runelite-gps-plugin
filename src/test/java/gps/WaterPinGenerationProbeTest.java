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
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/** PRODUCTION-PATH measurement: real generate(), real diagnostics, three pin classes. */
@RunWith(MockitoJUnitRunner.Silent.class)
public class WaterPinGenerationProbeTest
{
	private static final int START = WorldPointUtil.packWorldPoint(3222, 3218, 0);

	@Mock
	Client client;
	@Mock
	ClientThread clientThread;
	@Mock
	ItemContainer inventory;
	@Mock
	ShortestPathConfig config;

	@Before
	public void before()
	{
		when(config.calculationCutoff()).thenReturn(30);
		when(config.currencyThreshold()).thenReturn(10000000);
		when(config.useTeleportationSpells()).thenReturn(true);
		when(config.useSailing()).thenReturn(true);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.NONE);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(invocation -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		doReturn(new Item[]{
			new Item(ItemID.LAWRUNE, 10),
			new Item(ItemID.AIRRUNE, 30),
			new Item(ItemID.FIRERUNE, 10),
		}).when(inventory).getItems();
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
		doAnswer(invocation ->
		{
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));
	}

	private void scenario(String label, int pin) throws Exception
	{
		PathfinderConfig planning = new TestPathfinderConfig(client, config)
			.copyForPlanning();
		AlternativeRoutesService service = new AlternativeRoutesService(clientThread, planning);
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>(List.of());
		long start = System.nanoTime();
		service.generate(START, Set.of(pin), Set.of(), AlternativeRoutesMode.OWNED_INVENTORY, 10,
			(routes, catalog, unavailable, isDone) ->
			{
				if (isDone)
				{
					out.set(routes);
					done.countDown();
				}
			});
		done.await(180, TimeUnit.SECONDS);
		long wallMs = (System.nanoTime() - start) / 1_000_000;
		service.shutdown();
		System.out.println("=== " + label + " (" + WorldPointUtil.unpackWorldX(pin) + ","
			+ WorldPointUtil.unpackWorldY(pin) + ") sailable=" + SailingSea.isSailable(pin)
			+ " wallMs=" + wallMs + " routes=" + out.get().size());
		long cpu = 0;
		int blind = 0, guided = 0;
		for (AlternativeRoutesService.SearchRecord r : service.getLastSearchRecords())
		{
			cpu += r.cpuMs;
			if (r.astar)
			{
				guided++;
			}
			else
			{
				blind++;
			}
		}
		System.out.println("  searches guided=" + guided + " blind=" + blind
			+ " totalCpuMs=" + cpu);
		for (AlternativeRoutesService.SearchRecord r : service.getLastSearchRecords())
		{
			System.out.println("    " + (r.astar ? "A*" : "DIJK") + " nodes=" + r.nodesChecked
				+ " cpuMs=" + r.cpuMs + " " + r.label);
		}
	}

	@Test
	public void measure() throws Exception
	{
		scenario("LAND Dognose", WorldPointUtil.packWorldPoint(3048, 2648, 0));
		scenario("WATER off Dognose", WorldPointUtil.packWorldPoint(3036, 2652, 0));
		scenario("WATER mid-ocean", WorldPointUtil.packWorldPoint(2894, 2637, 0));
	}
}
