package gps;

import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.Transport;
import java.lang.management.ManagementFactory;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Plan step N3: the per-search availability rebuild must not allocate a method identity per
 * transport. Every chain iteration, seed, tail and walk search calls
 * rebuildAvailabilityWithExclusions, which built a fresh TeleportMethod for every transport in
 * both base lists just to test set membership (~12,000 throwaway objects per rebuild, ~19
 * rebuilds per generation), and TransportAvailability's builder allocated a key array per pass.
 */
@RunWith(MockitoJUnitRunner.class)
public class RebuildAllocationTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	/**
	 * Bytes one rebuild may allocate with exclusions present: the two origin maps of each
	 * availability are cloned (four clones of an int[] plus an Object[] at ~16K capacity) and the
	 * touched origins get a new array. Baseline before N3: 4,017,408 bytes.
	 */
	static final long BYTES_PER_REBUILD_BUDGET = 800_000;

	private static com.sun.management.ThreadMXBean threadBean()
	{
		java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
		Assume.assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
		com.sun.management.ThreadMXBean sun = (com.sun.management.ThreadMXBean) bean;
		Assume.assumeTrue(sun.isThreadAllocatedMemorySupported());
		sun.setThreadAllocatedMemoryEnabled(true);
		return sun;
	}

	@Test
	public void rebuildWithExclusionsDoesNotAllocateAMethodPerTransport()
	{
		when(config.calculationCutoff()).thenReturn(120);
		lenient().when(config.currencyThreshold()).thenReturn(10000000);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.ALL);
		lenient().when(config.useFairyRings()).thenReturn(true);
		lenient().when(config.useTeleportationSpells()).thenReturn(true);
		lenient().when(config.useSpiritTrees()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(i -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);

		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.setPlanningMode(true);
		planning.setBypassItemPossession(true);
		planning.refresh();
		List<TeleportMethod> catalog = new java.util.ArrayList<>(planning.getMethodCatalog());
		assertTrue("premise: a catalog to exclude from", catalog.size() > 50);
		// Four origin-less teleports and four origin-bound transports, so both the teleport array
		// and the copy-on-write origin maps are exercised.
		Set<TeleportMethod> excluded = new HashSet<>(catalog.subList(0, 4));
		List<Transport> originBound = new java.util.ArrayList<>();
		planning.getTransportsPacked(false).forEach((origin, transports) ->
		{
			if (originBound.size() < 4)
			{
				originBound.add(transports[0]);
			}
		});
		assertTrue("premise: origin-bound transports to exclude", originBound.size() == 4);
		for (Transport transport : originBound)
		{
			excluded.add(transport.method());
		}

		planning.rebuildAvailabilityWithExclusions(excluded);
		for (Transport transport : originBound)
		{
			Transport[] left = planning.getTransportsPacked(false).getOrDefault(transport.getOrigin(), new Transport[0]);
			for (Transport candidate : left)
			{
				assertTrue("an excluded transport must be gone from its origin", !excluded.contains(candidate.method()));
			}
		}
		planning.rebuildAvailabilityWithExclusions(Set.of());
		for (Transport transport : originBound)
		{
			Transport[] restored = planning.getTransportsPacked(false).get(transport.getOrigin());
			assertTrue("the base availability must be intact afterwards", java.util.Arrays.asList(restored).contains(transport));
		}

		com.sun.management.ThreadMXBean bean = threadBean();
		long thread = Thread.currentThread().getId();
		// Warm up, then measure the steady state of one rebuild each way.
		planning.rebuildAvailabilityWithExclusions(excluded);
		planning.rebuildAvailabilityWithExclusions(Set.of());
		long before = bean.getThreadAllocatedBytes(thread);
		planning.rebuildAvailabilityWithExclusions(excluded);
		long withExclusions = bean.getThreadAllocatedBytes(thread) - before;
		before = bean.getThreadAllocatedBytes(thread);
		planning.rebuildAvailabilityWithExclusions(Set.of());
		long without = bean.getThreadAllocatedBytes(thread) - before;
		System.out.println("REBUILDALLOC with 8 exclusions: " + withExclusions + " bytes; without: " + without + " bytes");

		assertTrue("a rebuild with exclusions allocated " + withExclusions + " bytes (budget "
			+ BYTES_PER_REBUILD_BUDGET + ")", withExclusions < BYTES_PER_REBUILD_BUDGET);
		assertTrue("with nothing excluded the base availability must be shared, not rebuilt: " + without + " bytes",
			without < 10_000);
	}
}
