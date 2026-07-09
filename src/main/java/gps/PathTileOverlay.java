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
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathStep;
import gps.pathfinder.TransportAvailability;
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

	private void renderTransports(Graphics2D graphics)
	{
		for (int a : plugin.getTransports().keys())
		{
			if (a == Transport.UNDEFINED_ORIGIN)
			{
				continue; // skip teleports
			}

			boolean drawStart = false;

			Point ca = tileCenter(a);

			if (ca == null)
			{
				continue;
			}

			StringBuilder s = new StringBuilder();
			for (Transport b : plugin.getTransports().getOrDefault(a, TransportAvailability.EMPTY_TRANSPORTS))
			{
				if (b == null || (b.getType() != null && b.getType().isTeleport()))
				{
					continue; // skip teleports
				}
				PrimitiveIntList destinations = WorldPointUtil.toLocalInstance(client, b.getDestination());
				for (int i = 0; i < destinations.size(); i++)
				{
					int destination = destinations.get(i);
					if (destination == Transport.UNDEFINED_DESTINATION)
					{
						continue;
					}
					Point cb = tileCenter(destination);
					if (cb != null)
					{
						graphics.drawLine(ca.getX(), ca.getY(), cb.getX(), cb.getY());
						drawStart = true;
					}
					if (WorldPointUtil.unpackWorldPlane(destination) > WorldPointUtil.unpackWorldPlane(a))
					{
						s.append("+");
					}
					else if (WorldPointUtil.unpackWorldPlane(destination) < WorldPointUtil.unpackWorldPlane(a))
					{
						s.append("-");
					}
					else
					{
						s.append("=");
					}
				}
			}

			if (drawStart)
			{
				drawTile(graphics, a, plugin.colourTransports, -1, true);
			}

			graphics.setColor(Color.WHITE);
			graphics.drawString(s.toString(), ca.getX(), ca.getY());
		}
	}

	private void renderCollisionMap(Graphics2D graphics)
	{
		CollisionMap map = plugin.getMap();
		for (Tile[] row : client.getTopLevelWorldView().getScene().getTiles()[client.getTopLevelWorldView().getPlane()])
		{
			for (Tile tile : row)
			{
				if (tile == null)
				{
					continue;
				}

				Polygon tilePolygon = Perspective.getCanvasTilePoly(client, tile.getLocalLocation());

				if (tilePolygon == null)
				{
					continue;
				}

				int location = WorldPointUtil.fromLocalInstance(client, tile.getLocalLocation());
				int x = WorldPointUtil.unpackWorldX(location);
				int y = WorldPointUtil.unpackWorldY(location);
				int z = WorldPointUtil.unpackWorldPlane(location);

				String s = (!map.n(x, y, z) ? "n" : "") +
					(!map.s(x, y, z) ? "s" : "") +
					(!map.e(x, y, z) ? "e" : "") +
					(!map.w(x, y, z) ? "w" : "");

				if (map.isBlocked(x, y, z))
				{
					graphics.setColor(plugin.colourCollisionMap);
					graphics.fill(tilePolygon);
				}
				if (!s.isEmpty() && !s.equals("nsew"))
				{
					graphics.setColor(Color.WHITE);
					int stringX = (int) (tilePolygon.getBounds().getCenterX()
						- graphics.getFontMetrics().getStringBounds(s, graphics).getWidth() / 2);
					int stringY = (int) tilePolygon.getBounds().getCenterY();
					graphics.drawString(s, stringX, stringY);
				}
			}
		}
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		playerTileLabelOffset = 0;
		hintedDoorTiles.clear();

		if (plugin.drawTransports)
		{
			renderTransports(graphics);
		}

		if (plugin.drawCollisionMap)
		{
			renderCollisionMap(graphics);
		}

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
			int counter = 0;
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
					edgeColor, 1 + counter++, head, glow, jump);
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
	 * Debug view of the off-route bands: the tiles on the warning boundary are tinted yellow and
	 * those on the recalculate boundary red — each is the ring of tiles exactly that Chebyshev
	 * distance from the player (the metric the recalc check uses), so the thresholds sit on real
	 * tiles the player can count. A label reports the live distance from the path.
	 */
	private void drawRecalculationRanges(Graphics2D graphics)
	{
		final Player player = client.getLocalPlayer();
		final int recalc = plugin.getRecalculateDistance();
		if (player == null || recalc < 0)
		{
			return;
		}
		final LocalPoint centre = player.getLocalLocation();
		drawChebyshevRing(graphics, centre, plugin.getOffRouteWarnDistance(),
			new Color(0xFF, 0xE0, 0x00, 60), new Color(0xFF, 0xE0, 0x00, 190));
		drawChebyshevRing(graphics, centre, recalc,
			new Color(0xFF, 0x3C, 0x3C, 60), new Color(0xFF, 0x3C, 0x3C, 190));

		final int d = plugin.getPathDistance();
		final Point anchor = Perspective.localToCanvas(client, centre, client.getTopLevelWorldView().getPlane());
		if (anchor != null)
		{
			final String label = "off-route: " + (d < 0 ? "?" : d)
				+ " (warn " + plugin.getOffRouteWarnDistance() + " / recalc " + recalc + ")";
			graphics.setColor(plugin.isOffRouteWarning() ? new Color(0xFF, 0x4C, 0x4C) : Color.WHITE);
			graphics.drawString(label, anchor.getX() + 6, anchor.getY());
		}
	}

	/**
	 * Tint the ring of tiles at exactly Chebyshev {@code radius} from the player — the boundary of the
	 * off-route square. Works in local space (player local ± radius tiles) so it needs no world/instance
	 * conversion. A zero radius degenerates to the player's own tile.
	 */
	private void drawChebyshevRing(Graphics2D graphics, LocalPoint centre, int radius, Color fill, Color outline)
	{
		final int size = Perspective.LOCAL_TILE_SIZE;
		for (int dx = -radius; dx <= radius; dx++)
		{
			for (int dy = -radius; dy <= radius; dy++)
			{
				// Perimeter only: interior tiles are inside the band, not on its boundary.
				if (Math.max(Math.abs(dx), Math.abs(dy)) != radius)
				{
					continue;
				}
				final LocalPoint lp = new LocalPoint(centre.getX() + dx * size, centre.getY() + dy * size);
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
	}

	/**
	 * Mark the finish stretch with green dots: the tiles at the end of the displayed path from which
	 * arrival fires, i.e. those within the finish distance of the end measured along the path (the
	 * same accumulated {@code distanceBetween2D} metric the arrival check uses). Standing on any of
	 * these while on the path counts as reaching the destination.
	 */
	private void drawArrivalTiles(Graphics2D graphics)
	{
		final int reached = plugin.getReachedDistance();
		final List<PathStep> path = plugin.getDisplayPath();
		if (reached <= 0 || path == null || path.isEmpty())
		{
			return;
		}
		final Color dot = new Color(0x3C, 0xC8, 0x6A);
		// Walk backwards from the end: a tile's remaining is the summed edge length from it to the
		// end, so once that reaches the finish distance no earlier tile can trigger arrival.
		int remaining = 0;
		for (int i = path.size() - 1; i >= 0 && remaining < reached; i--)
		{
			drawTileDot(graphics, path.get(i).getPackedPosition(), dot);
			if (i > 0)
			{
				remaining += Math.max(1, WorldPointUtil.distanceBetween2D(
					path.get(i - 1).getPackedPosition(), path.get(i).getPackedPosition()));
			}
		}
	}

	private void drawTileDot(Graphics2D graphics, int location, Color colour)
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
			graphics.setColor(colour);
			graphics.fill(new Ellipse2D.Double(cx - 3, cy - 3, 6, 6));
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

	private void drawTile(Graphics2D graphics, int location, Color color, int counter, boolean draw)
	{
		if (client == null)
		{
			return;
		}

		PrimitiveIntList points = WorldPointUtil.toLocalInstance(client, location);
		for (int i = 0; i < points.size(); i++)
		{
			int point = points.get(i);
			if (point == WorldPointUtil.UNDEFINED)
			{
				continue;
			}

			LocalPoint lp = WorldPointUtil.toLocalPoint(client, point);
			if (lp == null)
			{
				continue;
			}

			Polygon poly = Perspective.getCanvasTilePoly(client, lp);
			if (poly == null)
			{
				continue;
			}

			if (draw)
			{
				graphics.setColor(color);
				graphics.fill(poly);
			}

			drawCounter(graphics, poly.getBounds().getCenterX(), poly.getBounds().getCenterY(), counter);
		}
	}

	private static boolean directionChanges(int previous, int current, int next)
	{
		int dx1 = WorldPointUtil.unpackWorldX(current) - WorldPointUtil.unpackWorldX(previous);
		int dy1 = WorldPointUtil.unpackWorldY(current) - WorldPointUtil.unpackWorldY(previous);
		int dx2 = WorldPointUtil.unpackWorldX(next) - WorldPointUtil.unpackWorldX(current);
		int dy2 = WorldPointUtil.unpackWorldY(next) - WorldPointUtil.unpackWorldY(current);
		return dx1 != dx2 || dy1 != dy2;
	}

	private void drawLine(Graphics2D graphics, int startLoc, int endLoc, Color color, int counter, boolean arrowHead)
	{
		drawLine(graphics, startLoc, endLoc, color, counter, arrowHead, 0);
	}

	private void drawLine(Graphics2D graphics, int startLoc, int endLoc, Color color, int counter, boolean arrowHead,
		double glow)
	{
		drawLine(graphics, startLoc, endLoc, color, counter, arrowHead, glow, false);
	}

	private void drawLine(Graphics2D graphics, int startLoc, int endLoc, Color color, int counter, boolean arrowHead,
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

		if (counter == 1)
		{
			drawCounter(graphics, p1.getX(), p1.getY(), 0);
		}
		drawCounter(graphics, p2.getX(), p2.getY(), counter);
	}

	private void drawCounter(Graphics2D graphics, double x, double y, int counter)
	{
		if (counter >= 0 && !TileCounter.DISABLED.equals(plugin.showTileCounter))
		{
			int n = plugin.tileCounterStep > 0 ? plugin.tileCounterStep : 1;
			int s = plugin.getDisplayPath().size();
			if ((counter % n != 0) && (s != (counter + 1)))
			{
				return;
			}
			if (TileCounter.REMAINING.equals(plugin.showTileCounter))
			{
				counter = s - counter - 1;
			}
			if (n > 1 && counter == 0)
			{
				return;
			}
			String counterText = Integer.toString(counter);
			graphics.setColor(plugin.colourText);
			graphics.drawString(
				counterText,
				(int) (x - graphics.getFontMetrics().getStringBounds(counterText, graphics).getWidth() / 2), (int) y);
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
