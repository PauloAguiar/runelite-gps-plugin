package gps;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;

/**
 * Small 16px UI icons for the alternative-routes panel, rendered with Java2D so the plugin carries no
 * image assets. Each action has a base (grey) and a hover (accent) variant, mirroring the
 * base/hover icon swap used by the tile-packs panel controls.
 */
final class RouteIcons
{
	private static final int SIZE = 16;

	private static final Color GREY = new Color(0xA8, 0xA8, 0xA8);
	private static final Color LIGHT = new Color(0xED, 0xED, 0xED);
	private static final Color RED = new Color(0xE3, 0x1C, 0x1C);
	private static final Color GREEN = new Color(0x4C, 0xAF, 0x50);
	private static final Color GREEN_LIGHT = new Color(0x7C, 0xD6, 0x80);
	private static final Color ORANGE = new Color(0xFF, 0x98, 0x1F);
	private static final Color ORANGE_LIGHT = new Color(0xFF, 0xC0, 0x6A);
	private static final Color GOLD = new Color(0xF2, 0xC1, 0x4E);

	// Show / hide a route on the map (map pin). Active = currently shown.
	static final ImageIcon SHOW = new ImageIcon(pin(GREY));
	static final ImageIcon SHOW_HOVER = new ImageIcon(pin(LIGHT));
	static final ImageIcon SHOW_ACTIVE = new ImageIcon(pin(ORANGE));
	static final ImageIcon SHOW_ACTIVE_HOVER = new ImageIcon(pin(ORANGE_LIGHT));
	// Exclude a method from the next search (no-entry).
	static final ImageIcon EXCLUDE = new ImageIcon(ban(GREY));
	static final ImageIcon EXCLUDE_HOVER = new ImageIcon(ban(RED));
	// Resting state on route cards: present but nearly invisible, coloured up while the card is
	// hovered — toggling visibility instead shifted the row height.
	static final ImageIcon EXCLUDE_DIM = new ImageIcon(ban(new Color(0x45, 0x45, 0x45)));
	// Marks the route card's ETA.
	static final ImageIcon CLOCK = new ImageIcon(clock(GREY));
	// Re-include an excluded method (plus).
	static final ImageIcon INCLUDE = new ImageIcon(plus(GREY));
	static final ImageIcon INCLUDE_HOVER = new ImageIcon(plus(GREEN));
	// Recompute routes (circular refresh arrow).
	static final ImageIcon REFRESH = new ImageIcon(refresh(GREY));
	static final ImageIcon REFRESH_HOVER = new ImageIcon(refresh(LIGHT));
	// Clear all exclusions (trash can).
	static final ImageIcon CLEAR = new ImageIcon(trash(GREY));
	static final ImageIcon CLEAR_HOVER = new ImageIcon(trash(RED));
	// Catalog toggles: included (check), excluded (cross), partially-included category (dash).
	static final ImageIcon CHECK = new ImageIcon(check(GREEN));
	static final ImageIcon CHECK_HOVER = new ImageIcon(check(GREEN_LIGHT));
	static final ImageIcon CROSS = new ImageIcon(cross(GREY));
	static final ImageIcon CROSS_HOVER = new ImageIcon(cross(RED));
	static final ImageIcon DASH = new ImageIcon(dash(ORANGE));
	static final ImageIcon DASH_HOVER = new ImageIcon(dash(ORANGE_LIGHT));
	// Expand/collapse a category.
	static final ImageIcon SHOW_MORE = new ImageIcon(moreChevron(GREY));
	static final ImageIcon SHOW_MORE_HOVER = new ImageIcon(moreChevron(LIGHT));
	static final ImageIcon CHEVRON_RIGHT = new ImageIcon(chevron(GREY, false));
	static final ImageIcon CHEVRON_RIGHT_HOVER = new ImageIcon(chevron(LIGHT, false));
	static final ImageIcon CHEVRON_DOWN = new ImageIcon(chevron(GREY, true));
	static final ImageIcon CHEVRON_DOWN_HOVER = new ImageIcon(chevron(LIGHT, true));
	// Method the player can't use right now (missing item/level/quest/unlock).
	static final ImageIcon LOCKED = new ImageIcon(lock(ORANGE));
	// Method whose required item is owned but sitting in the bank (route through a bank to grab it).
	static final ImageIcon IN_BANK = new ImageIcon(coins(GOLD));
	// Capture a debug snapshot of the current routes (camera).
	static final ImageIcon DEBUG = new ImageIcon(camera(GREY));
	static final ImageIcon DEBUG_HOVER = new ImageIcon(camera(LIGHT));
	// Run the fixed performance-benchmark suite (stopwatch).
	static final ImageIcon BENCHMARK = new ImageIcon(stopwatch(GREY));
	static final ImageIcon BENCHMARK_HOVER = new ImageIcon(stopwatch(LIGHT));
	// Filter the catalog to only the currently-disabled methods (funnel). Orange = active.
	static final ImageIcon FILTER = new ImageIcon(funnel(GREY));
	static final ImageIcon FILTER_HOVER = new ImageIcon(funnel(LIGHT));
	static final ImageIcon FILTER_ACTIVE = new ImageIcon(funnel(ORANGE));
	static final ImageIcon FILTER_ACTIVE_HOVER = new ImageIcon(funnel(ORANGE_LIGHT));

