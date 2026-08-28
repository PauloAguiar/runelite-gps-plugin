package gps;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

/**
 * Capture 20260827-223426: the direct-sail leg's track computed fine offline yet never rendered
 * in the client - a failed early computation had poisoned the track cache permanently (nulls
 * were cached forever). Failures now cool down and retry; successes cache as before.
 */
public class SeaTrackTest
{
	@Test
	public void goodTracksCacheAndFailuresStayRetryable()
	{
		int from = WorldPointUtil.packWorldPoint(2873, 3221, 0);
		int to = WorldPointUtil.packWorldPoint(2897, 3335, 0);
		int[] track = SailingSea.seaPathBlocking(from, to);
		assertTrue("the capture's direct-sail track must compute", track != null && track.length > 1);
		assertTrue("a good track is cached", SailingSea.trackPermanentlyCached(from, to));

		// A land-to-land pair can never produce a track: the failure must NOT poison the cache.
		int landA = WorldPointUtil.packWorldPoint(3222, 3218, 0);
		int landB = WorldPointUtil.packWorldPoint(3165, 3487, 0);
		SailingSea.seaPathBlocking(landA, landB);
		assertFalse("an uncomputable pair must stay retryable, not cached as a permanent null",
			SailingSea.trackPermanentlyCached(landA, landB));
	}
}
