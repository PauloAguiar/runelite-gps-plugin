package shortestpath;

import org.junit.Assert;
import org.junit.Test;

public class ClosedDoorsTest
{
	private static int pack(int x, int y, int plane)
	{
		return WorldPointUtil.packWorldPoint(x, y, plane);
	}

	// Draynor Manor front doors: ids 134/135 at (3108-3109, 3353), straight walls on the
	// north edge (orientation 1). Walking into the manor crosses y=3353 -> y=3354.
	@Test
	public void draynorManorFrontDoorMatchesItsEdge()
	{
		ClosedDoors.Door door = ClosedDoors.doorBetween(pack(3108, 3353, 0), pack(3108, 3354, 0));
		Assert.assertNotNull(door);
		Assert.assertEquals(134, door.id);
		Assert.assertEquals("Large door", door.name);
	}

	@Test
	public void draynorManorFrontDoorMatchesFromTheFarSide()
	{
		// Same boundary walked in the other direction: the door sits on the from-tile's
		// neighbour, matched via the opposite-facing lookup.
		ClosedDoors.Door door = ClosedDoors.doorBetween(pack(3108, 3354, 0), pack(3108, 3353, 0));
		Assert.assertNotNull(door);
		Assert.assertEquals(134, door.id);
	}

	// Taverley gate: ids 1727/1728 at (2935, 3450-3451), on the east edge (orientation 2),
	// gating the x=2935 -> x=2936 crossing.
	@Test
	public void taverleyGateMatchesEastCrossing()
	{
		ClosedDoors.Door door = ClosedDoors.doorBetween(pack(2935, 3450, 0), pack(2936, 3450, 0));
		Assert.assertNotNull(door);
		Assert.assertEquals(1728, door.id);
		Assert.assertEquals("Gate", door.name);
	}

	@Test
	public void edgesAlongTheDoorTileButNotThroughItDoNotMatch()
	{
		// Walking north-south along x=2935 passes the gate tile but never crosses its east
		// edge; a hint there would be wrong.
		Assert.assertNull(ClosedDoors.doorBetween(pack(2935, 3450, 0), pack(2935, 3451, 0)));
		// And the parallel boundary one tile further west is doorless.
		Assert.assertNull(ClosedDoors.doorBetween(pack(2934, 3450, 0), pack(2935, 3450, 0)));
	}

	@Test
	public void nonAdjacentOrCrossPlanePairsNeverMatch()
	{
		Assert.assertNull(ClosedDoors.doorBetween(pack(3108, 3353, 0), pack(3108, 3355, 0)));
		Assert.assertNull(ClosedDoors.doorBetween(pack(3108, 3353, 0), pack(3108, 3354, 1)));
		Assert.assertNull(ClosedDoors.doorBetween(pack(3108, 3353, 0), pack(3108, 3353, 0)));
	}

	@Test
	public void diagonalStepThroughADoorCornerMatches()
	{
		// A diagonal step whose component boundaries include the Draynor Manor door edge is
		// gated by it too.
		ClosedDoors.Door door = ClosedDoors.doorBetween(pack(3108, 3353, 0), pack(3109, 3354, 0));
		Assert.assertNotNull(door);
	}
}
