package gps;

import java.awt.Polygon;
import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Plan step L13: the minimap clip, out of the plugin class. The mask sprite's opaque region
 * becomes the clip polygon: each row contributes its left edge (appended) and its right edge
 * (prepended), so the points run down the left side and back up the right, offset to where the
 * minimap widget sits on screen.
 */
public class MinimapClipTest
{
	@Test
	public void maskRowsBecomeAClosedOutline()
	{
		// A 4x4 mask with the outside colour at the corner and an opaque 2x2 block in the middle.
		BufferedImage mask = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		for (int y = 1; y <= 2; y++)
		{
			for (int x = 1; x <= 2; x++)
			{
				mask.setRGB(x, y, 0xFFFFFFFF);
			}
		}
		Polygon outline = MinimapClip.polygonOf(mask, 10, 20);
		assertEquals(4, outline.npoints);
		assertArrayEquals(new int[]{13, 13, 11, 11}, java.util.Arrays.copyOf(outline.xpoints, 4));
		assertArrayEquals(new int[]{22, 21, 21, 22}, java.util.Arrays.copyOf(outline.ypoints, 4));
		assertTrue(outline.contains(12, 21.5));
		assertFalse(outline.contains(10.5, 21.5));
	}
}
