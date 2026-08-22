package gps;

import java.util.Arrays;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Issue #22: every elemental staff the wiki lists as an unlimited source of a rune must be in
 * that rune's staff family — the mystic element staves (1401-1407) and the "pretty" lava /
 * steam variants were missing, so a player wielding a Mystic air staff was told they had no
 * air runes.
 */
public class ItemVariationsTest
{
	private static void assertFamily(ItemVariations family, int... ids)
	{
		for (int id : ids)
		{
			assertTrue(family + " must include item " + id,
				Arrays.stream(family.getIds()).anyMatch(i -> i == id));
		}
	}

	@Test
	public void mysticElementStavesAreRuneSources()
	{
		assertFamily(ItemVariations.STAFF_OF_AIR, ItemID.STAFF_OF_AIR, ItemID.MYSTIC_AIR_STAFF,
			ItemID.AIR_BATTLESTAFF, ItemID.MYSTIC_MIST_BATTLESTAFF, ItemID.MYSTIC_DUST_BATTLESTAFF);
		assertFamily(ItemVariations.STAFF_OF_EARTH, ItemID.STAFF_OF_EARTH, ItemID.MYSTIC_EARTH_STAFF,
			ItemID.EARTH_BATTLESTAFF, ItemID.MYSTIC_MUD_STAFF, ItemID.MYSTIC_LAVA_STAFF);
		assertFamily(ItemVariations.STAFF_OF_FIRE, ItemID.STAFF_OF_FIRE, ItemID.MYSTIC_FIRE_STAFF,
			ItemID.FIRE_BATTLESTAFF, ItemID.MYSTIC_LAVA_STAFF, ItemID.MYSTIC_LAVA_STAFF_PRETTY,
			ItemID.MYSTIC_STEAM_BATTLESTAFF, ItemID.MYSTIC_STEAM_BATTLESTAFF_PRETTY);
		assertFamily(ItemVariations.STAFF_OF_WATER, ItemID.STAFF_OF_WATER, ItemID.MYSTIC_WATER_STAFF,
			ItemID.WATER_BATTLESTAFF, ItemID.MYSTIC_MUD_STAFF, ItemID.MYSTIC_STEAM_BATTLESTAFF,
			ItemID.MYSTIC_STEAM_BATTLESTAFF_PRETTY);
	}

	@Test
	public void runeStaffSubstitutionCoversEveryElement()
	{
		// The substitution table must resolve for each elemental rune, else a staff never counts.
		for (ItemVariations rune : new ItemVariations[]{ItemVariations.AIR_RUNE, ItemVariations.EARTH_RUNE,
			ItemVariations.FIRE_RUNE, ItemVariations.WATER_RUNE})
		{
			int[] staves = ItemVariations.staves(rune);
			assertTrue("no staff family for " + rune, staves != null && staves.length > 0);
		}
	}
}
