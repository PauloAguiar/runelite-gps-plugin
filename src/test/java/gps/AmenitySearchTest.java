package gps;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Plan step N11: the name search indexed only places, landmarks, dungeons, minigames and training
 * spots, so "Falador bank" found nothing although the field's own hint promised it, and there were
 * no aliases (GE, Lumby, Wildy) and no way to type "nearest altar". Amenities join the index as one
 * entry per named site carrying every access tile (a multi-target route, the nearest booth wins),
 * a synonym table expands the query, and a leading "nearest" (or a bare category word) offers the
 * category's nearest-of row.
 */
public class AmenitySearchTest
{
	@Test
	public void namedAmenitiesAreIndexedOncePerSiteWithAllTheirTiles()
	{
		List<Destinations.Entry> index = Destinations.searchable(null);
		Destinations.Entry falador = null;
		Set<String> seen = new HashSet<>();
		boolean place = false;
		Set<String> amenities = Set.of("bank", "altar", "water", "furnace", "anvil", "range", "spinning_wheel", "pottery");
		for (Destinations.Entry entry : index)
		{
			// Named categories may repeat a name (a dungeon with two entrances); amenities are grouped.
			assertTrue("one amenity entry per (category, name): " + entry.category + " " + entry.name,
				!amenities.contains(entry.category) || seen.add(entry.category + "\t" + entry.name));
			if ("bank".equals(entry.category) && "Falador Bank".equals(entry.name))
			{
				falador = entry;
			}
			place |= "place".equals(entry.category);
		}
		assertNotNull("Falador Bank is searchable by name", falador);
		assertTrue("the entry carries every booth access tile: " + falador.tiles.size(), falador.tiles.size() >= 20);
		assertTrue("its representative tile is one of them", falador.tiles.contains(falador.packedPosition));
		assertTrue("places are still there", place);
		assertTrue("Falador bank scores as a name match", SearchMatcher.score(falador.name, "falador bank") > 0);
	}

	@Test
	public void aliasesExpandTheQuery()
	{
		assertTrue("GE", SearchMatcher.score("Grand Exchange", "ge") > 0);
		assertTrue("Lumby", SearchMatcher.score("Lumbridge Castle", "lumby") > 0);
		assertTrue("Wildy", SearchMatcher.score("Wilderness", "wildy") > 0);
		assertTrue("aliases compose with other words", SearchMatcher.score("Falador Bank", "fally bank") > 0);
		assertTrue("an alias never beats a literal exact match",
			SearchMatcher.score("Varrock", "varrock") > SearchMatcher.score("Varrock Palace", "varrock"));
		assertEquals("no accidental matches", 0, SearchMatcher.score("Brimhaven", "ge"));
	}

	@Test
	public void aLeadingNearestOrABareCategoryWordOffersTheNearestOption()
	{
		assertEquals("altar", Destinations.parseNearest("nearest altar").id);
		assertEquals("altar", Destinations.parseNearest("Nearest Altar").id);
		assertEquals("bank", Destinations.parseNearest("bank").id);
		assertEquals("bank_round_trip", Destinations.parseNearest("nearest bank (and back)").id);
		assertEquals("bank_round_trip", Destinations.parseNearest("nearest bank and back").id);
		assertEquals("range", Destinations.parseNearest("nearest cooking range").id);
		assertNull("a named search is not a nearest query", Destinations.parseNearest("falador bank"));
		assertNull(Destinations.parseNearest("nearest"));
		assertNull(Destinations.parseNearest(""));
		assertNull(Destinations.parseNearest("nearest dragon"));
	}

	@Test
	public void categoryLabelsReadLikeThePanel()
	{
		assertEquals("Bank", Destinations.categoryLabel("bank"));
		assertEquals("Cooking range", Destinations.categoryLabel("range"));
		assertEquals("Spinning wheel", Destinations.categoryLabel("spinning_wheel"));
		assertEquals("Landmark", Destinations.categoryLabel("landmark"));
	}
}
