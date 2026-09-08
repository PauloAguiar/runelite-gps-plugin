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

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Plan step N7: the "+ Bank" mode was excluded from the unreachable-target escape menu on the
 * theory that the distance field floods the inventory-only availability, so a target reachable
 * only through a banked item would look unreachable. The field floods BOTH bank states (reverse
 * transport index, teleport landings, blocked-landing patch), so a complete field that never
 * reaches the start is the same proof in every mode. Without the verdict, bank mode ran the full
 * chain against a sealed target: each iteration a blind flood of ~1.4M nodes.
 */
@RunWith(MockitoJUnitRunner.class)
public class BankModeUnreachableVerdictTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;
	@Mock
	ClientThread clientThread;

	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	/** The Broken Raft deck: a walkable tile on a sealed islet (field-verified not standable). */
	private static final int RAFT_DECK = WorldPointUtil.packWorldPoint(3253, 3180, 0);

	@Test
	public void bankModeGetsTheEscapeMenuForAProvablyUnreachableTarget() throws Exception
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(config.currencyThreshold()).thenReturn(10000000);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.ALL);
		lenient().when(config.useFairyRings()).thenReturn(true);
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
		List<AlternativeRoutesService.SearchRecord> records;
		List<RouteOption> routes;
		try
		{
			CountDownLatch latch = new CountDownLatch(1);
			AtomicReference<List<RouteOption>> out = new AtomicReference<>();
			service.generate(LUMBRIDGE, Set.of(RAFT_DECK), Set.of(), AlternativeRoutesMode.OWNED_WITH_BANK, 10, 3, false,
				(r, catalog, unavailable, done) ->
				{
					if (done)
					{
						out.set(r);
						latch.countDown();
					}
				});
			assertTrue("generation must finish", latch.await(180, TimeUnit.SECONDS));
			routes = out.get();
			records = service.getLastSearchRecords();
		}
		finally
		{
			service.shutdown();
		}
		long nodes = 0;
		for (AlternativeRoutesService.SearchRecord record : records)
		{
			nodes += Math.max(0, record.nodesChecked);
		}
		System.out.println("BANKVERDICT searches=" + records.size() + " nodes=" + nodes + " routes=" + routes.size());
		// With nothing in the inventory the closest approach is walk-only, so the escape menu is
		// one route; the verdict's value is skipping the walk, seed and tail passes (each a
		// ~2M-node flood against a sealed target). Without it bank mode ran the walk pass too.
		assertTrue("an escape menu, not an empty page", routes != null && !routes.isEmpty());
		StringBuilder passes = new StringBuilder();
		for (AlternativeRoutesService.SearchRecord record : records)
		{
			if (!record.label.startsWith("chain#"))
			{
				passes.append(' ').append(record.label);
			}
		}
		assertTrue("bank mode must skip the walk, seed and tail passes for a provably unreachable target, ran:"
			+ passes, passes.length() == 0);
		assertTrue("bank mode must short-circuit a provably unreachable target: " + records.size() + " searches",
			records.size() <= AlternativeRoutesService.UNREACHABLE_ESCAPE_ROUTES);
	}
}
