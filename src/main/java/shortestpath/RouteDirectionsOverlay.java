package shortestpath;

import com.google.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * Movable "directions" panel for the route currently shown on the map: the numbered steps to follow
 * — walking legs, teleports/transports to use, bank withdrawals and climbs — with live progress from
 * the player's position. Completed steps grey out (and collapse into a summary line), the step being
 * executed is orange (amber once it's nearly finished), the next step is white. Drag it wherever you
 * like (RuneLite overlay editing); hidden when no route is shown or via the config toggle.
 */
public class RouteDirectionsOverlay extends OverlayPanel
{
	private static final int MAX_LINES = 14;
	// The panel grows to fit its widest line between these bounds; only beyond MAX_WIDTH do lines
	// get ellipsized (clipping "Use Rat Pits M…" is worse than a wider panel).
	private static final int MIN_WIDTH = 140;
	private static final int MAX_WIDTH = 360;
	// Component insets plus the gap between the left text and the right-aligned time.
	private static final int PANEL_PADDING = 18;

	private static final Color DONE = new Color(0x80, 0x80, 0x80);
	// Map-app navigation blue for the active step; the lighter shade signals "about to end".
	private static final Color CURRENT = new Color(0x4C, 0x8B, 0xF5);
	private static final Color ENDING = new Color(0x8A, 0xB4, 0xF8);
	private static final Color NEXT = Color.WHITE;
	private static final Color UPCOMING = new Color(0xB4, 0xB4, 0xB4);

	// Magnifying-glass effect via the fonts' NATIVE sizes only: the RuneScape fonts are bitmap-style
	// and deform when scaled with deriveFont. Bold (16) > regular (16, lighter) > small.
	private static final Font FONT_CURRENT = FontManager.getRunescapeBoldFont();
	private static final Font FONT_NEXT = FontManager.getRunescapeFont();
	private static final Font FONT_OTHER = FontManager.getRunescapeSmallFont();

	private final Client client;
	private final ShortestPathPlugin plugin;

	// Progress along the displayed route: each frame the marker moves to the eligible path tile
	// nearest the player, and the ETA is (walk time to that tile + remaining route time from it).
	// It counts down as the player travels, grows again when backtracking or straying off the
	// path, and mid-ride transports are interpolated separately (see the riding branch).
	private RouteOption progressRoute;
	private int reachedIndex;
	// Remaining route time (ticks) from each path index, precomputed per route.
	private double[] remainingTicksAt;
	// The live remaining estimate chosen by the scoring pass.
	private double liveRemainingTicks;

	// Straight-line proximity is not reachability: a later leg can pass close by across a cliff the
	// route detours around, and straight-line cost always underprices a transport that exists
	// because you can't walk there. Progress therefore moves INCREMENTALLY — a few indexes per
	// update, and only while genuinely near the line — with two jump exceptions: standing exactly ON
	// a path tile at walking speed (teleport/transport landings), and ride interpolation.
	private static final int STEP_WINDOW = 8;
	private static final int NEAR_DISTANCE = 10;
	private static final double VEHICLE_TILES_PER_SECOND = 4.5;
	private static final long SPEED_SAMPLE_MILLIS = 400;
	private long speedSampleAt;
	private int speedSamplePosition = WorldPointUtil.UNDEFINED;
	private double speedTilesPerSecond;

	// Arrival lingering: the plugin clears the target the moment the destination is reached, which
	// would vanish the panel mid-glance. When the route disappears right after progress was at the
	// end, an "Arrived!" panel lingers instead — until clicked or the timer runs out.
	private static final long ARRIVAL_LINGER_MILLIS = 10_000;
	private static final long NEAR_END_GRACE_MILLIS = 4_000;
	private long nearEndAtMillis = Long.MIN_VALUE / 2;
	private boolean arrivalShowing;
	private long arrivalUntilMillis;
	// Wall-clock start of the current journey (stamped when a route starts being tracked) and
	// snapshots for the arrival panel — the plugin clears the target (and its source) the moment
	// the destination is reached, so they must be captured while the route is still alive.
	private long journeyStartMillis;
	private String arrivalSource;
	private long arrivalElapsedMillis;

	private static final Color ARRIVED = new Color(0x3C, 0xC8, 0x6A);

	@Inject
	public RouteDirectionsOverlay(Client client, ShortestPathPlugin plugin)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.showDirections)
		{
			return null;
		}
		long now = System.currentTimeMillis();
		RouteOption route = plugin.getDisplayedRoute();
		if (route == null)
		{
			// The route just ended: if progress was at the destination moments ago, this is an
			// arrival — linger with a farewell instead of vanishing mid-glance.
			if (!arrivalShowing && now - nearEndAtMillis < NEAR_END_GRACE_MILLIS)
			{
				arrivalShowing = true;
				arrivalUntilMillis = now + ARRIVAL_LINGER_MILLIS;
				arrivalElapsedMillis = Math.max(0, now - journeyStartMillis);
			}
			nearEndAtMillis = Long.MIN_VALUE / 2;
			if (arrivalShowing && now < arrivalUntilMillis)
			{
				return renderArrival(graphics);
			}
			arrivalShowing = false;
			return null;
		}
		arrivalShowing = false;
		List<RouteDirections.Step> steps = plugin.getRouteDirections(route);
		if (steps.isEmpty())
		{
			return null;
		}
		updateProgress(route, steps);
		// Within ~2 seconds of travel from the destination counts as "about to arrive".
		if (liveRemainingTicks <= 3.5)
		{
			nearEndAtMillis = now;
		}

		// The first step whose span the player hasn't finished yet is the one being executed.
		int current = steps.size() - 1;
		for (int i = 0; i < steps.size(); i++)
		{
			if (steps.get(i).getEndIndex() > reachedIndex)
			{
				current = i;
				break;
			}
		}

		String etaText = "ETA: " + formatTime((int) Math.ceil(liveRemainingTicks * RouteDirections.SECONDS_PER_TICK));

		// First pass: collect the lines, then size the panel to the widest one (within bounds) so
		// content is neither wrapped nor needlessly clipped; only lines beyond MAX_WIDTH truncate.
		List<Line> lines = new ArrayList<>();
		String source = plugin.getTargetSource();
		arrivalSource = source;
		if (source != null)
		{
			lines.add(new Line("Destination set by " + source, FONT_OTHER, UPCOMING, null, null));
		}

		// Window: collapse all but the most recent completed step into one summary line, then show
		// the current step and what follows, capped.
		int windowStart = Math.max(0, current - 1);
		if (windowStart > 0)
		{
			lines.add(new Line("✓ " + windowStart + (windowStart == 1 ? " step done" : " steps done"),
				FONT_OTHER, DONE, null, null));
		}
		int shown = 0;
		int i = windowStart;
		for (; i < steps.size() && shown < MAX_LINES; i++, shown++)
		{
			RouteDirections.Step step = steps.get(i);
			String stepText = i >= current && step.isDoor()
				? doorStepText(route, step) : step.getText();
			String text = (i + 1) + ". " + stepText;
			Color colour;
			Font font;
			if (i < current)
			{
				// The RuneScape fonts carry no U+2713 glyph, but the JVM's font pipeline substitutes
				// one from a system font (verified in-game on Windows). If reports of missing-glyph
				// boxes come in from other platforms, drop the prefix — the grey colour already
				// marks completion on its own.
				text = "✓ " + text;
				colour = DONE;
				font = FONT_OTHER;
			}
			else if (i == current)
			{
				colour = nearEnd(step) ? ENDING : CURRENT;
				font = FONT_CURRENT;
			}
			else if (i == current + 1)
			{
				colour = NEXT;
				font = FONT_NEXT;
			}
			else
			{
				colour = UPCOMING;
				font = FONT_OTHER;
			}
			lines.add(new Line(text, font, colour,
				formatTime((int) Math.ceil(step.getTicks() * RouteDirections.SECONDS_PER_TICK)), colour));
		}
		if (i < steps.size())
		{
			lines.add(new Line("… " + (steps.size() - i) + " more", FONT_OTHER, DONE, null, null));
		}

		Dimension dimension = renderPanel(graphics, lines);
		if (dimension != null)
		{
			drawEtaBadge(graphics, dimension, etaText);
		}
		return dimension;
	}

	/**
	 * Sizes the panel to its widest line (within bounds), emits the header spacer and the lines, and
	 * draws the decorated title over the reserved header row.
	 */
	private Dimension renderPanel(Graphics2D graphics, List<Line> lines)
	{
		// Fit the panel to the content: widest left text + its time column, clamped to sane bounds.
		int contentWidth = MIN_WIDTH;
		for (Line line : lines)
		{
			int width = graphics.getFontMetrics(line.font).stringWidth(line.left)
				+ rightWidth(graphics, line) + PANEL_PADDING;
			contentWidth = Math.max(contentWidth, width);
		}
		int panelWidth = Math.min(contentWidth, MAX_WIDTH);
		panelComponent.setPreferredSize(new Dimension(panelWidth, 0));

		// Spacer reserving the header row; the decorated title (pin glyph + bold text + accent rule)
		// is custom-drawn over it after the panel renders — TitleComponent supports no font/styling.
		panelComponent.getChildren().add(
			LineComponent.builder()
				.left(" ")
				.leftFont(FONT_CURRENT)
				.build());
		for (Line line : lines)
		{
			if (line.centred)
			{
				// Reserve the row with a spacer in the same font; the text itself is drawn
				// centred over the panel afterwards.
				panelComponent.getChildren().add(
					LineComponent.builder().left(" ").leftFont(line.font).build());
				continue;
			}
			LineComponent.LineComponentBuilder builder = LineComponent.builder()
				.left(ellipsize(graphics, line.font, line.left, panelWidth - rightWidth(graphics, line) - PANEL_PADDING))
				.leftColor(line.colour)
				.leftFont(line.font);
			if (line.right != null)
			{
				builder.right(line.right)
					.rightColor(line.rightColour)
					.rightFont(line.font == FONT_CURRENT ? FONT_NEXT : FONT_OTHER);
			}
			panelComponent.getChildren().add(builder.build());
		}

		Dimension dimension = super.render(graphics);
		if (dimension != null)
		{
			drawTitle(graphics, dimension);
			drawCentredLines(graphics, dimension, lines);
		}
		return dimension;
	}

	/**
	 * Draws the trailing centred lines over their reserved spacer rows, anchored bottom-up to the
	 * panel's bottom edge. TextLayout's tight glyph bounds centre the text properly — the
	 * RuneScape fonts' metrics don't match their visual size (see {@link #drawEtaBadge}).
	 */
	private void drawCentredLines(Graphics2D graphics, Dimension panelSize, List<Line> lines)
	{
		int first = lines.size();
		while (first > 0 && lines.get(first - 1).centred)
		{
			first--;
		}
		float bottom = panelSize.height - 7;
		for (int i = lines.size() - 1; i >= first; i--)
		{
			Line line = lines.get(i);
			TextLayout layout = new TextLayout(line.left, line.font, graphics.getFontRenderContext());
			Rectangle2D bounds = layout.getBounds();
			float x = (float) ((panelSize.width - bounds.getWidth()) / 2 - bounds.getX());
			// Place the glyph box's bottom on the anchor, then move the anchor up past it.
			float baseline = (float) (bottom - bounds.getHeight() - bounds.getY());
			graphics.setColor(Color.BLACK);
			layout.draw(graphics, x + 1, baseline + 1);
			graphics.setColor(line.colour);
			layout.draw(graphics, x, baseline);
			bottom -= bounds.getHeight() + 5;
		}
	}

	/**
	 * Decorated header drawn over the reserved top row: a small location-pin glyph, bold "GPS", and
	 * a navigation-blue rule under the header separating it from the steps.
	 */
	private void drawTitle(Graphics2D graphics, Dimension panelSize)
	{
		// Location pin: round head with a tail, hollow centre.
		final int px = 8;
		final int py = 4;
		graphics.setColor(CURRENT);
		graphics.fillOval(px, py, 9, 9);
		Polygon tail = new Polygon(
			new int[]{px + 1, px + 8, px + 4},
			new int[]{py + 7, py + 7, py + 13},
			3);
		graphics.fillPolygon(tail);
		graphics.setColor(new Color(0x10, 0x10, 0x10));
		graphics.fillOval(px + 3, py + 3, 3, 3);

		graphics.setFont(FONT_CURRENT);
		graphics.setColor(Color.BLACK);
		graphics.drawString("GPS", px + 14, py + 12);
		graphics.setColor(Color.WHITE);
		graphics.drawString("GPS", px + 13, py + 11);

		// Accent rule under the header row.
		graphics.setColor(new Color(CURRENT.getRed(), CURRENT.getGreen(), CURRENT.getBlue(), 170));
		graphics.drawLine(4, py + 15, panelSize.width - 4, py + 15);
	}

	/**
	 * The ETA as a floating pill overlapping the panel's top-right corner — a badge rather than a
	 * list row, map-app style.
	 */
	private void drawEtaBadge(Graphics2D graphics, Dimension panelSize, String etaText)
	{
		// TextLayout gives the glyphs' tight pixel bounds — the RuneScape fonts' metrics (ascent,
		// leading) don't match their visual size, which kept mis-centring the text in the pill.
		TextLayout layout = new TextLayout(etaText, FONT_OTHER, graphics.getFontRenderContext());
		Rectangle2D bounds = layout.getBounds();
		int width = (int) Math.ceil(bounds.getWidth()) + 12;
		int height = (int) Math.ceil(bounds.getHeight()) + 8;
		// Right edge pinned just inside the panel corner; the pill grows leftward as the countdown's
		// text widens. No overhang: with the overlay snapped to the screen edge, an overhanging pill
		// gets clipped and its text looks misaligned.
		int x = panelSize.width - width - 2;
		int y = 2;

		graphics.setColor(CURRENT);
		graphics.fillRoundRect(x, y, width, height, 8, 8);
		graphics.setColor(new Color(0x2A, 0x5C, 0xC4));
		graphics.drawRoundRect(x, y, width, height, 8, 8);
		// Centre the tight glyph box inside the pill on both axes.
		float textX = (float) (x + (width - bounds.getWidth()) / 2 - bounds.getX());
		float textY = (float) (y + (height - bounds.getHeight()) / 2 - bounds.getY());
		graphics.setColor(Color.BLACK);
		layout.draw(graphics, textX + 1, textY + 1);
		graphics.setColor(Color.WHITE);
		layout.draw(graphics, textX, textY);
	}

	/**
	 * One pending panel line: collected first so the panel can be sized to the widest line before
	 * any component is built.
	 */
	private static final class Line
	{
		private final String left;
		private final Font font;
		private final Color colour;
		private final String right;
		private final Color rightColour;
		// Centred lines are reserved as spacer rows and custom-drawn centred over the panel
		// afterwards — LineComponent only aligns left/right. Only trailing lines may be centred.
		private final boolean centred;

		private Line(String left, Font font, Color colour, String right, Color rightColour)
		{
			this(left, font, colour, right, rightColour, false);
		}

		private Line(String left, Font font, Color colour, String right, Color rightColour, boolean centred)
		{
			this.left = left;
			this.font = font;
			this.colour = colour;
			this.right = right;
			this.rightColour = rightColour;
			this.centred = centred;
		}
	}

	private static int rightWidth(Graphics2D graphics, Line line)
	{
		return line.right == null ? 0 : graphics.getFontMetrics(FONT_OTHER).stringWidth(line.right) + 6;
	}

	/**
	 * Compact wall-time: "34s" under a minute, "2m 05s" above.
	 */
	private static String formatTime(int seconds)
	{
		if (seconds < 60)
		{
			return seconds + "s";
		}
		return (seconds / 60) + "m " + String.format("%02ds", seconds % 60);
	}

	/**
	 * Truncates a line to {@code maxWidth} instead of letting LineComponent wrap it — wrapped steps
	 * blur into their neighbours and break the list rhythm. With the panel sized to its content this
	 * only kicks in past {@link #MAX_WIDTH}.
	 */
	private static String ellipsize(Graphics2D graphics, Font font, String text, int maxWidth)
	{
		java.awt.FontMetrics metrics = graphics.getFontMetrics(font);
		if (metrics.stringWidth(text) <= maxWidth)
		{
			return text;
		}
		int end = text.length();
		while (end > 1 && metrics.stringWidth(text.substring(0, end) + "…") > maxWidth)
		{
			end--;
		}
		return text.substring(0, end) + "…";
	}

	/**
	 * Live wording for a pending door step: a door that already stands open needs walking
	 * through, not opening. Out-of-scene doors keep the "Open" default — regular doors shut
	 * themselves, so closed is the safe assumption until the door is visible.
	 */
	private String doorStepText(RouteOption route, RouteDirections.Step step)
	{
		List<shortestpath.pathfinder.PathStep> path = route.getPath();
		if (step.getStartIndex() < 0 || step.getEndIndex() >= path.size())
		{
			return step.getText();
		}
		ClosedDoors.Door door = ClosedDoors.doorBetween(
			path.get(step.getStartIndex()).getPackedPosition(),
			path.get(step.getEndIndex()).getPackedPosition());
		if (door != null
			&& SceneObjects.presence(client, door.packedPosition, door.id) == SceneObjects.Presence.ABSENT)
		{
			return "Go through " + door.name;
		}
		return step.getText();
	}

	/**
	 * Whether the current step is nearly finished: within the last fifth (or last 3 tiles) of its
	 * span. Only meaningful for longer spans — short steps (a teleport edge) flip states quickly
	 * anyway.
	 */
	private boolean nearEnd(RouteDirections.Step step)
	{
		int span = step.getEndIndex() - step.getStartIndex();
		if (span < 8)
		{
			return false;
		}
		int remaining = step.getEndIndex() - reachedIndex;
		return remaining <= Math.max(3, span / 5);
	}

	/**
	 * The lingering arrival panel: who set the destination, a green "Arrived!" and the total
	 * wall time the journey took. The whole panel is the dismiss button (see
	 * {@link #dismissArrivalAt}).
	 */
	private Dimension renderArrival(Graphics2D graphics)
	{
		List<Line> lines = new ArrayList<>();
		if (arrivalSource != null)
		{
			lines.add(new Line("Destination set by " + arrivalSource, FONT_OTHER, UPCOMING, null, null));
		}
		lines.add(new Line("Arrived!", FONT_CURRENT, ARRIVED, null, null, true));
		lines.add(new Line("in " + formatTime((int) (arrivalElapsedMillis / 1000)), FONT_NEXT, NEXT, null, null, true));
		lines.add(new Line("(click to dismiss)", FONT_OTHER, DONE, null, null, true));
		return renderPanel(graphics, lines);
	}

	/**
	 * Dismisses the arrival panel when {@code point} (canvas coordinates) is inside it. Called from
	 * the plugin's mouse listener; returns true when the click was consumed.
	 */
	public boolean dismissArrivalAt(java.awt.Point point)
	{
		if (!arrivalShowing)
		{
			return false;
		}
		java.awt.Rectangle bounds = getBounds();
		if (bounds == null || !bounds.contains(point))
		{
			return false;
		}
		arrivalShowing = false;
		return true;
	}

	/**
	 * Moves the progress marker to the eligible path tile nearest the player, preferring the one
	 * closest to the previous position on ties — so standing where the path crosses itself doesn't
	 * teleport the highlight to the other pass. The ETA is then (walk time to that tile + remaining
	 * route time from it). No same-plane tile at all (e.g. an off-route dungeon detour) freezes the
	 * estimate.
	 */
	private void updateProgress(RouteOption route, List<RouteDirections.Step> steps)
	{
		List<shortestpath.pathfinder.PathStep> path = route.getPath();
		if (route != progressRoute)
		{
			progressRoute = route;
			reachedIndex = 0;
			remainingTicksAt = buildRemainingTicks(steps, path.size());
			liveRemainingTicks = remainingTicksAt.length > 0 ? remainingTicksAt[0] : 0;
			journeyStartMillis = System.currentTimeMillis();
		}
		Player player = client.getLocalPlayer();
		if (player == null || remainingTicksAt.length != path.size())
		{
			return;
		}
		int playerPacked = WorldPointUtil.fromLocalInstance(client, player.getLocalLocation());
		int playerPlane = WorldPointUtil.unpackWorldPlane(playerPacked);

		// Rolling speed estimate (tiles/second): faster than any running player means a transport is
		// carrying us — freeze the estimate until we land instead of scoring transient positions.
		long now = System.currentTimeMillis();
		if (speedSamplePosition == WorldPointUtil.UNDEFINED || now - speedSampleAt >= SPEED_SAMPLE_MILLIS)
		{
			if (speedSamplePosition != WorldPointUtil.UNDEFINED && now > speedSampleAt)
			{
				// A plane change between samples reads as a big move (stairs/teleports).
				int moved = WorldPointUtil.unpackWorldPlane(speedSamplePosition) == playerPlane
					? WorldPointUtil.distanceBetween(speedSamplePosition, playerPacked)
					: NEAR_DISTANCE * 2;
				speedTilesPerSecond = moved * 1000.0 / (now - speedSampleAt);
			}
			speedSampleAt = now;
			speedSamplePosition = playerPacked;
		}
		boolean riding = speedTilesPerSecond > VEHICLE_TILES_PER_SECOND;

		if (riding)
		{
			// Mid-transport: interpolate through the ride geometrically. Distance to the landing
			// tile over the full hop length gives the fraction completed; the ETA becomes the
			// remainder of the ride plus everything after it. Progress itself (step completion)
			// still only advances on landing.
			RouteDirections.Step ride = currentStep(plugin.getRouteDirections(route));
			if (ride != null && ride.isTransport())
			{
				int origin = path.get(Math.max(0, ride.getStartIndex())).getPackedPosition();
				int destination = path.get(ride.getEndIndex()).getPackedPosition();
				if (WorldPointUtil.unpackWorldPlane(destination) == playerPlane)
				{
					double total = WorldPointUtil.distanceBetween(origin, destination);
					if (total > 4)
					{
						double completed = Math.min(1,
							1 - WorldPointUtil.distanceBetween(playerPacked, destination) / total);
						liveRemainingTicks = remainingTicksAt[ride.getEndIndex()]
							+ ride.getTicks() * (1 - Math.max(0, completed));
					}
				}
			}
			return;
		}

		// The first uncrossed door ahead gates progress: straight-line proximity sees through a
		// closed door (path tiles beyond it are physically a wall's width away — even adjacent,
		// from the near doorway tile), so several door steps used to complete at once while
		// still outside. Anything at or past that edge only counts once the player is standing
		// exactly on the path beyond it, which a one-tile doorway forces anyway.
		int doorGate = Integer.MAX_VALUE;
		for (RouteDirections.Step step : steps)
		{
			if (step.isDoor() && step.getEndIndex() > reachedIndex)
			{
				doorGate = step.getEndIndex();
				break;
			}
		}

		// Progress = the eligible path tile NEAREST the player (ties broken toward the current
		// position). Scoring by (walk time + remaining) instead had a systematic forward bias —
		// remaining falls slightly faster per index than distance charges per tile — so the
		// marker rode the eligibility cap ~10 tiles ahead of the player, ending walk legs and
		// announcing "Open Door" while still crossing the room. The ETA is derived from the
		// selected tile afterwards, keeping its walk-back charge without steering the selection.
		int best = -1;
		int bestDistance = Integer.MAX_VALUE;
		int bestOffset = Integer.MAX_VALUE;
		for (int i = 0; i < path.size(); i++)
		{
			int packed = path.get(i).getPackedPosition();
			if (WorldPointUtil.unpackWorldPlane(packed) != playerPlane)
			{
				continue;
			}
			int distance = WorldPointUtil.distanceBetween(packed, playerPacked);
			// Eligible: an incremental move near the line (honest travel), or standing right on the
			// path (a teleport/transport landing anywhere along the route). Past an uncrossed door,
			// only exact on-path presence counts.
			boolean beyondDoor = i >= doorGate;
			boolean incremental = !beyondDoor && Math.abs(i - reachedIndex) <= STEP_WINDOW && distance <= NEAR_DISTANCE;
			boolean onPath = distance <= (beyondDoor ? 0 : 1);
			if (!incremental && !onPath)
			{
				continue;
			}
			int offset = Math.abs(i - reachedIndex);
			if (distance < bestDistance || (distance == bestDistance && offset < bestOffset))
			{
				best = i;
				bestDistance = distance;
				bestOffset = offset;
			}
		}
		if (best < 0)
		{
			// Nowhere near the route (long off-path detour): hold the last honest estimate.
			return;
		}
		reachedIndex = best;
		liveRemainingTicks = bestDistance / 2.0 + remainingTicksAt[best];
	}

	/**
	 * Progress state exposed for the debug snapshot.
	 */
	int getReachedIndex()
	{
		return reachedIndex;
	}

	double getLiveRemainingTicks()
	{
		return liveRemainingTicks;
	}

	double getSpeedTilesPerSecond()
	{
		return speedTilesPerSecond;
	}

	/**
	 * The step the player is currently executing: the first whose span isn't finished.
	 */
	private RouteDirections.Step currentStep(List<RouteDirections.Step> steps)
	{
		for (RouteDirections.Step step : steps)
		{
			if (step.getEndIndex() > reachedIndex)
			{
				return step;
			}
		}
		return steps.isEmpty() ? null : steps.get(steps.size() - 1);
	}

	/**
	 * Remaining route time (ticks) from each path index: total of all later steps plus the linear
	 * remainder of the step spanning the index. Index 0 holds the whole journey.
	 */
	private static double[] buildRemainingTicks(List<RouteDirections.Step> steps, int pathSize)
	{
		double[] remaining = new double[pathSize];
		double after = 0;
		for (int s = steps.size() - 1; s >= 0; s--)
		{
			RouteDirections.Step step = steps.get(s);
			int start = Math.max(0, Math.min(step.getStartIndex(), pathSize - 1));
			int end = Math.max(start, Math.min(step.getEndIndex(), pathSize - 1));
			int span = Math.max(1, end - start);
			for (int i = end; i >= start; i--)
			{
				double throughStep = step.getTicks() * ((end - i) / (double) span);
				remaining[i] = Math.max(remaining[i], after + throughStep);
			}
			after += step.getTicks();
		}
		return remaining;
	}
}
