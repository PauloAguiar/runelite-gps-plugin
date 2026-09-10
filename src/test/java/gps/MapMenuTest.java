package gps;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Plan step L22: the map menu, out of the plugin class. A world-map icon's menu target becomes
 * the destination key the transport data uses: colour tags stripped, lower case, letters and
 * spaces only, words joined by single underscores.
 */
public class MapMenuTest
{
	@Test
	public void iconTargetsBecomeDestinationKeys()
	{
		assertEquals("grand_exchange", MapMenu.simplify("<col=ff9040>Grand Exchange</col>"));
		assertEquals("slayer_master_konar", MapMenu.simplify("Slayer Master (Konar)"));
		assertEquals("fairy_ring", MapMenu.simplify("Fairy ring"));
	}
}
