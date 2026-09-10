package gps;

import gps.pathfinder.PathStep;
import gps.transport.Transport;
import gps.transport.TransportType;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan step L18: the house exit label, out of the plugin class. For a destination inside the
 * house, the transport on the first inside-to-outside edge after the current index names the
 * exit by what it is; a destination outside, a path that walks out, or an edge without a
 * transport yields nothing.
 */
public class PlayerOwnedHouseTest
{
	private static final PathStep IN_A = new PathStep(WorldPointUtil.packWorldPoint(1900, 5700, 0), false);
	private static final PathStep IN_B = new PathStep(WorldPointUtil.packWorldPoint(1901, 5700, 0), false);
	private static final PathStep OUT = new PathStep(WorldPointUtil.packWorldPoint(3200, 3200, 0), false);
	private static final List<PathStep> PATH = List.of(IN_A, IN_B, OUT);

	private static Transport transport(TransportType type, String displayInfo, String objectInfo)
	{
		Transport transport = mock(Transport.class);
		when(transport.getType()).thenReturn(type);
		when(transport.getDisplayInfo()).thenReturn(displayInfo);
		when(transport.getObjectInfo()).thenReturn(objectInfo);
		return transport;
	}

	private static BiFunction<PathStep, PathStep, Set<Transport>> exitEdge(Transport transport)
	{
		return (from, to) -> from == IN_B && to == OUT ? Set.of(transport) : Set.of();
	}

	@Test
	public void theExitIsNamedByWhatItIs()
	{
		int destination = IN_A.getPackedPosition();
		assertEquals("Fairy Ring BKR", PlayerOwnedHouse.exitInfo(destination, PATH, 0,
			exitEdge(transport(TransportType.FAIRY_RING, "BKR", null))));
		assertEquals("Mounted Glory: Edgeville", PlayerOwnedHouse.exitInfo(destination, PATH, 0,
			exitEdge(transport(TransportType.TELEPORTATION_BOX, "Edgeville", "Rub Amulet of Glory 12345"))));
		assertEquals("Jewelry Box: Duel Arena", PlayerOwnedHouse.exitInfo(destination, PATH, 0,
			exitEdge(transport(TransportType.TELEPORTATION_BOX, "Duel Arena", "Teleport Jewellery Box 1"))));
		assertEquals("Nexus: Varrock", PlayerOwnedHouse.exitInfo(destination, PATH, 0,
			exitEdge(transport(TransportType.TELEPORTATION_PORTAL_POH, "Varrock", null))));
		assertEquals("Spirit Tree: Tree Gnome Village", PlayerOwnedHouse.exitInfo(destination, PATH, 0,
			exitEdge(transport(TransportType.SPIRIT_TREE, "Tree Gnome Village", null))));
		assertEquals("Obelisk: Level 13", PlayerOwnedHouse.exitInfo(destination, PATH, 0,
			exitEdge(transport(TransportType.WILDERNESS_OBELISK, "Level 13", null))));
		assertEquals("a plain transport keeps its own label", "Portal", PlayerOwnedHouse.exitInfo(destination, PATH, 0,
			exitEdge(transport(TransportType.TRANSPORT, "Portal", null))));
	}

	@Test
	public void nothingOutsideOrWithoutATransport()
	{
		Transport fairy = transport(TransportType.FAIRY_RING, "BKR", null);
		assertNull("destination outside the house", PlayerOwnedHouse.exitInfo(OUT.getPackedPosition(), PATH, 0, exitEdge(fairy)));
		assertNull("no transport on the exit edge", PlayerOwnedHouse.exitInfo(IN_A.getPackedPosition(), PATH, 0, (a, b) -> Set.of()));
		assertNull("already past the exit", PlayerOwnedHouse.exitInfo(IN_A.getPackedPosition(), PATH, 1, exitEdge(fairy)));
		assertNull(PlayerOwnedHouse.exitInfo(IN_A.getPackedPosition(), null, 0, exitEdge(fairy)));
	}
}
