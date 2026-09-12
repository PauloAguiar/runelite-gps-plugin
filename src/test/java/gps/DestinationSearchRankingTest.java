package gps;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Panel series P1: the destination search's ranking, out of the panel class. The match tiers
 * (exact, prefix, word prefix, substring, subsequence) order the results before anything else;
 * within a tier the entry nearest the player comes first, or the alphabetical one when the
 * player's position is unknown; the list is capped at twelve; nothing matches nothing.
 */
public class DestinationSearchRankingTest
{
	private static Destinations.Entry at(String name, int x, int y)
	{
		return new Destinations.Entry("place", name, WorldPointUtil.packWorldPoint(x, y, 0));
	}

	private static List<String> names(List<Destinations.Entry> entries)
	{
		List<String> names = new ArrayList<>();
		for (Destinations.Entry entry : entries)
		{
			names.add(entry.name);
		}
		return names;
	}

	@Test
	public void theMatchTierRanksBeforeProximity()
	{
		// The player stands at the mine: nearest, but a word-prefix match ranks below the exact
		// and prefix matches.
		List<Destinations.Entry> pool = List.of(at("East Varrock mine", 3280, 3360), at("Ardougne", 2660, 3300),
			at("Varrock Bank", 3183, 3436), at("Varrock", 3210, 3424));
		List<Destinations.Entry> ranked = DestinationSearchView.rank(pool, "varrock", WorldPointUtil.packWorldPoint(3280, 3360, 0));
		assertEquals(List.of("Varrock", "Varrock Bank", "East Varrock mine"), names(ranked));
	}

	@Test
	public void proximityBreaksTiesWithinATierAndTheNameWithoutAPosition()
	{
		Destinations.Entry north = at("Lumbridge north", 3222, 3260);
		Destinations.Entry south = at("Lumbridge south", 3222, 3200);
		assertEquals(List.of("Lumbridge south", "Lumbridge north"),
			names(DestinationSearchView.rank(List.of(north, south), "lumbridge", WorldPointUtil.packWorldPoint(3222, 3190, 0))));
		assertEquals(List.of("Lumbridge north", "Lumbridge south"),
			names(DestinationSearchView.rank(List.of(south, north), "lumbridge", WorldPointUtil.UNDEFINED)));
	}

	@Test
	public void atMostTwelveResultsAndNoneForNoMatch()
	{
		List<Destinations.Entry> pool = new ArrayList<>();
		for (int i = 1; i <= 15; i++)
		{
			pool.add(at(String.format("Place %02d", i), 3000 + i, 3000));
		}
		assertEquals(12, DestinationSearchView.rank(pool, "place", WorldPointUtil.UNDEFINED).size());
		assertTrue(DestinationSearchView.rank(pool, "xyzzy", WorldPointUtil.UNDEFINED).isEmpty());
	}
}
