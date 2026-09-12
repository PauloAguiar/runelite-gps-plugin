package gps;

import gps.pathfinder.CostUnits;
import gps.transport.Transport;
import gps.transport.TransportType;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import static gps.PanelWidgets.CONTROL_SIZE;
import static gps.PanelWidgets.SAIL_DOT_COLOUR;
import static gps.PanelWidgets.WALK_DOT_COLOUR;
import static gps.PanelWidgets.addClickRecursively;
import static gps.PanelWidgets.control;
import static gps.PanelWidgets.dot;
import static gps.PanelWidgets.escapeHtml;
import static gps.PanelWidgets.joinLabels;
import static gps.PanelWidgets.methodDot;
import static gps.PanelWidgets.methodTooltip;
import static gps.PanelWidgets.methodTooltipBody;
import static gps.PanelWidgets.noteRow;
import static gps.PanelWidgets.priorityHoverIcon;
import static gps.PanelWidgets.priorityRestIcon;
import static gps.PanelWidgets.statusMarker;
import static gps.PanelWidgets.verticalGap;
import static gps.PanelWidgets.verticallyCentered;
import static gps.PanelWidgets.wrappedLabel;

/**
 * The routes section (panel series P4, out of the panel class): a bold "Routes (N)" header with
 * the more/refresh/clear controls, fixed above a scrolling list of route cards. Each card leads
 * with the shown-on-map pin and the ETA, then one row per method with its priority control (a
 * hover reveal), the bank marker for a detoured item, and one walking and one sailing total. The
 * highlighted card is the route actually drawn on the map: the explicitly selected one, or route
 * 1 by default. Clicking a card shows it on the map; clicking the shown one hides it.
 */
final class RouteListView extends JPanel
{
	// The found routes now span more than this multiple of the cheapest: the good options are in,
	// the search is grinding out longer alternatives.
	static final int LONG_ROUTE_MULTIPLE = 3;

	private final ShortestPathPlugin plugin;
	// Opens the tier menu for a method (the catalog owns it; both views share one exclusion set).
	private final BiConsumer<Component, TeleportMethod> priorityMenu;
	// Fixed (non-scrolling) slot for the routes header, mounted above the card scroll area so the
	// count and controls stay visible while the cards scroll.
	private final JPanel headerHolder = new JPanel();
	private final JPanel listPanel = new JPanel();
	// The last shown inputs, for the header's busy note and the cards' status markers.
	private List<RouteOption> routes = List.of();
	private Map<TeleportMethod, MethodAvailability> unavailable = Map.of();

