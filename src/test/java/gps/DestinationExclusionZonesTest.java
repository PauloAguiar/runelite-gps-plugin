package gps;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * ONE-WAY ZONES must never hold a searchable destination.
 *
 * Some places can be left but never returned to, so a pin there is unroutable forever — no
 * transport row can fix it, unlike the sealed-entrance backlog. Tutorial Island is the clear
 * case: its bank and altar were imported by the amenity dump and, because that dump names
 * amenities after the nearest labelled place, they even shipped as "Lumbridge Bank" and
 * "Lumbridge Swamp Altar" — indistinguishable from the real ones in search results.
 *
 * The dump is regenerated from the cache periodically, so this guards the exclusion rather than
 * trusting a one-off deletion.
 */
public class DestinationExclusionZonesTest
{
	/** {minX, minY, maxX, maxY, why} — areas that cannot be re-entered once left. */
	private static final Object[][] ONE_WAY_ZONES = {
		{3050, 3060, 3145, 3150, "Tutorial Island"},
	};

	@Test
	public void noDestinationSitsInAOneWayZone()
	{
		List<String> offenders = new ArrayList<>();
		for (Destinations.Entry entry : Destinations.resourceEntries())
		{
			int x = WorldPointUtil.unpackWorldX(entry.packedPosition);
			int y = WorldPointUtil.unpackWorldY(entry.packedPosition);
			for (Object[] zone : ONE_WAY_ZONES)
			{
				if (x >= (Integer) zone[0] && y >= (Integer) zone[1]
					&& x <= (Integer) zone[2] && y <= (Integer) zone[3])
				{
					offenders.add(zone[4] + ": " + entry.category + " / " + entry.name
						+ " @" + x + "," + y);
				}
			}
		}
		assertTrue("destinations inside one-way zones can never be routed to and must not ship:\n  "
			+ String.join("\n  ", offenders), offenders.isEmpty());
	}
}
