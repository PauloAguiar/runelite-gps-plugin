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
import net.runelite.api.Skill;
import net.runelite.client.callback.ClientThread;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Plan step N12 (honest states): a page whose routes all stop short said only "Destination can't
 * be reached", whatever the reason. The generator now tells two causes apart: the target is
 * reachable with everything the game offers but not with this mode's items and unlocks, or no
 * known route exists at all (a sealed tile, or a gap in the map data). The panel words each.
 */
@RunWith(MockitoJUnitRunner.class)
public class UnreachableCauseTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;
	@Mock
	ClientThread clientThread;

	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int VARROCK = WorldPointUtil.packWorldPoint(3212, 3422, 0);
	/** The Broken Raft deck: sealed in every mode. */
	private static final int RAFT_DECK = WorldPointUtil.packWorldPoint(3253, 3180, 0);
	/** Mos Le'Harmless charter landing: charter ships only, which need coins the inventory lacks. */
	private static final int MOS_LE_HARMLESS = WorldPointUtil.packWorldPoint(3668, 2931, 1);

	@Test
	public void theCauseTellsMissingUnlocksFromNoKnownRoute() throws Exception
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(config.currencyThreshold()).thenReturn(10000000);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.ALL);
		lenient().when(config.useCharterShips()).thenReturn(true);
		lenient().when(config.useTeleportationSpells()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(i -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		doAnswer(invocation ->
		{
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		AlternativeRoutesService service = new AlternativeRoutesService(clientThread, planning);
		try
		{
			generate(service, VARROCK, AlternativeRoutesMode.OWNED_INVENTORY);
			assertEquals("a reached target has no cause", AlternativeRoutesService.UnreachableCause.NONE, service.lastUnreachableCause());

			generate(service, MOS_LE_HARMLESS, AlternativeRoutesMode.OWNED_INVENTORY);
			assertEquals("reachable with everything, not with an empty inventory",
				AlternativeRoutesService.UnreachableCause.MISSING_UNLOCKS, service.lastUnreachableCause());

			generate(service, RAFT_DECK, AlternativeRoutesMode.OWNED_INVENTORY);
			assertEquals("sealed everywhere", AlternativeRoutesService.UnreachableCause.NO_KNOWN_ROUTE, service.lastUnreachableCause());

			generate(service, RAFT_DECK, AlternativeRoutesMode.ALL_EVERYTHING);
			assertEquals("the all mode already includes everything: no probe needed",
				AlternativeRoutesService.UnreachableCause.NO_KNOWN_ROUTE, service.lastUnreachableCause());
		}
		finally
		{
			service.shutdown();
		}
	}

	private static void generate(AlternativeRoutesService service, int target, AlternativeRoutesMode mode) throws Exception
	{
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(LUMBRIDGE, Set.of(target), Set.of(), mode, 10, 3, false,
			(routes, catalog, unavailable, done) ->
			{
				if (done)
				{
					out.set(routes);
					latch.countDown();
				}
			});
		assertTrue("generation must finish", latch.await(180, TimeUnit.SECONDS));
		assertTrue("routes expected", out.get() != null && !out.get().isEmpty());
	}
}