	// The plugin's identity mark: the navigation-blue location pin, matching the GPS overlay's
	// title glyph. Used for the sidebar tab (and exportable for the hub listing icon).
	private static final Color GPS_BLUE = new Color(0x4C, 0x8B, 0xF5);

	static BufferedImage gpsPin()
	{
		// The sidebar tab needs presence: scale the pin up to fill the 16px tile (the panel's
		// row pins stay smaller so they read as buttons next to text).
		return render(g ->
		{
			g.translate(8.0, 8.0);
			g.scale(1.3, 1.3);
			g.translate(-8.0, -8.5);
			drawPin(g, GPS_BLUE);
		});
	}

	// ── Destination-search category icons ──────────────────────────────
	// A coherent, meaningful set (one glyph per category) replacing the old hash-coloured dots.
	private static final ImageIcon DEST_PLACE = new ImageIcon(place());
	private static final ImageIcon DEST_BANK = new ImageIcon(coinStack());
	private static final ImageIcon DEST_BANK_ROUND_TRIP = new ImageIcon(coinStackReturn());
	private static final ImageIcon DEST_ALTAR = new ImageIcon(altar());
	private static final ImageIcon DEST_WATER = new ImageIcon(droplet());
	private static final ImageIcon DEST_FURNACE = new ImageIcon(flame(new Color(0xF2, 0x8A, 0x3B)));
	private static final ImageIcon DEST_ANVIL = new ImageIcon(anvil());
	private static final ImageIcon DEST_RANGE = new ImageIcon(pot());
	private static final ImageIcon DEST_SPINNING = new ImageIcon(wheel());
	private static final ImageIcon DEST_POTTERY = new ImageIcon(vase());
	private static final ImageIcon DEST_FAIRY = new ImageIcon(ring());
	private static final ImageIcon DEST_SPIRIT_TREE = new ImageIcon(tree());
	private static final ImageIcon DEST_DUNGEON = new ImageIcon(dungeon());
	private static final ImageIcon DEST_MINIGAME = new ImageIcon(minigame());
	private static final ImageIcon DEST_LANDMARK = new ImageIcon(landmark());
	private static final ImageIcon DEST_PIN = new ImageIcon(pin(GPS_BLUE));

	// Route-card marker: the GPS pin the route number sits beside.
	static final ImageIcon ROUTE_PIN = new ImageIcon(pin(GPS_BLUE));
	// Panel message-banner glyphs: a warning triangle, an info circle, and a busy spinner.
	static final ImageIcon BANNER_WARNING = new ImageIcon(warningTriangle(ORANGE));
	static final ImageIcon BANNER_INFO = new ImageIcon(infoCircle(GPS_BLUE));
	static final ImageIcon BANNER_BUSY = new ImageIcon(spinner(GPS_BLUE));

	/** The icon for a destination category, falling back to a location pin for anything unmapped. */
	static ImageIcon destinationIcon(String category)
	{
		switch (category)
		{
			case "place": return DEST_PLACE;
			case "bank": return DEST_BANK;
			case "bank_round_trip": return DEST_BANK_ROUND_TRIP;
			case "altar": return DEST_ALTAR;
			case "water": return DEST_WATER;
			case "furnace": return DEST_FURNACE;
			case "anvil": return DEST_ANVIL;
			case "range": return DEST_RANGE;
			case "spinning_wheel": return DEST_SPINNING;
			case "pottery": return DEST_POTTERY;
			case "fairy_ring": return DEST_FAIRY;
			case "spirit_tree": return DEST_SPIRIT_TREE;
			case "dungeon": return DEST_DUNGEON;
			case "minigame": return DEST_MINIGAME;
			case "landmark": return DEST_LANDMARK;
			default: return DEST_PIN;
		}
	}

