package gps.transport;

import gps.WorldPointUtil;
import java.util.HashMap;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Xeric's Honour (Mount Quidamortem) requires using an Ancient tablet on the talisman once —
 * tracked by varbit 4916 (gameval ZEAH_TELEPORT_UNLOCKED; upstream issue #380). Both Honour
 * rows (the carried talisman and the POH teleport box) must carry that gate, so accounts that
 * never used the tablet aren't offered a locked teleport.
 */
public class XericHonourGateTest
{
	@Test
	public void bothHonourRowsAreGatedOnVarbit4916()
	{
		int honour = WorldPointUtil.packWorldPoint(1254, 3560, 0);
		HashMap<Integer, Set<Transport>> all = TransportLoader.loadAllFromResources();
		int checked = 0;
		for (Set<Transport> transports : all.values())
		{
			for (Transport transport : transports)
			{
				if (transport.getDestination() != honour)
				{
					continue;
				}
				String info = String.valueOf(transport.getDisplayInfo());
				if (!info.contains("Honour"))
				{
					continue;
				}
				checked++;
				assertFalse(info + " must carry a varbit requirement",
					transport.getVarRequirements().isEmpty());
				assertTrue(info + " must gate on varbit 4916",
					transport.getVarRequirements().stream().anyMatch(r -> r.toString().contains("4916")));
			}
		}
		assertTrue("expected both Honour rows (talisman + POH box), found " + checked, checked >= 2);
	}
}
