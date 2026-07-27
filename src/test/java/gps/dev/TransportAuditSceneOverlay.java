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
		if (plugin.showBoatDebug)
		{
			renderBoatDebug(graphics);
			renderBoatTiles(graphics);
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

	private static final Color HULL_WALKABLE = new Color(0, 255, 180, 90);
	private static final Color HULL_STRUCTURE = new Color(255, 255, 255, 60);

	/**
	 * The boat's ACTUAL tiles, projected onto the sea: every deck tile of the player's boat
	 * WorldView, its corners pushed through transformToMainWorld (rotation and sub-tile glide
	 * included) — walkable deck in teal, hull structure in white. The rotated quads are the
	 * ground truth the water map's hull clearance must respect.
	 */
	private void renderBoatTiles(Graphics2D graphics)
	{
		net.runelite.api.Player player = client.getLocalPlayer();
		net.runelite.api.WorldView top = client.getTopLevelWorldView();
		if (player == null || top == null || player.getWorldView() == null
			|| player.getWorldView().isTopLevel())
		{
			return;
		}
		net.runelite.api.WorldView boatView = player.getWorldView();
		net.runelite.api.WorldEntity boat = top.worldEntities().byIndex(boatView.getId());
		if (boat == null)
		{
			// byIndex assumption may be the broken link — fall back to our own hull by owner.
			for (net.runelite.api.WorldEntity entity : top.worldEntities())
			{
				if (entity != null && entity.getOwnerType() == net.runelite.api.WorldEntity.OWNER_TYPE_SELF_PLAYER)
				{
					boat = entity;
					break;
				}
			}
		}
		if (boat == null || boatView.getCollisionMaps() == null)
		{
			return;
		}
		int plane = boatView.getPlane();
		if (plane < 0 || plane >= boatView.getCollisionMaps().length
			|| boatView.getCollisionMaps()[plane] == null)
		{
			return;
		}
		int[][] flags = boatView.getCollisionMaps()[plane].getFlags();
		net.runelite.api.Scene boatScene = boatView.getScene();
		net.runelite.api.Tile[][][] deckTiles = boatScene != null ? boatScene.getTiles() : null;
		if (deckTiles == null || plane >= deckTiles.length || deckTiles[plane] == null)
		{
			return;
		}
		int topPlane = top.getPlane();
		for (int sx = 0; sx < Math.min(flags.length, boatView.getSizeX()); sx++)
		{
			for (int sy = 0; sy < Math.min(flags[sx].length, boatView.getSizeY()); sy++)
			{
				// The hull is where the deck scene has actual CONTENT: the padding around it
				// carries zero flags too (which read as "walkable"), so flags alone painted a
				// giant square of sea. Rendered paint/model = a real boat tile.
				net.runelite.api.Tile tile = sx < deckTiles[plane].length
					&& deckTiles[plane][sx] != null && sy < deckTiles[plane][sx].length
					? deckTiles[plane][sx][sy] : null;
				if (tile == null
					|| (tile.getSceneTilePaint() == null && tile.getSceneTileModel() == null))
				{
					continue;
				}
				boolean walkable = (flags[sx][sy] & TransportAuditPlugin.LIVE_BLOCK_MASK) == 0;
				// Scene-overload LocalPoints take BASE-ADDED coordinates (barracuda-trial's
				// working incantation) — raw scene coords land the quads translated off the
				// hull and the rotation pivot in the wrong place.
				net.runelite.api.coords.LocalPoint center = net.runelite.api.coords.LocalPoint.fromScene(
					boatScene.getBaseX() + sx, boatScene.getBaseY() + sy, boatScene);
				if (center == null)
				{
					continue;
				}
				// Tile corners in deck space, projected one by one so rotation survives.
				Polygon quad = new Polygon();
				int[][] corners = {{-64, -64}, {64, -64}, {64, 64}, {-64, 64}};
				boolean complete = true;
				for (int[] corner : corners)
				{
					net.runelite.api.coords.LocalPoint sea =
						boat.transformToMainWorld(center.plus(corner[0], corner[1]));
					Point canvas = sea != null
						? Perspective.localToCanvas(client, sea, topPlane) : null;
					if (canvas == null)
					{
						complete = false;
						break;
					}
					quad.addPoint(canvas.getX(), canvas.getY());
				}
				if (!complete)
				{
					continue;
				}
				graphics.setColor(walkable ? HULL_WALKABLE : HULL_STRUCTURE);
				graphics.fillPolygon(quad);
			}
		}
	}

	/**
	 * Every link of the boat-position chain, live: which value freezes names the culprit.
	 * Lines cover the player's view, every WorldEntity (owner type, raw/target local points,
	 * orientation), the byIndex lookup our conversion uses, RuneLite's fromLocalInstance
	 * verdict, and GPS's packed result.
	 */
	private void renderBoatDebug(Graphics2D graphics)
	{
		java.util.List<String> lines = new java.util.ArrayList<>();
		net.runelite.api.Player player = client.getLocalPlayer();
		net.runelite.api.WorldView top = client.getTopLevelWorldView();
		lines.add("tick " + client.getTickCount());
		if (player == null || top == null)
		{
			lines.add("no player/top view");
		}
		else
		{
			net.runelite.api.WorldView playerView = player.getWorldView();
			lines.add("playerView id=" + (playerView == null ? "null" : playerView.getId())
				+ " topLevel=" + (playerView != null && playerView.isTopLevel()));
			net.runelite.api.coords.WorldPoint deck = player.getWorldLocation();
			lines.add("player.getWorldLocation=" + (deck == null ? "null"
				: deck.getX() + "," + deck.getY() + "," + deck.getPlane()) + " [deck coords]");
			int shown = 0;
			for (net.runelite.api.WorldEntity entity : top.worldEntities())
			{
				if (entity == null || ++shown > 4)
				{
					continue;
				}
				net.runelite.api.coords.LocalPoint el = entity.getLocalLocation();
				net.runelite.api.coords.LocalPoint et = entity.getTargetLocation();
				lines.add("entity view=" + (entity.getWorldView() == null ? "?" : entity.getWorldView().getId())
					+ " owner=" + entity.getOwnerType()
					+ " local=" + (el == null ? "null" : el.getX() + "," + el.getY()
						+ " scene " + el.getSceneX() + "," + el.getSceneY())
					+ " target=" + (et == null ? "null" : et.getX() + "," + et.getY())
					+ " orient=" + entity.getOrientation());
			}
			if (shown == 0)
			{
				lines.add("worldEntities: EMPTY");
			}
			if (playerView != null && !playerView.isTopLevel())
			{
				net.runelite.api.WorldEntity boat = top.worldEntities().byIndex(playerView.getId());
				lines.add("byIndex(" + playerView.getId() + ")=" + (boat == null ? "NULL" : "present"));
				if (boat != null && boat.getLocalLocation() != null)
				{
					net.runelite.api.coords.WorldPoint world =
						net.runelite.api.coords.WorldPoint.fromLocalInstance(client, boat.getLocalLocation());
					lines.add("WP.fromLocalInstance=" + (world == null ? "NULL"
						: world.getX() + "," + world.getY() + "," + world.getPlane()));
				}
			}
			int packed = gps.WorldPointUtil.fromLocalInstance(client, player);
			lines.add("gps.fromLocalInstance=" + (packed == gps.WorldPointUtil.UNDEFINED ? "UNDEFINED"
				: gps.WorldPointUtil.unpackWorldX(packed) + "," + gps.WorldPointUtil.unpackWorldY(packed)
					+ "," + gps.WorldPointUtil.unpackWorldPlane(packed)));
		}
		graphics.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
		int y = 46;
		for (String line : lines)
		{
			graphics.setColor(java.awt.Color.BLACK);
			graphics.drawString(line, 11, y + 1);
			graphics.setColor(java.awt.Color.YELLOW);
			graphics.drawString(line, 10, y);
			y += 14;
		}
	}

	/**
	 * Water tinted per ground OVERLAY id: sailing seas use different overlays for deep water
	 * vs the damaging shallows, and until the ids are confirmed in the field each distinct id
	 * gets a visibly distinct shade (stable per id). Sail over the hurting patch, note its
	 * shade, dump — the dump's overlay histogram names the id.
	 */
	private static Color waterShade(int overlayId)
	{
		if (overlayId == 0)
		{
			return COLLISION_WATER_FILL;
		}
		// Deterministic tint: hue rotates in the blue-green band by id, alpha constant.
		float hue = 0.47f + (Math.floorMod(overlayId * 2654435761L, 5L)) * 0.035f;
		Color base = Color.getHSBColor(hue, 0.85f, 0.9f);
		return new Color(base.getRed(), base.getGreen(), base.getBlue(), 110);
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
					: cell[2] == TransportAuditPlugin.COLLISION_WATER ? waterShade(cell[5])
					: COLLISION_MISSING;
				graphics.setColor(fill);
				graphics.fillPolygon(poly);
				// No early-out: walls draw on top of fills, outlining blocked volumes.
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