	RouteListView(ShortestPathPlugin plugin, BiConsumer<Component, TeleportMethod> priorityMenu)
	{
		super(new BorderLayout());
		this.plugin = plugin;
		this.priorityMenu = priorityMenu;
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// Top-anchor the content so a short list keeps each row at its natural height. The wrapper
		// tracks the viewport width: without that, HORIZONTAL_SCROLLBAR_NEVER still lays the view out
		// at its preferred width and CLIPS the overflow at the right edge (the "scrollbar eats the
		// cards" effect) instead of shrinking the rows to fit.
		ScrollableBox listWrapper = new ScrollableBox(new BorderLayout());
		listWrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		listWrapper.add(listPanel, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(listWrapper,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		headerHolder.setLayout(new BoxLayout(headerHolder, BoxLayout.Y_AXIS));
		headerHolder.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(headerHolder, BorderLayout.NORTH);
		add(scroll, BorderLayout.CENTER);
	}

	/**
	 * Shows {@code routes} (as they stream in; the previous list was cleared when the generation
	 * started), highlighting {@code selected}; the header appears once there is a target, a
	 * generation or a result to head.
	 */
	void show(List<RouteOption> newRoutes, RouteOption selected, boolean calculating, boolean hasTarget,
		Map<TeleportMethod, MethodAvailability> newUnavailable)
	{
		routes = newRoutes;
		unavailable = newUnavailable;
		headerHolder.removeAll();
		if (hasTarget || calculating || !routes.isEmpty())
		{
			headerHolder.add(header(routes.size(), calculating));
		}
		headerHolder.revalidate();
		headerHolder.repaint();
		listPanel.removeAll();
		for (int i = 0; i < routes.size(); i++)
		{
			listPanel.add(card(i, routes.get(i), routes.get(i) == selected));
			listPanel.add(verticalGap(6));
		}
		listPanel.revalidate();
		listPanel.repaint();
	}

	/**
	 * The ETA is the route's full configured cost: travel time, the bank detour, AND the
	 * implicit cost modifiers (charged items, transport type, currency). The modifiers are
	 * REAL-WORLD corrections, not preferences: tick-optimal cost is a lower bound no human hits,
	 * and interacting with a method (finding the item, its menu, the confirm click) has latency
	 * the raw path math cannot see, so the corrected number is the honest estimate. Explicit
	 * priorities stay outside (they are the chip): list order = this ETA + priority chips,
	 * nothing hidden. Static for tests.
	 */
	static int routeEtaUnits(RouteOption route)
	{
		return route.getTotalCost();
	}

	static int routeEtaSeconds(RouteOption route)
	{
		return (int) Math.ceil(routeEtaUnits(route) * CostUnits.SECONDS_PER_UNIT);
	}

	/** Seconds as "59s" or "1m 5s". */
	static String formatDuration(int seconds)
	{
		if (seconds < 60)
		{
			return seconds + "s";
		}
		return (seconds / 60) + "m " + (seconds % 60) + "s";
	}

	/**
	 * Whether the found routes already span more than {@link #LONG_ROUTE_MULTIPLE} times the
	 * cheapest: the good options are in and fully usable, and the search is grinding out longer
	 * alternatives, so the busy note says so instead of a bare "calculating".
	 */
	static boolean searchingLongerRoutes(List<RouteOption> routes)
	{
		if (routes.isEmpty())
		{
			return false;
		}
		int min = Integer.MAX_VALUE;
		int max = 0;
		for (RouteOption route : routes)
		{
			int cost = route.getTotalCost();
			min = Math.min(min, cost);
			max = Math.max(max, cost);
		}
		return max > (long) min * LONG_ROUTE_MULTIPLE;
	}

	/**
	 * The results section: a bold orange "Routes (N)" title (with a quiet "calculating" note while
	 * the generation streams) over a centred control panel: bordered, coloured icon buttons for
	 * more routes (green plus), refresh (blue) and clear (red). Tooltips explain each.
	 */
	private JPanel header(int count, boolean calculating)
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// Extra top inset separates the routes header from the search controls and notes above it.
		section.setBorder(new EmptyBorder(10, 0, 6, 0));
		section.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

		JPanel titleRow = new JPanel(new BorderLayout(5, 0));
		titleRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		JLabel title = new JLabel(calculating && count == 0 ? "Routes" : "Routes (" + count + ")");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		titleRow.add(title, BorderLayout.WEST);
		if (calculating)
		{
			boolean longer = searchingLongerRoutes(routes);
			JLabel busy = new JLabel(longer ? "longer routes…" : "calculating…",
				RouteIcons.BANNER_BUSY, SwingConstants.LEADING);
			busy.setIconTextGap(4);
			busy.setFont(FontManager.getRunescapeSmallFont());
			busy.setForeground(Color.GRAY);
			busy.setToolTipText(longer
				? "Your best routes are ready to use; still searching for longer alternatives"
				: "Calculating routes…");
			titleRow.add(busy, BorderLayout.EAST);
		}
		section.add(titleRow);

		JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		controls.setAlignmentX(Component.LEFT_ALIGNMENT);
		controls.setBorder(new EmptyBorder(6, 0, 0, 0));
		controls.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		if (!calculating && !routes.isEmpty() && plugin.canLoadMoreRoutes())
		{
			controls.add(controlButton(RouteIcons.SHOW_MORE, RouteIcons.SHOW_MORE_HOVER,
				"Search for more routes", plugin::loadMoreRoutes));
		}
		if (!calculating)
		{
			controls.add(controlButton(RouteIcons.CTRL_REFRESH, RouteIcons.CTRL_REFRESH_HOVER,
				"Recalculate the routes to the current destination", plugin::recomputeAlternatives));
		}
		controls.add(controlButton(RouteIcons.CTRL_CLEAR, RouteIcons.CTRL_CLEAR_HOVER,
			"Clear the current destination and its route", plugin::clearTarget));
		section.add(controls);
		return section;
	}

	/** A bordered, colour-icon control button (rollover swaps the icon; the panel lifts on hover). */
	private static JButton controlButton(ImageIcon icon, ImageIcon hover, String tooltip, Runnable action)
	{
		JButton button = new JButton(icon);
		button.setRolloverIcon(hover);
		button.setFocusPainted(false);
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		button.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
		button.setToolTipText(tooltip);
		button.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(3, 12, 3, 12)));
		button.addActionListener(e -> action.run());
		button.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				button.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				button.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}
		});
		return button;
	}

	private JPanel card(int index, RouteOption route, boolean selected)
	{
		JPanel card = new JPanel(new BorderLayout());
		// Selection reads as a filled state: slightly lighter card plus a 3px orange edge stripe,
		// instead of the old full orange outline. Children are non-opaque so one background rules.
		Color cardBg = selected ? ColorScheme.DARK_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR;
		card.setBackground(cardBg);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(new Color(0x3A, 0x3A, 0x3A)),
			BorderFactory.createMatteBorder(0, 3, 0, 0, selected ? ColorScheme.BRAND_ORANGE : cardBg)));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

		JPanel topRow = new JPanel(new BorderLayout());
		topRow.setOpaque(false);
		// Left inset 4 (not the methods' 8): the pin glyph is centred in its 16px canvas while the
		// method dots start at their canvas edge, so the smaller inset lines the pin up with the
		// dot column below.
		topRow.setBorder(new EmptyBorder(4, 4, 2, 5));

		boolean reaches = plugin.routeReachesTarget(route);
		// Shown-on-map pin leads the card (orange when this route is the one drawn), then the
		// quiet rank chip, then the ETA, the decision-making number.
		JPanel left = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		left.setOpaque(false);
		// Pin and rank read as one unit (no gap); the clock and ETA sit a space apart.
		JLabel rank = new JLabel(Integer.toString(index + 1),
			selected ? RouteIcons.SHOW_ACTIVE : RouteIcons.SHOW, SwingConstants.LEADING);
		rank.setIconTextGap(1);
		rank.setFont(FontManager.getRunescapeSmallFont());
		rank.setForeground(Color.GRAY);
		left.add(rank);
		// The ETA counts travel plus the bank detour; ordering additionally counts preference
		// modifiers (transport type, currency), so a route can be faster yet ranked lower.
		JLabel eta = new JLabel(formatDuration(routeEtaSeconds(route)), RouteIcons.CLOCK, SwingConstants.LEADING);
		eta.setIconTextGap(3);
		eta.setBorder(new EmptyBorder(0, 12, 0, 0));
		eta.setFont(FontManager.getRunescapeBoldFont());
		eta.setForeground(selected ? ColorScheme.BRAND_ORANGE : Color.WHITE);
		eta.setToolTipText("<html>Estimated time, assuming you run"
			+ (route.isViaBank() ? " (includes the bank detour)" : "")
			+ ".<br>Includes your cost modifiers: real-world corrections for the clicks and"
			+ "<br>menus a method costs beyond raw travel (charged items, transport type,"
			+ "<br>currency). Routes are ordered by this plus the green/red priority chips.</html>");
		if (!reaches)
		{
			eta.setToolTipText("The target can't be reached; this ends at the closest reachable tile");
		}
		left.add(eta);
		// Explicit-priority chip beside the ETA. Implicit cost modifiers are inside the ETA
		// itself now, so the chip is purely the user's prefer/avoid/walk/bank bias, and list
		// order is always ETA plus chip, nothing hidden. Green = ranks as if faster, red = slower.
		int adjustment = plugin.routeAdjustmentSeconds(route);
		if (adjustment != 0)
		{
			JLabel priorityChip = new JLabel((adjustment > 0 ? "+" : "−") + Math.abs(adjustment) + "s");
			priorityChip.setFont(FontManager.getRunescapeSmallFont());
			priorityChip.setBorder(new EmptyBorder(0, 4, 0, 0));
			priorityChip.setForeground(adjustment < 0
				? new Color(70, 200, 90) : ColorScheme.PROGRESS_ERROR_COLOR);
			priorityChip.setToolTipText("Your priority bias: changes this route's position, not its ETA");
			left.add(priorityChip);
		}
		topRow.add(left, BorderLayout.WEST);

		JPanel right = new JPanel(new FlowLayout(FlowLayout.TRAILING, 5, 0));
		right.setOpaque(false);
		if (route.isViaBank())
		{
			// The bank detour as a compact header chip; the coin glyph on the method row below
			// marks WHICH method the detour is for. The tooltip states WHAT gets withdrawn,
			// resolved on the CLIENT thread (item names come from getItemDefinition, which
			// asserts it; the EDT crash of 2026-08-15) and swapped in when ready.
			JLabel bankChip = new JLabel(RouteIcons.IN_BANK);
			// Connectors (a jungle bush, a dig) are bank-gated too but have no method row; the
			// placeholder names them by object so the chip never reads "for: <nothing>".
			StringBuilder needs = new StringBuilder(joinLabels(route.getBankMethods()));
			for (Transport connector : route.getBankTransports())
			{
				String text = RouteDirections.objectText(connector);
				if (text != null && needs.indexOf(text) < 0)
				{
					needs.append(needs.length() == 0 ? "" : ", ").append(text);
				}
			}
			bankChip.setToolTipText("<html>Walks to a bank first; withdraws the item for: <b>"
				+ escapeHtml(needs.toString()) + "</b></html>");
			plugin.getClientThread().invokeLater(() ->
			{
				List<String> pickups = RouteDirections.pickupLines(plugin, route);
				if (!pickups.isEmpty())
				{
					StringBuilder tip = new StringBuilder("<html>Withdraws at a bank:");
					for (String pickup : pickups)
					{
						tip.append("<br>• <b>").append(escapeHtml(pickup)).append("</b>");
					}
					SwingUtilities.invokeLater(() ->
						bankChip.setToolTipText(tip.append("</html>").toString()));
				}
			});
			right.add(bankChip);
		}
		topRow.add(right, BorderLayout.EAST);
		card.add(topRow, BorderLayout.NORTH);

		JPanel methods = new JPanel();
		methods.setLayout(new BoxLayout(methods, BoxLayout.Y_AXIS));
		methods.setOpaque(false);
		methods.setBorder(new EmptyBorder(1, 8, 5, 5));
		if (!reaches)
		{
			methods.add(noteRow("<font color='#FF981F'>Can't reach the target, ends at the closest point.</font>",
				"This destination isn't reachable; the route stops at the nearest tile GPS can get to."));
		}
		// Each method row reveals its OWN priority control only while the pointer is over that row.
		for (int m = 0; m < route.getMethods().size(); m++)
		{
			methods.add(methodRow(route.getMethods().get(m), route, route.walkBefore(m)));
		}
		// One walking row for the WHOLE route: every leg between methods plus the trailing leg;
		// per-method walk counts live in the method tooltips instead of cluttering each row.
		int totalWalk = route.getTrailingWalkSteps();
		for (int m = 0; m < route.getMethods().size(); m++)
		{
			totalWalk += route.walkBefore(m);
		}
		if (totalWalk > 0 || route.isWalkOnly())
		{
			methods.add(walkRow(totalWalk));
		}
		// One sailing row for the whole route, mirroring the walk row: total sea tiles across
		// every sailing leg (distances inverted from the legs' durations).
		int totalSail = 0;
		for (int m = 0; m < route.getMethods().size(); m++)
		{
			if (route.getMethods().get(m).getType() == TransportType.SAILING
				&& m < route.getMethodDurations().size())
			{
				totalSail += SailingSea.tilesFromDuration(route.getMethodDurations().get(m));
			}
		}
		if (totalSail > 0)
		{
			methods.add(sailRow(totalSail));
		}
		card.add(methods, BorderLayout.CENTER);

		// The best route is the fallback whenever nothing else is selected, so hiding it never
		// shows anything else: say what the click does, not what it cannot (plan step N12).
		card.setToolTipText(selected
			? (index == 0 ? "Showing on map (the best route)" : "Showing on map, click to hide")
			: "Click to show this route on the map");
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		// A click anywhere on the card (its icon controls aside) shows that route on the map.
		addClickRecursively(card, new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				plugin.selectRoute(index);
			}
		});
		return card;
	}

	/**
	 * Fires the handler with true when the pointer enters the component tree and false when it
	 * truly leaves it (Swing fires exit when moving onto a CHILD, so exits are checked against the
	 * root's bounds). Used to reveal a route row's priority control only while hovering that row.
	 */
	private static void addHoverRecursively(Component root, Consumer<Boolean> handler)
	{
		MouseAdapter hover = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				handler.accept(true);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				Point p = SwingUtilities.convertPoint((Component) e.getSource(), e.getPoint(), root);
				if (!root.contains(p))
				{
					handler.accept(false);
				}
			}
		};
		addHoverListener(root, hover);
	}

	private static void addHoverListener(Component component, MouseAdapter hover)
	{
		component.addMouseListener(hover);
		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				addHoverListener(child, hover);
			}
		}
	}

	/** The sailing twin of the walk row: sea-blue dot, total tiles sailed across the route. */
	private static JPanel sailRow(int tiles)
	{
		JPanel row = new JPanel(new BorderLayout(5, 0));
		row.setOpaque(false);
		JLabel dot = new JLabel(dot(SAIL_DOT_COLOUR));
		dot.setToolTipText("Sailing");
		row.add(verticallyCentered(dot), BorderLayout.WEST);
		JLabel text = wrappedLabel("Sail <font color='#9E9E9E'>" + tiles + " tiles</font>");
		text.setVerticalAlignment(SwingConstants.CENTER);
		text.setToolTipText("Total open-sea distance across this route's sailing legs"
			+ " (ETAs assume a mid-tier hull speed)");
		row.add(text, BorderLayout.CENTER);
		return row;
	}

	/**
	 * The walking-leg row, shaped exactly like a method row: a neutral grey dot in the
	 * category-dot column, then the step count.
	 */
	private static JPanel walkRow(int steps)
	{
		JPanel row = new JPanel(new BorderLayout(5, 0));
		row.setOpaque(false);

		JLabel dot = new JLabel(dot(WALK_DOT_COLOUR));
		dot.setToolTipText("Walking");
		row.add(verticallyCentered(dot), BorderLayout.WEST);

		JLabel text = wrappedLabel(steps > 0
			? "Walk <font color='#9E9E9E'>" + steps + " tiles</font>"
			: "Walk");
		text.setVerticalAlignment(SwingConstants.CENTER);
		text.setToolTipText("Total walking across this route: every leg between methods plus the final stretch");
		row.add(text, BorderLayout.CENTER);
		return row;
	}

	/**
	 * A route-card method row: category dot plus wrapped label plus the priority control.
	 * Methods whose required item must first be withdrawn from the bank get a bank glyph, so it
	 * is clear which method the route's bank detour is for. {@code walkBefore} tiles of walking to
	 * reach the method go into the tooltip.
	 */
	private JPanel methodRow(TeleportMethod method, RouteOption route, int walkBefore)
	{
		JPanel row = new JPanel(new BorderLayout(5, 0));
		row.setOpaque(false);

		JLabel dot = new JLabel(methodDot(method));
		dot.setAlignmentY(Component.CENTER_ALIGNMENT);
		dot.setToolTipText(method.getType() == TransportType.TELEPORTATION_ITEM
			? (method.isConsumable() ? "Item (charged: consumes a charge or the item)" : "Item (permanent: reusable)")
			: method.category());
		MethodAvailability status = unavailable.get(method);
		boolean bankGated = route.getBankMethods().contains(method);
		// The dot and any inline glyphs form a left-to-right box, centred against each other; the
		// whole box is then centred vertically against the (possibly two-line) label.
		JPanel west = new JPanel();
		west.setLayout(new BoxLayout(west, BoxLayout.X_AXIS));
		west.setOpaque(false);
		west.add(dot);
		// Network methods carry their real glyph inline after the dot (like the bank marker and
		// the overlay's fairy-ring step), so "C K S" reads as a fairy-ring code at a glance.
		String networkGlyph = method.getType() == TransportType.FAIRY_RING ? "fairy_ring"
			: method.getType() == TransportType.SPIRIT_TREE ? "spirit_tree" : null;
		if (networkGlyph != null)
		{
			JLabel glyph = new JLabel(RouteIcons.destinationIcon(networkGlyph));
			glyph.setAlignmentY(Component.CENTER_ALIGNMENT);
			glyph.setBorder(new EmptyBorder(0, 3, 0, 0));
			glyph.setToolTipText(method.category());
			west.add(glyph);
		}
		if (bankGated)
		{
			JLabel bankMarker = new JLabel(RouteIcons.IN_BANK);
			bankMarker.setAlignmentY(Component.CENTER_ALIGNMENT);
			bankMarker.setBorder(new EmptyBorder(0, 3, 0, 0));
			// Client-thread resolution, same as the header chip: item names assert it.
			bankMarker.setToolTipText("This method needs an item from your bank; the route withdraws it first");
			plugin.getClientThread().invokeLater(() ->
			{
				String pickup = RouteDirections.pickupLineFor(plugin, route, method);
				if (pickup != null)
				{
					SwingUtilities.invokeLater(() -> bankMarker.setToolTipText(
						"<html>From your bank: <b>" + escapeHtml(pickup) + "</b></html>"));
				}
			});
			west.add(bankMarker);
		}
		// The availability map records IN_BANK in every mode; on a route it is already shown by
		// the bank marker above, so only add the status marker for other, distinct reasons.
		if (status != null && !bankGated)
		{
			JLabel statusMarker = statusMarker(plugin, status, method);
			statusMarker.setAlignmentY(Component.CENTER_ALIGNMENT);
			statusMarker.setBorder(new EmptyBorder(0, 3, 0, 0));
			west.add(statusMarker);
		}
		row.add(verticallyCentered(west), BorderLayout.WEST);

		// No per-row step counts: the card's walk row totals every leg, and this row's tooltip
		// still carries its own walk-to-reach detail. Route rows sit without their category header,
		// so network methods name their vehicle ("Balloon to Varrock", not a bare "Varrock").
		JLabel text = wrappedLabel(escapeHtml(method.routeLabel()));
		text.setVerticalAlignment(SwingConstants.CENTER);
		text.setToolTipText(walkBefore > 0
			? "<html>Walk " + walkBefore + " tiles to reach this method.<br>" + methodTooltipBody(method) + "</html>"
			: methodTooltip(method));
		row.add(text, BorderLayout.CENTER);

		// Shows the method's CURRENT tier: near-invisible dash at rest when normal (so the row
		// stays quiet), the stacked arrows when a preference is set. Hovering the row brightens
		// it; clicking opens the priority menu (prefer/avoid tiers; exclude at the bottom).
		MethodPriority cardTier = plugin.getMethodPriority(method);
		final IconActionLabel[] holder = new IconActionLabel[1];
		holder[0] = new IconActionLabel(
			cardTier == MethodPriority.NORMAL ? RouteIcons.PRIORITY_NEUTRAL_DIM : priorityRestIcon(cardTier),
			priorityHoverIcon(cardTier),
			"Priority: " + cardTier.label + ", click to change",
			() -> priorityMenu.accept(holder[0], method));
		IconActionLabel priority = holder[0];
		JPanel actionWrap = new JPanel(new GridBagLayout());
		actionWrap.setOpaque(false);
		actionWrap.setPreferredSize(new Dimension(CONTROL_SIZE, CONTROL_SIZE));
		actionWrap.add(control(priority));
		row.add(actionWrap, BorderLayout.EAST);

		// Reveal only this row's control on hover, not the whole card.
		final MethodPriority hoverTier = cardTier;
		addHoverRecursively(row, hovered ->
			priority.setRestIcon(hovered
				? priorityHoverIcon(hoverTier)
				: (hoverTier == MethodPriority.NORMAL
					? RouteIcons.PRIORITY_NEUTRAL_DIM : priorityRestIcon(hoverTier))));
		return row;
	}
}
