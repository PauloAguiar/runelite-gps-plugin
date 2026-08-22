package gps;

import gps.pathfinder.PathStep;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.Transport;
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
import net.runelite.client.callback.ClientThread;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Issues #20 / #21 / #7 — the free-bank family, replayed from the reporter's snapshots: start
 * 2726,3486 (Camelot), target 2950,2902 inside the Kharazi jungle, bank pickup cost 0, machete
 * ONLY in the bank. The jungle bushes are plain connectors (never on the card), so the route
 * used to thread them after a free bank pass-through and show no withdraw at all.
 */
@RunWith(MockitoJUnitRunner.class)
public class FreeBankFamilyTest
{
	private static final int CAMELOT = WorldPointUtil.packWorldPoint(2726, 3486, 0);
	private static final int KHARAZI = WorldPointUtil.packWorldPoint(2950, 2902, 0);
	/** A Seers' Village bank standing tile (bank.tsv) and the Royal seed pod's landing tile. */
	private static final int SEERS_BANK = WorldPointUtil.packWorldPoint(2722, 3493, 0);
	private static final int GRAND_TREE = WorldPointUtil.packWorldPoint(2465, 3495, 0);
	private static final int ROYAL_SEED_POD = 19564;

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

	private AlternativeRoutesService service;

	@Before
	public void before()
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(config.currencyThreshold()).thenReturn(10000000);
		when(config.costBankPickup()).thenReturn(0);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.INVENTORY_AND_BANK);
		// The reporter crossed to Karamja by ship (Ardougne -> Brimhaven, 30gp): ships on, coins in hand.
		lenient().when(config.useShips()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(i -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		// Legends' Quest underway (varp 139 > 49): the bushes' quest gate, so only the machete decides.
		lenient().when(client.getVarpValue(139)).thenReturn(75);
		doReturn(new Item[]{new Item(ItemID.COINS, 10000)}).when(inventory).getItems();
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
		doAnswer(invocation ->
		{
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));
	}

	@After
	public void after()
	{
		if (service != null)
		{
			service.shutdown();
		}
	}

	private List<RouteOption> generate(int start, int target, Item[] bankItems) throws Exception
	{
		doReturn(bankItems).when(bank).getItems();
		TestPathfinderConfig base = new TestPathfinderConfig(client, config);
		base.bank = bank;
		base.setBankSnapshot(bankItems);
		PathfinderConfig planning = base.copyForPlanning();
		planning.refresh();
		service = new AlternativeRoutesService(clientThread, planning);
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(start, Set.of(target), Set.of(), AlternativeRoutesMode.OWNED_WITH_BANK, 10, 3, false,
			(routes, catalog, unavailable, done) ->
			{
				if (done)
				{
					out.set(routes);
					latch.countDown();
				}
			});
		assertTrue("generation must finish", latch.await(120, TimeUnit.SECONDS));
		return out.get();
	}

	private static boolean usesBushes(RouteOption route)
	{
		for (Transport transport : route.getBankTransports())
		{
			if (transport.getObjectInfo() != null && transport.getObjectInfo().contains("Jungle Bush"))
			{
				return true;
			}
		}
		return false;
	}

	@Test
	public void bankedMacheteMeansAWithdrawStepNamesIt()
	{
		List<RouteOption> routes;
		try
		{
			routes = generate(CAMELOT, KHARAZI, new Item[]{new Item(ItemID.MACHETTE, 1)});
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
		boolean reached = false;
		for (RouteOption route : routes)
		{
			if (!route.isReached())
			{
				continue;
			}
			reached = true;
			// Any route into the jungle cut bushes, and the only machete is in the bank: the route
			// MUST be flagged as a bank route with the bushes as its bank-gated connectors.
			assertTrue("a jungle route must declare its bank stop: " + route.getMethods(), route.isViaBank());
			assertTrue("the withdraw must be FOR the bushes (the machete)", usesBushes(route));
		}
		StringBuilder dump = new StringBuilder();
		for (RouteOption route : routes)
		{
			int last = route.getPath().get(route.getPath().size() - 1).getPackedPosition();
			dump.append("\n  cost ").append(route.getTotalCost()).append(" reached ").append(route.isReached())
				.append(" viaBank ").append(route.isViaBank()).append(" steps ").append(route.getPath().size())
				.append(" end ").append(WorldPointUtil.unpackWorldX(last)).append(',').append(WorldPointUtil.unpackWorldY(last))
				.append(" methods ").append(route.getMethods()).append(" bankTransports ").append(route.getBankTransports().size());
			List<PathStep> path = route.getPath();
			for (int i = 1; i < path.size(); i++)
			{
				int a = path.get(i - 1).getPackedPosition(), b = path.get(i).getPackedPosition();
				if (Math.abs(WorldPointUtil.unpackWorldX(a) - WorldPointUtil.unpackWorldX(b)) > 1
					|| Math.abs(WorldPointUtil.unpackWorldY(a) - WorldPointUtil.unpackWorldY(b)) > 1
					|| WorldPointUtil.unpackWorldPlane(a) != WorldPointUtil.unpackWorldPlane(b))
				{
					dump.append(" jump@").append(i).append(' ').append(WorldPointUtil.unpackWorldX(a)).append(',').append(WorldPointUtil.unpackWorldY(a))
						.append("->").append(WorldPointUtil.unpackWorldX(b)).append(',').append(WorldPointUtil.unpackWorldY(b));
				}
			}
		}
		assertTrue("with a banked machete the jungle interior is reachable; routes:" + dump, reached);
	}

	@Test
	public void noMacheteAnywhereMeansNoJungle()
	{
		List<RouteOption> routes;
		try
		{
			routes = generate(CAMELOT, KHARAZI, new Item[0]);
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
		for (RouteOption route : routes)
		{
			assertFalse("without a machete the bushes must not be cut: " + route.getMethods(), route.isReached());
		}
	}
	/**
	 * Issues #20 / #7 — the idle flip. Standing ON a Seers' bank tile with the pod in hand (and
	 * another in the bank), bank pickup 0: the route is "Royal seed pod", full stop. It must not
	 * carry a banked step (the overlay reads one as a withdrawal of an item already in hand), must
	 * not be a bank route, and no "Bank -> same method" twin may sit beside it.
	 */
	@Test
	public void podInHandNeverFlipsTheBankNorSpawnsATwin()
	{
		doReturn(new Item[]{new Item(ItemID.COINS, 10000), new Item(ROYAL_SEED_POD, 1)}).when(inventory).getItems();
		List<RouteOption> routes;
		try
		{
			routes = generate(SEERS_BANK, GRAND_TREE, new Item[]{new Item(ROYAL_SEED_POD, 1)});
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
		assertFalse("routes expected", routes.isEmpty());
		Set<List<TeleportMethod>> seen = new HashSet<>();
		boolean pod = false;
		for (RouteOption route : routes)
		{
			assertFalse("no route may be a bank route: " + route.getMethods(), route.isViaBank());
			for (PathStep step : route.getPath())
			{
				assertFalse("no step may be banked (idle flip): " + route.getMethods(), step.isBankVisited());
			}
			assertTrue("twin route, same methods twice: " + route.getMethods(), seen.add(route.getMethods()));
			for (TeleportMethod method : route.getMethods())
			{
				pod |= method.label().contains("Royal seed pod");
			}
		}
		assertTrue("the pod route is expected", pod);
	}
}
