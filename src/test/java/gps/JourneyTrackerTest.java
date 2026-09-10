package gps;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Plan step L6: the journey timer, out of the plugin class. It starts on the first tick the
 * player moved since arming or is performing an animation (a teleport cast counts even without
 * moving), never on the tick that merely records where the player stands, and re-arming resets
 * it so a new destination or a newly chosen path is timed from its own first action.
 */
public class JourneyTrackerTest
{
	private static final int A = WorldPointUtil.packWorldPoint(3200, 3200, 0);
	private static final int B = WorldPointUtil.packWorldPoint(3201, 3200, 0);

	@Test
	public void standingStillAfterArmingStartsNothing()
	{
		JourneyTracker journey = new JourneyTracker();
		journey.arm();
		journey.tick(A, false, 1_000);
		journey.tick(A, false, 2_000);
		assertEquals(0, journey.startMillis());
		assertEquals("a never-started journey reports zero", 0, journey.elapsedMillis(9_000));
	}

	@Test
	public void theFirstMoveStartsTheClock()
	{
		JourneyTracker journey = new JourneyTracker();
		journey.arm();
		journey.tick(A, false, 1_000);
		journey.tick(B, false, 2_000);
		journey.tick(B, false, 3_000);
		assertEquals(2_000, journey.startMillis());
		assertEquals(5_000, journey.elapsedMillis(7_000));
	}

	@Test
	public void anAnimationStartsTheClockWithoutMoving()
	{
		JourneyTracker journey = new JourneyTracker();
		journey.arm();
		journey.tick(A, true, 1_500);
		assertEquals("a teleport cast is the first action", 1_500, journey.startMillis());
		journey.tick(A, false, 2_500);
		assertEquals("the start does not move once set", 1_500, journey.startMillis());
	}

	@Test
	public void reArmingForgetsTheOldJourney()
	{
		JourneyTracker journey = new JourneyTracker();
		journey.arm();
		journey.tick(A, false, 1_000);
		journey.tick(B, false, 2_000);
		journey.arm();
		assertEquals(0, journey.startMillis());
		// The first tick after re-arming only records the position: no phantom move from the
		// forgotten location.
		journey.tick(A, false, 3_000);
		assertEquals(0, journey.startMillis());
		journey.tick(B, false, 4_000);
		assertEquals(4_000, journey.startMillis());
	}
}
