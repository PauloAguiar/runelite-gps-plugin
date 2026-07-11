package gps;

import com.google.inject.Inject;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import gps.pathfinder.PathStep;
import gps.transport.BankPickupRequirements;
import gps.transport.Transport;

public class PathTileOverlay extends Overlay
{
	private static final int TRANSPORT_LABEL_GAP = 3;
	// The flowing highlight on the drawn line: a train of small ripples travelling towards the
	// destination — one every WAVE_SPACING steps, each fading over WAVE_HALF_WIDTH steps to either
	// side, moving at WAVE_TILES_PER_SECOND.
	private static final double WAVE_TILES_PER_SECOND = 20;
	private static final double WAVE_SPACING = 60;
	private static final double WAVE_HALF_WIDTH = 4;
	// Half-size of the window the debug off-route bands are computed over, in tiles. Bounds the cost and
	// keeps the render on-screen even when the configured recalculate distance is very large.
	private static final int BAND_WINDOW = 32;
	private final Client client;
	private final ShortestPathPlugin plugin;
	private int playerTileLabelOffset = 0;
	// Door tiles already hinted this frame — a door can sit on two path edges (a diagonal
	// approach then the straight crossing), which would otherwise stack "Open Door" twice.
	private final Set<Integer> hintedDoorTiles = new HashSet<>();

	@Inject
	public PathTileOverlay(Client client, ShortestPathPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_LOW);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		playerTileLabelOffset = 0;
		hintedDoorTiles.clear();

		if (plugin.drawRecalculationRanges)
		{
			drawArrivalTiles(graphics);
			drawRecalculationRanges(graphics);
		}

		if (plugin.drawTiles && plugin.getPathfinder() != null && !plugin.getDisplayPath().isEmpty())
		{
			Color pathColor = plugin.getPathColor();
			Color color = new Color(
				pathColor.getRed(),
				pathColor.getGreen(),
				pathColor.getBlue(),
				pathColor.getAlpha() / 2);
			Color blockedColor = new Color(
				plugin.colourPathBlocked.getRed(),
				plugin.colourPathBlocked.getGreen(),
				plugin.colourPathBlocked.getBlue(),
				plugin.colourPathBlocked.getAlpha() / 2);
			// The stretch the player has already covered is greyed out — the live part is what's ahead.
			Color doneColor = new Color(0x80, 0x80, 0x80, pathColor.getAlpha() / 2);

			List<PathStep> path = plugin.getDisplayPath();
			// The path beyond the first door not yet seen open renders in the blocked colour: the
			// route assumes doors are passable, this marks the part the player can't walk yet.
			int blockedFrom = plugin.blockedFromIndex(path);
			// Progress along the displayed route: edges up to here are done (greyed).
			int progress = plugin.displayedRouteProgress();
			// Repeating ripples flow along the line towards the destination: each edge's glow is
			// its proximity to the nearest ripple centre in a train spaced WAVE_SPACING apart.
			double waveOffset = ((System.currentTimeMillis() % 600_000L) / 1000.0 * WAVE_TILES_PER_SECOND)
				% WAVE_SPACING;
			for (int i = 1; i < path.size(); i++)
			{
				PathStep currentStep = path.get(i - 1);
				PathStep nextStep = path.get(i);
				// A non-adjacent pair is a teleport/transport jump, not a walkable edge.
				boolean jump = WorldPointUtil.distanceBetween(
					currentStep.getPackedPosition(), nextStep.getPackedPosition()) > 1;
				// Arrowheads only where they carry information: at direction changes and the end.
				boolean head = i == path.size() - 1
					|| directionChanges(currentStep.getPackedPosition(), nextStep.getPackedPosition(),
						path.get(i + 1).getPackedPosition());
				double phase = (i - waveOffset) % WAVE_SPACING;
				if (phase < 0)
				{
					phase += WAVE_SPACING;
				}
				double waveDistance = Math.min(phase, WAVE_SPACING - phase);
				// Edge i covers path[i-1]->path[i]; it's done once progress has reached path[i].
				boolean done = i <= progress;
				double glow = jump || done ? 0 : Math.max(0, 1 - waveDistance / WAVE_HALF_WIDTH);
				Color edgeColor = done ? doneColor : (i >= blockedFrom ? blockedColor : color);
				drawLine(graphics, currentStep.getPackedPosition(), nextStep.getPackedPosition(),
					edgeColor, head, glow, jump);
				drawTransportInfo(graphics, currentStep, nextStep, path, i - 1);
			}

			// GPS decorations for the displayed route: a small waypoint dot where each section ends
			// (walk up to here, then do the next step) and a growing green pulse on the destination.
			RouteOption displayedRoute = plugin.getDisplayedRoute();
			if (displayedRoute != null && plugin.showDirections)
			{
				drawGpsMarkers(graphics, displayedRoute);
			}

			if (plugin.isPathUnreachable())
			{
				playerTileLabelOffset += drawLabelOnPlayerTile(graphics, plugin.unreachableText, playerTileLabelOffset);
			}
		}

