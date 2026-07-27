package gps.dev;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Scene half of the dev transport audit: a red (orange for doors) tile outline and floating
 * label on every unmapped traversal object. Dev client only.
 */
class TransportAuditSceneOverlay extends Overlay
{
	static final Color UNMAPPED = new Color(255, 60, 60);
	static final Color UNMAPPED_DOOR = new Color(255, 160, 40);
	static final Color CONFIRM = new Color(176, 128, 255);
	static final Color KNOWN = new Color(80, 200, 255);
	private static final Color KNOWN_DIM = new Color(80, 200, 255, 90);

	private final Client client;
	private final TransportAuditPlugin plugin;

	@Inject
	TransportAuditSceneOverlay(Client client, TransportAuditPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	private static final Color COLLISION_BOTH = new Color(120, 120, 120, 70);
	private static final Color COLLISION_PHANTOM = new Color(255, 60, 60, 110);
	private static final Color COLLISION_MISSING = new Color(255, 160, 40, 110);
	private static final Color COLLISION_WATER_FILL = new Color(60, 140, 255, 80);
	private static final Color COLLISION_WALL = new Color(210, 210, 210, 160);
	private static final Color COLLISION_EDGE_MISMATCH = new Color(255, 0, 255, 220);

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final int currentPlane = client.getTopLevelWorldView().getPlane();
		if (plugin.showCollision)
		{
			renderCollision(graphics);
		}
		// Known-data browser: dim cyan at every curated origin in the scene, bright cyan on the
		// selected entry's origin AND landing — "what does the data think is here?".
		if (plugin.showKnown)
		{
			for (TransportAuditPlugin.KnownEntry entry : plugin.knownEntries())
			{
				drawKnownTile(graphics, entry.origin, currentPlane, KNOWN_DIM, null);
			}
			drawKnownTile(graphics, plugin.knownHighlightOrigin(), currentPlane, KNOWN, "origin");
			drawKnownTile(graphics, plugin.knownHighlightDest(), currentPlane, KNOWN, "landing");
		}
		// Meta-tagged rows (machine-derived values): violet markers at their origin tiles, so a
		// field session can see what nearby needs confirming.
		for (MetaEdges.Entry entry : plugin.metaEdges())
		{
			if (entry.origin == gps.WorldPointUtil.UNDEFINED
				|| gps.WorldPointUtil.unpackWorldPlane(entry.origin) != currentPlane)
			{
				continue;
			}
			LocalPoint location = LocalPoint.fromWorld(client.getTopLevelWorldView(),
				gps.WorldPointUtil.unpackWorldX(entry.origin),
				gps.WorldPointUtil.unpackWorldY(entry.origin));
			if (location == null)
			{
				continue;
			}
			Polygon poly = Perspective.getCanvasTilePoly(client, location);
			if (poly != null)
			{
				OverlayUtil.renderPolygon(graphics, poly, CONFIRM);
			}
		}
		for (TransportAuditPlugin.Finding finding : plugin.findings())
		{
			// Only the rendered plane: other-plane findings would project onto this plane's
			// terrain at the same x,y (they stay listed in the panel, tagged with their plane).
			if (finding.object.getPlane() != currentPlane)
			{
				continue;
			}
			LocalPoint location = finding.object.getLocalLocation();
			if (location == null)
			{
				continue;
			}
			Color color = TransportAuditPanel.stateColor(plugin.stateOf(finding));
			Polygon poly = Perspective.getCanvasTilePoly(client, location);
			if (poly != null)
			{
				OverlayUtil.renderPolygon(graphics, poly, color);
			}
			String label = finding.name + ": " + finding.action;
			Point text = Perspective.getCanvasTextLocation(client, graphics, location, label, 40);
			if (text != null)
			{
				OverlayUtil.renderTextLocation(graphics, text, label, color);
			}
		}
		return null;
	}

	/**
	 * The collision debug view (old shortest-path debug, upgraded with live comparison):
	 * gray fill = blocked in both maps; RED fill = phantom (static blocks, live open);
	 * ORANGE fill = missing (live blocks, static open); light lines = static wall edges;
	 * MAGENTA edge = live and static disagree about that wall.
	 */
	private void renderCollision(Graphics2D graphics)
	{
		for (int[] cell : plugin.collisionCells())
		{
			// Cells carry SCENE coords: drawing by scene position stays correct in the instanced
			// sea scene while sailing, where world-coord round-trips return null.
			LocalPoint location = LocalPoint.fromScene(cell[0], cell[1]);
			Polygon poly = Perspective.getCanvasTilePoly(client, location);
			if (poly == null || poly.npoints < 4)
			{
				continue;
			}
			if (cell[2] != 0)
			{
				Color fill = cell[2] == TransportAuditPlugin.COLLISION_BOTH_BLOCKED ? COLLISION_BOTH
					: cell[2] == TransportAuditPlugin.COLLISION_STATIC_ONLY ? COLLISION_PHANTOM
					: cell[2] == TransportAuditPlugin.COLLISION_WATER ? COLLISION_WATER_FILL
					: COLLISION_MISSING;
				graphics.setColor(fill);
				graphics.fillPolygon(poly);
				continue;
			}
			// getCanvasTilePoly corners: 0=SW, 1=SE, 2=NE, 3=NW. Direction bit order N,E,S,W.
			int[][] edgeCorners = {{3, 2}, {1, 2}, {0, 1}, {0, 3}};
			for (int d = 0; d < 4; d++)
			{
				boolean wall = (cell[3] & (1 << d)) != 0;
				boolean mismatch = (cell[4] & (1 << d)) != 0;
				if (!wall && !mismatch)
				{
					continue;
				}
				graphics.setColor(mismatch ? COLLISION_EDGE_MISMATCH : COLLISION_WALL);
				graphics.setStroke(new java.awt.BasicStroke(mismatch ? 3 : 1));
				int a = edgeCorners[d][0];
				int b = edgeCorners[d][1];
				graphics.drawLine(poly.xpoints[a], poly.ypoints[a], poly.xpoints[b], poly.ypoints[b]);
			}
		}
	}

	/** One known-data tile: outline (and label for the spotlighted pair) when in the scene. */
	private void drawKnownTile(Graphics2D graphics, int packedTile, int currentPlane,
		Color color, String label)
	{
		if (packedTile == gps.WorldPointUtil.UNDEFINED
			|| gps.WorldPointUtil.unpackWorldPlane(packedTile) != currentPlane)
		{
			return;
		}
		LocalPoint location = LocalPoint.fromWorld(client.getTopLevelWorldView(),
			gps.WorldPointUtil.unpackWorldX(packedTile), gps.WorldPointUtil.unpackWorldY(packedTile));
		if (location == null)
		{
			return;
		}
		Polygon poly = Perspective.getCanvasTilePoly(client, location);
		if (poly != null)
		{
			OverlayUtil.renderPolygon(graphics, poly, color);
		}
		if (label != null)
		{
			Point text = Perspective.getCanvasTextLocation(client, graphics, location, label, 30);
			if (text != null)
			{
				OverlayUtil.renderTextLocation(graphics, text, label, color);
			}
		}
	}
}
