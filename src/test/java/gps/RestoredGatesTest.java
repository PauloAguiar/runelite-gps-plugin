package gps;

import gps.transport.Transport;
import gps.transport.TransportLoader;
import gps.transport.parser.VarRequirement;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Skill;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Gates upstream's transports.tsv carries that ours had lost since the 2025 import (found by a
 * row-by-row comparison on 2026-10-11, after the Myreque tunnels sent a player into a shortcut
 * "too small to fit"): the Waterfall Quest raft and swims need the quest started, webs need a
 * slashing item, the Burgh de Rott gates and low fence need In Aid of the Myreque past stage 39
 * (the fence 25 Agility too), and the Temple of Light's tunnel to the Underground Pass needs the
 * quest past the temple. The Keldagrim tunnel stays ungated on purpose: the rope is used once,
 * and upstream's ROPE=1 would send established players to a bank for one.
 */
public class RestoredGatesTest
{
	private static final Map<Integer, Set<Transport>> ALL = TransportLoader.loadAllFromResources();

	private static Transport row(int x, int y, int tx, int ty, String object)
	{
		for (Transport t : ALL.getOrDefault(WorldPointUtil.packWorldPoint(x, y, 0), Set.of()))
		{
			if (t.getDestination() == WorldPointUtil.packWorldPoint(tx, ty, 0) && object.equals(t.getObjectInfo()))
			{
				return t;
			}
		}
		throw new AssertionError("no row " + x + "," + y + " -> " + tx + "," + ty + " " + object);
	}

	private static VarRequirement onlyVar(Transport t)
	{
		assertEquals(t.getObjectInfo(), 1, t.getVarRequirements().size());
		return t.getVarRequirements().iterator().next();
	}

	@Test
	public void theWaterfallRaftNeedsTheQuestStarted()
	{
		VarRequirement var = onlyVar(row(2510, 3494, 2512, 3481, "Board Log raft 1987"));
		assertTrue("varplayer 65, the quest's progress", var.isVarPlayer());
		assertEquals(65, var.getId());
	}

	@Test
	public void aWebNeedsSomethingToSlashItWith()
	{
		Transport web = row(3158, 3950, 3158, 3952, "Slash Web 733");
		assertNotNull(web.getItemRequirements());
		assertTrue("a knife is one of the slashing items", contains(web.getItemRequirements().getItems()[0], 946));
		assertTrue("so is a rune scimitar", contains(web.getItemRequirements().getItems()[0], 1333));
	}

	@Test
	public void burghDeRottNeedsInAidOfTheMyrequePastStage39()
	{
		assertEquals(1990, onlyVar(row(3484, 3244, 3484, 3243, "Open Gate 12817")).getId());
		Transport fence = row(3474, 3221, 3473, 3221, "Jump-over Low fence 12776");
		assertEquals(1990, onlyVar(fence).getId());
		assertEquals(25, fence.getSkillLevels()[Skill.AGILITY.ordinal()]);
	}

	@Test
	public void theTempleTunnelToTheUndergroundPassNeedsTheQuestPastTheTemple()
	{
		assertEquals(1103, onlyVar(row(1857, 4639, 2311, 9793, "Enter Tunnel 9977")).getId());
		assertEquals(1103, onlyVar(row(2311, 9793, 1857, 4639, "Descend Cave 9974")).getId());
	}

	@Test
	public void theKeldagrimTunnelStaysFreeOnPurpose()
	{
		assertNull(row(2778, 3869, 2772, 10232, "Enter Tunnel 5009").getItemRequirements());
	}

	private static boolean contains(int[] ids, int id)
	{
		for (int candidate : ids)
		{
			if (candidate == id)
			{
				return true;
			}
		}
		return false;
	}
}
