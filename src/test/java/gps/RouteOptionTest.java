package gps;

import gps.pathfinder.PathStep;
import gps.transport.Transport;
import gps.transport.TransportType;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pins the methodEdgeIndexes convention END TO END: scanMethods records a transport edge by
 * its ARRIVAL step, and sailingJumpDepartures converts to the DEPARTURE index the overlays
 * key jumps by. The off-by-one this guards against left every sea leg permanently dashed
 * while all the track machinery tested green (capture 20260730-203634).
 */
public class RouteOptionTest
{
	@Test
	public void sailingJumpDeparturesConvertArrivalToDeparture()
	{
		// Path: walk 0->1->2, sail 2->3 (edge recorded by ARRIVAL index 3), walk 3->4.
		List<PathStep> path = List.of(
			step(3000, 3000), step(3001, 3000), step(3002, 3000),
			step(3200, 3200), step(3201, 3200));
		Transport sail = new Transport.TransportBuilder()
			.origin(WorldPointUtil.packWorldPoint(3002, 3000, 0))
			.destination(WorldPointUtil.packWorldPoint(3200, 3200, 0))
			.type(TransportType.SAILING)
			.duration(50)
			.displayInfo("Sailing: test")
			.build();
		TeleportMethod method = TeleportMethod.fromTransport(sail);
		RouteOption route = new RouteOption(path, List.of(method), List.of(3), List.of(50),
			100, 100, true, Set.of(), List.of(2), 0);
		assertEquals("departure index = arrival index - 1",
			Set.of(2), route.sailingJumpDepartures());
	}

	private static PathStep step(int x, int y)
	{
		return new PathStep(WorldPointUtil.packWorldPoint(x, y, 0), false);
	}
}
