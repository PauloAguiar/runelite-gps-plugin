package gps;

import gps.transport.Transport;
import gps.transport.TransportLoader;
import gps.transport.parser.VarCheckType;
import gps.transport.parser.VarRequirement;
import gps.transport.parser.VarRequirement.VarType;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Varbit 2187 (POH_HOUSE_LOCATION) numbers the house locations 1-6 in release order, then
 * Hosidius 8, Prifddinas 9 and Aldarin 13; 7 and 10-12 are never set. The cache's enum 252 keys
 * the location names by exactly these values (tooling HouseLocationEnumProbeTest dumps it, and
 * upstream's commit 2c97f6ff fixed the same mistake from it). The data and the panel
 * used to read Prifddinas as 7 and Aldarin as 9, so a Prifddinas owner saw "Your house: Aldarin"
 * and was routed to the Aldarin portal (report 2026-11-08), and an Aldarin owner had no house
 * routes at all. Every house-portal row's value must name the location its outside tile sits in.
 */
public class HouseLocationVarbitTest
{
	private static final int HOUSE_LOCATION = 2187;
	private static final int HOUSE_TEMPLATE = WorldPointUtil.packWorldPoint(1923, 5709, 0);
	private static final Map<String, Integer> PORTALS = Map.of(
		"Rimmington", WorldPointUtil.packWorldPoint(2953, 3224, 0),
		"Taverley", WorldPointUtil.packWorldPoint(2893, 3465, 0),
		"Pollnivneach", WorldPointUtil.packWorldPoint(3340, 3003, 0),
		"Rellekka", WorldPointUtil.packWorldPoint(2670, 3631, 0),
		"Brimhaven", WorldPointUtil.packWorldPoint(2757, 3178, 0),
		"Yanille", WorldPointUtil.packWorldPoint(2544, 3096, 0),
		"Hosidius", WorldPointUtil.packWorldPoint(1743, 3517, 0),
		"Prifddinas", WorldPointUtil.packWorldPoint(3239, 6076, 0),
		"Aldarin", WorldPointUtil.packWorldPoint(1422, 2963, 0));

	@Test
	public void theNameTableFollowsTheGameValues()
	{
		assertEquals("Yanille", PanelVarbits.houseLocationName(6));
		assertEquals("Hosidius", PanelVarbits.houseLocationName(8));
		assertEquals("Prifddinas", PanelVarbits.houseLocationName(9));
		assertEquals("Aldarin", PanelVarbits.houseLocationName(13));
		for (int unused : new int[]{0, 7, 10, 11, 12, 14})
		{
			assertEquals("value " + unused + " is no location", null, PanelVarbits.houseLocationName(unused));
		}
	}

	@Test
	public void everyHousePortalRowLandsInTheLocationItsValueNames()
	{
		Set<Integer> valuesSeen = new HashSet<>();
		int rows = 0;
		for (Set<Transport> set : TransportLoader.loadAllFromResources().values())
		{
			for (Transport transport : set)
			{
				int value = houseLocationValue(transport);
				if (value < 0)
				{
					continue;
				}
				rows++;
				valuesSeen.add(value);
				String name = PanelVarbits.houseLocationName(value);
				assertNotNull("value " + value + " names a location (" + transport.getDisplayInfo() + ")", name);
				// Teleports have no origin; Home Portal rows lead into the house template, exits out of it.
				int outside = transport.getDestination() == HOUSE_TEMPLATE ? transport.getOrigin() : transport.getDestination();
				int distance = WorldPointUtil.distanceBetween(PORTALS.get(name), outside);
				assertTrue(transport.getDisplayInfo() + " (2187=" + value + ") lands " + distance + " tiles from the "
					+ name + " portal", distance <= 4);
			}
		}
		assertTrue("the house rows load: " + rows, rows >= 60);
		assertEquals(Set.of(1, 2, 3, 4, 5, 6, 8, 9, 13), valuesSeen);
		assertFalse(valuesSeen.contains(7));
	}

	/** The value a row's single "2187=N" clause demands, or -1 for rows without one. */
	private static int houseLocationValue(Transport transport)
	{
		for (VarRequirement requirement : transport.getVarRequirements())
		{
			if (requirement.getVarType() == VarType.VARBIT && requirement.getId() == HOUSE_LOCATION
				&& requirement.getCheckType() == VarCheckType.EQUAL && requirement.getAlternatives() == null)
			{
				return requirement.getValue();
			}
		}
		return -1;
	}
}
