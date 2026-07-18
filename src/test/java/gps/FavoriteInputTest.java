package gps;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pins the coordinate grammar shared by the destination search bar and the favourite editor's
 * "At" field: x and y (4-5 digits) with an optional plane, separated by spaces, commas or
 * semicolons in any mix.
 */
public class FavoriteInputTest
{
	private static void assertCoordinate(String text, int x, int y, int plane)
	{
		assertEquals(text, WorldPointUtil.packWorldPoint(x, y, plane),
			ShortestPathPanel.parseCoordinateQuery(text));
	}

	@Test
	public void acceptsEverySeparatorStyle()
	{
		assertCoordinate("3221 3218", 3221, 3218, 0);
		assertCoordinate("3221 3218 1", 3221, 3218, 1);
		assertCoordinate("3221,3218", 3221, 3218, 0);
		assertCoordinate("3221,3218,1", 3221, 3218, 1);
		assertCoordinate("3221, 3218", 3221, 3218, 0);
		assertCoordinate("3221, 3218, 0", 3221, 3218, 0);
		assertCoordinate("3221;3218;2", 3221, 3218, 2);
		assertCoordinate("3221 10891", 3221, 10891, 0); // 5-digit y: the underground bands
	}

	@Test
	public void rejectsNonCoordinates()
	{
		assertEquals(WorldPointUtil.UNDEFINED, ShortestPathPanel.parseCoordinateQuery("my house"));
		assertEquals(WorldPointUtil.UNDEFINED, ShortestPathPanel.parseCoordinateQuery("3221"));
		assertEquals(WorldPointUtil.UNDEFINED, ShortestPathPanel.parseCoordinateQuery("3221 3218 Lumbridge"));
		assertEquals(WorldPointUtil.UNDEFINED, ShortestPathPanel.parseCoordinateQuery("9999 99999"));
	}
}
