package gps;

import gps.transport.TransportType;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Plan step L23: a route knows which of its methods arrives at a given path index, so the
 * overlays label and pulse a leg from the shown route's own edges rather than re-deriving it.
 */
public class RouteMethodEdgesTest
{
	@Test
	public void theMethodArrivingAtAnIndex()
	{
		TeleportMethod glory = new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Amulet of glory: Edgeville", 1);
		TeleportMethod fairy = new TeleportMethod(TransportType.FAIRY_RING, "BKR", 2);
		RouteOption route = new RouteOption(List.of(), List.of(glory, fairy), List.of(3, 9), List.of(1, 1),
			10, 10, true, Set.of(), List.of(), 0);
		assertSame(glory, route.methodArrivingAt(3));
		assertSame(fairy, route.methodArrivingAt(9));
		assertNull(route.methodArrivingAt(2));
		assertNull(route.methodArrivingAt(4));
	}
}
