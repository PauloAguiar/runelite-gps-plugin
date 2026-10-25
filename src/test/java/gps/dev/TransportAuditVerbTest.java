package gps.dev;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Which menu actions the dev audit treats as "using this object moves the player". "Activate"
 * is kept for the Mount Karuulm elevator, so the Nightmare Zone power-ups, whose Activate is a
 * buff, are excused by object id (the audit was lighting them up during every game, 2026-10-25).
 */
public class TransportAuditVerbTest
{
	@Test
	public void nightmareZonePowerUpsAreNotTransports()
	{
		for (int powerUp : new int[]{26256, 26264, 26265, 26266})
		{
			assertFalse("power-up " + powerUp, TransportAuditPlugin.isTraversalAction(powerUp, "Activate", false));
		}
	}

	@Test
	public void activateStillCountsOnOtherObjects()
	{
		assertTrue(TransportAuditPlugin.isTraversalAction(34660, "Activate", false));
	}

	@Test
	public void traversalVerbsAndWallDoorsCount()
	{
		assertTrue(TransportAuditPlugin.isTraversalAction(26256, "Climb-up", false));
		assertTrue(TransportAuditPlugin.isTraversalAction(5, "Open", true));
		assertFalse("a non-wall Open is not a door", TransportAuditPlugin.isTraversalAction(5, "Open", false));
		assertFalse(TransportAuditPlugin.isTraversalAction(5, "Examine", false));
	}
}