	private static BufferedImage place()
	{
		final Color body = new Color(0x8A, 0xB4, 0xF8);
		return render(g ->
		{
			g.setColor(body);
			g.fill(new Rectangle2D.Double(2, 6, 5, 8));    // shorter building
			g.fill(new Rectangle2D.Double(8, 3, 6, 11));   // taller building
			g.setComposite(AlphaComposite.Clear);
			for (double wy : new double[]{8, 11})
			{
				g.fill(new Rectangle2D.Double(3.4, wy, 1.2, 1.4));
			}
			for (double wy : new double[]{5.5, 8, 10.5})
			{
				g.fill(new Rectangle2D.Double(9.4, wy, 1.2, 1.4));
				g.fill(new Rectangle2D.Double(11.4, wy, 1.2, 1.4));
			}
			g.setComposite(AlphaComposite.SrcOver);
		});
	}

	private static BufferedImage coinStack()
	{
		final Color gold = new Color(0xF2, 0xC1, 0x4E);
		final Color edge = new Color(0xB8, 0x8E, 0x2A);
		return render(g ->
		{
			for (double y : new double[]{9.5, 6.5, 3.5})
			{
				g.setColor(gold);
				g.fill(new Ellipse2D.Double(3, y, 10, 3.6));
				g.setColor(edge);
				g.setStroke(new BasicStroke(1f));
				g.draw(new Ellipse2D.Double(3, y, 10, 3.6));
			}
		});
	}

	private static BufferedImage coinStackReturn()
	{
		final Color gold = new Color(0xF2, 0xC1, 0x4E);
		final Color edge = new Color(0xB8, 0x8E, 0x2A);
		final Color arrow = new Color(0x8A, 0xB4, 0xF8);
		return render(g ->
		{
			// A smaller coin stack, bottom-left, with a return arrow looping over it.
			for (double y : new double[]{11, 8.5})
			{
				g.setColor(gold);
				g.fill(new Ellipse2D.Double(1.5, y, 8, 3.2));
				g.setColor(edge);
				g.setStroke(new BasicStroke(1f));
				g.draw(new Ellipse2D.Double(1.5, y, 8, 3.2));
			}
			g.setColor(arrow);
			g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(new Arc2D.Double(4.5, 1.5, 9.5, 9, -20, 220, Arc2D.OPEN));   // out-and-back loop
			Path2D head = new Path2D.Double();                                  // arrowhead pointing home
			head.moveTo(3.2, 7.4);
			head.lineTo(6.6, 6.2);
			head.lineTo(5.8, 9.6);
			head.closePath();
			g.fill(head);
		});
	}

	private static BufferedImage altar()
	{
		return render(g ->
		{
			g.setColor(new Color(0xC9, 0xB8, 0xE8));
			g.fill(new Rectangle2D.Double(3.5, 11, 9, 3));   // base
			g.fill(new Rectangle2D.Double(4.5, 8.5, 7, 2));  // top slab
			g.fill(new Rectangle2D.Double(6.5, 10, 3, 1.2));  // column
			g.setColor(new Color(0xFF, 0xB4, 0x4A));
			g.fill(flameShape(8, 4.2, 0.75));                // candle glow
		});
	}

	private static BufferedImage droplet()
	{
		return render(g ->
		{
			g.setColor(new Color(0x4A, 0xA3, 0xE0));
			Path2D drop = new Path2D.Double();
			drop.moveTo(8, 2.5);
			drop.curveTo(11.5, 7, 12, 9, 12, 10.5);
			drop.curveTo(12, 13, 10.2, 14.5, 8, 14.5);
			drop.curveTo(5.8, 14.5, 4, 13, 4, 10.5);
			drop.curveTo(4, 9, 4.5, 7, 8, 2.5);
			drop.closePath();
			g.fill(drop);
			g.setColor(new Color(0xBF, 0xE4, 0xFF));
			g.fill(new Ellipse2D.Double(6, 9.5, 2, 3));      // highlight
		});
	}

