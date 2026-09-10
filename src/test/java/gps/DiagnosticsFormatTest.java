package gps;

import gps.transport.TransportType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The diagnostics classes' pure formatting (issue text and debug JSON), out of the plugin class:
 * a packed point as "x, y, plane" or "(none)", a route's methods as "A + B" or "walk", a packed
 * point as a JSON object or null for undefined.
 */
public class DiagnosticsFormatTest
{
	private static RouteOption route(TeleportMethod... methods)
	{
		return new RouteOption(List.of(), List.of(methods), List.of(), List.of(), 10, 10, true, Set.of(), List.of(), 0);
	}

	@Test
	public void pointText()
	{
		assertEquals("(none)", IssueReport.pointText(WorldPointUtil.UNDEFINED));
		assertEquals("3200, 3201, 1", IssueReport.pointText(WorldPointUtil.packWorldPoint(3200, 3201, 1)));
	}

	@Test
	public void methodSummary()
	{
		assertEquals("walk", IssueReport.methodSummary(route()));
		TeleportMethod glory = new TeleportMethod(TransportType.TELEPORTATION_ITEM,
			"Amulet of glory: Al Kharid", WorldPointUtil.packWorldPoint(3087, 3496, 0));
		TeleportMethod fairy = new TeleportMethod(TransportType.FAIRY_RING,
			"BKR", WorldPointUtil.packWorldPoint(3469, 3431, 0));
		assertEquals(glory.routeLabel() + " + " + fairy.routeLabel(), IssueReport.methodSummary(route(glory, fairy)));
	}

	@Test
	public void packedPointJson()
	{
		assertNull(DebugSnapshot.packedPointJson(WorldPointUtil.UNDEFINED));
		int packed = WorldPointUtil.packWorldPoint(3200, 3201, 1);
		Map<String, Object> json = DebugSnapshot.packedPointJson(packed);
		assertEquals(packed, json.get("packed"));
		assertEquals(3200, json.get("x"));
		assertEquals(3201, json.get("y"));
		assertEquals(1, json.get("plane"));
		assertEquals("key order is what the dashboard reads", List.of("packed", "x", "y", "plane"), List.copyOf(json.keySet()));
	}
}
