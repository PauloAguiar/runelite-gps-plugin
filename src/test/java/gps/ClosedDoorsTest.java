package gps;

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

	// Canifis house door: the map places it OPEN (id 24368, action "Close") at (3480,3494),
	// so it is IGNORED by the door matching: doors placed open by default can't have their state
	// read reliably from the scene, and false-blocking an open doorway is worse than not hinting.
	@Test
	public void openPlacedDoorIsNotMatched()
	{
		Assert.assertNull(ClosedDoors.doorBetween(pack(3480, 3494, 0), pack(3480, 3495, 0)));
		Assert.assertNull(ClosedDoors.doorBetween(pack(3479, 3494, 0), pack(3480, 3494, 0)));
	}

	@Test
	public void diagonalStepThroughADoorCornerMatches()
	{
		// A diagonal step whose component boundaries include the Draynor Manor door edge is
		// gated by it too.
		ClosedDoors.Door door = ClosedDoors.doorBetween(pack(3108, 3353, 0), pack(3109, 3354, 0));
		Assert.assertNotNull(door);
	}

	// West Ardougne house door (id 1537 at 2510,3304, east edge): a MULTILOC — its base
	// definition is nameless and actionless (the openable "Door" appearance is a varbit-swapped
	// impostor, Song of the Elves progress), so the original dump missed it and every door like
	// it. The dumper now resolves impostors; this pins the class staying in the registry.
	@Test
	public void multilocDoorIsRegistered()
	{
		ClosedDoors.Door door = ClosedDoors.doorBetween(pack(2510, 3304, 0), pack(2511, 3304, 0));
		Assert.assertNotNull(door);
		Assert.assertEquals(1537, door.id);
		Assert.assertEquals("Door", door.name);
	}
}
