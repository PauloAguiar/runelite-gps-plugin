package gps;

import com.google.inject.Inject;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import gps.pathfinder.PathStep;

public class PathMinimapOverlay extends Overlay
{
	private final Client client;
	private final ShortestPathPlugin plugin;

	@Inject
	private PathMinimapOverlay(Client client, ShortestPathPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_LOW);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	public static void renderMinimapRect(Client client, Graphics2D graphics, Point center, Color color)
	{
		double angle = client.getCameraYawTarget() * Perspective.UNIT;
		double tileSize = client.getMinimapZoom();
		int x = (int) Math.round(center.getX() - tileSize / 2);
		int y = (int) Math.round(center.getY() - tileSize / 2);
		int width = (int) Math.round(tileSize);
		int height = (int) Math.round(tileSize);
		graphics.setColor(color);
		graphics.rotate(angle, center.getX(), center.getY());
		graphics.fillRect(x, y, width, height);
		graphics.rotate(-angle, center.getX(), center.getY());
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.drawMinimap || !plugin.hasPathTargets())
		{
			return null;
		}

		Shape minimapClipArea = plugin.getMinimapClipArea();
		if (minimapClipArea == null)
		{
			return null;
		}
		else
		{
			graphics.setClip(plugin.getMinimapClipArea());
		}
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

		java.util.List<PathStep> pathPoints = plugin.getDisplayPath();
		Color pathColor = plugin.getPathColor();
		Color doneColor = new Color(0x80, 0x80, 0x80);
		// Same tri-state colouring as the scene: grey for the covered stretch, blocked colour past
		// the first un-crossed obstacle, normal in between.
		int progress = plugin.displayedRouteProgress();
		int blockedFrom = plugin.blockedFromIndex(pathPoints);
		for (int i = 0; i < pathPoints.size(); i++)
		{
			int pathPoint = pathPoints.get(i).getPackedPosition();
			if (WorldPointUtil.unpackWorldPlane(pathPoint) != client.getTopLevelWorldView().getPlane())
			{
				continue;
			}

			Color color = i <= progress ? doneColor
				: (i >= blockedFrom ? plugin.colourPathBlocked : pathColor);
			drawOnMinimap(graphics, pathPoint, color);
		}
		for (int target : plugin.getPathTargets())
		{
			if (!pathPoints.isEmpty() && target != pathPoints.get(pathPoints.size() - 1).getPackedPosition())
			{
				drawOnMinimap(graphics, target, plugin.colourPathCalculating);
			}
		}

		return null;
	}

	private void drawOnMinimap(Graphics2D graphics, int location, Color color)
	{
		PrimitiveIntList points = WorldPointUtil.toLocalInstance(client, location);
		for (int i = 0; i < points.size(); i++)
		{
			LocalPoint lp = WorldPointUtil.toLocalPoint(client, points.get(i));

			if (lp == null)
			{
				continue;
			}

			Point posOnMinimap = Perspective.localToMinimap(client, lp);

			if (posOnMinimap == null)
			{
				continue;
			}

			renderMinimapRect(client, graphics, posOnMinimap, color);
		}
	}
}
