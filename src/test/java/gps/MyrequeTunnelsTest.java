package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathStep;
import gps.pathfinder.Pathfinder;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.SplitFlagMap;
import gps.pathfinder.TestPathfinderConfig;
import java.lang.reflect.Proxy;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Myreque tunnels under Mort Myre: the cave between the Hollows end (3492,9862) and 3513,9811
 * is the 93 Agility and 78 Mining shortcut of Darkness of Hallowvale, and the pair at 3491,9876
 * and 3483,9832 opens with In Aid of the Myreque. The rows had lost those gates (upstream carries
 * them), so a player was routed into the shortcut and told by the game it was too small to fit
 * (capture gps-capture-20261011-194735, build 0a0d7da).
 */
public class MyrequeTunnelsTest
{
	private static final int HOLLOWS_END = WorldPointUtil.packWorldPoint(3492, 9862, 0);
	private static final int FAR_END = WorldPointUtil.packWorldPoint(3513, 9811, 0);
	private static final int MYREQUE_A = WorldPointUtil.packWorldPoint(3491, 9876, 0);
	private static final int MYREQUE_B = WorldPointUtil.packWorldPoint(3483, 9832, 0);

	@Test
	public void theHollowsShortcutNeedsItsLevelsAndQuest()
	{
		assertTrue("93 Agility, 78 Mining, quest done: one step through", jumps(HOLLOWS_END, FAR_END, 99, QuestState.FINISHED));
		assertFalse("low Agility: no squeezing through", jumps(HOLLOWS_END, FAR_END, 1, QuestState.FINISHED));
		assertFalse("quest not done: no shortcut", jumps(HOLLOWS_END, FAR_END, 99, QuestState.NOT_STARTED));
	}

	@Test
	public void theMyrequeCaveNeedsInAidOfTheMyreque()
	{
		assertTrue(jumps(MYREQUE_A, MYREQUE_B, 99, QuestState.FINISHED));
		assertFalse(jumps(MYREQUE_A, MYREQUE_B, 99, QuestState.NOT_STARTED));
	}

	/** Whether the owned-mode route from start to target is the single cave edge (a jump, not a walk around). */
	private static boolean jumps(int start, int target, int skills, QuestState quests)
	{
		final Thread clientThread = Thread.currentThread();
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getGameState":
						return GameState.LOGGED_IN;
					case "getClientThread":
						return clientThread;
					case "getBoostedSkillLevel":
					case "getRealSkillLevel":
						return args[0] == Skill.AGILITY || args[0] == Skill.MINING ? skills : 99;
					default:
						return HybridPageFillTest.defaultValue(method.getReturnType());
				}
			});
		ShortestPathConfig config = Mockito.mock(ShortestPathConfig.class, invocation ->
		{
			String name = invocation.getMethod().getName();
			Class<?> type = invocation.getMethod().getReturnType();
			if (type == boolean.class)
			{
				return !"avoidWilderness".equals(name);
			}
			if (type == int.class)
			{
				return "calculationCutoff".equals(name) ? 120 : 0;
			}
			if (type == TeleportationItem.class)
			{
				return TeleportationItem.INVENTORY;
			}
			return HybridPageFillTest.defaultValue(type);
		});
		PathfinderConfig owned = new TestPathfinderConfig(client, config, quests, true, true);
		owned.refresh();
		CollisionMap map = new CollisionMap(SplitFlagMap.fromResources());
		Pathfinder pathfinder = new Pathfinder(owned, start, Destinations.walkableTargets(map, target));
		pathfinder.run();
		List<PathStep> path = pathfinder.getPath();
		return pathfinder.getResult().isReached() && path.size() == 2;
	}
}