		return null;
	}

	/**
	 * The hint for a door/gate transport on the path, shown only while it needs opening: when the
	 * closed door object (whose id is the trailing token of the objectInfo, e.g. "Open Door 9398")
	 * is still standing at either end of the edge. Opening the door replaces the object, so the hint
	 * disappears by itself. Null for non-door transports and doors that are already open.
	 */
	private String closedDoorText(Transport transport)
	{
		String objectInfo = transport.getObjectInfo();
		if (objectInfo == null || !objectInfo.startsWith("Open "))
		{
			return null;
		}
		int lastSpace = objectInfo.lastIndexOf(' ');
		if (lastSpace <= 0)
		{
			return null;
		}
		String idText = objectInfo.substring(lastSpace + 1);
		if (idText.isEmpty() || !idText.chars().allMatch(Character::isDigit))
		{
			return null;
		}
		int objectId = Integer.parseInt(idText);
		if (objectPresent(transport.getOrigin(), objectId) || objectPresent(transport.getDestination(), objectId))
		{
			return objectInfo.substring(0, lastSpace);
		}
		return null;
	}

	private boolean objectPresent(int packedLocation, int objectId)
	{
		return SceneObjects.objectPresent(client, packedLocation, objectId);
	}

	private void drawGpsMarkers(Graphics2D graphics, RouteOption route)
	{
		List<RouteDirections.Step> steps = plugin.getRouteDirections(route);
		List<PathStep> path = route.getPath();
		if (steps.isEmpty() || path.isEmpty())
		{
			return;
		}
		int destinationIndex = path.size() - 1;
		Set<Integer> marked = new HashSet<>();
		for (RouteDirections.Step step : steps)
		{
			int end = step.getEndIndex();
			if (end <= 0 || end >= destinationIndex)
			{
				continue; // the destination gets the pulse, not a dot
			}
			int packed = path.get(end).getPackedPosition();
			if (marked.add(packed))
			{
				drawSectionMarker(graphics, packed);
			}
		}
		// A round trip's halfway point (the bank) pulses GOLD while the outbound leg is being
		// walked — reach it and the beacon vanishes, leaving the green home pulse for the way back.
		RouteOption displayed = plugin.getDisplayedRoute();
		if (displayed != null && displayed.isRoundTrip())
		{
			int turnaround = displayed.getTurnaroundIndex();
			if (turnaround > 0 && turnaround < path.size()
				&& plugin.displayedRouteProgress() < turnaround - 2)
			{
				drawDestinationPulse(graphics, path.get(turnaround).getPackedPosition(), PULSE_TURNAROUND);
			}
		}
		drawDestinationPulse(graphics, path.get(destinationIndex).getPackedPosition());
	}

	/**
	 * A small waypoint dot on the tile where a route section ends: white ring with a path-coloured
	 * core, so the "walk up to here" points stand out along the drawn line.
	 */
	private void drawSectionMarker(Graphics2D graphics, int location)
	{
		PrimitiveIntList points = WorldPointUtil.toLocalInstance(client, location);
		for (int i = 0; i < points.size(); i++)
		{
			LocalPoint lp = WorldPointUtil.toLocalPoint(client, points.get(i));
			if (lp == null)
			{
				continue;
			}
			Polygon poly = Perspective.getCanvasTilePoly(client, lp);
			if (poly == null)
			{
				continue;
			}
			final double cx = poly.getBounds().getCenterX();
			final double cy = poly.getBounds().getCenterY();
			graphics.setColor(Color.WHITE);
			graphics.fill(new Ellipse2D.Double(cx - 4.5, cy - 4.5, 9, 9));
			Color pathColour = plugin.getPathColor();
			graphics.setColor(new Color(pathColour.getRed(), pathColour.getGreen(), pathColour.getBlue()));
			graphics.fill(new Ellipse2D.Double(cx - 3, cy - 3, 6, 6));
		}
	}

	/**
	 * The journey's end: a green circle growing out of the destination tile and fading, looping —
	 * the same rhythm as the teleport pulse. The flattening follows the camera: a circle drawn on
	 * the ground plane projects to an ellipse with the same height/width ratio as the projected
	 * tile itself (the yaw factor cancels out of a square tile's bounding box, leaving only the
	 * pitch compression) — so it's a perfect circle seen top-down and flattens as the camera drops.
	 */
	/**
	 * Debug view of the off-route bands, drawn as contours hugging the PATH (not the player): tiles
	 * exactly the warning distance from the nearest path tile are tinted yellow and those exactly the
	 * recalculate distance red — the same Chebyshev metric the recalc check uses, so the two thresholds
	 * trace the corridor the player must stay inside. Computed over a bounded window around the player
	 * (the on-screen stretch) so a large configured distance can't blow up the render. A label reports
	 * the live distance from the path.
	 */
	private void drawRecalculationRanges(Graphics2D graphics)
	{
		final Player player = client.getLocalPlayer();
		final int recalc = plugin.getRecalculateDistance();
		final List<PathStep> path = plugin.getDisplayPath();
		if (player == null || recalc < 0 || path == null || path.isEmpty())
		{
			return;
		}
		final int warn = plugin.getOffRouteWarnDistance();
		final int centre = WorldPointUtil.fromLocalInstance(client, player);
		final int cx = WorldPointUtil.unpackWorldX(centre);
		final int cy = WorldPointUtil.unpackWorldY(centre);
		final int plane = WorldPointUtil.unpackWorldPlane(centre);
		final int window = Math.min(recalc + 1, BAND_WINDOW);

		// Path tiles near enough to be some window tile's nearest (within window of a tile within window).
		final int[] near = new int[path.size()];
		int nearCount = 0;
		for (PathStep step : path)
		{
			final int p = step.getPackedPosition();
			if (WorldPointUtil.unpackWorldPlane(p) == plane
				&& WorldPointUtil.distanceBetween(centre, p) <= 2 * window)
			{
				near[nearCount++] = p;
			}
		}
		if (nearCount > 0)
		{
			final Color warnFill = new Color(0xFF, 0xE0, 0x00, 70);
			final Color warnLine = new Color(0xFF, 0xE0, 0x00, 200);
			final Color recalcFill = new Color(0xFF, 0x3C, 0x3C, 70);
			final Color recalcLine = new Color(0xFF, 0x3C, 0x3C, 200);
			for (int dx = -window; dx <= window; dx++)
			{
				for (int dy = -window; dy <= window; dy++)
				{
					final int tile = WorldPointUtil.packWorldPoint(cx + dx, cy + dy, plane);
					int dmin = Integer.MAX_VALUE;
					for (int k = 0; k < nearCount; k++)
					{
						final int dist = WorldPointUtil.distanceBetween(tile, near[k]);
						if (dist < dmin)
						{
							dmin = dist;
						}
						if (dmin < warn)
						{
							break; // interior to the warning band — not a boundary tile
						}
					}
					// Only the two contour lines; the recalc boundary wins a tie with the warn boundary.
					if (dmin == recalc)
					{
						highlightWorldTile(graphics, tile, recalcFill, recalcLine);
					}
					else if (dmin == warn)
					{
						highlightWorldTile(graphics, tile, warnFill, warnLine);
					}
				}
			}
		}

		final int d = plugin.getPathDistance();
		final Point anchor = Perspective.localToCanvas(client, player.getLocalLocation(), plane);
		if (anchor != null)
		{
			final String label = "off-route: " + (d < 0 ? "?" : d)
				+ " (warn " + warn + " / recalc " + recalc + ")";
			graphics.setColor(plugin.isOffRouteWarning() ? new Color(0xFF, 0x4C, 0x4C) : Color.WHITE);
			graphics.drawString(label, anchor.getX() + 6, anchor.getY());
		}
	}

	private void highlightWorldTile(Graphics2D graphics, int location, Color fill, Color outline)
	{
		final PrimitiveIntList points = WorldPointUtil.toLocalInstance(client, location);
		for (int i = 0; i < points.size(); i++)
		{
			final LocalPoint lp = WorldPointUtil.toLocalPoint(client, points.get(i));
			if (lp == null)
			{
				continue;
			}
			final Polygon poly = Perspective.getCanvasTilePoly(client, lp);
			if (poly == null)
			{
				continue;
			}
			graphics.setColor(fill);
			graphics.fill(poly);
			graphics.setColor(outline);
			graphics.draw(poly);
		}
	}

	/**
	 * Tint the ACTUAL arrival tiles green: the plugin's arrival zone, i.e. every walkable tile within
	 * the finish distance of the destination (flooded over the collision map). This is the exact set
	 * the arrival check tests membership of — standing on any tinted tile completes the journey.
	 */
	private void drawArrivalTiles(Graphics2D graphics)
	{
		final Color fill = new Color(0x3C, 0xC8, 0x6A, 60);
		final Color outline = new Color(0x3C, 0xC8, 0x6A, 170);
		for (int tile : plugin.getArrivalTiles())
		{
			highlightWorldTile(graphics, tile, fill, outline);
		}
	}

	// The two pulse colours: green marks the journey's end; gold marks a round trip's turnaround
	// (the bank) while the outbound leg is being walked — an "arrive here first" beacon that
	// disappears once the player reaches it and the trip continues home.
	private static final Color PULSE_DESTINATION = new Color(0, 220, 90);
	private static final Color PULSE_TURNAROUND = new Color(255, 196, 64);

	private void drawDestinationPulse(Graphics2D graphics, int location)
	{
		drawDestinationPulse(graphics, location, PULSE_DESTINATION);
	}

	private void drawDestinationPulse(Graphics2D graphics, int location, Color colour)
	{
		PrimitiveIntList points = WorldPointUtil.toLocalInstance(client, location);
		for (int i = 0; i < points.size(); i++)
		{
			LocalPoint lp = WorldPointUtil.toLocalPoint(client, points.get(i));
			if (lp == null)
			{
				continue;
			}
			Polygon poly = Perspective.getCanvasTilePoly(client, lp);
			if (poly == null)
			{
				continue;
			}
			final double cx = poly.getBounds().getCenterX();
			final double cy = poly.getBounds().getCenterY();
			final double tileWidth = Math.max(8, poly.getBounds().getWidth());
			// Ground-plane flattening from the tile's own projection; clamped for degenerate polys.
			final double flatten = Math.min(1.0, Math.max(0.1,
				poly.getBounds().getHeight() / Math.max(1.0, poly.getBounds().getWidth())));

			final long period = 1400L;
			final int rings = 2;
			final Stroke previousStroke = graphics.getStroke();
			graphics.setStroke(new BasicStroke(2.2f));
			for (int r = 0; r < rings; r++)
			{
				double phase = ((System.currentTimeMillis() + (long) (r * period / (double) rings)) % period)
					/ (double) period;
				double radius = tileWidth * (0.25 + phase * 1.1);
				int alpha = (int) Math.round(180 * (1.0 - phase));
				if (alpha <= 0)
				{
					continue;
				}
				graphics.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), alpha));
				graphics.draw(new Ellipse2D.Double(
					cx - radius, cy - radius * flatten, radius * 2, radius * 2 * flatten));
			}
			graphics.setStroke(previousStroke);
		}
	}

	private Point tileCenter(int b)
	{
		if (b == WorldPointUtil.UNDEFINED || client == null)
		{
			return null;
		}

		if (WorldPointUtil.unpackWorldPlane(b) != client.getTopLevelWorldView().getPlane())
		{
			return null;
		}

		LocalPoint lp = WorldPointUtil.toLocalPoint(client, b);
		if (lp == null)
		{
			return null;
		}

		Polygon poly = Perspective.getCanvasTilePoly(client, lp);
		if (poly == null)
		{
			return null;
		}

		int cx = poly.getBounds().x + poly.getBounds().width / 2;
		int cy = poly.getBounds().y + poly.getBounds().height / 2;
		return new Point(cx, cy);
	}

	private static boolean directionChanges(int previous, int current, int next)
	{
		int dx1 = WorldPointUtil.unpackWorldX(current) - WorldPointUtil.unpackWorldX(previous);
		int dy1 = WorldPointUtil.unpackWorldY(current) - WorldPointUtil.unpackWorldY(previous);
		int dx2 = WorldPointUtil.unpackWorldX(next) - WorldPointUtil.unpackWorldX(current);
		int dy2 = WorldPointUtil.unpackWorldY(next) - WorldPointUtil.unpackWorldY(current);
		return dx1 != dx2 || dy1 != dy2;
	}

	private void drawLine(Graphics2D graphics, int startLoc, int endLoc, Color color, boolean arrowHead,
		double glow, boolean jump)
	{
		PrimitiveIntList starts = WorldPointUtil.toLocalInstance(client, startLoc);
		PrimitiveIntList ends = WorldPointUtil.toLocalInstance(client, endLoc);

		if (starts.isEmpty() || ends.isEmpty())
		{
			return;
		}

		int start = starts.get(0);
		int end = ends.get(0);

		final int z = client.getTopLevelWorldView().getPlane();
		if (WorldPointUtil.unpackWorldPlane(start) != z)
		{
			return;
		}

		LocalPoint lpStart = WorldPointUtil.toLocalPoint(client, start);
		LocalPoint lpEnd = WorldPointUtil.toLocalPoint(client, end);

		if (lpStart == null || lpEnd == null)
		{
			return;
		}

		final int startHeight = Perspective.getTileHeight(client, lpStart, z);
		final int endHeight = Perspective.getTileHeight(client, lpEnd, z);

		Point p1 = Perspective.localToCanvas(client, lpStart.getX(), lpStart.getY(), startHeight);
		Point p2 = Perspective.localToCanvas(client, lpEnd.getX(), lpEnd.getY(), endHeight);

		if (p1 == null || p2 == null)
		{
			return;
		}

		Line2D.Double line = new Line2D.Double(p1.getX(), p1.getY(), p2.getX(), p2.getY());

		graphics.setColor(color);
		if (jump)
		{
			// Teleport/transport jumps: a thin dashed hint, like the world map draws them —
			// a full solid beam across the scene reads as a walkable line, which it isn't.
			graphics.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
				10f, new float[]{8, 8}, 0));
		}
		else
		{
			graphics.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		}
		graphics.draw(line);
		if (arrowHead)
		{
			ArrowHead.draw(graphics, p1.getX(), p1.getY(), p2.getX(), p2.getY(), 12);
		}
		if (glow > 0)
		{
			// The flowing ripple: a subtly brighter core stroke — only a mild step towards white,
			// at the path colour's own opacity, so it reads as a sheen rather than a flash.
			graphics.setColor(new Color(
				color.getRed() + (int) ((255 - color.getRed()) * glow * 0.18),
				color.getGreen() + (int) ((255 - color.getGreen()) * glow * 0.18),
				color.getBlue() + (int) ((255 - color.getBlue()) * glow * 0.18),
				color.getAlpha()));
			graphics.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			graphics.draw(line);
		}
	}

	private int drawLabelAtCanvasPoint(Graphics2D graphics, Point point, String text, int verticalOffset)
	{
		if (point == null || text == null || text.isEmpty())
		{
			return 0;
		}

		double height = drawLabel(graphics, point, text, verticalOffset);

		return (int) height + TRANSPORT_LABEL_GAP;
	}

	private int drawLabelAtPackedLocation(Graphics2D graphics, int location, String text, int verticalOffset)
	{
		PrimitiveIntList points = WorldPointUtil.toLocalInstance(client, location);
		for (int i = 0; i < points.size(); i++)
		{
			LocalPoint lp = WorldPointUtil.toLocalPoint(client, points.get(i));
			if (lp == null)
			{
				continue;
			}

			Point p = Perspective.localToCanvas(client, lp, client.getTopLevelWorldView().getPlane());
			if (p == null)
			{
				continue;
			}

			verticalOffset += drawLabelAtCanvasPoint(graphics, p, text, verticalOffset);
		}
		return verticalOffset;
	}

	/**
	 * A pulsing "teleport from here" highlight: diamond rings expanding out from the tile and fading,
	 * looping. Drawn every frame (scene overlays repaint continuously) off wall-clock time, so the motion
	 * stays smooth regardless of game ticks. Anchored to the tile the player casts from — for a
	 * cast-from-anywhere teleport that sits under the player, drawing the eye to "teleport now".
	 */
	private void drawTeleportPulse(Graphics2D graphics, int location)
	{
		PrimitiveIntList points = WorldPointUtil.toLocalInstance(client, location);
		for (int i = 0; i < points.size(); i++)
		{
			LocalPoint lp = WorldPointUtil.toLocalPoint(client, points.get(i));
			if (lp == null)
			{
				continue;
			}
			Polygon poly = Perspective.getCanvasTilePoly(client, lp);
			if (poly == null || poly.npoints == 0)
			{
				continue;
			}
			final double cx = poly.getBounds().getCenterX();
			final double cy = poly.getBounds().getCenterY();

			final long period = 1400L;
			final int rings = 2;
			final Color base = plugin.colourTeleportPulse;
			final Stroke previousStroke = graphics.getStroke();
			graphics.setStroke(new BasicStroke(2.2f));
			for (int r = 0; r < rings; r++)
			{
				// Stagger the two rings by half a period so one is always small/bright while the other
				// is large/faint — a continuous outward pulse.
				double phase = ((System.currentTimeMillis() + (long) (r * period / (double) rings)) % period)
					/ (double) period;
				double scale = 1.0 + phase * 2.6;
				int alpha = (int) Math.round(170 * (1.0 - phase));
				if (alpha <= 0)
				{
					continue;
				}
				Path2D ring = new Path2D.Double();
				for (int v = 0; v < poly.npoints; v++)
				{
					double x = cx + (poly.xpoints[v] - cx) * scale;
					double y = cy + (poly.ypoints[v] - cy) * scale;
					if (v == 0)
					{
						ring.moveTo(x, y);
					}
					else
					{
						ring.lineTo(x, y);
					}
				}
				ring.closePath();
				graphics.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
				graphics.draw(ring);
			}
			graphics.setStroke(previousStroke);
		}
	}

	private double drawLabel(Graphics2D graphics, Point point, String text, int verticalOffset)
	{
		Rectangle2D textBounds = graphics.getFontMetrics().getStringBounds(text, graphics);
		double height = textBounds.getHeight();
		int x = (int) (point.getX() - textBounds.getWidth() / 2);
		int y = (int) (point.getY() - height) - verticalOffset;
		graphics.setColor(Color.BLACK);
		graphics.drawString(text, x + 1, y + 1);
		graphics.setColor(plugin.colourText);
		graphics.drawString(text, x, y);
		return height;
	}

	private int drawLabelOnPlayerTile(Graphics2D graphics, String text, int verticalOffset)
	{
		if (client.getLocalPlayer() == null)
		{
			return 0;
		}

		Point playerPoint = Perspective.localToCanvas(client, client.getLocalPlayer().getLocalLocation(), client.getTopLevelWorldView().getPlane());
		return drawLabelAtCanvasPoint(graphics, playerPoint, text, verticalOffset);
	}

	private void drawTransportInfo(Graphics2D graphics, PathStep currentStep, PathStep nextStep, List<PathStep> path, int pathIndex)
	{
		int location = currentStep.getPackedPosition();
		if (nextStep == null || !plugin.showTransportInfo ||
			WorldPointUtil.unpackWorldPlane(location) != client.getTopLevelWorldView().getPlane())
		{
			return;
		}

		// Sailing: teleports are suppressed while aboard a boat. When the path is
		// unreachable as a result, show a one-time hint on the player tile.
		if (pathIndex == 0 && plugin.getPathfinderConfig().isOnSailingBoat()
			&& plugin.getPathfinder().isDone() && plugin.isPathUnreachable())
		{
			playerTileLabelOffset = drawLabelOnPlayerTile(graphics,
				"Disembark the boat to resume pathfinding", playerTileLabelOffset);
			return;
		}

		if (plugin.isPathUnreachable() || !plugin.getPathfinder().isDone())
		{
			return;
		}
		int locationEnd = nextStep.getPackedPosition();

		// Workaround for weird pathing inside PoH to instead show info on the player
		// tile
		LocalPoint playerLocalPoint = client.getLocalPlayer().getLocalLocation();
		int playerPackedPoint = WorldPointUtil.fromLocalInstance(client, playerLocalPoint);
		int px = WorldPointUtil.unpackWorldX(playerPackedPoint);
		int py = WorldPointUtil.unpackWorldY(playerPackedPoint);
		int tx = WorldPointUtil.unpackWorldX(location);
		int ty = WorldPointUtil.unpackWorldY(location);
		boolean transportAndPlayerInsidePoh = ShortestPathPlugin.isInsidePoh(tx, ty)
			&& ShortestPathPlugin.isInsidePoh(px, py);
		Set<Transport> candidateTransports = plugin.transportsForEdge(currentStep, nextStep);

		// When inside POH, only show the POH exit info once (not per-transport)
		if (transportAndPlayerInsidePoh)
		{
			String pohExitInfo = plugin.getPohExitInfo(locationEnd, path, pathIndex);
			if (pohExitInfo == null)
			{
				return;
			}

			// Find the display name of the teleport that brought us to POH using bank-aware
			// lookup
			String text = null;
			for (Transport transport : candidateTransports)
			{
				text = transport.getDisplayInfo();
				if (text != null && !text.isEmpty())
				{
					break;
				}
			}
			if (text == null || text.isEmpty())
			{
				return;
			}
			text = text + " (Exit: " + pohExitInfo + ")";

			Point p = Perspective.localToCanvas(client, playerLocalPoint, client.getTopLevelWorldView().getPlane());
			if (p == null)
			{
				return;
			}

			drawLabel(graphics, p, text, 0);
			return;
		}

		// A doorway on a plain walk edge: the collision map bakes openable doors passable, so
		// the path assumes they are open. Hint while the door is actually closed (the closed
		// object still stands in the scene) — opening it replaces the object, so the hint
		// clears by itself. Edges with a mapped door transport are handled below instead.
		if (candidateTransports.isEmpty())
		{
			ClosedDoors.Door door = ClosedDoors.doorBetween(location, locationEnd);
			if (door != null && ClosedDoors.state(client, door) == ClosedDoors.State.CLOSED
				&& hintedDoorTiles.add(door.packedPosition))
			{
				playerTileLabelOffset = drawLabelAtPackedLocation(
					graphics, door.packedPosition, "Open " + door.name, playerTileLabelOffset);
			}
		}

		// Check if this is a bank step and items need to be picked up
		Set<Integer> bankLocations = plugin.getPathfinderConfig().getDestinations("bank");
		if (bankLocations != null && plugin.getPathfinderConfig().bank != null)
		{
			List<String> bankPickupItems = BankPickupRequirements.getRequiredBankItems(
				client,
				plugin.getPathfinderConfig().bank,
				plugin.getPathfinderConfig(),
				bankLocations,
				path,
				pathIndex
			);
			if (!bankPickupItems.isEmpty())
			{
				String pickupText = "Pick up: " + String.join(", ", bankPickupItems);
				playerTileLabelOffset = drawLabelAtPackedLocation(graphics, location, pickupText, playerTileLabelOffset);

				// By default, bank pickup info replaces the default transport hint text;
				// enable the option to show both
				if (!plugin.showBankPickupInfo)
				{
					return;
				}
			}
		}

		// Only show transports the player can currently use; fall back to all if none are usable.
		Map<Integer, Integer> playerHas = BankPickupRequirements.collectPlayerItems(client);
		List<Transport> usableTransports = new ArrayList<>();
		for (Transport t : candidateTransports)
		{
			if (BankPickupRequirements.transportSatisfiedBy(t, playerHas))
			{
				usableTransports.add(t);
			}
		}
		Collection<Transport> transportsToShow = usableTransports.isEmpty() ? candidateTransports : usableTransports;

		// Teleports ("use this item/spell") get a pulsing highlight on the tile you cast from, drawn
		// once for the edge even if several teleport options share it.
		boolean teleportEdge = false;
		for (Transport transport : transportsToShow)
		{
			if (transport.getType() != null && transport.getType().isTeleport())
			{
				teleportEdge = true;
				break;
			}
		}
		// Also pulse when the DISPLAYED route teleports on this edge but the config-derived lookup
		// above missed it — a charged jewellery leg under a "perm-only" teleport-item setting.
		if (!teleportEdge)
		{
			teleportEdge = plugin.displayedRouteTeleportsAt(pathIndex);
		}
		if (teleportEdge && plugin.showTeleportPulse)
		{
			drawTeleportPulse(graphics, location);
		}

		Set<String> shownTexts = new HashSet<>();
		for (Transport transport : transportsToShow)
		{
			String text = transport.getDisplayInfo();
			if (text == null || text.isEmpty())
			{
				// Stairs, ladders, agility shortcuts and other object transports have no display
				// info, so the drawn line just ends at them with no cue. Fall back to their menu
				// text ("Climb-up Staircase", "Walk-across Log balance") so the player is told to
				// use them. Doors are left to the closed-door hint below — an open door needs no
				// label.
				String objectText = RouteDirections.objectText(transport);
				if (objectText != null && !objectText.startsWith("Open "))
				{
					text = objectText;
				}
			}
			if (text == null || text.isEmpty())
			{
				// Doors/gates: hint only while the door is actually closed (the closed object still
				// stands in the scene) — an open door needs no cue, and labelling every doorway on
				// the path would be noise.
				text = closedDoorText(transport);
			}
			if (text == null || text.isEmpty() || !shownTexts.add(text))
			{
				continue;
			}

			// Check if this transport goes to POH - if so, look ahead to find the exit
			// transport
			String pohExitInfo = plugin.getPohExitInfo(locationEnd, path, pathIndex);
			if (pohExitInfo != null)
			{
				text = text + " (Exit: " + pohExitInfo + ")";
			}

			playerTileLabelOffset = drawLabelAtPackedLocation(graphics, location, text, playerTileLabelOffset);
		}

		// Fallback: the displayed route teleports here (e.g. a charged tablet/jewellery) but the
		// config-derived lookup above found no matching transport — its teleport-item setting
		// ("Inventory (perm)") excludes consumables. Label it straight from the route's method so
		// the world cue matches the pulse and the panel step.
		if (shownTexts.isEmpty())
		{
			TeleportMethod routeMethod = plugin.displayedRouteMethodAt(pathIndex);
			if (routeMethod != null && routeMethod.getType() != null && routeMethod.getType().isTeleport())
			{
				String label = routeMethod.label();
				if (label != null && !label.isEmpty())
				{
					playerTileLabelOffset = drawLabelAtPackedLocation(graphics, location, label, playerTileLabelOffset);
				}
			}
		}
	}

}
