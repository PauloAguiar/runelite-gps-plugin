package gps;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pins the inline favourite editor's input grammar: an optional LEADING coordinate in any of the
 * search bar's formats, then the label; no coordinate means "use the fallback position" (the
 * player's tile).
 */
public class FavoriteInputTest
{
	private static final int FALLBACK = WorldPointUtil.packWorldPoint(3200, 3200, 0);

	private static void assertParsed(String text, int x, int y, int plane, String label)
	{
		ShortestPathPanel.ParsedFavorite parsed = ShortestPathPanel.parseFavoriteInput(text, FALLBACK);
		assertEquals(text, WorldPointUtil.packWorldPoint(x, y, plane), parsed.position);
		assertEquals(text, label, parsed.label);
	}

	@Test
	public void acceptsEverySeparatorStyle()
	{
		assertParsed("3221 3218 Lumbridge", 3221, 3218, 0, "Lumbridge");
		assertParsed("3221 3218 1 upstairs", 3221, 3218, 1, "upstairs");
		assertParsed("3221,3218 Lumbridge", 3221, 3218, 0, "Lumbridge");
		assertParsed("3221,3218,1 upstairs", 3221, 3218, 1, "upstairs");
		assertParsed("3221, 3218 Lumbridge", 3221, 3218, 0, "Lumbridge");
		assertParsed("3221, 3218, 0 ground floor", 3221, 3218, 0, "ground floor");
	}

	@Test
	public void planeTokenMustBeAWholeToken()
	{
		// "12" is the label's first word, not plane 1.
		assertParsed("3221 3218 12 barrels", 3221, 3218, 0, "12 barrels");
	}

	@Test
	public void plainLabelsUseTheFallbackPosition()
	{
		ShortestPathPanel.ParsedFavorite parsed = ShortestPathPanel.parseFavoriteInput("my house", FALLBACK);
		assertEquals(FALLBACK, parsed.position);
		assertEquals("my house", parsed.label);
	}

	@Test
	public void implausibleCoordinatesAreJustALabel()
	{
		// x beyond the map: not a coordinate prefix, the whole text is the label.
		ShortestPathPanel.ParsedFavorite parsed =
			ShortestPathPanel.parseFavoriteInput("9999 99999 nowhere", FALLBACK);
		assertEquals(FALLBACK, parsed.position);
		assertEquals("9999 99999 nowhere", parsed.label);
	}
}
