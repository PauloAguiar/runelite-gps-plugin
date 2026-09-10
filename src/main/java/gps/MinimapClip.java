package gps;

import java.awt.Color;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.SpriteManager;

/**
 * The minimap's clip shape (plan step L13, out of the plugin class): the outline of the fixed or
 * resizable mask sprite, positioned where the minimap widget sits, cached until the widget moves
 * or changes size; an ellipse over the widget bounds when the sprite is not loaded yet. The
 * minimap overlay clips to it and the map menu tests the mouse against it.
 */
final class MinimapClip
{
	private final Client client;
	private final SpriteManager spriteManager;

	private Shape clipFixed;
	private Shape clipResizeable;
	private BufferedImage spriteFixed;
	private BufferedImage spriteResizeable;
	private Rectangle rectangle = new Rectangle();

	MinimapClip(Client client, SpriteManager spriteManager)
	{
		this.client = client;
		this.spriteManager = spriteManager;
	}

	/** The clip shape, or null while the minimap is hidden. */
	Shape area()
	{
		Widget minimapWidget = drawWidget();

		if (minimapWidget == null || minimapWidget.isHidden() || !rectangle.equals(rectangle = minimapWidget.getBounds()))
		{
			clipFixed = null;
			clipResizeable = null;
			spriteFixed = null;
			spriteResizeable = null;
		}

		if (minimapWidget == null || minimapWidget.isHidden())
		{
			return null;
		}

		if (client.isResized())
		{
			if (clipResizeable != null)
			{
				return clipResizeable;
			}
			if (spriteResizeable == null)
			{
				spriteResizeable = spriteManager.getSprite(SpriteID.RESIZE_MAP_MASK, 0);
			}
			if (spriteResizeable != null)
			{
				clipResizeable = polygonOf(spriteResizeable, rectangle.x, rectangle.y);
				return clipResizeable;
			}
			return simpleArea();
		}
		if (clipFixed != null)
		{
			return clipFixed;
		}
		if (spriteFixed == null)
		{
			spriteFixed = spriteManager.getSprite(SpriteID.FIXED_MAP_MASK, 0);
		}
		if (spriteFixed != null)
		{
			clipFixed = polygonOf(spriteFixed, rectangle.x, rectangle.y);
			return clipFixed;
		}
		return simpleArea();
	}

	private Widget drawWidget()
	{
		if (client.isResized())
		{
			if (client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1)
			{
				return client.getWidget(InterfaceID.ToplevelPreEoc.MINIMAP);
			}
			return client.getWidget(InterfaceID.ToplevelOsrsStretch.MINIMAP);
		}
		return client.getWidget(InterfaceID.Toplevel.MINIMAP);
	}

	private Shape simpleArea()
	{
		Widget minimapDrawArea = drawWidget();

		if (minimapDrawArea == null || minimapDrawArea.isHidden())
		{
			return null;
		}

		Rectangle bounds = minimapDrawArea.getBounds();

		return new Ellipse2D.Double(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
	}

	/**
	 * The outline of a mask sprite's non-background region: the corner pixel is the background;
	 * each row contributes its left edge (appended) and right edge (prepended), so the points run
	 * down one side and back up the other. Offset to the widget's screen position.
	 */
	static Polygon polygonOf(BufferedImage image, int offsetX, int offsetY)
	{
		Color outsideColour = null;
		Color previousColour;
		final int width = image.getWidth();
		final int height = image.getHeight();
		List<java.awt.Point> points = new ArrayList<>();
		for (int y = 0; y < height; y++)
		{
			previousColour = outsideColour;
			for (int x = 0; x < width; x++)
			{
				int rgb = image.getRGB(x, y);
				int a = (rgb & 0xff000000) >>> 24;
				int r = (rgb & 0x00ff0000) >> 16;
				int g = (rgb & 0x0000ff00) >> 8;
				int b = (rgb & 0x000000ff);
				Color colour = new Color(r, g, b, a);
				if (x == 0 && y == 0)
				{
					outsideColour = colour;
					previousColour = colour;
				}
				if (!colour.equals(outsideColour) && previousColour.equals(outsideColour))
				{
					points.add(new java.awt.Point(x, y));
				}
				if ((colour.equals(outsideColour) || x == (width - 1)) && !previousColour.equals(outsideColour))
				{
					points.add(0, new java.awt.Point(x, y));
				}
				previousColour = colour;
			}
		}
		Polygon polygon = new Polygon();
		for (java.awt.Point point : points)
		{
			polygon.addPoint(point.x + offsetX, point.y + offsetY);
		}
		return polygon;
	}
}
