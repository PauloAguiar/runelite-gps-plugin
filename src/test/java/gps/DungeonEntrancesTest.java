package gps;

import gps.transport.Transport;
import gps.transport.TransportLoader;
import gps.transport.parser.VarRequirement;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Dungeon entrances GPS had no way into until 2026-10-25 (the Smoke Devil Dungeon, Kurask Lair,
 * Hespori's cave, the Sailing islands' caves and more), from the game's own world-map links:
 * each has a row in and a row back out, and the requirements the OSRS Wiki gives are on the row in.
 * DestinationsReachableTest covers the pins they made reachable.
 */
public class DungeonEntrancesTest
{
	private static final Map<Integer, Set<Transport>> ALL = TransportLoader.loadAllFromResources();

	/** {origin, landing, object in; stand, landing, object out}, plane 0 unless a third number is given. */
	private static final Object[][] ENTRANCES = {
		{"2452 3232 0", "3015 5629 0", "Climb Ladder 29635", "3016 5630 0", "2452 3232 0", "Climb Ladder 29636"},
		{"3271 2463 0", "3288 8868 1", "Climb Steps 57904", "3288 8868 1", "3271 2463 0", "Climb Steps 57903"},
		{"2637 2414 0", "2682 8875 0", "Climb-down Dark hole 60073", "2682 8875 0", "2637 2414 0", "Climb-up Rope 60076"},
		{"2141 3944 0", "2330 10354 2", "Climb-down Ladder 14996", "2331 10353 2", "2141 3943 0", "Climb-up Ladder 14995"},
		{"2561 3356 0", "2561 9756 0", "Climb-down Ladder 17384", "2563 9756 0", "2561 3355 0", "Climb-up Ladder 17385"},
		{"2132 2994 0", "1940 9451 0", "Climb-down Ladder 40745", "1940 9451 0", "2132 2994 0", "Climb-up Ladder 40746"},
		{"2075 3687 0", "2074 10088 0", "Climb-down Ladder 60093", "2074 10088 0", "2075 3687 0", "Climb-up Ladder 60094"},
		{"1692 3086 0", "1694 9480 0", "Climb-down Staircase 50859", "1694 9480 0", "1692 3080 0", "Climb-up Staircase 50860"},
		{"1640 3164 0", "1640 9567 0", "Climb-down Staircase 52641", "1640 9567 0", "1640 3164 0", "Climb-up Staircase 52642"},
		{"1356 2922 0", "1357 9318 0", "Climb-down Staircase 54948", "1357 9318 0", "1356 2922 0", "Climb-up Staircase 54947"},
		{"1230 3729 0", "1242 10082 0", "Enter Cave 34499", "1243 10081 0", "1230 3729 0", "Exit Cave 34435"},
		{"2310 2919 0", "2167 9308 0", "Enter Cave 40736", "2167 9308 0", "2310 2918 0", "Exit Opening 40737"},
		{"1289 3134 0", "1245 9527 0", "Enter Cave 57591", "1243 9529 0", "1290 3134 0", "Enter Cave 57592"},
		{"1942 2805 0", "1957 9190 0", "Enter Cave 60166", "1957 9190 0", "1942 2805 0", "Exit Cave 60167"},
		{"3744 3778 0", "3603 10231 0", "Enter Cave Entrance 30869", "3603 10231 0", "3746 3779 0", "Exit Cave Exit 30878"},
		{"2911 4067 0", "2887 10481 0", "Enter Cave entrance 60117", "2887 10481 0", "2911 4067 0", "Exit Cave exit 60118"},
		{"2217 2329 0", "2220 8730 0", "Enter Cave entrance 60129", "2220 8730 0", "2217 2329 0", "Exit Opening 60144"},
		{"2219 3477 0", "2292 9914 0", "Enter Cave entrance 60169", "2292 9914 0", "2219 3477 0", "Exit Cave exit 60170"},
		{"1966 3097 0", "1933 9519 0", "Enter Cave entrance 60173", "1933 9519 0", "1965 3097 0", "Exit Opening 60174"},
		{"1192 2784 0", "1191 9187 0", "Enter Cave entrance 60202", "1191 9187 0", "1192 2784 0", "Exit Cave exit 60203"},
		{"2964 3330 0", "2960 9736 0", "Enter Crypt 39617", "2960 9736 0", "2958 3337 0", "Climb Stairs 39609"},
		{"2631 3294 0", "2631 9694 0", "Climb-down Manhole 882", "2633 9694 0", "2631 3293 0", "Climb-up Ladder 17385"},
		{"3191 3355 0", "3189 9758 0", "Open Trapdoor 10558", "3189 9758 0", "3190 3358 0", "Climb-up Ladder 10560"},
		{"3681 3497 0", "3682 9961 0", "Open Trapdoor 11667", "3682 9961 0", "3681 3497 0", "Climb-up ladder 11666"},
		{"2412 3060 0", "2403 9415 0", "Enter Smoky cave 30176", "2403 9415 0", "2411 3060 0", "Use Crevice 534"},
		{"2823 3118 0", "2830 9522 0", "Search Rocks 2584", "2830 9521 0", "2824 3117 0", "Climb Hand holds 2585"},
		{"2278 3610 0", "2276 9988 0", "Enter Cave Entrance 30177", "2276 9988 0", "2278 3610 0", "Leave Cave Exit 30178"},
	};

