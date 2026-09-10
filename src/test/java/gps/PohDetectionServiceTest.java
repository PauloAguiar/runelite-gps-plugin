package gps;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan step L11: house-furniture detection, out of the plugin class. Inside-the-house evidence
 * comes from the template regions or from spawned furniture; a scan repeats per tick until it
 * finds something, capped at six attempts for a bare house; leaving the house or leaving
 * building mode re-arms it; declarations are only ever raised; the result persists once per
 * change and restores when nothing was scanned this session.
 */
@RunWith(MockitoJUnitRunner.class)
public class PohDetectionServiceTest
{
	@Mock
	ConfigManager configManager;

	private static final String GROUP = "gps";

	/** A scene the test controls: whether it is a house, and which object ids it holds. */
	private static final class FakeScene implements PohDetectionService.Scene
	{
		boolean house;
		final Set<Integer> ids = new HashSet<>();
		int walks;

		@Override
		public boolean isHouse()
		{
			return house;
		}

		@Override
		public boolean isInstance()
		{
			return house;
		}

		@Override
		public String describeChunks()
		{
			return "chunks";
		}

		@Override
		public Set<Integer> objectIds()
		{
			walks++;
			return ids;
		}
	}

	/** Declarations that record every raise; a raise takes effect, as the real config does. */
	private static final class FakeDeclarations implements PohDetectionService.Declarations
	{
		boolean fairyRing;
		boolean spiritTree;
		boolean obelisk;
		JewelleryBoxTier tier = JewelleryBoxTier.NONE;
		final List<String> raised = new ArrayList<>();

		@Override
		public boolean fairyRing()
		{
			return fairyRing;
		}

		@Override
		public boolean spiritTree()
		{
			return spiritTree;
		}

		@Override
		public boolean obelisk()
		{
			return obelisk;
		}

		@Override
		public JewelleryBoxTier jewelleryBoxTier()
		{
			return tier;
		}

		/** Records the raise and applies it, as the real config does for the next scan. */
		@Override
		public void raise(String key, Object value)
		{
			raised.add(key + "=" + value);
			switch (key)
			{
				case "usePohFairyRing":
					fairyRing = true;
					break;
				case "usePohSpiritTree":
					spiritTree = true;
					break;
				case "usePohObelisk":
					obelisk = true;
					break;
				case "pohJewelleryBoxTier":
					tier = (JewelleryBoxTier) value;
					break;
				default:
					throw new IllegalArgumentException(key);
			}
		}
	}

	private final FakeScene scene = new FakeScene();
	private final FakeDeclarations declarations = new FakeDeclarations();
	private boolean building;
	private boolean smartDetect = true;
	private final AtomicInteger changes = new AtomicInteger();

	private PohDetectionService service()
	{
		return new PohDetectionService(() -> scene, () -> building, () -> smartDetect, declarations,
			configManager, GROUP, changes::incrementAndGet);
	}

	@Test
	public void outsideAHouseNothingIsScanned()
	{
		PohDetectionService service = service();
		scene.ids.add(ObjectID.POH_FAIRY_RING);
		service.onTick();
		service.onTick();
		assertFalse(service.isScanned());
		assertEquals(0, scene.walks);
		verify(configManager, never()).setRSProfileConfiguration(GROUP, PohDetectionService.CONFIG_KEY_POH_FURNITURE, "x");
	}

	@Test
	public void insideAHouseTheFirstFindRaisesDeclarationsPersistsAndStopsRescanning()
	{
		PohDetectionService service = service();
		scene.house = true;
		scene.ids.add(ObjectID.POH_FAIRY_RING);
		scene.ids.add(ObjectID.POH_JEWELLERY_BOX_2);
		declarations.tier = JewelleryBoxTier.BASIC;

		service.onTick();
		assertTrue(service.isScanned());
		assertEquals(List.of("Fancy jewellery box", "Fairy ring"), service.detectedNames());
		assertEquals("only what was off or lower is raised", List.of("usePohFairyRing=true", "pohJewelleryBoxTier=Fancy"),
			declarations.raised);
		verify(configManager).setRSProfileConfiguration(GROUP, PohDetectionService.CONFIG_KEY_POH_FURNITURE, "FANCY,fairyRing");
		assertEquals(1, changes.get());

		service.onTick();
		service.onTick();
		assertEquals("found this visit: no rescans", 1, scene.walks);
	}

