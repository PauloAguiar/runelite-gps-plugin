package gps;

import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import net.runelite.api.gameval.ObjectID;

public class PohScannerTest
{
	@Test
	public void emptyHouseDetectsNothing()
	{
		PohScanner.Detected d = PohScanner.detect(Set.of(1, 2, 3));
		assertFalse(d.any());
		assertEquals(JewelleryBoxTier.NONE, d.jewelleryBox);
	}

	@Test
	public void recognisesEachCleanFeature()
	{
		PohScanner.Detected d = PohScanner.detect(Set.of(
			ObjectID.POH_FAIRY_RING, ObjectID.POH_SPIRIT_TREE, ObjectID.POH_WILDERNESS_OBELISK,
			ObjectID.POH_JEWELLERY_BOX_2));
		assertTrue(d.fairyRing);
		assertTrue(d.spiritTree);
		assertTrue(d.obelisk);
		assertEquals(JewelleryBoxTier.FANCY, d.jewelleryBox);
		assertTrue(d.any());
	}

	@Test
	public void encodeDecodeRoundTripsEveryCombination()
	{
		for (JewelleryBoxTier tier : JewelleryBoxTier.values())
		{
			for (int flags = 0; flags < 8; flags++)
			{
				PohScanner.Detected original = new PohScanner.Detected(
					(flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, tier);
				PohScanner.Detected decoded = PohScanner.decode(PohScanner.encode(original));
				assertTrue(tier + "/" + flags, original.sameAs(decoded));
			}
		}
	}

	@Test
	public void missingOrMalformedStoredDataDecodesToNull()
	{
		assertEquals(null, PohScanner.decode(null));
		assertEquals(null, PohScanner.decode(""));
		assertEquals(null, PohScanner.decode("garbage,fairyRing"));
		assertEquals(null, PohScanner.encode(null));
		// Duplicate flags are tolerated, not fatal.
		assertTrue(PohScanner.decode("NONE,fairyRing,fairyRing").fairyRing);
	}

	@Test
	public void jewelleryBoxTakesTheHighestTierPresent()
	{
		// A house upgraded to ornate may still show the lower box ids; the highest wins.
		assertEquals(JewelleryBoxTier.ORNATE, PohScanner.detect(Set.of(
			ObjectID.POH_JEWELLERY_BOX_1, ObjectID.POH_JEWELLERY_BOX_3)).jewelleryBox);
		assertEquals(JewelleryBoxTier.BASIC, PohScanner.detect(Set.of(
			ObjectID.POH_JEWELLERY_BOX_1)).jewelleryBox);
	}

	@Test
	public void openFairyHouseAlsoCounts()
	{
		assertTrue(PohScanner.detect(Set.of(ObjectID.POH_FAIRY_HOUSE_OPEN)).fairyRing);
	}
}
