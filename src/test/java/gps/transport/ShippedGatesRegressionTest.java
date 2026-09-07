package gps.transport;

import gps.WorldPointUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.runelite.api.Quest;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * The three concrete data defects the 2026-09-06 review found in the shipped transports, pinned
 * so a future edit cannot quietly reintroduce them.
 */
public class ShippedGatesRegressionTest
{
	private static List<Transport> all()
	{
		List<Transport> out = new ArrayList<>();
		for (Set<Transport> set : TransportLoader.loadAllFromResources().values())
		{
			out.addAll(set);
		}
		return out;
	}

	@Test
	public void mageOfZamorakAbyssTeleportIsInTheGraph()
	{
		int origin = WorldPointUtil.packWorldPoint(3106, 3559, 0);
		int destination = WorldPointUtil.packWorldPoint(3035, 4852, 0);
		boolean present = false;
		for (Transport t : all())
		{
			if (t.getOrigin() == origin && t.getDestination() == destination)
			{
				present = true;
				break;
			}
		}
		assertTrue("a leading space in the destination cell used to drop this row entirely", present);
	}

	@Test
	public void shadowOfTheStormGatesTheTrapdoor()
	{
		int origin = WorldPointUtil.packWorldPoint(3077, 3493, 0);
		int destination = WorldPointUtil.packWorldPoint(3077, 9893, 0);
		boolean gated = false;
		for (Transport t : all())
		{
			if (t.getOrigin() == origin && t.getDestination() == destination)
			{
				Set<Quest> quests = t.getQuests();
				gated = quests != null && quests.contains(Quest.SHADOW_OF_THE_STORM);
			}
		}
		assertTrue("the trapdoor row spelled the quest 'Shadows of the Storm' and gated nothing", gated);
	}

	@Test
	public void apeAtollTeleportIsGatedOnAwowogei()
	{
		boolean gated = false;
		for (Transport t : all())
		{
			if ("Ape Atoll Teleport".equals(t.getDisplayInfo()))
			{
				Set<Quest> quests = t.getQuests();
				gated |= quests != null && quests.contains(Quest.RECIPE_FOR_DISASTER__KING_AWOWOGEI);
			}
		}
		assertTrue("the spell row named a quest the enum does not know", gated);
	}
}