	@Test
	public void aBareHouseIsScannedSixTimesThenLeftAlone()
	{
		PohDetectionService service = service();
		scene.house = true;
		for (int i = 0; i < 10; i++)
		{
			service.onTick();
		}
		assertEquals(PohDetectionService.MAX_ATTEMPTS, scene.walks);
		assertTrue("scanned, with nothing", service.isScanned());
		assertTrue(service.detectedNames().isEmpty());
		assertEquals("persisted once (the empty result), then unchanged", 1, changes.get());
	}

	@Test
	public void spawnedFurnitureIsInsideEvidenceAndJoinsTheScan()
	{
		PohDetectionService service = service();
		service.furnitureSpawned(ObjectID.POH_SPIRIT_TREE);
		service.furnitureSpawned(4151); // not furniture
		service.onTick();
		assertTrue("the scene was not judged a house, the spawn was proof enough", service.isScanned());
		assertEquals(List.of("Spirit tree"), service.detectedNames());
		assertEquals(List.of("usePohSpiritTree=true"), declarations.raised);
	}

	@Test
	public void leavingBuildingModeReArmsTheScanAndOnlyChangesPersist()
	{
		PohDetectionService service = service();
		scene.house = true;
		scene.ids.add(ObjectID.POH_FAIRY_RING);
		service.onTick();
		assertEquals(1, scene.walks);

		// Building: the furniture is unchanged, and the scan does not re-run by itself.
		building = true;
		service.onTick();
		assertEquals(1, scene.walks);
		// Built a spirit tree, left building mode: one more scan, the new piece raised.
		scene.ids.add(ObjectID.POH_SPIRIT_TREE);
		building = false;
		service.onTick();
		assertEquals(2, scene.walks);
		assertEquals(List.of("usePohFairyRing=true", "usePohSpiritTree=true"), declarations.raised);
		verify(configManager, times(2)).setRSProfileConfiguration(org.mockito.ArgumentMatchers.eq(GROUP),
			org.mockito.ArgumentMatchers.eq(PohDetectionService.CONFIG_KEY_POH_FURNITURE), org.mockito.ArgumentMatchers.anyString());

		// Out of the house and back in: a fresh visit scans again; nothing changed, so no persist.
		scene.house = false;
		service.onTick();
		scene.house = true;
		service.onTick();
		assertEquals(3, scene.walks);
		verify(configManager, times(2)).setRSProfileConfiguration(org.mockito.ArgumentMatchers.eq(GROUP),
			org.mockito.ArgumentMatchers.eq(PohDetectionService.CONFIG_KEY_POH_FURNITURE), org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	public void smartDetectOffMeansNoScan()
	{
		PohDetectionService service = service();
		scene.house = true;
		scene.ids.add(ObjectID.POH_FAIRY_RING);
		smartDetect = false;
		service.onTick();
		assertFalse(service.isScanned());
		assertEquals(0, scene.walks);
	}

	@Test
	public void restoreReadsTheSnapshotOnlyWhenNothingWasScannedAndResetForgets()
	{
		when(configManager.getRSProfileConfiguration(GROUP, PohDetectionService.CONFIG_KEY_POH_FURNITURE))
			.thenReturn("ORNATE,obelisk");
		PohDetectionService service = service();
		service.restore();
		assertTrue(service.isScanned());
		assertEquals(List.of("Ornate jewellery box", "Obelisk"), service.detectedNames());

		service.reset();
		assertFalse(service.isScanned());
		assertTrue(service.detectedNames().isEmpty());
	}
}
