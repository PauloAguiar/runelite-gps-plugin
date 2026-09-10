package gps;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Plan step L10: the off-route bands, out of the plugin class. On route, warning, recalculate
 * (only on a move, only with auto-recalculate, cancel instead when preferred), the boat's
 * stretched bands, the grace window after a transport jump, and "no same-plane tile" as on route.
 */
public class OffRouteTrackerTest
{
	private static int at(int x)
	{
		return WorldPointUtil.packWorldPoint(3200 + x, 3200, 0);
	}

	@Test
	public void recalculationDisabledMeansNoWarningAndNoPathScan()
	{
		OffRouteTracker tracker = new OffRouteTracker();
		AtomicInteger scans = new AtomicInteger();
		assertEquals(OffRouteTracker.Verdict.ON_ROUTE,
			tracker.tick(at(0), scans::incrementAndGet, -1, 5, true, false, false));
		assertFalse(tracker.isWarning());
		assertEquals("the path scan is not paid for when nothing can come of it", 0, scans.get());
	}

	@Test
	public void theThreeBands()
	{
		OffRouteTracker tracker = new OffRouteTracker();
		tracker.reset(at(0));
		assertEquals(OffRouteTracker.Verdict.ON_ROUTE, tracker.tick(at(1), () -> 2, 10, 5, true, false, false));
		assertFalse(tracker.isWarning());
		assertEquals(2, tracker.distance());
		assertEquals(OffRouteTracker.Verdict.WARNING, tracker.tick(at(2), () -> 7, 10, 5, true, false, false));
		assertTrue(tracker.isWarning());
		assertEquals(OffRouteTracker.Verdict.RECALCULATE, tracker.tick(at(3), () -> 12, 10, 5, true, false, false));
		assertFalse("a recalculation clears the warning", tracker.isWarning());
	}

	@Test
	public void recalculationNeedsAMoveAndTheSettingAndMayCancelInstead()
	{
		OffRouteTracker tracker = new OffRouteTracker();
		tracker.reset(at(0));
		assertEquals("standing still far away (just teleported off-path) only warns",
			OffRouteTracker.Verdict.WARNING, tracker.tick(at(0), () -> 12, 10, 5, true, false, false));
		assertEquals("auto-recalculate off: keep the route, warn",
			OffRouteTracker.Verdict.WARNING, tracker.tick(at(1), () -> 12, 10, 5, false, false, false));
		assertEquals(OffRouteTracker.Verdict.CANCEL, tracker.tick(at(2), () -> 12, 10, 5, true, true, false));
	}

	@Test
	public void theHelmStretchesTheBands()
	{
		OffRouteTracker tracker = new OffRouteTracker();
		tracker.reset(at(0));
		// On foot 12 recalculates; aboard the recalc band is 20 and the warn band 15: 12 is on route.
		assertEquals(OffRouteTracker.Verdict.ON_ROUTE, tracker.tick(at(1), () -> 12, 10, 5, true, false, true));
		assertEquals(OffRouteTracker.Verdict.WARNING, tracker.tick(at(2), () -> 16, 10, 5, true, false, true));
		assertEquals(OffRouteTracker.Verdict.RECALCULATE, tracker.tick(at(3), () -> 20, 10, 5, true, false, true));
	}

	@Test
	public void aTransportJumpArmsAGraceWindowThatClearsBackOnThePath()
	{
		OffRouteTracker tracker = new OffRouteTracker();
		tracker.reset(at(0));
		// A 30-tile leap (a teleport landing) far from the path: not drifting, no warning.
		assertEquals(OffRouteTracker.Verdict.ON_ROUTE, tracker.tick(at(30), () -> 40, 10, 5, true, false, false));
		assertFalse(tracker.isWarning());
		// Walking on, still far: the grace holds for its window (19 more ticks).
		for (int i = 1; i <= 19; i++)
		{
			assertEquals("grace tick " + i, OffRouteTracker.Verdict.ON_ROUTE,
				tracker.tick(at(30 + i), () -> 40, 10, 5, true, false, false));
		}
		assertEquals("the window ran out: a move far off the path recalculates",
			OffRouteTracker.Verdict.RECALCULATE, tracker.tick(at(50), () -> 40, 10, 5, true, false, false));

		// A jump, then landing near the path: the grace clears at once.
		tracker.tick(at(90), () -> 40, 10, 5, true, false, false);
		assertEquals(OffRouteTracker.Verdict.ON_ROUTE, tracker.tick(at(91), () -> 1, 10, 5, true, false, false));
		assertEquals("cleared: the next far move recalculates",
			OffRouteTracker.Verdict.RECALCULATE, tracker.tick(at(92), () -> 40, 10, 5, true, false, false));
	}

	@Test
	public void noSamePlaneTileMeansOnRoute()
	{
		OffRouteTracker tracker = new OffRouteTracker();
		tracker.reset(at(0));
		assertEquals(OffRouteTracker.Verdict.ON_ROUTE, tracker.tick(at(1), () -> -1, 10, 5, true, false, false));
		assertFalse(tracker.isWarning());
		assertEquals(-1, tracker.distance());
	}
}
