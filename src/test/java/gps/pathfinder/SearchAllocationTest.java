package gps.pathfinder;

import gps.ShortestPathConfig;
import gps.TeleportationItem;
import gps.WorldPointUtil;
import java.lang.management.ManagementFactory;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.QuestState;
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
 * Plan step N2: the search hot loop must not allocate per node. The review found an Integer
 * boxed per settled tile (targets.contains), one per expanded tile for the door mask and one
 * for the bank-tile check, a clock read per node, an O(nodes x targets) post-pass, and a node
 * graph that copies itself a dozen times while growing to a couple of million nodes and is then
 * thrown away - hundreds of megabytes of garbage per generation, the likeliest source of GC
 * hitches on the client. This measures bytes allocated on the search thread per settled node.
 */
@RunWith(MockitoJUnitRunner.class)
public class SearchAllocationTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int ARDOUGNE = WorldPointUtil.packWorldPoint(2662, 3305, 0);
	/** Bytes per settled node the loop is allowed after the cleanup. */
	static final int BYTES_PER_NODE_BUDGET = 64;

	private PathfinderConfig walkConfig()
	{
		when(config.calculationCutoff()).thenReturn(120);
		lenient().when(config.currencyThreshold()).thenReturn(10000000);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.NONE);
		lenient().when(config.useAgilityShortcuts()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(i -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		PathfinderConfig pathConfig = new TestPathfinderConfig(client, config, QuestState.FINISHED, true, true);
		pathConfig.refresh();
		return pathConfig;
	}

	private static com.sun.management.ThreadMXBean threadBean()
	{
		java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
		Assume.assumeTrue("needs the HotSpot thread allocation counter", bean instanceof com.sun.management.ThreadMXBean);
		com.sun.management.ThreadMXBean sun = (com.sun.management.ThreadMXBean) bean;
		Assume.assumeTrue(sun.isThreadAllocatedMemorySupported());
		sun.setThreadAllocatedMemoryEnabled(true);
		return sun;
	}

	private static long[] measure(PathfinderConfig pathConfig, int start, Set<Integer> targets)
	{
		com.sun.management.ThreadMXBean bean = threadBean();
		long thread = Thread.currentThread().getId();
		// Warm the JIT and the lazily loaded map regions on a first run, then measure a second.
		new Pathfinder(pathConfig, start, targets, Integer.MAX_VALUE, null).run();
		long before = bean.getThreadAllocatedBytes(thread);
		Pathfinder pathfinder = new Pathfinder(pathConfig, start, targets, Integer.MAX_VALUE, null);
		pathfinder.run();
		long bytes = bean.getThreadAllocatedBytes(thread) - before;
		int nodes = pathfinder.getResult().getNodesChecked();
		assertTrue("the search must settle enough nodes to measure: " + nodes + " (reached="
			+ pathfinder.getResult().isReached() + ", " + pathfinder.getResult().getTerminationReason()
			+ ", closest=" + WorldPointUtil.unpackWorldPoint(pathfinder.getResult().getClosestReachedPoint())
			+ ", astar=" + pathfinder.isAstar() + ", target=" + WorldPointUtil.unpackWorldPoint(pathfinder.getResult().getTarget())
			+ ", pathEnd=" + (pathfinder.getPath().isEmpty() ? "none" : WorldPointUtil.unpackWorldPoint(
				pathfinder.getPath().get(pathfinder.getPath().size() - 1).getPackedPosition()))
			+ ", steps=" + pathfinder.getPath().size() + ")", nodes > 100_000);
		return new long[]{bytes, nodes};
	}

	@Test
	public void blindWalkSearchAllocatesLittlePerNode()
	{
		long[] m = measure(walkConfig(), LUMBRIDGE, Set.of(ARDOUGNE));
		long perNode = m[0] / m[1];
		System.out.println("SEARCHALLOC single target: " + m[1] + " nodes, " + m[0] + " bytes, " + perNode + " B/node");
		assertTrue("a blind search allocated " + perNode + " bytes per settled node (budget "
			+ BYTES_PER_NODE_BUDGET + ")", perNode < BYTES_PER_NODE_BUDGET);
	}

	/** Many targets: the per-node target check and the closest-tile post-pass must not scale with them. */
	@Test
	public void manyTargetsDoNotMultiplyTheCost()
	{
		PathfinderConfig pathConfig = walkConfig();
		// Every target unreachable (tiles in unmapped ocean far west of the world), so the search
		// floods its whole component while the per-node target check and the closest-tile
		// post-pass run over 120 targets. A single reachable bank would end the search at
		// Lumbridge castle's after ~1,800 nodes; plane-3 copies of the banks still hit a roof.
		Set<Integer> banks = new HashSet<>();
		for (int i = 0; i < 120; i++)
		{
			banks.add(WorldPointUtil.packWorldPoint(1000 + i, 2000 + i, 0));
		}
		Set<Integer> farOnly = new HashSet<>();
		farOnly.add(WorldPointUtil.packWorldPoint(3253, 3180, 0)); // the Broken Raft deck: unreachable
		long[] one = measure(pathConfig, LUMBRIDGE, farOnly);
		long[] many = measure(pathConfig, LUMBRIDGE, banks);
		System.out.println("SEARCHALLOC unreachable, 1 target: " + one[1] + " nodes " + one[0] / one[1] + " B/node; "
			+ banks.size() + " targets: " + many[1] + " nodes " + many[0] / many[1] + " B/node");
		assertTrue("per-node allocation must not grow with the target count",
			many[0] / many[1] < BYTES_PER_NODE_BUDGET);
	}
}
