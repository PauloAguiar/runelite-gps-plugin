package gps;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Minigame-only interiors (destination-exclusions.tsv) hold no destination pins: their amenities
 * serve the activity, not overworld routing. Field call 2026-08-27 for the Trouble Brewing
 * arena's water sources - "there is no point in routing to it".
 */
public class DestinationExclusionsTest
{
	@Test
	public void troubleBrewingAmenitiesAreNotDestinations()
	{
		boolean anyWaterSourceElsewhere = false;
		for (Destinations.Entry entry : Destinations.resourceEntries())
		{
			assertFalse("a pin inside an excluded minigame interior must not load: "
					+ entry.category + " / " + entry.name,
				Destinations.insideExcludedZone(entry.packedPosition));
			assertFalse("the Trouble Brewing water sources are minigame-only",
				"Mos Le'Harmless Water source".equals(entry.name));
			anyWaterSourceElsewhere |= entry.name != null && entry.name.contains("Water source");
		}
		assertTrue("real water sources elsewhere still load", anyWaterSourceElsewhere);
	}
}
