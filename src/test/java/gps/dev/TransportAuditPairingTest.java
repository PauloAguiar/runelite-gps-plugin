package gps.dev;

import gps.WorldPointUtil;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The one-way/two-way pairing rules, pinned with the exact field data that exposed two bugs:
 * live captures read as ONE-WAY until restart (pairing was never recomputed on capture — fixed
 * at the completeCapture call site, exercised here by recomputing after each add the way that
 * site now does), and same-plane short hops (rockslides) paired with THEMSELVES through the
 * 5-tile tolerance, showing two-way after a single direction.
 */
public class TransportAuditPairingTest
{
	private static int tile(int x, int y, int p)
	{
		return WorldPointUtil.packWorldPoint(x, y, p);
	}

	private static TransportAuditPlugin plugin()
	{
		return new TransportAuditPlugin();
	}

	@Test
	public void varlamoreStairsPairAcrossTheTwoObjects()
	{
		// The user's captures, verbatim: descent via 27856 and ascent via 27854.
		TransportAuditPlugin plugin = plugin();
		plugin.capturedEdges.add(new int[]{tile(1610, 3796, 1), tile(1617, 3796, 0), 27856});
		plugin.recomputeEdgePairs();
		assertEquals("only one direction captured yet",
			TransportAuditPlugin.FindingState.CAPTURED_ONE_WAY,
			plugin.capturedState(27856, tile(1613, 3797, 1), true));

		plugin.capturedEdges.add(new int[]{tile(1617, 3796, 0), tile(1610, 3796, 1), 27854});
		plugin.recomputeEdgePairs();
		assertEquals("both directions captured — must read as complete, LIVE, without a restart",
			TransportAuditPlugin.FindingState.CAPTURED_SESSION,
			plugin.capturedState(27856, tile(1613, 3797, 1), true));
		assertEquals(TransportAuditPlugin.FindingState.CAPTURED_SESSION,
			plugin.capturedState(27854, tile(1615, 3797, 0), true));
	}

	@Test
	public void samePlaneShortHopDoesNotPairWithItself()
	{
		// An Underground Pass rockslide: origin and dest 2 tiles apart on the same plane — well
		// inside the 5-tile pairing tolerance, so without the identity/direction checks it
		// paired with itself and showed two-way after a single crossing.
		TransportAuditPlugin plugin = plugin();
		plugin.capturedEdges.add(new int[]{tile(2485, 9720, 0), tile(2485, 9722, 0), 3309});
		plugin.recomputeEdgePairs();
		assertEquals(TransportAuditPlugin.FindingState.CAPTURED_ONE_WAY,
			plugin.capturedState(3309, tile(2485, 9721, 0), true));
	}

	@Test
	public void samePlaneShortHopPairsWithItsRealReverse()
	{
		TransportAuditPlugin plugin = plugin();
		plugin.capturedEdges.add(new int[]{tile(2485, 9720, 0), tile(2485, 9722, 0), 3309});
		plugin.capturedEdges.add(new int[]{tile(2485, 9722, 0), tile(2485, 9720, 0), 3309});
		plugin.recomputeEdgePairs();
		assertEquals(TransportAuditPlugin.FindingState.CAPTURED_SESSION,
			plugin.capturedState(3309, tile(2485, 9721, 0), true));
	}

	@Test
	public void sameDirectionJitterDuplicateIsNotAReverse()
	{
		// Two captures of the SAME crossing with 1-tile landing jitter: same direction, so they
		// must not satisfy each other's reverse check.
		TransportAuditPlugin plugin = plugin();
		plugin.capturedEdges.add(new int[]{tile(2485, 9720, 0), tile(2485, 9722, 0), 3309});
		plugin.capturedEdges.add(new int[]{tile(2485, 9720, 0), tile(2486, 9722, 0), 3309});
		plugin.recomputeEdgePairs();
		assertEquals(TransportAuditPlugin.FindingState.CAPTURED_ONE_WAY,
			plugin.capturedState(3309, tile(2485, 9721, 0), true));
	}

	@Test
	public void crossPlanePairsViaSeparateObjects()
	{
		// Cave entrance/exit style: each object one-directional; together a round trip.
		TransportAuditPlugin plugin = plugin();
		plugin.capturedEdges.add(new int[]{tile(2435, 3314, 0), tile(2495, 9716, 0), 3213});
		plugin.capturedEdges.add(new int[]{tile(2496, 9713, 0), tile(2436, 3315, 0), 3214});
		plugin.recomputeEdgePairs();
		assertEquals(TransportAuditPlugin.FindingState.CAPTURED_SESSION,
			plugin.capturedState(3213, tile(2434, 3315, 0), true));
		assertEquals(TransportAuditPlugin.FindingState.CAPTURED_SESSION,
			plugin.capturedState(3214, tile(2498, 9716, 0), true));
	}

	@Test
	public void operatorOneWayMarkerCompletesTheEdge()
	{
		TransportAuditPlugin plugin = plugin();
		int[] edge = {tile(2485, 9720, 0), tile(2485, 9722, 0), 3309};
		plugin.capturedEdges.add(edge);
		plugin.noReverseKeys.add("2485 9720 0|2485 9722 0|3309");
		plugin.recomputeEdgePairs();
		assertEquals(TransportAuditPlugin.FindingState.CAPTURED_SESSION,
			plugin.capturedState(3309, tile(2485, 9721, 0), true));
	}
}
