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
		PohScanner.Detected d = PohScanner.detect(Set.of(1, 2, 3), Set.of());
		assertFalse(d.any());
		assertEquals(JewelleryBoxTier.NONE, d.jewelleryBox);
	}

	@Test
	public void recognisesEachCleanFeature()
	{
		PohScanner.Detected d = PohScanner.detect(Set.of(
			ObjectID.POH_FAIRY_RING, ObjectID.POH_SPIRIT_TREE, ObjectID.POH_WILDERNESS_OBELISK,
			ObjectID.POH_JEWELLERY_BOX_2), Set.of());
		assertTrue(d.fairyRing);
		assertTrue(d.spiritTree);
		assertTrue(d.obelisk);
		assertEquals(JewelleryBoxTier.FANCY, d.jewelleryBox);
		assertTrue(d.any());
	}

	@Test
	public void spawnRelevanceMatchesWhatDetectRecognises()
	{
		// Every id detect() reacts to must be flagged, so the spawn-event fast path never misses
		// furniture the tile scan would have found.
		for (int id : new int[]{
			ObjectID.POH_FAIRY_RING, ObjectID.POH_FAIRY_HOUSE, ObjectID.POH_FAIRY_HOUSE_OPEN,
			ObjectID.POH_SPIRIT_TREE, ObjectID.POH_WILDERNESS_OBELISK,
			ObjectID.POH_JEWELLERY_BOX_1, ObjectID.POH_JEWELLERY_BOX_2, ObjectID.POH_JEWELLERY_BOX_3})
		{
			assertTrue(String.valueOf(id), PohScanner.isRecognised(id, Set.of()));
			assertTrue(PohScanner.detect(Set.of(id), Set.of()).any());
		}
		assertFalse(PohScanner.isRecognised(1, Set.of()));
	}

	@Test
	public void encodeDecodeRoundTripsEveryCombination()
	{
		for (JewelleryBoxTier tier : JewelleryBoxTier.values())
		{
			for (int flags = 0; flags < 8; flags++)
			{
				PohScanner.Detected original = new PohScanner.Detected(
					(flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, tier, Set.of(), false);
				PohScanner.Detected decoded = PohScanner.decode(PohScanner.encode(original));
				assertTrue(tier + "/" + flags, original.sameAs(decoded));
			}
		}
	}

	@Test
	public void portalChamberPortalsAndTheNexusAreDetected()
	{
		// The portal rows carry the built portals' object ids (Trollheim 60790/60800/60810 by material,
		// game cache 2026-10-13); the nexus is 33408..33410. An empty chamber offered every portal.
		Set<Integer> portalIds = Set.of(60790, 60800, 60810, 13616);
		PohScanner.Detected d = PohScanner.detect(Set.of(60800, 33409, 7), portalIds);
		assertEquals(Set.of(60800), d.portals);
		assertTrue(d.nexus);
		assertTrue(d.any());
		assertTrue(PohScanner.isRecognised(60800, portalIds));
		assertTrue(PohScanner.isRecognised(33409, Set.of()));
		assertFalse(PohScanner.isRecognised(60800, Set.of()));

		PohScanner.Detected decoded = PohScanner.decode(PohScanner.encode(d));
		assertTrue(d.sameAs(decoded));
		assertEquals(Set.of(60800), decoded.portals);
		assertTrue(decoded.nexus);
		assertFalse(PohScanner.detect(Set.of(60800), portalIds).sameAs(PohScanner.detect(Set.of(60790), portalIds)));
		// Stored scans from before portals were read decode with none, not as never scanned.
		assertEquals(Set.of(), PohScanner.decode("NONE,fairyRing").portals);
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
			ObjectID.POH_JEWELLERY_BOX_1, ObjectID.POH_JEWELLERY_BOX_3), Set.of()).jewelleryBox);
		assertEquals(JewelleryBoxTier.BASIC, PohScanner.detect(Set.of(
			ObjectID.POH_JEWELLERY_BOX_1), Set.of()).jewelleryBox);
	}

	@Test
	public void openFairyHouseAlsoCounts()
	{
		assertTrue(PohScanner.detect(Set.of(ObjectID.POH_FAIRY_HOUSE_OPEN), Set.of()).fairyRing);
	}
}
