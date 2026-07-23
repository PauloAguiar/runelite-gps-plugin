package gps;

import gps.transport.Transport;
import gps.transport.TransportLoader;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The Underground Pass fire-arrow bridge (field-authored via the transport builder): the row is
 * advisory, not gated — ANY fire arrow type works and the player prepares it themselves, so the
 * Items column stays empty and the Note carries the warning instead. Duration is an estimate
 * (~20t covers lighting and shooting), Meta-tagged ~duration for later field measurement.
 */
public class FireArrowBridgeTest
{
	@Test
	public void guideRopeRowLoadsWithNoteAndEstimatedTicks()
	{
		int origin = WorldPointUtil.packWorldPoint(2447, 9722, 0);
		int dest = WorldPointUtil.packWorldPoint(2443, 9716, 0);
		Transport bridge = TransportLoader.loadAllFromResources()
			.getOrDefault(origin, Set.of()).stream()
			.filter(t -> t.getDestination() == dest)
			.findFirst().orElse(null);
		assertNotNull("guide rope row must load", bridge);
		assertEquals(20, bridge.getDuration());
		assertEquals("Needs a bow and a lit fire arrow", bridge.getNote());
		assertTrue("no item gating — the note advises instead", bridge.getItemRequirements() == null);
	}
}
