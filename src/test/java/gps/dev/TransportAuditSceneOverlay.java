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
		if (plugin.showBoatWake)
		{
			// A 2D overlay always paints OVER the rendered 3D scene, so the only way to let the
			// ship model sit above the ribbons is to not paint there: the hull's footprint is
			// subtracted from the clip while the ribbons draw, and the model shows through the
			// hole. Their inner ends therefore disappear under the boat instead of butting
			// against it with a hard edge.
			java.awt.Shape previousClip = graphics.getClip();
			Polygon hullBox = hullBoxPolygon();
			if (hullBox != null)
			{
				java.awt.Rectangle canvas = previousClip != null ? previousClip.getBounds()
					: new java.awt.Rectangle(client.getCanvasWidth(), client.getCanvasHeight());
				java.awt.geom.Area clip = new java.awt.geom.Area(canvas);
				clip.subtract(new java.awt.geom.Area(hullBox));
				graphics.setClip(clip);
			}
			renderBoatWake(graphics);
			renderPredictedCourse(graphics);
			graphics.setClip(previousClip);
		}
		if (plugin.showBoatTiles)
		{
			renderBoatTiles(graphics);
		}
		if (plugin.showBoatText)
		{
			renderBoatDebug(graphics);
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

	private static final Color HULL_FILL = new Color(0, 220, 180, 60);
	private static final Color HULL_ANCHOR_FILL = new Color(255, 40, 40, 90);
	private static final Color HULL_ANCHOR_LINE = new Color(255, 40, 40, 230);
	// Draw-side deck offset, ONE TILE bow-ward (-Y). CALIBRATE AGAINST THE HULL, NEVER THE
	// CHARACTER: the player model glides sub-tile on a moving boat and reads up to a tile off
	// its logical cell — zeroing this to chase the character pushed every quad (and the
	// harvested stern cells) one tile off the back of the boat. The hull is static in deck
	// space and matched at -128 ("the exact hitbox", field round 2).
	private static final int BOW_SHIFT_DECK_Y = -128;
	private static final Color HULL_PERIMETER = new Color(139, 84, 33, 230);
	// {centre, edge, band} at the hull end and at the far end of each ribbon; segments
	// interpolate between them, so colour AND alpha ramp along the path.
	private static final Color[] COURSE_NEAR = {
		new Color(80, 255, 120, 230), new Color(80, 255, 120, 140), new Color(80, 255, 120, 55)};
	private static final Color[] COURSE_FAR = {
		new Color(80, 255, 140, 60), new Color(80, 255, 140, 35), new Color(80, 255, 140, 12)};
	// The wake begins where the course left off (same green), ageing into yellow and out to
	// FULLY TRANSPARENT: the oldest sample drops off the deque every tick, so any alpha left at
	// the tip would pop out of existence. Ending at zero makes that invisible.
	private static final Color[] WAKE_NEAR = COURSE_NEAR;
	private static final Color[] WAKE_FAR = {
		new Color(255, 210, 70, 0), new Color(255, 210, 70, 0), new Color(255, 210, 70, 0)};
	private static final Color HULL_CONFIG_BOX = new Color(255, 255, 255, 200);
	private static final Color COURSE_CENTRE = new Color(80, 255, 120, 220);
	private static final Color COURSE_EDGE = new Color(80, 255, 120, 120);
	private static final Color COURSE_BAND = new Color(80, 255, 120, 40);
	/** Ticks of course predicted ahead of the hull. */
	private static final int COURSE_STEPS = 12;
	/** Orientation units per tick the hull turns: 128/2048 of a circle = 22.5 degrees. */
	private static final int TURN_PER_TICK = 128;

	/**
	 * The boat's footprint on the sea: every deck cell with rendered content, corners pushed
	 * through transformToMainWorld so rotation and sub-tile glide survive — one uniform fill,
	 * a brown perimeter and a bow nose. The RED tile is the boat's true position: the
	 * top-level cell holding the WorldEntity's local location, which is what
	 * gps.fromLocalInstance resolves and what routing consumes.
	 *
	 * Deck conventions, each won in the field: content lives on the PLAYER'S plane; the model
	 * is drawn one tile bow-ward of the scene grid ({@link #BOW_SHIFT_DECK_Y}); the bow is the
	 * LOWEST deck Y. Calibrate against the hull, never the character (it glides sub-tile).
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
		if (boat == null)
		{
			return;
		}
		// Content lives on the PLAYER'S plane (the deck renders there; the view's own plane is
		// the hull volume below). Collision flags are deliberately NOT read: they don't encode
		// deck walkability consistently, and routing only needs the footprint and the anchor.
		int plane = player.getWorldLocation() != null
			? player.getWorldLocation().getPlane() : boatView.getPlane();
		net.runelite.api.Scene boatScene = boatView.getScene();
		net.runelite.api.Tile[][][] deckTiles = boatScene != null ? boatScene.getTiles() : null;
		if (deckTiles == null || plane < 0 || plane >= deckTiles.length || deckTiles[plane] == null)
		{
			return;
		}
		int topPlane = top.getPlane();

		// Pass 1: the hull = deck cells with rendered content (the padding around it has none).
		java.util.Set<Integer> content = new java.util.HashSet<>();
		int bowY = Integer.MAX_VALUE;
		for (int sx = 0; sx < Math.min(deckTiles[plane].length, boatView.getSizeX()); sx++)
		{
			if (deckTiles[plane][sx] == null)
			{
				continue;
			}
			for (int sy = 0; sy < Math.min(deckTiles[plane][sx].length, boatView.getSizeY()); sy++)
			{
				net.runelite.api.Tile tile = deckTiles[plane][sx][sy];
				if (tile != null
					&& (tile.getSceneTilePaint() != null || tile.getSceneTileModel() != null))
				{
					content.add((sx << 16) | sy);
					bowY = Math.min(bowY, sy);
				}
			}
		}
		if (content.isEmpty())
		{
			return;
		}

		// Pass 2: uniform fill plus brown boundary edges.
		java.util.List<int[]> bowTip = new java.util.ArrayList<>();
		for (int cell : content)
		{
			int sx = cell >> 16;
			int sy = cell & 0xFFFF;
			net.runelite.api.coords.LocalPoint center = net.runelite.api.coords.LocalPoint.fromScene(
				boatScene.getBaseX() + sx, boatScene.getBaseY() + sy, boatScene);
			if (center == null)
			{
				continue;
			}
			Point[] corners = projectQuad(boat, center, topPlane);
			if (corners == null)
			{
				continue;
			}
			Polygon quad = new Polygon();
			for (Point corner : corners)
			{
				quad.addPoint(corner.getX(), corner.getY());
			}
			graphics.setColor(HULL_FILL);
			graphics.fillPolygon(quad);
			graphics.setColor(HULL_PERIMETER);
			graphics.setStroke(new java.awt.BasicStroke(2));
			int[][] neighbours = {{0, -1, 0, 1}, {1, 0, 1, 2}, {0, 1, 2, 3}, {-1, 0, 3, 0}};
			for (int[] n : neighbours)
			{
				if (!content.contains(((sx + n[0]) << 16) | (sy + n[1])))
				{
					graphics.drawLine(corners[n[2]].getX(), corners[n[2]].getY(),
						corners[n[3]].getX(), corners[n[3]].getY());
				}
			}
			if (sy == bowY)
			{
				bowTip.add(new int[]{sx, sy});
			}
		}

		// The boat's TRUE tile: the top-level cell containing the WorldEntity's local location —
		// the exact anchor gps.fromLocalInstance resolves and routing/progress consume. Red,
		// drawn in sea space (no deck projection), so anchor-vs-hull drift is visible at a
		// glance while gliding.
		net.runelite.api.coords.LocalPoint anchor = boat.getLocalLocation();
		if (anchor != null)
		{
			Polygon anchorPoly = Perspective.getCanvasTilePoly(client, anchor);
			if (anchorPoly != null)
			{
				graphics.setColor(HULL_ANCHOR_FILL);
				graphics.fillPolygon(anchorPoly);
				graphics.setColor(HULL_ANCHOR_LINE);
				graphics.setStroke(new java.awt.BasicStroke(2));
				graphics.drawPolygon(anchorPoly);
			}
		}

		// The client's OWN hull box (WorldEntityConfig bounds, rotated by the entity's
		// orientation) — authoritative and calibration-free. Drawn alongside the content-derived
		// footprint so the two can be compared: if they coincide, the deck scan and its
		// hard-won BOW_SHIFT can be retired in favour of this.
		Polygon configBox = hullBoxPolygon();
		if (configBox != null)
		{
			graphics.setColor(HULL_CONFIG_BOX);
			graphics.setStroke(new java.awt.BasicStroke(2));
			graphics.draw(configBox);
		}

		// The bow ^ spans the FULL front row: wings anchored at the outermost front corners,
		// apex one tile ahead of the row's middle — a nose, not a one-tile doodle.
		if (!bowTip.isEmpty())
		{
			int minSx = Integer.MAX_VALUE;
			int maxSx = Integer.MIN_VALUE;
			for (int[] t : bowTip)
			{
				minSx = Math.min(minSx, t[0]);
				maxSx = Math.max(maxSx, t[0]);
			}
			net.runelite.api.coords.LocalPoint leftCell = net.runelite.api.coords.LocalPoint.fromScene(
				boatScene.getBaseX() + minSx, boatScene.getBaseY() + bowY, boatScene);
			net.runelite.api.coords.LocalPoint rightCell = net.runelite.api.coords.LocalPoint.fromScene(
				boatScene.getBaseX() + maxSx, boatScene.getBaseY() + bowY, boatScene);
			if (leftCell != null && rightCell != null)
			{
				int apexX = (leftCell.getX() + rightCell.getX()) / 2;
				net.runelite.api.coords.LocalPoint apexCell =
					new net.runelite.api.coords.LocalPoint(apexX, leftCell.getY(), leftCell.getWorldView());
				Point apex = project(boat, apexCell.plus(0, BOW_SHIFT_DECK_Y - 160), topPlane);
				Point left = project(boat, leftCell.plus(-64, BOW_SHIFT_DECK_Y - 64), topPlane);
				Point right = project(boat, rightCell.plus(64, BOW_SHIFT_DECK_Y - 64), topPlane);
				if (apex != null && left != null && right != null)
				{
					graphics.setColor(HULL_PERIMETER);
					graphics.setStroke(new java.awt.BasicStroke(3));
					graphics.drawLine(left.getX(), left.getY(), apex.getX(), apex.getY());
					graphics.drawLine(apex.getX(), apex.getY(), right.getX(), right.getY());
				}
			}
		}
	}

	/**
	 * The boat's WAKE as a swept-path curve: the centre line the hull travelled plus its port
	 * and starboard edges, filled as a band. Each sample's three model-space points (port,
	 * centre, starboard) go through ONE modelToCanvas call so the sample's own orientation is
	 * applied by the same transform the client uses — the technique anmcgrath's turning-circles
	 * plugin (BSD-2) uses for its prediction boxes, applied to history instead of prediction.
	 *
	 * The hull's beam comes from {@link net.runelite.api.WorldEntityConfig} — the client's own
	 * bounding box, no deck scanning needed.
	 */
	private void renderBoatWake(Graphics2D graphics)
	{
		java.util.List<int[]> wake = plugin.boatWake();
		net.runelite.api.WorldEntity boat = plugin.playerBoat();
		if (wake.size() < 2 || boat == null || boat.getConfig() == null)
		{
			return;
		}
		net.runelite.api.WorldEntityConfig config = boat.getConfig();
		float centreX = config.getBoundsX();
		float centreY = config.getBoundsY();
		float halfBeam = config.getBoundsWidth() / 2f;
		float[] modelX = {centreX - halfBeam, centreX, centreX + halfBeam};
		float[] modelY = {centreY, centreY, centreY};
		float[] modelZ = {0, 0, 0};

		// The hull is at the END of the wake (newest sample) and the START of the course.
		drawSweptRibbon(graphics, wake, centreX, centreY, halfBeam,
			WAKE_NEAR, WAKE_FAR, false);
	}

	/**
	 * The course the hull would sweep if it turned toward the MOUSE and held speed: same green
	 * ribbon as the wake, projected forward. The client's own numbers drive it — turning is
	 * {@link #TURN_PER_TICK} orientation units per tick (22.5 degrees, the 16-heading model)
	 * and speed is measured from the wake. Acceleration is deliberately NOT modelled: this is
	 * a diagnostic for turning radius and swept width, so a constant-speed arc keeps it honest
	 * rather than inventing a curve we haven't calibrated.
	 */
	private void renderPredictedCourse(Graphics2D graphics)
	{
		net.runelite.api.WorldEntity boat = plugin.playerBoat();
		if (boat == null || boat.getConfig() == null || boat.getTargetLocation() == null)
		{
			return;
		}
		double speed = plugin.boatSpeedTiles;
		if (speed <= 0)
		{
			return; // parked: no course to draw
		}
		int target = mouseHeading(boat.getTargetLocation());
		if (target < 0)
		{
			return;
		}
		net.runelite.api.WorldEntityConfig config = boat.getConfig();
		int orientation = boat.getTargetOrientation();
		double x = boat.getTargetLocation().getX();
		double y = boat.getTargetLocation().getY();
		java.util.List<int[]> course = new java.util.ArrayList<>(COURSE_STEPS + 2);
		// Begin a half-hull BEHIND the centre: the ribbon then emerges from under the bow
		// rather than starting with a hard line across the deck.
		double startRadians = Math.toRadians(270 - orientation / (2048 / 360.0));
		double halfLength = config.getBoundsHeight() / 2.0;
		course.add(new int[]{
			(int) Math.round(x - Math.cos(startRadians) * halfLength),
			(int) Math.round(y - Math.sin(startRadians) * halfLength),
			orientation});
		course.add(new int[]{(int) x, (int) y, orientation});
		for (int step = 0; step < COURSE_STEPS; step++)
		{
			if (orientation != target)
			{
				// Shortest way round: signed delta folded into [-1024, 1024).
				int delta = Math.floorMod(target - orientation + 1024, 2048) - 1024;
				int turn = Math.min(TURN_PER_TICK, Math.abs(delta)) * (delta >= 0 ? 1 : -1);
				orientation = Math.floorMod(orientation + turn, 2048);
			}
			// SailingMath's convention: degrees = 270 - orientation/(2048/360).
			double radians = Math.toRadians(270 - orientation / (2048 / 360.0));
			x += Math.cos(radians) * speed * 128;
			y += Math.sin(radians) * speed * 128;
			course.add(new int[]{(int) Math.round(x), (int) Math.round(y), orientation});
		}
		drawSweptRibbon(graphics, course, config.getBoundsX(), config.getBoundsY(),
			config.getBoundsWidth() / 2f, COURSE_NEAR, COURSE_FAR, true);
	}

	/**
	 * Which of the 16 heading sectors the cursor sits in, as an orientation — the sector
	 * dividers are drawn from the hull outwards in CANVAS space and the mouse is side-tested
	 * against them, so the isometric projection is handled for free (the approach
	 * anmcgrath's turning-circles plugin uses). -1 when the hull is off-screen.
	 */
	private int mouseHeading(net.runelite.api.coords.LocalPoint boatCentre)
	{
		Point centre = Perspective.localToCanvas(client, boatCentre, 0);
		if (centre == null)
		{
			return -1;
		}
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		if (mouse == null)
		{
			return -1;
		}
		float[] lineX = {0, 0};
		float[] lineY = {1000, -1000};
		float[] lineZ = {0, 0};
		int[][] dividers = new int[16][];
		for (int n = 0; n < 16; n++)
		{
			int[] canvasX = new int[2];
			int[] canvasY = new int[2];
			// Offset by half a sector (64) so the lines DIVIDE sectors instead of centring them.
			Perspective.modelToCanvas(client, client.getTopLevelWorldView(), 2,
				boatCentre.getX(), boatCentre.getY(), 0, 64 + 128 * n,
				lineX, lineY, lineZ, canvasX, canvasY);
			if (canvasX[1] == Integer.MIN_VALUE)
			{
				return -1;
			}
			dividers[n] = new int[]{centre.getX(), centre.getY(), canvasX[1], canvasY[1]};
		}
		for (int i = 0; i < 15; i++)
		{
			if (sideOf(dividers[i], mouse) >= 0 && sideOf(dividers[i + 1], mouse) < 0)
			{
				return 128 + i * 128;
			}
		}
		return 0;
	}

	/** >0 left of the line, <0 right, 0 on it. */
	private static long sideOf(int[] line, net.runelite.api.Point point)
	{
		return (long) (line[2] - line[0]) * (point.getY() - line[1])
			- (long) (line[3] - line[1]) * (point.getX() - line[0]);
	}

	/**
	 * Paints a run of {x, y, orientation} hull samples as a swept path: port and starboard
	 * edges, a filled band between them, and the centre line. Each sample's three model-space
	 * points go through ONE modelToCanvas call so the client applies that sample's rotation.
	 */
	/**
	 * Paints a run of {x, y, orientation} hull samples as a swept path: port and starboard
	 * edges with a filled band between them, plus the centre line. Each sample's three
	 * model-space points go through ONE modelToCanvas call so the client applies that sample's
	 * own rotation.
	 *
	 * Colour ramps from {@code near} (against the hull) to {@code far}, per segment — a single
	 * Path2D can only carry one colour, so the ribbon is painted segment by segment. The wake
	 * and the course share their near colour, which is what makes the predicted path appear to
	 * turn into the wake as the boat sails along it.
	 */
	private void drawSweptRibbon(Graphics2D graphics, java.util.List<int[]> samples,
		float centreX, float centreY, float halfBeam,
		Color[] near, Color[] far, boolean hullAtStart)
	{
		if (samples.size() < 2)
		{
			return;
		}
		float[] modelX = {centreX - halfBeam, centreX, centreX + halfBeam};
		float[] modelY = {centreY, centreY, centreY};
		float[] modelZ = {0, 0, 0};
		java.util.List<int[]> projected = new java.util.ArrayList<>(samples.size());
		int[] canvasX = new int[3];
		int[] canvasY = new int[3];
		for (int[] sample : samples)
		{
			Perspective.modelToCanvas(client, client.getTopLevelWorldView(), 3,
				sample[0], sample[1], 0, sample[2], modelX, modelY, modelZ, canvasX, canvasY);
			if (canvasX[1] == Integer.MIN_VALUE)
			{
				continue; // off-screen sample: skip rather than break the ribbon
			}
			projected.add(new int[]{canvasX[0], canvasY[0], canvasX[1], canvasY[1],
				canvasX[2], canvasY[2]});
		}
		if (projected.size() < 2)
		{
			return;
		}
		java.awt.Stroke edgeStroke = new java.awt.BasicStroke(1.5f,
			java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND);
		java.awt.Stroke centreStroke = new java.awt.BasicStroke(2.5f,
			java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND);
		for (int i = 0; i < projected.size() - 1; i++)
		{
			int[] from = projected.get(i);
			int[] to = projected.get(i + 1);
			// Distance from the hull, 0 at its end of the ribbon and 1 at the far tip.
			double along = (double) (i + 1) / (projected.size() - 1);
			double t = hullAtStart ? along : 1 - along;
			// Tail-weighted easing: most of the ribbon keeps its near colour and the fade
			// concentrates toward the tip, instead of washing out linearly from the hull.
			t = t * t;

			Polygon quad = new Polygon();
			quad.addPoint(from[0], from[1]);
			quad.addPoint(to[0], to[1]);
			quad.addPoint(to[4], to[5]);
			quad.addPoint(from[4], from[5]);
			graphics.setColor(blend(near[2], far[2], t));
			graphics.fillPolygon(quad);

			graphics.setStroke(edgeStroke);
			graphics.setColor(blend(near[1], far[1], t));
			graphics.drawLine(from[0], from[1], to[0], to[1]);
			graphics.drawLine(from[4], from[5], to[4], to[5]);

			graphics.setStroke(centreStroke);
			graphics.setColor(blend(near[0], far[0], t));
			graphics.drawLine(from[2], from[3], to[2], to[3]);
		}
	}

	/**
	 * The hull's own bounding box (WorldEntityConfig, rotated by the entity's orientation) as a
	 * canvas polygon — authoritative and calibration-free. Doubles as the shape subtracted from
	 * the ribbons' clip so the 3D ship model shows through them. Null when ashore or off-screen.
	 */
	private Polygon hullBoxPolygon()
	{
		net.runelite.api.WorldEntity boat = plugin.playerBoat();
		if (boat == null || boat.getConfig() == null || boat.getLocalLocation() == null)
		{
			return null;
		}
		net.runelite.api.WorldEntityConfig config = boat.getConfig();
		float halfWidth = config.getBoundsWidth() / 2f;
		float halfHeight = config.getBoundsHeight() / 2f;
		float boundsX = config.getBoundsX();
		float boundsY = config.getBoundsY();
		float[] boxX = {boundsX - halfWidth, boundsX + halfWidth,
			boundsX + halfWidth, boundsX - halfWidth};
		float[] boxY = {boundsY - halfHeight, boundsY - halfHeight,
			boundsY + halfHeight, boundsY + halfHeight};
		float[] boxZ = {0, 0, 0, 0};
		int[] canvasX = new int[4];
		int[] canvasY = new int[4];
		Perspective.modelToCanvas(client, client.getTopLevelWorldView(), 4,
			boat.getLocalLocation().getX(), boat.getLocalLocation().getY(), 0,
			boat.getOrientation(), boxX, boxY, boxZ, canvasX, canvasY);
		if (canvasX[0] == Integer.MIN_VALUE)
		{
			return null;
		}
		Polygon box = new Polygon();
		for (int i = 0; i < 4; i++)
		{
			box.addPoint(canvasX[i], canvasY[i]);
		}
		return box;
	}

	/** Linear RGBA blend; t=0 keeps {@code from}, t=1 reaches {@code to}. */
	private static Color blend(Color from, Color to, double t)
	{
		double clamped = Math.max(0, Math.min(1, t));
		return new Color(
			(int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * clamped),
			(int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * clamped),
			(int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * clamped),
			(int) Math.round(from.getAlpha() + (to.getAlpha() - from.getAlpha()) * clamped));
	}

	/** The four bow-shifted, rotated corners of a deck cell on the sea, or null off-screen. */
	private Point[] projectQuad(net.runelite.api.WorldEntity boat,
		net.runelite.api.coords.LocalPoint center, int topPlane)
	{
		int[][] offsets = {{-64, -64}, {64, -64}, {64, 64}, {-64, 64}};
		Point[] out = new Point[4];
		for (int i = 0; i < 4; i++)
		{
			out[i] = project(boat, center.plus(offsets[i][0], offsets[i][1] + BOW_SHIFT_DECK_Y), topPlane);
			if (out[i] == null)
			{
				return null;
			}
		}
		return out;
	}

	private Point project(net.runelite.api.WorldEntity boat,
		net.runelite.api.coords.LocalPoint deckPoint, int topPlane)
	{
		net.runelite.api.coords.LocalPoint sea = boat.transformToMainWorld(deckPoint);
		return sea != null ? Perspective.localToCanvas(client, sea, topPlane) : null;
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
