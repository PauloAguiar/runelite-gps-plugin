package gps;

import java.awt.Color;
import java.awt.Image;
import java.awt.image.BufferedImage;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;

/**
 * The widget mark for the route's next item, built once per item and kept by the overlay: the
 * client's outline of the sprite thickened by one pixel outward, plus a light fill of the sprite.
 * Quest Helper marks its items with the same one-pixel ring and fill from an always-on-top overlay,
 * which draws after the inventory's own draw hook where this mark lands; on an item both plugins
 * want, its ring covers the inner pixel of ours and the outer ring stays visible in this plugin's
 * colour. The outline is drawn one pixel up and left of the slot, the fill at the slot.
 */
final class RouteItemMark {
    final BufferedImage outline;
    final Image fill;

    private RouteItemMark(BufferedImage outline, Image fill) {
        this.outline = outline;
        this.fill = fill;
    }

    static RouteItemMark of(ItemManager itemManager, int itemId, int quantity, Color colour) {
        BufferedImage sprite = itemManager.getImage(itemId, quantity, false);
        BufferedImage ring = itemManager.getItemOutline(itemId, quantity, colour);
        return new RouteItemMark(thickened(sprite, ring, colour.getRGB()),
            ImageUtil.fillImage(sprite, ColorUtil.colorWithAlpha(colour, 65)));
    }

    /**
     * The ring plus every pixel touching it from outside the sprite, in {@code rgb}, on a canvas one
     * pixel larger on each side so a ring at the sprite's edge still grows.
     */
    static BufferedImage thickened(BufferedImage sprite, BufferedImage ring, int rgb) {
        int w = ring.getWidth(), h = ring.getHeight();
        BufferedImage out = new BufferedImage(w + 2, h + 2, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < w + 2; x++) {
            for (int y = 0; y < h + 2; y++) {
                int ringPixel = pixel(ring, x - 1, y - 1);
                if (ringPixel >>> 24 != 0) {
                    out.setRGB(x, y, ringPixel);
                    continue;
                }
                if (pixel(sprite, x - 1, y - 1) >>> 24 == 0 && touchesRing(ring, x - 1, y - 1))
                    out.setRGB(x, y, rgb);
            }
        }
        return out;
    }

    private static boolean touchesRing(BufferedImage ring, int x, int y) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                if (pixel(ring, x + dx, y + dy) >>> 24 != 0)
                    return true;
        return false;
    }

    private static int pixel(BufferedImage image, int x, int y) {
        return x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight() ? 0 : image.getRGB(x, y);
    }
}
