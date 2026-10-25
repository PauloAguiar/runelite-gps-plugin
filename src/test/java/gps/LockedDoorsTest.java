package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.SplitFlagMap;
import gps.transport.Transport;
import gps.transport.TransportLoader;
import gps.transport.parser.VarRequirement;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Doors and barriers the game opens only to players who meet a requirement, which the collision
 * map had as ordinary doors: open to everyone, so routes led anyone through them. Each is closed
 * in the map and crossed by rows carrying the requirement, as the Crafting Guild's door always
 * was. Found on 2026-10-25 by comparing Reimagined World Map's gate list with ours; every
 * requirement is the OSRS Wiki's, cited on the rows' comment in transports.tsv.
 */
public class LockedDoorsTest
{
	private static final Map<Integer, Set<Transport>> ALL = TransportLoader.loadAllFromResources();
	private static final CollisionMap MAP = new CollisionMap(SplitFlagMap.fromResources());
	/** Index of the quest points in a transport's skill levels (after the skills, total and combat level). */
	private static final int QUEST_POINTS = Skill.values().length + 2;
	/** A walk this long from inside without reaching the outside counts as sealed. */
	private static final int SEAL_LIMIT = 200_000;

	@Test
	public void theChampionsGuildNeeds32QuestPoints()
	{
		door(3191, 3363, 3191, 3362, "Open Door 1805");
		assertEquals(32, row(3191, 3363, 3191, 3362, "Open Door 1805").getSkillLevels()[QUEST_POINTS]);
		sealed(3191, 3360, 3191, 3365);
	}

	@Test
	public void theHeroesGuildNeedsHeroesQuest()
	{
		door(2902, 3510, 2901, 3510, "Open Door 2624");
		door(2902, 3511, 2901, 3511, "Open Door 2625");
		assertTrue(row(2902, 3510, 2901, 3510, "Open Door 2624").getQuests().contains(Quest.HEROES_QUEST));
		assertTrue(row(2902, 3511, 2901, 3511, "Open Door 2625").getQuests().contains(Quest.HEROES_QUEST));
		sealed(2899, 3510, 2904, 3510);
	}

	@Test
	public void theLegendsGuildGatesNeedLegendsQuestStarted()
	{
		door(2728, 3349, 2728, 3350, "Open Gate 2391");
		door(2729, 3349, 2729, 3350, "Open Gate 2392");
		legendsStarted(row(2728, 3349, 2728, 3350, "Open Gate 2391"));
		legendsStarted(row(2729, 3349, 2729, 3350, "Open Gate 2392"));
		sealed(2728, 3352, 2728, 3347);
	}

	@Test
	public void theFarmingGuildTiersNeed45And65And85Farming()
	{
		// The north-east strip past the main door, then the west and north wings.
		door(1259, 3750, 1259, 3749, "Pass Barrier");
		door(1260, 3750, 1260, 3749, "Pass Barrier");
		door(1242, 3729, 1241, 3729, "Pass Barrier");
		door(1242, 3730, 1241, 3730, "Pass Barrier");
		door(1248, 3747, 1248, 3748, "Pass Barrier");
		door(1249, 3747, 1249, 3748, "Pass Barrier");
		assertEquals(45, row(1259, 3750, 1259, 3749, "Pass Barrier").getSkillLevels()[Skill.FARMING.ordinal()]);
		assertEquals(65, row(1242, 3729, 1241, 3729, "Pass Barrier").getSkillLevels()[Skill.FARMING.ordinal()]);
		assertEquals(85, row(1248, 3747, 1248, 3748, "Pass Barrier").getSkillLevels()[Skill.FARMING.ordinal()]);
		sealed(1240, 3729, 1243, 3729);
		sealed(1248, 3749, 1248, 3746);
	}

	@Test
	public void theLighthouseNeedsHorrorFromTheDeep()
	{
		door(2509, 3635, 2509, 3636, "Walk-through Doorway 4577");
		assertTrue(row(2509, 3635, 2509, 3636, "Walk-through Doorway 4577").getQuests().contains(Quest.HORROR_FROM_THE_DEEP));
		sealed(2509, 3638, 2509, 3633);
	}

	@Test
	public void theWildernessAgilityCourseNeeds52Agility()
	{
		door(2998, 3916, 2998, 3917, "Open Door 23555");
		assertEquals(52, row(2998, 3916, 2998, 3917, "Open Door 23555").getSkillLevels()[Skill.AGILITY.ordinal()]);
		sealed(2998, 3920, 2998, 3914);
	}

	@Test
	public void theShamanCavesGatesNeedALockpickAnd50ThievingThen50Strength()
	{
		door(2809, 9332, 2809, 9331, "Open Ancient Gate 2913");
		door(2810, 9332, 2810, 9331, "Open Ancient Gate 2912");
		door(2809, 9314, 2809, 9313, "Open Ancient Gate 2923");
		door(2810, 9314, 2810, 9313, "Open Ancient Gate 2922");
		Transport lockpicked = row(2809, 9332, 2809, 9331, "Open Ancient Gate 2913");
		assertEquals(50, lockpicked.getSkillLevels()[Skill.THIEVING.ordinal()]);
		assertTrue("a lockpick", contains(lockpicked.getItemRequirements().getItems()[0], 1523));
		legendsStarted(lockpicked);
		Transport forced = row(2809, 9314, 2809, 9313, "Open Ancient Gate 2923");
		assertEquals(50, forced.getSkillLevels()[Skill.STRENGTH.ordinal()]);
		legendsStarted(forced);
	}