	private static BufferedImage flame(Color colour)
	{
		return render(g ->
		{
			g.setColor(colour);
			g.fill(flameShape(8, 8, 1.0));
		});
	}

	private static Path2D flameShape(double cx, double cy, double scale)
	{
		Path2D f = new Path2D.Double();
		f.moveTo(cx, cy - 6 * scale);
		f.curveTo(cx + 3 * scale, cy - 3 * scale, cx + 3 * scale, cy - scale, cx + 1.5 * scale, cy);
		f.curveTo(cx + 3 * scale, cy + scale, cx + 3.5 * scale, cy + 3 * scale, cx + 2 * scale, cy + 5 * scale);
		f.curveTo(cx + scale, cy + 6.5 * scale, cx - scale, cy + 6.5 * scale, cx - 2 * scale, cy + 5 * scale);
		f.curveTo(cx - 3.5 * scale, cy + 3 * scale, cx - 2.5 * scale, cy + scale, cx - 1 * scale, cy);
		f.curveTo(cx - 2.5 * scale, cy - scale, cx - 1.5 * scale, cy - 4 * scale, cx, cy - 6 * scale);
		f.closePath();
		return f;
	}

	private static BufferedImage anvil()
	{
		return render(g ->
		{
			g.setColor(new Color(0x9A, 0xA5, 0xB1));
			Path2D horn = new Path2D.Double();
			horn.moveTo(3, 5);
			horn.lineTo(1.3, 6.2);
			horn.lineTo(3, 7.4);
			horn.closePath();
			g.fill(horn);
			g.fill(new Rectangle2D.Double(3, 5, 10, 2.4));    // top face
			g.fill(new Rectangle2D.Double(6.5, 7.4, 3, 2.6));  // waist
			g.fill(new RoundRectangle2D.Double(4, 10, 8, 2.6, 2, 2)); // base
		});
	}

	private static BufferedImage pot()
	{
		return render(g ->
		{
			g.setColor(new Color(0xE0, 0x60, 0x3B));
			g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(new Arc2D.Double(3.2, 6.5, 3, 4, 40, 280, Arc2D.OPEN));   // left handle
			g.draw(new Arc2D.Double(9.8, 6.5, 3, 4, -140, 280, Arc2D.OPEN)); // right handle
			g.fill(new RoundRectangle2D.Double(4, 7, 8, 6.5, 3.5, 3.5));     // body
			g.fill(new RoundRectangle2D.Double(3, 6, 10, 2, 1.5, 1.5));      // rim
		});
	}

	private static BufferedImage wheel()
	{
		return render(g ->
		{
			g.setColor(new Color(0xC7, 0xA6, 0x5A));
			g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(new Ellipse2D.Double(2.5, 2.5, 11, 11));
			final double cx = 8, cy = 8, r = 5.3;
			for (int a = 0; a < 180; a += 45)
			{
				double rad = Math.toRadians(a);
				g.draw(new Line2D.Double(cx - Math.cos(rad) * r, cy - Math.sin(rad) * r,
					cx + Math.cos(rad) * r, cy + Math.sin(rad) * r));
			}
			g.fill(new Ellipse2D.Double(6.7, 6.7, 2.6, 2.6));
		});
	}

	private static BufferedImage vase()
	{
		return render(g ->
		{
			g.setColor(new Color(0xB5, 0x79, 0x3B));
			Path2D vase = new Path2D.Double();
			vase.moveTo(6, 2.5);
			vase.lineTo(10, 2.5);
			vase.lineTo(9.2, 5);
			vase.curveTo(12.5, 7, 12.5, 12, 8, 13.5);
			vase.curveTo(3.5, 12, 3.5, 7, 6.8, 5);
			vase.closePath();
			g.fill(vase);
			g.setColor(new Color(0x8A, 0x5A, 0x2A));
			g.setStroke(new BasicStroke(1f));
			g.draw(new Line2D.Double(5, 8.5, 11, 8.5));       // decorative band
		});
	}

	private static BufferedImage ring()
	{
		return render(g ->
		{
			g.setColor(new Color(0x5F, 0xB8, 0x65));
			g.fill(new Ellipse2D.Double(2.5, 2.5, 11, 11));
			g.setComposite(AlphaComposite.Clear);
			g.fill(new Ellipse2D.Double(5.5, 5.5, 5, 5));
			g.setComposite(AlphaComposite.SrcOver);
			// A couple of "mushrooms" on the ring to read as a fairy ring rather than a plain torus.
			g.setColor(new Color(0xE0, 0x60, 0x60));
			g.fill(new Ellipse2D.Double(7, 1.6, 2, 1.6));
			g.fill(new Ellipse2D.Double(12, 7, 1.6, 2));
		});
	}

