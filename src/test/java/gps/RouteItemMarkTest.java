package gps;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Quest Helper draws its item ring from an always-on-top overlay, after the inventory's own draw
 * hook where this plugin's mark lands, and the two rings have the same shape: on an item both
 * plugins want, Quest Helper's covers ours. The mark is therefore the item's outline thickened by
 * one pixel outward, so a ring in this plugin's colour stays visible around Quest Helper's.
 */
public class RouteItemMarkTest
{
	private static final int GOLD = new Color(0xF2, 0xC1, 0x4E).getRGB();
	private static final int CYAN = Color.CYAN.getRGB();

	@Test
	public void theOutlineGrowsOneRingOutward()
	{
		// A one-pixel sprite at (2,2) and the client's ring around it: its eight neighbours.
		BufferedImage sprite = new BufferedImage(5, 5, BufferedImage.TYPE_INT_ARGB);
		sprite.setRGB(2, 2, Color.WHITE.getRGB());
		BufferedImage ring = new BufferedImage(5, 5, BufferedImage.TYPE_INT_ARGB);
		for (int x = 1; x <= 3; x++)
			for (int y = 1; y <= 3; y++)
				if (x != 2 || y != 2)
					ring.setRGB(x, y, CYAN);

		BufferedImage mark = RouteItemMark.thickened(sprite, ring, GOLD);

		assertEquals("one pixel of margin on each side", 7, mark.getWidth());
		assertEquals(7, mark.getHeight());
		Set<String> inner = new HashSet<>();
		Set<String> outer = new HashSet<>();
		Set<String> clear = new HashSet<>();
		for (int x = 0; x < 7; x++)
		{
			for (int y = 0; y < 7; y++)
			{
				int rgb = mark.getRGB(x, y);
				if (rgb == CYAN)
					inner.add(x + "," + y);
				else if (rgb == GOLD)
					outer.add(x + "," + y);
				else if ((rgb >>> 24) == 0)
					clear.add(x + "," + y);
				else
					throw new AssertionError("unexpected colour at " + x + "," + y);
			}
		}
		Set<String> expectedInner = new HashSet<>();
		Set<String> expectedOuter = new HashSet<>();
		for (int x = 1; x <= 5; x++)
		{
			for (int y = 1; y <= 5; y++)
			{
				boolean ring1 = x >= 2 && x <= 4 && y >= 2 && y <= 4 && !(x == 3 && y == 3);
				if (ring1)
					expectedInner.add(x + "," + y);
				else if (!(x == 3 && y == 3))
					expectedOuter.add(x + "," + y);
			}
		}
		assertEquals("the client's ring, shifted by the margin, keeps its colour", expectedInner, inner);
		assertEquals("every pixel touching the ring from outside joins it, in the plugin's colour", expectedOuter, outer);
		assertEquals("the sprite itself and the far margin stay clear", 49 - 8 - 16, clear.size());
	}

	@Test
	public void aRingAtTheSpriteEdgeStillGetsItsOuterPixels()
	{
		// A sprite pixel in the corner: the client's ring is clipped to the image, the thickening is not.
		BufferedImage sprite = new BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB);
		sprite.setRGB(0, 0, Color.WHITE.getRGB());
		BufferedImage ring = new BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB);
		ring.setRGB(1, 0, CYAN);
		ring.setRGB(0, 1, CYAN);
		ring.setRGB(1, 1, CYAN);

		BufferedImage mark = RouteItemMark.thickened(sprite, ring, GOLD);

		assertEquals(GOLD, mark.getRGB(3, 0));
		assertEquals(GOLD, mark.getRGB(0, 3));
		assertEquals(GOLD, mark.getRGB(3, 3));
		assertEquals("the sprite pixel stays clear", 0, mark.getRGB(1, 1) >>> 24);
	}
}