	@Test
	public void eachEntranceHasARowInAndARowOut()
	{
		for (Object[] e : ENTRANCES)
		{
			row((String) e[0], (String) e[1], (String) e[2]);
			row((String) e[3], (String) e[4], (String) e[5]);
		}
	}

	@Test
	public void theFreeOnesAskNothing()
	{
		for (Object[] e : ENTRANCES)
		{
			Transport in = row((String) e[0], (String) e[1], (String) e[2]);
			assertTrue(e[2] + ": no skill", allZero(in.getSkillLevels()));
			assertTrue(e[2] + ": no quest", in.getQuests().isEmpty());
			assertTrue(e[2] + ": no var", in.getVarRequirements().isEmpty());
		}
	}

	@Test
	public void theBrimhavenAgilityArenaCosts200Coins()
	{
		Transport in = row("2809 3193 0", "2805 9592 0", "Climb-down Ladder 3617");
		assertEquals(200, quantity(in));
		assertTrue(contains(in.getItemRequirements().getItems()[0], 995));
		row("2806 9591 0", "2805 3191 0", "Climb Climbing rope 3610");
	}

	@Test
	public void theVolcanicMineNeeds50MiningAnd30Numulite()
	{
		Transport in = row("3816 3807 0", "3806 10194 3", "Climb-down Volcano Entrance 31031");
		assertEquals(50, in.getSkillLevels()[Skill.MINING.ordinal()]);
		assertTrue(contains(in.getItemRequirements().getItems()[0], 21555));
		row("3806 10194 3", "3816 3808 0", "Climb-up Exit 31032");
	}

	@Test
	public void theRedChinchompaCaveNeedsTheHardWesternProvincesDiary()
	{
		Transport in = row("2527 2893 0", "2532 9293 0", "Enter Cave 19039");
		assertEquals(1, in.getVarRequirements().size());
		VarRequirement diary = in.getVarRequirements().iterator().next();
		assertFalse("a varbit", diary.isVarPlayer());
		assertEquals(4473, diary.getId());
		row("2532 9293 0", "2533 2894 0", "Exit Cave 19037");
	}

	@Test
	public void theWerewolfCourseNeeds60AgilityCreatureOfFenkenstrainAndARingOfCharos()
	{
		Transport in = row("3543 3463 0", "3549 9865 0", "Open Trapdoor 11636");
		assertEquals(60, in.getSkillLevels()[Skill.AGILITY.ordinal()]);
		assertTrue(in.getQuests().contains(Quest.CREATURE_OF_FENKENSTRAIN));
		assertTrue(contains(in.getItemRequirements().getItems()[0], 4202));
		assertTrue(contains(in.getItemRequirements().getItems()[0], 6465));
		row("3549 9865 0", "3547 3462 0", "Climb-up Ladder 11635");
	}

	@Test
	public void theGiantsFoundryAndTheWolfDenNeedTheirQuests()
	{
		assertTrue(row("3360 3149 0", "3364 11483 0", "Enter Cave 44635").getQuests().contains(Quest.SLEEPING_GIANTS));
		row("3364 11483 0", "3360 3149 0", "Exit Exit 44636");
		assertTrue(row("1497 3130 0", "1486 9501 1", "Enter Wolf Den 54692").getQuests().contains(Quest.MEAT_AND_GREET));
		row("1486 9501 1", "1497 3130 0", "Exit Cave 54690");
	}

	private static Transport row(String from, String to, String object)
	{
		int origin = packed(from);
		int destination = packed(to);
		for (Transport t : ALL.getOrDefault(origin, Set.of()))
		{
			if (t.getDestination() == destination && object.equals(t.getObjectInfo()))
			{
				return t;
			}
		}
		throw new AssertionError("no row " + from + " -> " + to + " " + object);
	}

	private static int packed(String point)
	{
		String[] p = point.split(" ");
		return WorldPointUtil.packWorldPoint(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
	}

	private static int quantity(Transport t)
	{
		return t.getItemRequirements().getQuantities()[0];
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
