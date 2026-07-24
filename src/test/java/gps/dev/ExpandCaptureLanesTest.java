package gps.dev;

import gps.WorldPointUtil;
import java.util.List;
import java.util.function.IntPredicate;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Captures across multi-tile objects (a 4-wide lava gap, 2-wide climbing rocks) only record
 * the lane actually traversed — {@link TransportAuditPlugin#expandCaptureLanes} translates
 * the capture to the sibling lanes of the footprint. Real cases from the Mount Karuulm
 * field session that motivated it.
 */
public class ExpandCaptureLanesTest
{
	private static final IntPredicate OPEN = p -> false;

	private static int tile(int x, int y, int plane)
	{
		return WorldPointUtil.packWorldPoint(x, y, plane);
	}

	@Test
	public void twoLaneRocksDeriveTheOtherLane()
	{
		// Rocks 34548 (2x1 at x 1351-1352): southbound was only captured on the x=1351 lane.
		List<int[]> lanes = TransportAuditPlugin.expandCaptureLanes(
			tile(1351, 10252, 0), tile(1351, 10250, 0), 1351, 1352, OPEN);
		assertEquals(1, lanes.size());
		assertArrayEquals(new int[]{tile(1352, 10252, 0), tile(1352, 10250, 0)}, lanes.get(0));
	}

	@Test
	public void fourLaneGapDerivesThreeSiblings()
	{
		// Lava gap 34515 (4 lanes, x 1269-1272), jumped north on the x=1269 lane.
		List<int[]> lanes = TransportAuditPlugin.expandCaptureLanes(
			tile(1269, 10170, 0), tile(1269, 10175, 0), 1269, 1272, OPEN);
		assertEquals(3, lanes.size());
		assertArrayEquals(new int[]{tile(1270, 10170, 0), tile(1270, 10175, 0)}, lanes.get(0));
		assertArrayEquals(new int[]{tile(1271, 10170, 0), tile(1271, 10175, 0)}, lanes.get(1));
		assertArrayEquals(new int[]{tile(1272, 10170, 0), tile(1272, 10175, 0)}, lanes.get(2));
	}

	@Test
	public void blockedLanesAreDropped()
	{
		IntPredicate blocked = p -> WorldPointUtil.unpackWorldX(p) == 1271;
		List<int[]> lanes = TransportAuditPlugin.expandCaptureLanes(
			tile(1269, 10170, 0), tile(1269, 10175, 0), 1269, 1272, blocked);
		assertEquals(2, lanes.size());
	}

	@Test
	public void crossPlaneStepsKeepEachEndpointsPlane()
	{
		// Steps 34530: up from plane 0 to plane 1, two lanes (y 10205-10206).
		List<int[]> lanes = TransportAuditPlugin.expandCaptureLanes(
			tile(1329, 10205, 0), tile(1334, 10205, 1), 10205, 10206, OPEN);
		assertEquals(1, lanes.size());
		assertArrayEquals(new int[]{tile(1329, 10206, 0), tile(1334, 10206, 1)}, lanes.get(0));
	}

	@Test
	public void diagonalTravelIsNotExpanded()
	{
		assertTrue(TransportAuditPlugin.expandCaptureLanes(
			tile(1270, 10176, 0), tile(1272, 10174, 0), 1269, 1272, OPEN).isEmpty());
	}

	@Test
	public void angledWalkInApproachIsNotExpanded()
	{
		// Origin lane coordinate outside the footprint span (the Karuulm steps were clicked
		// from 2-3 tiles out, so the recorded origin never lined up with a lane).
		assertTrue(TransportAuditPlugin.expandCaptureLanes(
			tile(1313, 10191, 1), tile(1318, 10189, 2), 10188, 10189, OPEN).isEmpty());
	}
}
