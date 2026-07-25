package gps;

import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Template-only zones (destination-remaps.tsv): a target inside pure-scenery geometry — like
 * Iban's Temple's visible interior, whose real version is instanced — snaps to the zone's
 * anchor tile instead of erroring as unreachable. Clue steps and Quest Helper tiles land on
 * these regularly (gps-capture-20260724-212231 was a map pin on the temple's well platform).
 */
public class DestinationRemapTest
{
	private static final int TEMPLE_DOORS = WorldPointUtil.packWorldPoint(2145, 4647, 1);

	@Test
	public void templeInteriorSnapsToTheDoors()
	{
		int wellPlatform = WorldPointUtil.packWorldPoint(2139, 4647, 1);
		assertEquals(TEMPLE_DOORS, Destinations.remapTemplateOnlyZones(wellPlatform));
		// walkableTargets applies the remap before its ring logic, so every routing flow
		// (map pin, Quest Helper, panel search) gets the anchor as its target set.
		assertEquals(Set.of(TEMPLE_DOORS), Destinations.walkableTargets(null, wellPlatform));
	}

	@Test
	public void boxEdgesAndOtherPlanesPassThrough()
	{
		int antechamber = WorldPointUtil.packWorldPoint(2145, 4647, 1);
		assertEquals(antechamber, Destinations.remapTemplateOnlyZones(antechamber));
		int belowTemple = WorldPointUtil.packWorldPoint(2139, 4647, 0);
		assertEquals(belowTemple, Destinations.remapTemplateOnlyZones(belowTemple));
	}
}