	private static BufferedImage tree()
	{
		return render(g ->
		{
			g.setColor(new Color(0x8A, 0x5A, 0x2A));
			g.fill(new Rectangle2D.Double(7, 8.5, 2, 5.5));  // trunk
			g.setColor(new Color(0x4C, 0xAF, 0x50));
			g.fill(new Ellipse2D.Double(2.5, 1.5, 11, 9));   // canopy
		});
	}

	private static BufferedImage dungeon()
	{
		return render(g ->
		{
			g.setColor(new Color(0x8A, 0x8F, 0x98));           // rocky mound
			Path2D mound = new Path2D.Double();
			mound.moveTo(1.5, 14);
			mound.curveTo(2.5, 4.5, 13.5, 4.5, 14.5, 14);
			mound.closePath();
			g.fill(mound);
			g.setColor(new Color(0x1E, 0x20, 0x26));           // dark cave mouth
			Path2D mouth = new Path2D.Double();
			mouth.moveTo(5, 14);
			mouth.curveTo(5, 8, 11, 8, 11, 14);
			mouth.closePath();
			g.fill(mouth);
		});
	}

	private static BufferedImage minigame()
	{
		return render(g ->
		{
			g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.setColor(new Color(0xC7, 0xD0, 0xDA));           // two crossed blades, tips up
			g.draw(new Line2D.Double(2.5, 2.5, 12, 12));
			g.draw(new Line2D.Double(13.5, 2.5, 4, 12));
			g.setColor(new Color(0xF2, 0xC1, 0x4E));           // gold pommels at the hilts
			g.fill(new Ellipse2D.Double(11, 11, 3, 3));
			g.fill(new Ellipse2D.Double(2.8, 11, 3, 3));
		});
	}

	private static BufferedImage landmark()
	{
		return render(g ->
		{
			g.setColor(new Color(0x9A, 0xA5, 0xB1));           // pole
			g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(new Line2D.Double(4.5, 2, 4.5, 14.5));
			g.setColor(new Color(0xE0, 0x60, 0x3B));           // pennant
			Path2D flag = new Path2D.Double();
			flag.moveTo(4.5, 2.5);
			flag.lineTo(13, 4.7);
			flag.lineTo(4.5, 6.9);
			flag.closePath();
			g.fill(flag);
		});
	}

	private static BufferedImage warningTriangle(Color colour)
	{
		return render(g ->
		{
			g.setColor(colour);
			g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			Path2D tri = new Path2D.Double();
			tri.moveTo(8, 1.6);
			tri.lineTo(15, 14);
			tri.lineTo(1, 14);
			tri.closePath();
			g.fill(tri);
			g.setComposite(AlphaComposite.Clear);              // exclamation cut out
			g.fill(new Rectangle2D.Double(7.2, 5.6, 1.6, 4.6));
			g.fill(new Ellipse2D.Double(7.2, 11.2, 1.6, 1.6));
			g.setComposite(AlphaComposite.SrcOver);
		});
	}

	private static BufferedImage infoCircle(Color colour)
	{
		return render(g ->
		{
			g.setColor(colour);
			g.fill(new Ellipse2D.Double(1.5, 1.5, 13, 13));
			g.setComposite(AlphaComposite.Clear);              // "i" cut out
			g.fill(new Ellipse2D.Double(7.1, 3.6, 1.9, 1.9));
			g.fill(new Rectangle2D.Double(7.1, 6.7, 1.9, 5.4));
			g.setComposite(AlphaComposite.SrcOver);
		});
	}

	private static BufferedImage spinner(Color colour)
	{
		return render(g ->
		{
			g.setColor(colour);
			g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(new Arc2D.Double(2.5, 2.5, 11, 11, 90, 280, Arc2D.OPEN));   // broken ring = loading
		});
	}

	private RouteIcons()
	{
	}

	private interface Drawer
	{
		void draw(Graphics2D g);
	}

	private static BufferedImage render(Drawer drawer)
	{
		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		drawer.draw(g);
		g.dispose();
		return image;
	}

