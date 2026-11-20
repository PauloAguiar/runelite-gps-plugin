package gps.transport;

import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * The staves that open fairy rings. Lunar Diplomacy stage 145 is where the Lunar staff is made and handed to the
 * Oneiromancer; until she has one, a finished Lunar staff can't be wielded, so it opens no ring (field report
 * 2026-11-19). The part-made staves work as a Dramen staff all along, and the Lumbridge elite diary waives the staff.
 */
public class FairyRingStavesTest
{
	private static Set<Integer> staves(int eliteDiary, int lunarStage)
	{
		Client client = Mockito.mock(Client.class);
		when(client.getVarbitValue(VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE)).thenReturn(eliteDiary);
		when(client.getVarbitValue(VarbitID.LUNAR_QUEST_MAIN)).thenReturn(lunarStage);
		Set<Integer> ids = new HashSet<>();
		for (int id : BankPickupRequirements.fairyRingStaves(client))
		{
			ids.add(id);
		}
		return ids;
	}

	@Test
	public void theLunarStaffCountsOnceTheOneiromancerHasOne()
	{
		assertFalse("stage 145: made, not handed over", staves(0, 145).contains(ItemID.LUNAR_MOONCLAN_LIMINAL_STAFF));
		assertTrue("stage 155: handed over, it can be wielded", staves(0, 155).contains(ItemID.LUNAR_MOONCLAN_LIMINAL_STAFF));
	}

	@Test
	public void dramenAndPartMadeStavesAlwaysCount()
	{
		Set<Integer> always = Set.of(ItemID.DRAMEN_STAFF, ItemID.DRAMEN_STAFF_AIR, ItemID.DRAMEN_STAFF_FIRE,
			ItemID.DRAMEN_STAFF_WATER);
		assertTrue(staves(0, 0).containsAll(always));
		assertTrue(staves(0, 145).containsAll(always));
		assertTrue(staves(0, 155).containsAll(always));
	}

	@Test
	public void theDiaryWaivesTheStaff()
	{
		assertEquals(Set.of(), staves(1, 145));
		assertEquals(Set.of(), staves(1, 155));
	}
}