	@Test
	public void theFireWallsRoundUngaduluNeedLegendsQuest()
	{
		int[][] walls = {
			{2788, 9325, 2789, 9325}, {2788, 9326, 2789, 9326}, {2788, 9331, 2789, 9331}, {2788, 9332, 2789, 9332},
			{2789, 9324, 2789, 9325}, {2790, 9324, 2790, 9325}, {2795, 9324, 2795, 9325}, {2796, 9324, 2796, 9325},
			{2789, 9333, 2789, 9332}, {2790, 9333, 2790, 9332}, {2795, 9333, 2795, 9332}, {2796, 9333, 2796, 9332},
			{2797, 9325, 2796, 9325}, {2797, 9326, 2796, 9326}, {2797, 9331, 2796, 9331}, {2797, 9332, 2796, 9332},
		};
		for (int[] w : walls)
		{
			closed(w[0], w[1], w[2], w[3]);
		}
		assertTrue(row(2788, 9325, 2789, 9325, "Touch Fire Wall 2908").getQuests().contains(Quest.LEGENDS_QUEST));
		assertTrue(row(2789, 9325, 2788, 9325, "Touch Fire Wall 2908").getQuests().contains(Quest.LEGENDS_QUEST));
		sealed(2792, 9328, 2786, 9328);
	}

	@Test
	public void theBrineRatCavernsPictureWallNeedsOlafsQuestAndItsGateAKey()
	{
		closed(2707, 10147, 2706, 10147);
		assertTrue(row(2707, 10147, 2706, 10147, "Search Picture wall 23156").getQuests().contains(Quest.OLAFS_QUEST));
		assertTrue(row(2706, 10147, 2707, 10147, "Search Picture wall 23156").getQuests().contains(Quest.OLAFS_QUEST));
		door(2725, 10168, 2726, 10168, "Open Gate 23216");
		int[] keys = row(2725, 10168, 2726, 10168, "Open Gate 23216").getItemRequirements().getItems()[0];
		for (int key = 11039; key <= 11043; key++)
		{
			assertTrue("gate key " + key, contains(keys, key));
		}
	}

	@Test
	public void theBarrowsBrokenFencesNeedInAidOfTheMyrequePastStage39()
	{
		for (int[] f : new int[][]{{3582, 3263, 3582, 3264}, {3538, 3261, 3538, 3262}})
		{
			closed(f[0], f[1], f[2], f[3]);
			assertEquals(1990, onlyVar(row(f[0], f[1], f[2], f[3], "Climb-over Broken fence 18411")).getId());
			assertEquals(1990, onlyVar(row(f[2], f[3], f[0], f[1], "Climb-over Broken fence 18411")).getId());
		}
	}

	/** Closed in the map, and crossed by a row each way: in as the caller checks, out with no requirement. */
	private static void door(int x, int y, int tx, int ty, String object)
	{
		closed(x, y, tx, ty);
		row(x, y, tx, ty, object);
		Transport out = row(tx, ty, x, y, object);
		assertTrue(object + " out: no skill", allZero(out.getSkillLevels()));
		assertTrue(object + " out: no quest", out.getQuests().isEmpty());
		assertTrue(object + " out: no var", out.getVarRequirements().isEmpty());
		assertNull(object + " out: no item", out.getItemRequirements());
	}

	private static void closed(int x, int y, int tx, int ty)
	{
		int a = WorldPointUtil.packWorldPoint(x, y, 0);
		int b = WorldPointUtil.packWorldPoint(tx, ty, 0);
		assertFalse(x + "," + y + " -> " + tx + "," + ty + " walkable", MAP.canStep(a, b));
		assertFalse(tx + "," + ty + " -> " + x + "," + y + " walkable", MAP.canStep(b, a));
	}

	/** Walking from inside never reaches the outside tile: the door is the only way in. */
	private static void sealed(int x, int y, int outX, int outY)
	{
		int start = WorldPointUtil.packWorldPoint(x, y, 0);
		int outside = WorldPointUtil.packWorldPoint(outX, outY, 0);
		Set<Integer> seen = new HashSet<>();
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		seen.add(start);
		queue.add(start);
		while (!queue.isEmpty() && seen.size() < SEAL_LIMIT)
		{
			int at = queue.poll();
			assertFalse("walked out to " + outX + "," + outY + " from " + x + "," + y, at == outside);
			int ax = WorldPointUtil.unpackWorldX(at);
			int ay = WorldPointUtil.unpackWorldY(at);
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					int next = WorldPointUtil.packWorldPoint(ax + dx, ay + dy, 0);
					if ((dx != 0 || dy != 0) && MAP.canStep(at, next) && seen.add(next))
					{
						queue.add(next);
					}
				}
			}
		}
		assertTrue("the inside at " + x + "," + y + " is a closed area", queue.isEmpty());
	}

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

	private static void legendsStarted(Transport t)
	{
		VarRequirement var = onlyVar(t);
		assertTrue("varplayer 139, Legends' Quest's progress", var.isVarPlayer());
		assertEquals(139, var.getId());
	}

	private static VarRequirement onlyVar(Transport t)
	{
		assertEquals(t.getObjectInfo(), 1, t.getVarRequirements().size());
		return t.getVarRequirements().iterator().next();
	}

	private static boolean allZero(int[] levels)
	{
		for (int level : levels)
		{
			if (level != 0)
			{
				return false;
			}
		}
		return true;
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
