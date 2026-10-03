package gps;

import gps.transport.Transport;
import gps.transport.TransportLoader;
import gps.transport.TransportType;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * An "Open ..." transport is a doorway only when it crosses between two neighbouring tiles on
 * one plane: doors and gates, which need no cue once open. "Open Trapdoor" is the transport
 * itself and keeps its ground label (capture gps-capture-20261003-163557: the Mourner HQ trapdoor
 * step was in the directions but its label never showed, because the overlay treated every
 * "Open" edge as a door and only labels doors while the closed object stands on the edge).
 */
public class DoorwayStepTest
{
	private static Transport first(String row)
	{
		Map<Integer, java.util.Set<Transport>> transports = new HashMap<>();
		TransportLoader.addTransportsFromContents(transports,
			"# Origin\tDestination\tmenuOption menuTarget objectID\n" + row + "\n", TransportType.TRANSPORT, 0);
		return transports.values().iterator().next().iterator().next();
	}

	@Test
	public void aDoorBetweenNeighbouringTilesIsADoorway()
	{
		assertTrue(RouteDirections.isDoorway(first("3207 3214 0\t3207 3215 0\tOpen Door 1535")));
		assertTrue("a two-wide gate", RouteDirections.isDoorway(first("1918 4639 0\t1916 4639 0\tOpen Gate 10")));
	}

	@Test
	public void aTrapdoorOrLadderIsNot()
	{
		assertFalse("the Mourner HQ trapdoor leads to the tunnels",
			RouteDirections.isDoorway(first("2543 3327 0\t2044 4649 0\tOpen Trapdoor 8783")));
		assertFalse("a plane change is never a doorway",
			RouteDirections.isDoorway(first("3207 3214 0\t3207 3215 1\tOpen Trapdoor 1")));
		assertFalse("not an Open edge at all",
			RouteDirections.isDoorway(first("1905 4638 0\t1901 4638 1\tClimb-up Staircase 10015")));
	}
}
