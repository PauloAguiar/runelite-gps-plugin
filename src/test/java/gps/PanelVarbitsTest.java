package gps;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Plan step L25: the panel's cached varbits, out of the plugin class. The house varbit maps to
 * a location name (none without a house or for an unknown id), and the balloon unlock varbits
 * map to the log types that have a route: normal logs need the quest (2), the rest a first
 * flight (1), in the log-storage order normal, oak, willow, yew, magic.
 */
public class PanelVarbitsTest
{
	@Test
	public void houseLocationNames()
	{
		assertNull(PanelVarbits.houseLocationName(0));
		assertEquals("Rimmington", PanelVarbits.houseLocationName(1));
		assertEquals("Aldarin", PanelVarbits.houseLocationName(9));
		assertNull(PanelVarbits.houseLocationName(10));
	}

	@Test
	public void balloonRoutesUnlockByVarbit()
	{
		// Entrana, Taverley, Castle Wars, Grand Tree, Crafting Guild, Varrock.
		assertArrayEquals(new boolean[]{false, false, false, false, false},
			PanelVarbits.balloonRoutesUnlocked(new int[]{1, 1, 0, 0, 0, 0}));
		assertArrayEquals("Taverley at 2 unlocks normal logs; Crafting Guild and Varrock are oak and willow",
			new boolean[]{true, true, true, false, false},
			PanelVarbits.balloonRoutesUnlocked(new int[]{0, 2, 0, 0, 1, 1}));
		assertArrayEquals("Castle Wars is yew, Grand Tree is magic",
			new boolean[]{false, false, false, true, true},
			PanelVarbits.balloonRoutesUnlocked(new int[]{0, 0, 1, 1, 0, 0}));
	}
}
