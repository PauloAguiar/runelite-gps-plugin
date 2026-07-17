package gps;

import net.runelite.api.WorldView;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the "is this scene a player-owned house" check: an instance whose map regions (the
 * template regions the scene is assembled from) include one of the POH template regions
 * (rx 29-32, ry 110-111 — regions 7534-8303). Confirmed against a real house's chunk dump, a
 * game-cache scan, and other plugins' POH region lists (2026-07-17); the transport data's POH
 * model band (y 5696) is NOT what live houses are built from, and checking it was why house
 * detection failed in the field.
 */
public class PohSceneDetectionTest
{
	private static WorldView instanceWithRegions(int... regions)
	{
		WorldView worldView = Mockito.mock(WorldView.class);
		Mockito.when(worldView.isInstance()).thenReturn(true);
		Mockito.when(worldView.getMapRegions()).thenReturn(regions);
		return worldView;
	}

	@Test
	public void everyPohTemplateRegionIsDetected()
	{
		for (int region : new int[]{7534, 7535, 7790, 7791, 8046, 8047, 8302, 8303})
		{
			assertTrue(String.valueOf(region),
				ShortestPathPlugin.isPohScene(instanceWithRegions(region)));
		}
	}

	@Test
	public void realHouseRegionSetIsDetected()
	{
		// The house whose chunk dump identified the band mapped entirely into region 7534.
		assertTrue(ShortestPathPlugin.isPohScene(instanceWithRegions(7534)));
	}

	@Test
	public void nonHouseInstancesAreNotDetected()
	{
		// A raid or cutscene instance is assembled from other regions.
		assertFalse(ShortestPathPlugin.isPohScene(instanceWithRegions(12889, 13136)));
		// The transport data's POH model band (region 7513, y 5696) is not a live house.
		assertFalse(ShortestPathPlugin.isPohScene(instanceWithRegions(7513)));
		assertFalse(ShortestPathPlugin.isPohScene(instanceWithRegions()));
		assertFalse(ShortestPathPlugin.isPohScene(instanceWithRegions((int[]) null)));
	}

	@Test
	public void nonInstanceWorldsAreNeverAHouse()
	{
		assertFalse(ShortestPathPlugin.isPohScene(null));
		// Standing OUTSIDE a house portal the overworld is not an instance, whatever its regions.
		WorldView overworld = Mockito.mock(WorldView.class);
		Mockito.when(overworld.isInstance()).thenReturn(false);
		assertFalse(ShortestPathPlugin.isPohScene(overworld));
	}
}
