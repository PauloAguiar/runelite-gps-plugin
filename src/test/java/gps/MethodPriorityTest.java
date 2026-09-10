package gps;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The tier enum's own arithmetic and labels; the ranking it produces is RoutePreferencesTest.
 */
public class MethodPriorityTest
{
	@Test
	public void secondsConvertToCostUnits()
	{
		// 1 tick = 0.6s, 2 units per tick.
		assertEquals(17, MethodPriority.unitsFromSeconds(5));
		assertEquals(33, MethodPriority.unitsFromSeconds(10));
		assertEquals(67, MethodPriority.unitsFromSeconds(20));
		assertEquals(-17, MethodPriority.unitsFromSeconds(-5));
		assertEquals(0, MethodPriority.unitsFromSeconds(0));
	}

	@Test
	public void chipTextIsSignedSeconds()
	{
		assertEquals("−10s", MethodPriority.PREFER_2.chipText());
		assertEquals("+5s", MethodPriority.AVOID_1.chipText());
		assertEquals("", MethodPriority.NORMAL.chipText());
	}
}