	private static BufferedImage pin(Color colour)
	{
		return render(g -> drawPin(g, colour));
	}

	private static void drawPin(Graphics2D g, Color colour)
	{
		final double cx = 8, cy = 6.4, r = 4.0;
		Path2D body = new Path2D.Double();
		body.moveTo(cx - 3.0, cy + 1.6);
		body.curveTo(cx - 2.0, cy + 4.4, cx - 0.5, cy + 5.4, cx, 14.6);
		body.curveTo(cx + 0.5, cy + 5.4, cx + 2.0, cy + 4.4, cx + 3.0, cy + 1.6);
		body.closePath();
		g.setColor(colour);
		g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
		g.fill(body);
		g.setComposite(AlphaComposite.Clear);
		final double hr = 1.65;
		g.fill(new Ellipse2D.Double(cx - hr, cy - hr, 2 * hr, 2 * hr));
		g.setComposite(AlphaComposite.SrcOver);
	}

	private static BufferedImage ban(Color colour)
	{
		return render(g ->
		{
			g.setColor(colour);
			final double m = 1.7, d = SIZE - 2 * m;
			g.draw(new Ellipse2D.Double(m, m, d, d));
			g.draw(new Line2D.Double(5.0, 8.0, 11.0, 8.0));
		});
	}

	private static BufferedImage plus(Color colour)
	{
		return render(g ->
		{
			g.setColor(colour);
			final double m = 1.7, d = SIZE - 2 * m;
			g.draw(new Ellipse2D.Double(m, m, d, d));
			g.draw(new Line2D.Double(8, 5, 8, 11));
			g.draw(new Line2D.Double(5, 8, 11, 8));
		});
	}

	private static BufferedImage refresh(Color colour)
	{
		return render(g ->
		{
			g.setColor(colour);
			final double m = 2.6, d = SIZE - 2 * m;
			final double start = 65, extent = 250;
			Arc2D arc = new Arc2D.Double(m, m, d, d, start, extent, Arc2D.OPEN);
			g.draw(arc);
			Point2D p0 = arc.getStartPoint();
			Point2D p1 = new Arc2D.Double(m, m, d, d, start + 5, 1, Arc2D.OPEN).getStartPoint();
			double angle = Math.atan2(p0.getY() - p1.getY(), p0.getX() - p1.getX());
			arrowHead(g, p0.getX(), p0.getY(), angle, 3.4, colour);
		});
	}

	private static BufferedImage trash(Color colour)
	{
		return render(g ->
		{
			g.setColor(colour);
			g.draw(new Line2D.Double(3.5, 4.6, 12.5, 4.6));
			g.draw(new Line2D.Double(6.5, 4.6, 6.5, 3.2));
			g.draw(new Line2D.Double(9.5, 4.6, 9.5, 3.2));
			g.draw(new Line2D.Double(6.5, 3.2, 9.5, 3.2));
			Path2D body = new Path2D.Double();
			body.moveTo(4.4, 5.4);
			body.lineTo(5.2, 13.0);
			body.lineTo(10.8, 13.0);
			body.lineTo(11.6, 5.4);
			g.draw(body);
			g.draw(new Line2D.Double(6.6, 6.4, 6.9, 12.0));
			g.draw(new Line2D.Double(8.0, 6.4, 8.0, 12.0));
			g.draw(new Line2D.Double(9.4, 6.4, 9.1, 12.0));
		});
	}

	private static BufferedImage check(Color colour)
	{
		return render(g ->
		{
			g.setColor(colour);
			g.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			Path2D tick = new Path2D.Double();
			tick.moveTo(3.5, 8.5);
			tick.lineTo(6.6, 11.6);
			tick.lineTo(12.5, 4.5);
			g.draw(tick);
		});
	}

	private static BufferedImage cross(Color colour)
	{
		return render(g ->
		{
			g.setColor(colour);
			g.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(new Line2D.Double(4.2, 4.2, 11.8, 11.8));
			g.draw(new Line2D.Double(11.8, 4.2, 4.2, 11.8));
		});
	}

	private static BufferedImage dash(Color colour)
	{
		return render(g ->
		{
			g.setColor(colour);
			g.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(new Line2D.Double(4.0, 8.0, 12.0, 8.0));
		});
	}

