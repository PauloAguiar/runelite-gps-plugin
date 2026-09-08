package gps;

import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.client.callback.ClientThread;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Plan step N4: the distance field is a reverse flood from the target set over the usable
 * transports, ~200 ms per build, while the searches it guides cost ~1 ms each. A player walking
 * toward a pinned target regenerates every few tiles with the same target, the same usable
 * transports, the same exclusions and the same synthesized sea legs, and rebuilt the identical
 * field each time. The field is immutable once built, so a generation whose inputs are unchanged
 * reuses the previous one; any changed input rebuilds it.
 */
@RunWith(MockitoJUnitRunner.class)
public class FieldReuseTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;
	@Mock
	ClientThread clientThread;

	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int DRAYNOR = WorldPointUtil.packWorldPoint(3093, 3244, 0);
	private static final int VARROCK = WorldPointUtil.packWorldPoint(3212, 3422, 0);
	private static final int FALADOR = WorldPointUtil.packWorldPoint(2965, 3380, 0);

	@Test
	public void unchangedInputsReuseTheField() throws Exception
	{
		AtomicInteger skillLevel = new AtomicInteger(99);
		when(config.calculationCutoff()).thenReturn(120);
		when(config.currencyThreshold()).thenReturn(10000000);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.ALL);
		lenient().when(config.useFairyRings()).thenReturn(true);
		lenient().when(config.useGnomeGliders()).thenReturn(true);
		lenient().when(config.useTeleportationSpells()).thenReturn(true);
		lenient().when(config.useSpiritTrees()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(i -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenAnswer(i -> skillLevel.get());
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
			generate(service, LUMBRIDGE, VARROCK);
			assertFalse("the first generation has nothing to reuse", service.lastFieldReused());

			generate(service, DRAYNOR, VARROCK);
			assertTrue("same target and usable transports from another start: the field is reused",
				service.lastFieldReused());

			generate(service, DRAYNOR, FALADOR);
			assertFalse("a different target rebuilds the field", service.lastFieldReused());

			generate(service, DRAYNOR, FALADOR);
			assertTrue("and the new field is reused in turn", service.lastFieldReused());

			// The owned modes honor possession and unlock gates, so the usable set shrinks.
			generate(service, DRAYNOR, FALADOR, AlternativeRoutesMode.OWNED_INVENTORY);
			assertFalse("a mode that admits a different usable set rebuilds the field", service.lastFieldReused());

			generate(service, LUMBRIDGE, FALADOR, AlternativeRoutesMode.OWNED_INVENTORY);
			assertTrue("unchanged again: reused", service.lastFieldReused());

			skillLevel.set(1);
			generate(service, LUMBRIDGE, FALADOR, AlternativeRoutesMode.OWNED_INVENTORY);
			assertFalse("a changed unlock state (agility 1) rebuilds the field", service.lastFieldReused());
		}
		finally
		{
			service.shutdown();
		}
	}

	private static void generate(AlternativeRoutesService service, int start, int target) throws Exception
	{
		generate(service, start, target, AlternativeRoutesMode.ALL_EVERYTHING);
	}

	private static void generate(AlternativeRoutesService service, int start, int target, AlternativeRoutesMode mode)
		throws Exception
	{
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(start, Set.of(target), Set.of(), mode, 10, 3, false,
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
		System.out.println("FIELDREUSE field=" + service.getLastTimingSummary()[5] + "ms reused=" + service.lastFieldReused());
	}
}
