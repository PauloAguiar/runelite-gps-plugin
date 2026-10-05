package gps;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The altars that swap the spellbook are not prayer altars. The pyramid altar under Jaldraocht
 * drains all Prayer and swaps to Ancient Magicks, the Astral Altar on Lunar Isle swaps to the Lunar
 * spellbook, and Tyss at the Dark Altar gives the Arceuus one (wiki, 2026-10-04). The cache dump
 * listed the first two as plain altars because they have a Pray option, so "nearest altar" could
 * end at one and they searched as "Altar (3231, 9311)". Each now has its own entry under the
 * "Spellbook altar" category, and the prayer altars no longer include them.
 */
public class SpellbookAltarTest
{
	private static final int[] PYRAMID = {3233, 9311};
	private static final int[] ASTRAL = {2158, 3864};

	private static boolean around(int packed, int[] centre)
	{
		return Math.abs(WorldPointUtil.unpackWorldX(packed) - centre[0]) <= 3 && Math.abs(WorldPointUtil.unpackWorldY(packed) - centre[1]) <= 3;
	}

	@Test
	public void nearestAltarNeverEndsAtASpellbookAltar()
	{
		Set<Integer> altars = Destinations.tilesForCategory("altar", null);
		assertTrue("prayer altars are still there", altars.size() > 300);
		for (int tile : altars)
		{
			assertFalse("the pyramid altar drains Prayer and swaps the spellbook", around(tile, PYRAMID));
			assertFalse("the Astral Altar swaps to Lunar", around(tile, ASTRAL));
		}
		for (Set<Integer> legacy : List.of(Destination.loadAllFromResources().get("altar")))
		{
			for (int tile : legacy)
			{
				assertFalse("the world-map 'closest altar' list too", around(tile, PYRAMID) || around(tile, ASTRAL));
			}
		}
	}

	@Test
	public void eachSpellbookHasItsOwnSearchEntry()
	{
		Map<String, Destinations.Entry> entries = new HashMap<>();
		for (Destinations.Entry entry : Destinations.searchable(null))
		{
			if ("spellbook_altar".equals(entry.category))
			{
				entries.put(entry.name, entry);
			}
		}
		assertEquals(Set.of("Ancient Magicks altar", "Lunar spellbook altar", "Arceuus spellbook altar"), entries.keySet());
		assertEquals("every side of the pyramid altar", 8, entries.get("Ancient Magicks altar").tiles.size());
		assertEquals("every side of the Astral Altar", 12, entries.get("Lunar spellbook altar").tiles.size());
		for (int tile : entries.get("Ancient Magicks altar").tiles)
		{
			assertTrue(around(tile, PYRAMID));
		}
		assertEquals("Tyss, at the Dark Altar", WorldPointUtil.packWorldPoint(1714, 3883, 0),
			entries.get("Arceuus spellbook altar").packedPosition);
		assertEquals("Spellbook altar", Destinations.categoryLabel("spellbook_altar"));
		assertEquals("it draws as an altar, not as a bare pin", RouteIcons.destinationIcon("altar"), RouteIcons.destinationIcon("spellbook_altar"));
	}
}