	private static BufferedImage lock(Color colour)
	{
		return render(g ->
		{
			g.setColor(colour);
			// Shackle
			g.draw(new Arc2D.Double(4.75, 2.5, 6.5, 7.5, 0, 180, Arc2D.OPEN));
			// Body
			g.fillRoundRect(3, 7, 10, 6, 3, 3);
		});
	}

	private static BufferedImage coins(Color colour)
	{
		return render(g ->
		{
			g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			final double x = 3.5, w = 9.0, h = 3.4;
			// Bottom-to-top so the upper coins overlap the lower ones, reading as a stack.
			final double[] ys = {9.6, 7.0, 4.4};
			for (double y : ys)
			{
				g.setColor(colour);
				g.fill(new Ellipse2D.Double(x, y, w, h));
				g.setColor(colour.darker());
				g.draw(new Ellipse2D.Double(x, y, w, h));
			}
		});
	}

	private static BufferedImage funnel(Color colour)
	{
		return render(g ->
		{
			g.setColor(colour);
			Path2D f = new Path2D.Double();
			f.moveTo(2.5, 3);
			f.lineTo(13.5, 3);
			f.lineTo(9, 8);
			f.lineTo(9, 13.5);
			f.lineTo(7, 12);
			f.lineTo(7, 8);
			f.closePath();
			g.fill(f);
		});
	}

	private static BufferedImage clock(Color colour)
	{
		return render(g ->
		{
			g.setColor(colour);
			g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(new Ellipse2D.Double(2.5, 2.5, 11, 11));
			g.draw(new Line2D.Double(8, 8, 8, 4.5));     // minute hand, pointing up
			g.draw(new Line2D.Double(8, 8, 10.5, 9.5));  // hour hand, pointing ~4 o'clock
		});
	}

	private static BufferedImage camera(Color colour)
	{
		return render(g ->
		{
			g.setColor(colour);
			// Body with a small viewfinder bump, and the lens as a hollow circle.
			g.draw(new Line2D.Double(5.5, 4.0, 6.8, 4.0));
			g.drawRoundRect(2, 5, 12, 8, 3, 3);
			g.draw(new Ellipse2D.Double(6.0, 6.5, 5.0, 5.0));
		});
	}

	private static BufferedImage stopwatch(Color colour)
	{
		return render(g ->
		{
			g.setColor(colour);
			// Round body with a crown on top and two hands frozen mid-measurement.
			g.draw(new Ellipse2D.Double(3.0, 4.5, 10.0, 10.0));
			g.draw(new Line2D.Double(8.0, 2.2, 8.0, 3.6));   // crown stem
			g.draw(new Line2D.Double(6.6, 2.2, 9.4, 2.2));   // crown cap
			g.draw(new Line2D.Double(8.0, 9.5, 8.0, 6.6));   // minute hand
			g.draw(new Line2D.Double(8.0, 9.5, 10.2, 10.9)); // second hand
		});
	}

	private static BufferedImage chevron(Color colour, boolean down)
	{
		return render(g ->
		{
			g.setColor(colour);
			Path2D triangle = new Path2D.Double();
			if (down)
			{
				triangle.moveTo(4.5, 6.0);
				triangle.lineTo(11.5, 6.0);
				triangle.lineTo(8.0, 11.0);
			}
			else
			{
				triangle.moveTo(6.0, 4.5);
				triangle.lineTo(11.0, 8.0);
				triangle.lineTo(6.0, 11.5);
			}
			triangle.closePath();
			g.fill(triangle);
		});
	}

	// A stacked double-down chevron: "show more" — distinct from the single expand/collapse chevron.
	private static BufferedImage moreChevron(Color colour)
	{
		return render(g ->
		{
			g.setColor(colour);
			g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			for (double y : new double[]{5.0, 8.6})
			{
				Path2D v = new Path2D.Double();
				v.moveTo(4.5, y);
				v.lineTo(8.0, y + 3.0);
				v.lineTo(11.5, y);
				g.draw(v);
			}
		});
	}

	private static void arrowHead(Graphics2D g, double x, double y, double angle, double size, Color colour)
	{
		Path2D head = new Path2D.Double();
		head.moveTo(x, y);
		head.lineTo(x - size * Math.cos(angle - Math.PI / 6), y - size * Math.sin(angle - Math.PI / 6));
		head.lineTo(x - size * Math.cos(angle + Math.PI / 6), y - size * Math.sin(angle + Math.PI / 6));
		head.closePath();
		g.setColor(colour);
		g.fill(head);
	}
}
