package shortestpath;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;

/**
 * The "view": lists up to {@link AlternativeRoutesService#MAX_ROUTES} alternative routes to the
 * target, then — below them — the full catalog of teleport/transport methods for the current mode,
 * grouped into collapsible categories with per-method and per-category include/exclude toggles.
 * <p>
 * The route cards and the catalog share one exclusion set: the ✕ on a route's method and the
 * check/cross in the catalog flip the same state. Clicking a route card shows it on the world map.
 * Built on the tile-packs style: small icon controls with hover states and tooltips.
 */
public class ShortestPathPanel extends PluginPanel
{
	private static final int CONTROL_SIZE = 18;
	private static final int METHOD_TEXT_WIDTH = 132;
	// Tallest the expanded teleport-methods box may grow before it scrolls internally.
	private static final int CATALOG_MAX_HEIGHT = 240;

	// Stable, distinct-ish palette; categories hash into it so the same category always gets the
	// same dot colour.
	private static final Color[] CATEGORY_PALETTE =
	{
		new Color(0x5B, 0x9B, 0xD5), // blue
		new Color(0x4C, 0xAF, 0x50), // green
		new Color(0xE9, 0x7D, 0x3B), // orange
		new Color(0xB4, 0x6F, 0xD4), // purple
		new Color(0x4D, 0xB6, 0xAC), // teal
		new Color(0xE5, 0x73, 0x99), // pink
		new Color(0xC0, 0xA8, 0x3B), // gold
		new Color(0x7E, 0x8C, 0x9A), // slate
		new Color(0x8B, 0xC3, 0x4A), // lime
		new Color(0xD1, 0x5B, 0x5B), // red
	};

	private final ShortestPathPlugin plugin;
	private final JLabel statusLabel = new JLabel();
	private final JLabel bankWarningLabel = new JLabel();
	// Fixed (non-scrolling) slot below the header holding the teleport-methods catalog.
	private final JPanel catalogHolder = new JPanel();
	// Filter box for the catalog; a persistent component so typing keeps focus while only the rows
	// below repopulate. Shown only while the catalog is expanded.
	private final IconTextField catalogSearch = new IconTextField();
	// The scrollable rows box of the expanded catalog; repopulated in place when the filter changes.
	private JPanel catalogRowsPanel;
	private JScrollPane catalogRowsScroll;
	// Snapshot of the inputs the catalog section was last built from. Routes stream several updates
	// per generation; rebuilding ~1000 catalog rows on the EDT for each of them made the toggles
	// unresponsive (the row under the cursor kept being replaced). Rebuild only when these change.
	private List<TeleportMethod> renderedCatalog;
	private Set<TeleportMethod> renderedExclusions;
	private Map<TeleportMethod, MethodAvailability> renderedUnavailable;
	private boolean renderedCatalogExpanded;
	private final JPanel listPanel = new JPanel();
	// "Go to" destination search: type a place or amenity ("Falador bank", "nearest altar")
	// and pick a result to set it as the GPS destination.
	private final IconTextField destinationSearch = new IconTextField();
	private final JPanel destinationResults = new JPanel();
	private JButton ownedButton;
	private JButton allButton;
	private JButton variantOneButton;
	private JButton variantTwoButton;

	// Cached last render input so expand/collapse can re-render without a round-trip to the plugin.
	private List<RouteOption> cachedRoutes = List.of();
	private List<TeleportMethod> cachedCatalog = List.of();
	private Map<TeleportMethod, MethodAvailability> cachedUnavailable = Map.of();
	private Set<TeleportMethod> cachedExclusions = Set.of();
	private boolean cachedCalculating = false;
	private boolean cachedHasTarget = false;
	private final Set<String> expandedCategories = new HashSet<>();
	// Whether the whole "Teleport methods" catalog section (shown at the top) is expanded. Collapsed
	// by default so the routes stay the focus; the user opens it to browse/toggle methods.
	private boolean catalogExpanded = false;
	// Funnel filter next to the catalog search: narrow the list to disabled methods or to a single
	// kind of unavailability (missing item/level/quest, in bank, not unlocked).
	private CatalogFilter catalogFilter = CatalogFilter.ALL;

	/** The funnel-filter options for the teleport-methods catalog. */
	private enum CatalogFilter
	{
		ALL("Show all methods", null, false),
		DISABLED("Disabled (excluded)", null, true),
		MISSING_ITEM("Missing an item", MethodAvailability.MISSING_ITEM, false),
		IN_BANK("Item in the bank", MethodAvailability.IN_BANK, false),
		MISSING_LEVEL("Missing a skill level", MethodAvailability.MISSING_LEVEL, false),
		MISSING_QUEST("Missing a quest", MethodAvailability.MISSING_QUEST, false),
		LOCKED("Not unlocked yet", MethodAvailability.LOCKED, false);

		private final String label;
		// The availability kind this filter keeps (null when it doesn't filter by availability).
		private final MethodAvailability availability;
		// True for the "disabled" filter, which keeps user-excluded methods regardless of availability.
		private final boolean disabled;

		CatalogFilter(String label, MethodAvailability availability, boolean disabled)
		{
			this.label = label;
			this.availability = availability;
			this.disabled = disabled;
		}

		boolean isActive()
		{
			return this != ALL;
		}
	}

	public ShortestPathPanel(ShortestPathPlugin plugin)
	{
		super(false);
		this.plugin = plugin;
		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Fixed top area: the header (title/modes/refresh/status) plus the teleport-methods catalog,
		// which scrolls inside its own bounded box (see buildCatalogSection) instead of pushing the
		// route list down. Only the routes scroll in the main area below.
		catalogHolder.setLayout(new BoxLayout(catalogHolder, BoxLayout.Y_AXIS));
		catalogHolder.setBackground(ColorScheme.DARK_GRAY_COLOR);

		catalogSearch.setIcon(IconTextField.Icon.SEARCH);
		catalogSearch.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		catalogSearch.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		catalogSearch.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				populateCatalogRows();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				populateCatalogRows();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				populateCatalogRows();
			}
		});
		JPanel top = new JPanel(new BorderLayout());
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(buildHeader(), BorderLayout.NORTH);
		// The teleport-methods catalog, then the "Go to" destination search beneath it, then notes.
		JPanel belowHeader = new JPanel(new BorderLayout());
		belowHeader.setBackground(ColorScheme.DARK_GRAY_COLOR);
		belowHeader.add(catalogHolder, BorderLayout.NORTH);
		belowHeader.add(buildDestinationSearch(), BorderLayout.CENTER);
		top.add(belowHeader, BorderLayout.CENTER);
		top.add(buildNotes(), BorderLayout.SOUTH);
		add(top, BorderLayout.NORTH);

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
		add(scroll, BorderLayout.CENTER);

		render();
	}

	/**
	 * A panel that always lays out at the scroll viewport's width. A plain JPanel inside a JScrollPane
	 * keeps its preferred width even with the horizontal scrollbar disabled, so any row slightly wider
	 * than the viewport pushes the whole content under the vertical scrollbar and gets clipped.
	 */
	private static final class ScrollableBox extends JPanel implements javax.swing.Scrollable
	{
		private ScrollableBox(java.awt.LayoutManager layout)
		{
			super(layout);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(java.awt.Rectangle visibleRect, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(java.awt.Rectangle visibleRect, int orientation, int direction)
		{
			return Math.max(visibleRect.height - 16, 16);
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	private JPanel buildHeader()
	{
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(new EmptyBorder(0, 0, 8, 0));

		JPanel titleRow = new JPanel(new BorderLayout());
		titleRow.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// The plugin's identity mark: blue pin + bold white "GPS", matching the overlay header
		// and the sidebar tab.
		JLabel title = new JLabel("GPS", new ImageIcon(RouteIcons.gpsPin()), SwingConstants.LEADING);
		title.setIconTextGap(6);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		titleRow.add(title, BorderLayout.WEST);

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
		actions.setBackground(ColorScheme.DARK_GRAY_COLOR);
		actions.add(control(new IconActionLabel(RouteIcons.DEBUG, RouteIcons.DEBUG_HOVER,
			"Save a debug snapshot of the current routes to disk (for reproducing issues)",
			plugin::captureDebugSnapshot)));
		actions.add(control(new IconActionLabel(RouteIcons.CLEAR, RouteIcons.CLEAR_HOVER,
			"Re-include all excluded methods", plugin::clearExclusions)));
		titleRow.add(actions, BorderLayout.EAST);

		header.add(titleRow, BorderLayout.NORTH);

		JPanel bottom = new JPanel(new BorderLayout());
		bottom.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Two-level mode picker: family (Owned / All) on top, its two variants indented beneath so they
		// read as sub-options of whichever family is selected.
		JPanel modeRows = new JPanel(new BorderLayout(0, 4));
		modeRows.setBackground(ColorScheme.DARK_GRAY_COLOR);
		modeRows.setBorder(new EmptyBorder(8, 0, 0, 0));

		JPanel familyRow = new JPanel(new GridLayout(1, 2, 4, 0));
		familyRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		ownedButton = new JButton("Owned");
		ownedButton.setToolTipText("Only methods whose items you actually possess");
		ownedButton.setFocusPainted(false);
		ownedButton.addActionListener(e -> plugin.setRoutesMode(
			plugin.getRoutesMode().isSecondVariant()
				? AlternativeRoutesMode.OWNED_WITH_BANK : AlternativeRoutesMode.OWNED_INVENTORY));
		allButton = new JButton("All");
		allButton.setToolTipText("Ignore item possession");
		allButton.setFocusPainted(false);
		allButton.addActionListener(e -> plugin.setRoutesMode(
			plugin.getRoutesMode().isSecondVariant()
				? AlternativeRoutesMode.ALL_EVERYTHING : AlternativeRoutesMode.ALL_UNLOCKED));
		familyRow.add(ownedButton);
		familyRow.add(allButton);
		modeRows.add(familyRow, BorderLayout.NORTH);

		JPanel variantRow = new JPanel(new GridLayout(1, 2, 4, 0));
		variantRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		variantOneButton = new JButton();
		variantOneButton.setFont(FontManager.getRunescapeSmallFont());
		variantOneButton.setFocusPainted(false);
		variantOneButton.addActionListener(e -> plugin.setRoutesMode(
			plugin.getRoutesMode().isOwned()
				? AlternativeRoutesMode.OWNED_INVENTORY : AlternativeRoutesMode.ALL_UNLOCKED));
		variantTwoButton = new JButton();
		variantTwoButton.setFont(FontManager.getRunescapeSmallFont());
		variantTwoButton.setFocusPainted(false);
		variantTwoButton.addActionListener(e -> plugin.setRoutesMode(
			plugin.getRoutesMode().isOwned()
				? AlternativeRoutesMode.OWNED_WITH_BANK : AlternativeRoutesMode.ALL_EVERYTHING));
		variantRow.add(variantOneButton);
		variantRow.add(variantTwoButton);

		// Nest the variants under the family row: a short left indent, a vertical rail acting as the
		// "belongs to" connector, then the two variant buttons.
		JPanel variantNest = new JPanel(new BorderLayout());
		variantNest.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JPanel rail = new JPanel();
		rail.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		rail.setPreferredSize(new Dimension(2, 1));
		JPanel railWrap = new JPanel(new BorderLayout());
		railWrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		railWrap.setBorder(new EmptyBorder(0, 10, 0, 6));
		railWrap.add(rail, BorderLayout.CENTER);
		variantNest.add(railWrap, BorderLayout.WEST);
		variantNest.add(variantRow, BorderLayout.CENTER);
		modeRows.add(variantNest, BorderLayout.CENTER);

		bottom.add(modeRows, BorderLayout.NORTH);

		JPanel lower = new JPanel(new BorderLayout());
		lower.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JButton findButton = new JButton("Refresh routes to target");
		findButton.setToolTipText("Recalculate alternative routes to the destination GPS is currently set to");
		findButton.setFocusPainted(false);
		findButton.addActionListener(e -> plugin.recomputeAlternatives());
		JPanel findWrap = new JPanel(new BorderLayout());
		findWrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		findWrap.setBorder(new EmptyBorder(8, 0, 0, 0));
		findWrap.add(findButton, BorderLayout.CENTER);
		lower.add(findWrap, BorderLayout.NORTH);


		bottom.add(lower, BorderLayout.SOUTH);

		header.add(bottom, BorderLayout.SOUTH);

		updateModeButtons();
		return header;
	}

	/**
	 * The status line ("N routes found", "No destination set", ...) and the red bank
	 * warning. Shown below the teleport-methods catalog, directly above the route cards.
	 */
	private JPanel buildNotes()
	{
		// Red bank warning directly above the status line; only visible in "Inv + bank" mode until the
		// bank has been opened once this session (before that, banked items are invisible to the plugin).
		bankWarningLabel.setText("<html><b>Bank contents unknown</b> — open your bank once so banked items can be found.</html>");
		bankWarningLabel.setFont(FontManager.getRunescapeSmallFont());
		bankWarningLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		bankWarningLabel.setBorder(new EmptyBorder(4, 0, 0, 0));
		bankWarningLabel.setVisible(false);

		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setBorder(new EmptyBorder(4, 0, 0, 0));

		JPanel notes = new JPanel();
		notes.setLayout(new BoxLayout(notes, BoxLayout.Y_AXIS));
		notes.setBackground(ColorScheme.DARK_GRAY_COLOR);
		notes.setBorder(new EmptyBorder(0, 0, 6, 0));
		bankWarningLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		notes.add(bankWarningLabel);
		notes.add(statusLabel);
		return notes;
	}

	/**
	 * Stores the latest data and re-renders. Must be called on the Swing EDT.
	 */
	public void displayRoutes(List<RouteOption> routes, List<TeleportMethod> catalog,
		Map<TeleportMethod, MethodAvailability> unavailable, Set<TeleportMethod> exclusions,
		boolean calculating, boolean hasTarget)
	{
		cachedRoutes = routes != null ? routes : List.of();
		cachedCatalog = catalog != null ? catalog : List.of();
		cachedUnavailable = unavailable != null ? unavailable : Map.of();
		cachedExclusions = exclusions != null ? exclusions : Set.of();
		cachedCalculating = calculating;
		cachedHasTarget = hasTarget;
		render();
	}

	private void render()
	{
		updateModeButtons();
		listPanel.removeAll();

		String status;
		if (cachedCalculating)
		{
			status = cachedRoutes.isEmpty()
				? "Calculating routes…"
				: ("Calculating… (" + cachedRoutes.size() + " so far)");
		}
		else if (!cachedRoutes.isEmpty())
		{
			status = cachedRoutes.size() + (cachedRoutes.size() == 1 ? " route found" : " routes found");
		}
		else if (cachedHasTarget)
		{
			// A search ran for the current target but produced nothing — distinct from "no target set".
			status = "No routes found to the target."
				+ (plugin.getRoutesMode() == AlternativeRoutesMode.ALL_EVERYTHING ? "" : "<br>Try a broader mode (Inv + bank, or All).");
		}
		else
		{
			// GPS has no active target. (Quest Helper draws its own line for some steps and
			// doesn't hand GPS a destination — set one on the map to find routes.)
			status = "No destination set.";
		}
		// Method toggles no longer recalculate; flag a route list generated with different exclusions.
		if (!cachedCalculating && cachedHasTarget && plugin.isRouteListStale())
		{
			status += "<br><font color='#FF981F'>Exclusions changed — press \"Refresh routes\" to apply.</font>";
		}
		// The bank container is only populated once the bank has been opened this session; without it
		// Bank mode cannot see banked items (same constraint as Shortest Path itself).
		bankWarningLabel.setVisible(
			plugin.getRoutesMode() == AlternativeRoutesMode.OWNED_WITH_BANK && !plugin.isBankContentsKnown());
		statusLabel.setText("<html>" + status + "</html>");

		// The teleport-methods catalog lives in a fixed slot below the header (collapsed by default).
		// Expanded it scrolls inside its own bounded box, so it never pushes the routes off screen.
		// Rebuilt only when its inputs changed — streamed route updates leave it untouched so its
		// toggles stay responsive while a generation is running.
		boolean catalogDirty = !cachedCatalog.equals(renderedCatalog)
			|| !cachedExclusions.equals(renderedExclusions)
			|| !cachedUnavailable.equals(renderedUnavailable)
			|| catalogExpanded != renderedCatalogExpanded;
		if (catalogDirty)
		{
			refreshCatalog();
		}

		// Routes are shown as they stream in (even while still calculating). The previous list was
		// cleared when this generation started, so only the new routes appear. The highlighted card is
		// the route actually drawn on the map — the explicitly selected one, or route 1 by default.
		RouteOption selected = plugin.getDisplayedRoute();
		for (int i = 0; i < cachedRoutes.size(); i++)
		{
			listPanel.add(buildRouteCard(i, cachedRoutes.get(i), cachedRoutes.get(i) == selected));
			listPanel.add(verticalGap(4));
		}
		// Only offer "show more" once this generation has finished.
		if (!cachedCalculating && !cachedRoutes.isEmpty() && plugin.canLoadMoreRoutes())
		{
			listPanel.add(buildShowMoreButton());
			listPanel.add(verticalGap(4));
		}

		listPanel.revalidate();
		listPanel.repaint();
	}

	private JPanel buildShowMoreButton()
	{
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		JButton more = new JButton("Show more routes");
		more.setFocusPainted(false);
		more.setToolTipText("Search for more alternative routes");
		more.addActionListener(e -> plugin.loadMoreRoutes());
		wrap.add(more, BorderLayout.CENTER);
		return wrap;
	}

	private JPanel buildRouteCard(int index, RouteOption route, boolean selected)
	{
		JPanel card = new JPanel(new BorderLayout());
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createLineBorder(
			selected ? ColorScheme.BRAND_ORANGE : ColorScheme.MEDIUM_GRAY_COLOR));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

		JPanel topRow = new JPanel(new BorderLayout());
		topRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		topRow.setBorder(new EmptyBorder(3, 6, 3, 4));

		JLabel name = new JLabel("Route " + (index + 1));
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(selected ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		if (!route.isReached())
		{
			name.setToolTipText("The exact target isn't reachable — this ends at the closest reachable tile");
		}
		topRow.add(name, BorderLayout.WEST);

		JPanel right = new JPanel(new FlowLayout(FlowLayout.TRAILING, 5, 0));
		right.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		// Weighted cost, plus — when configured weights changed it — the raw cost in parentheses.
		boolean weighted = route.getRawCost() != route.getTotalCost();
		JLabel cost = new JLabel("≈ " + route.getTotalCost()
			+ (weighted ? " (" + route.getRawCost() + ")" : ""));
		cost.setFont(FontManager.getRunescapeSmallFont());
		cost.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		cost.setToolTipText(weighted
			? "<html>Blended cost incl. your configured method weights (lower is shorter).<br>"
				+ "In parentheses: the raw cost — walk distance + travel time only.</html>"
			: "Blended cost: walk distance + travel time (lower is shorter)");
		right.add(cost);
		// Status indicator (orange when shown); the whole card is the click target, see makeSelectable.
		right.add(control(new JLabel(selected ? RouteIcons.SHOW_ACTIVE : RouteIcons.SHOW)));
		topRow.add(right, BorderLayout.EAST);
		card.add(topRow, BorderLayout.NORTH);

		JPanel methods = new JPanel();
		methods.setLayout(new BoxLayout(methods, BoxLayout.Y_AXIS));
		methods.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		methods.setBorder(new EmptyBorder(2, 6, 4, 4));
		if (route.isViaBank())
		{
			// Kept to one line: the method that needs the bank is identified by the bank glyph on its
			// own row below, and this note's tooltip names it too.
			methods.add(noteRow("<i>Walks to a bank first</i>",
				"<html>Withdraws the item for: <b>" + escapeHtml(joinLabels(route.getBankMethods()))
					+ "</b><br>The drawn path includes the walk to a bank before that method is used</html>"));
		}
		for (int m = 0; m < route.getMethods().size(); m++)
		{
			methods.add(buildMethodRow(route.getMethods().get(m), route.getBankMethods(), route.walkBefore(m)));
		}
		// Trailing walking leg after the last method — the whole route for walk-only ones.
		if (route.getTrailingWalkSteps() > 0 || route.isWalkOnly())
		{
			methods.add(buildWalkRow(route.getTrailingWalkSteps()));
		}
		card.add(methods, BorderLayout.CENTER);

		card.setToolTipText(selected ? "Showing on map — click to hide" : "Click to show this route on the map");
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		makeSelectable(card, index);
		return card;
	}

	// Neutral dot colour for walking legs; deliberately outside the category palette so walking
	// doesn't masquerade as a teleport category.
	private static final Color WALK_DOT_COLOUR = new Color(0x9E, 0x9E, 0x9E);

	/**
	 * A walking-leg row, shaped exactly like a method row: a neutral grey dot in the category-dot
	 * column, then the step count.
	 */
	private JPanel buildWalkRow(int steps)
	{
		JPanel row = new JPanel(new BorderLayout(5, 0));
		row.setOpaque(false);

		JLabel dot = new JLabel(dot(WALK_DOT_COLOUR));
		dot.setVerticalAlignment(SwingConstants.TOP);
		dot.setBorder(new EmptyBorder(2, 0, 0, 0));
		dot.setToolTipText("Walking");
		row.add(dot, BorderLayout.WEST);

		JLabel text = wrappedLabel("(" + steps + ") Walk");
		text.setToolTipText("Walk " + steps + " tiles to the destination");
		row.add(text, BorderLayout.CENTER);
		return row;
	}

	/**
	 * A route-card method row: category dot + wrapped label + an exclude (✕) icon. Methods whose
	 * required item must first be withdrawn from the bank get a bank glyph, so it's clear which
	 * method the route's bank detour is for. {@code walkBefore} tiles of walking to reach the method
	 * are shown as a "(N)" prefix on the label.
	 */
	private JPanel buildMethodRow(TeleportMethod method, Set<TeleportMethod> bankMethods, int walkBefore)
	{
		JPanel row = new JPanel(new BorderLayout(5, 0));
		row.setOpaque(false);

		JLabel dot = new JLabel(categoryDot(method.category()));
		dot.setVerticalAlignment(SwingConstants.TOP);
		dot.setBorder(new EmptyBorder(2, 0, 0, 0));
		dot.setToolTipText(method.category());
		MethodAvailability status = cachedUnavailable.get(method);
		boolean bankGated = bankMethods.contains(method);
		if (status != null || bankGated)
		{
			JPanel west = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
			west.setOpaque(false);
			west.add(dot);
			if (bankGated)
			{
				JLabel bankMarker = new JLabel(RouteIcons.IN_BANK);
				bankMarker.setToolTipText("This method needs an item from your bank — the route walks to a bank to withdraw it first");
				west.add(bankMarker);
			}
			if (status != null)
			{
				west.add(statusLabel(status));
			}
			row.add(west, BorderLayout.WEST);
		}
		else
		{
			row.add(dot, BorderLayout.WEST);
		}

		String prefix = walkBefore > 0 ? "(" + walkBefore + ") " : "";
		JLabel text = wrappedLabel(prefix + escapeHtml(method.label()));
		text.setToolTipText(walkBefore > 0
			? "<html>Walk " + walkBefore + " tiles to reach this method.<br>" + methodTooltipBody(method) + "</html>"
			: methodTooltip(method));
		row.add(text, BorderLayout.CENTER);

		IconActionLabel exclude = new IconActionLabel(RouteIcons.EXCLUDE, RouteIcons.EXCLUDE_HOVER,
			"Exclude \"" + method.label() + "\" from teleportation methods", () -> plugin.excludeMethod(method));
		JPanel actionWrap = new JPanel(new BorderLayout());
		actionWrap.setOpaque(false);
		actionWrap.add(control(exclude), BorderLayout.NORTH);
		row.add(actionWrap, BorderLayout.EAST);

		return row;
	}

	/** The funnel icon that opens the catalog filter menu; orange while a filter is active. */
	private JLabel buildCatalogFilter()
	{
		boolean active = catalogFilter.isActive();
		JLabel funnel = new JLabel(active ? RouteIcons.FILTER_ACTIVE : RouteIcons.FILTER);
		funnel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		funnel.setToolTipText("Filter: " + catalogFilter.label + " (click to change)");
		funnel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				showCatalogFilterMenu(funnel);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				funnel.setIcon(active ? RouteIcons.FILTER_ACTIVE_HOVER : RouteIcons.FILTER_HOVER);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				funnel.setIcon(active ? RouteIcons.FILTER_ACTIVE : RouteIcons.FILTER);
			}
		});
		return funnel;
	}

	private void showCatalogFilterMenu(JComponent anchor)
	{
		JPopupMenu menu = new JPopupMenu();
		menu.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		menu.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		ButtonGroup group = new ButtonGroup();
		for (CatalogFilter option : CatalogFilter.values())
		{
			JRadioButtonMenuItem item = new JRadioButtonMenuItem(option.label, option == catalogFilter);
			item.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			item.setForeground(Color.WHITE);
			item.setFont(FontManager.getRunescapeSmallFont());
			item.addActionListener(e ->
			{
				catalogFilter = option;
				refreshCatalog();
			});
			group.add(item);
			menu.add(item);
		}
		menu.show(anchor, 0, anchor.getHeight());
	}

	/** Rebuilds just the teleport-methods catalog slot (used on collapse/expand and dirty renders). */
	private void refreshCatalog()
	{
		catalogHolder.removeAll();
		if (!cachedCatalog.isEmpty())
		{
			catalogHolder.add(buildCatalogSection());
		}
		catalogHolder.revalidate();
		catalogHolder.repaint();
		renderedCatalog = cachedCatalog;
		renderedExclusions = cachedExclusions;
		renderedUnavailable = cachedUnavailable;
		renderedCatalogExpanded = catalogExpanded;
	}

	private JPanel buildCatalogSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		section.setBorder(new EmptyBorder(0, 0, 0, 0));
		section.setAlignmentX(Component.LEFT_ALIGNMENT);

		// Collapsible section header: chevron + title + method count.
		JPanel titleRow = new JPanel(new BorderLayout(5, 0));
		titleRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		titleRow.setBorder(new EmptyBorder(0, 0, 4, 0));
		titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		// "available" = methods usable right now (not missing an item/level/quest/unlock); the count
		// the player actually cares about. Broken down into permanent (unlimited use) and charged
		// (consumes a charge or the item itself — teleport tabs, charged jewellery).
		int available = 0;
		int included = 0;
		int permanent = 0;
		int charged = 0;
		for (TeleportMethod method : cachedCatalog)
		{
			if (!cachedUnavailable.containsKey(method))
			{
				available++;
				if (method.isConsumable())
				{
					charged++;
				}
				else
				{
					permanent++;
				}
			}
			if (!cachedExclusions.contains(method))
			{
				included++;
			}
		}
		JLabel title = new JLabel("Teleport methods (" + available + "/" + cachedCatalog.size() + ")");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		title.setToolTipText(available + " usable now · " + included + " included in searches · "
			+ cachedCatalog.size() + " total");
		titleRow.add(title, BorderLayout.CENTER);
		titleRow.add(control(new JLabel(catalogExpanded ? RouteIcons.CHEVRON_DOWN : RouteIcons.CHEVRON_RIGHT)),
			BorderLayout.EAST);
		titleRow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		titleRow.setToolTipText(catalogExpanded ? "Collapse the methods list" : "Expand the methods list");
		addClickRecursively(titleRow, new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				// Toggle just the catalog — rebuilding the whole panel (and every route card) on a
				// collapse was the source of the lag.
				catalogExpanded = !catalogExpanded;
				refreshCatalog();
			}
		});
		section.add(titleRow);

		// Usable breakdown — permanent (unlimited) vs charged (consumes a charge/the item).
		if (available > 0)
		{
			JLabel breakdown = new JLabel(permanent + " permanent · " + charged + " charged");
			breakdown.setFont(FontManager.getRunescapeSmallFont());
			breakdown.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			breakdown.setToolTipText("Of the usable methods: " + permanent + " permanent (unlimited use) · "
				+ charged + " charged (teleport tabs, charged jewellery — consumed or lose a charge)");
			breakdown.setAlignmentX(Component.LEFT_ALIGNMENT);
			breakdown.setBorder(new EmptyBorder(0, 0, 4, 0));
			section.add(breakdown);
		}

		if (!catalogExpanded)
		{
			catalogRowsPanel = null;
			catalogRowsScroll = null;
			section.setBorder(new EmptyBorder(0, 0, 4, 0));
			return section;
		}

		// Filter box (persistent component, see the field comment) — only mounted while expanded —
		// with a funnel that opens a menu to narrow by disabled/unavailability kind.
		catalogSearch.setAlignmentX(Component.LEFT_ALIGNMENT);
		catalogSearch.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		JPanel filterWrap = new JPanel(new BorderLayout());
		filterWrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		filterWrap.setBorder(new EmptyBorder(0, 4, 0, 2));
		filterWrap.add(control(buildCatalogFilter()), BorderLayout.CENTER);
		JPanel searchWrap = new JPanel(new BorderLayout());
		searchWrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		searchWrap.setBorder(new EmptyBorder(0, 0, 4, 0));
		searchWrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		searchWrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
		searchWrap.add(catalogSearch, BorderLayout.CENTER);
		searchWrap.add(filterWrap, BorderLayout.EAST);
		section.add(searchWrap);

		// The method rows scroll inside their own bounded box with their own scrollbar, so a long
		// (or fully expanded) catalog never pushes the route list off screen. The rows panel tracks
		// the viewport width so the scrollbar sits beside the rows instead of clipping them.
		ScrollableBox rows = new ScrollableBox(null);
		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		rows.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JScrollPane rowsScroll = new JScrollPane(rows,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		rowsScroll.setBorder(BorderFactory.createEmptyBorder());
		rowsScroll.getVerticalScrollBar().setUnitIncrement(16);
		rowsScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
		catalogRowsPanel = rows;
		catalogRowsScroll = rowsScroll;
		populateCatalogRows();
		section.add(rowsScroll);
		section.setBorder(new EmptyBorder(0, 0, 8, 0));

		return section;
	}

	/**
	 * (Re)fills the expanded catalog's rows box from the current filter text. Called on every filter
	 * keystroke — repopulates in place so the search field keeps focus. While a filter is active,
	 * matching categories are shown force-expanded (a filter that only matched collapsed categories
	 * would otherwise look like it found nothing).
	 */
	private void populateCatalogRows()
	{
		JPanel rows = catalogRowsPanel;
		JScrollPane rowsScroll = catalogRowsScroll;
		if (rows == null || rowsScroll == null)
		{
			return;
		}
		rows.removeAll();

		String filter = catalogSearch.getText() == null ? "" : catalogSearch.getText().trim().toLowerCase();
		boolean filtering = !filter.isEmpty();

		Map<String, List<TeleportMethod>> grouped = new TreeMap<>();
		for (TeleportMethod method : cachedCatalog)
		{
			// The funnel filter narrows to disabled methods or a single unavailability kind.
			if (catalogFilter.disabled && !cachedExclusions.contains(method))
			{
				continue;
			}
			if (catalogFilter.availability != null && cachedUnavailable.get(method) != catalogFilter.availability)
			{
				continue;
			}
			// A filter hit on the category keeps the whole category; otherwise match the method label.
			if (!filtering
				|| method.category().toLowerCase().contains(filter)
				|| method.label().toLowerCase().contains(filter))
			{
				grouped.computeIfAbsent(method.category(), k -> new ArrayList<>()).add(method);
			}
		}
		for (List<TeleportMethod> items : grouped.values())
		{
			items.sort(Comparator.comparing(m -> m.label().toLowerCase()));
		}

		if (grouped.isEmpty())
		{
			String message = catalogFilter.isActive() ? "No methods — " + catalogFilter.label.toLowerCase()
				: "No methods match \"" + escapeHtml(filter) + "\"";
			JLabel none = wrappedLabel("<i>" + message + "</i>");
			none.setBorder(new EmptyBorder(2, 4, 2, 0));
			none.setAlignmentX(Component.LEFT_ALIGNMENT);
			rows.add(none);
		}
		for (Map.Entry<String, List<TeleportMethod>> entry : grouped.entrySet())
		{
			String category = entry.getKey();
			List<TeleportMethod> items = entry.getValue();
			// A text filter or an active funnel filter force categories open so the matches show.
			boolean expanded = filtering || catalogFilter.isActive() || expandedCategories.contains(category);
			rows.add(buildCategoryHeader(category, items, expanded));
			if (expanded)
			{
				for (TeleportMethod item : items)
				{
					rows.add(buildCatalogItemRow(item));
				}
			}
		}

		// Bounded height: natural size for short lists, capped so the routes below stay visible.
		int height = Math.min(rows.getPreferredSize().height + 2, CATALOG_MAX_HEIGHT);
		rowsScroll.setPreferredSize(new Dimension(10, height));
		rowsScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		rows.revalidate();
		rows.repaint();
		catalogHolder.revalidate();
		catalogHolder.repaint();
	}

	private JPanel buildCategoryHeader(String category, List<TeleportMethod> items, boolean expanded)
	{
		int excludedCount = 0;
		for (TeleportMethod method : items)
		{
			if (cachedExclusions.contains(method))
			{
				excludedCount++;
			}
		}
		boolean allIncluded = excludedCount == 0;
		boolean allExcluded = excludedCount == items.size();

		JPanel row = new JPanel(new BorderLayout(3, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(3, 4, 3, 4)));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

		ImageIcon icon;
		ImageIcon hover;
		String tip;
		Runnable action;
		if (allIncluded)
		{
			icon = RouteIcons.CHECK;
			hover = RouteIcons.CHECK_HOVER;
			tip = "All included — click to exclude every " + category.toLowerCase();
			action = () -> plugin.excludeMethods(items);
		}
		else if (allExcluded)
		{
			icon = RouteIcons.CROSS;
			hover = RouteIcons.CROSS_HOVER;
			tip = "All excluded — click to include every " + category.toLowerCase();
			action = () -> plugin.includeMethods(items);
		}
		else
		{
			icon = RouteIcons.DASH;
			hover = RouteIcons.DASH_HOVER;
			tip = (items.size() - excludedCount) + " of " + items.size() + " included — click to include all";
			action = () -> plugin.includeMethods(items);
		}
		row.add(control(new IconActionLabel(icon, hover, tip, action)), BorderLayout.WEST);

		String count = allIncluded
			? " (" + items.size() + ")"
			: " (" + (items.size() - excludedCount) + "/" + items.size() + ")";
		JLabel name = new JLabel(category + count);
		name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		row.add(name, BorderLayout.CENTER);

		row.add(control(new JLabel(expanded ? RouteIcons.CHEVRON_DOWN : RouteIcons.CHEVRON_RIGHT)), BorderLayout.EAST);

		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setToolTipText(expanded ? "Collapse" : "Expand to toggle individual methods");
		addClickRecursively(row, new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggleCategory(category);
			}
		});
		return row;
	}

	private JPanel buildCatalogItemRow(TeleportMethod item)
	{
		boolean excluded = cachedExclusions.contains(item);

		JPanel row = new JPanel(new BorderLayout(3, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(2, 18, 2, 4));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

		IconActionLabel toggle = excluded
			? new IconActionLabel(RouteIcons.CROSS, RouteIcons.CROSS_HOVER,
				"Excluded — click to include", () -> plugin.includeMethod(item))
			: new IconActionLabel(RouteIcons.CHECK, RouteIcons.CHECK_HOVER,
				"Included — click to exclude", () -> plugin.excludeMethod(item));
		JPanel icons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
		icons.setOpaque(false);
		icons.add(control(toggle));
		MethodAvailability status = cachedUnavailable.get(item);
		if (status != null)
		{
			icons.add(control(statusLabel(status)));
		}
		// GridBag centres the icons vertically against the label instead of top-anchoring them.
		JPanel west = new JPanel(new GridBagLayout());
		west.setOpaque(false);
		west.add(icons);
		row.add(west, BorderLayout.WEST);

		JLabel text = wrappedLabel(escapeHtml(item.label()));
		text.setToolTipText(methodTooltip(item));
		if (excluded)
		{
			text.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		}
		row.add(text, BorderLayout.CENTER);

		return row;
	}

	/**
	 * Marker for a method the player can't use in the current mode: a bank glyph for an item that's only
	 * in the bank, a padlock for everything else, each with a reason tooltip.
	 */
	private static JLabel statusLabel(MethodAvailability status)
	{
		JLabel label = new JLabel(status == MethodAvailability.IN_BANK ? RouteIcons.IN_BANK : RouteIcons.LOCKED);
		label.setToolTipText(statusReason(status));
		return label;
	}

	private static String statusReason(MethodAvailability status)
	{
		switch (status)
		{
			case IN_BANK:
				return "In your bank — switch to \"Inventory + bank\" or withdraw it";
			case MISSING_ITEM:
				return "You don't have the required item";
			case MISSING_LEVEL:
				return "Your skill level is too low";
			case MISSING_QUEST:
				return "Requires an unfinished quest";
			case LOCKED:
			default:
				return "Not unlocked yet (diary, minigame, purchase or setting)";
		}
	}

	private void toggleCategory(String category)
	{
		if (!expandedCategories.add(category))
		{
			expandedCategories.remove(category);
		}
		// Repopulate the rows in place: cheaper than a full render, and the catalog section's dirty
		// check (which doesn't track per-category expansion) would skip the rebuild anyway.
		populateCatalogRows();
	}

	/**
	 * Human list of method labels, e.g. "Fairy ring" or "Fairy ring and Cowbell amulet".
	 */
	private static String joinLabels(Set<TeleportMethod> methods)
	{
		StringBuilder joined = new StringBuilder();
		int i = 0;
		for (TeleportMethod method : methods)
		{
			if (i > 0)
			{
				joined.append(i == methods.size() - 1 ? " and " : ", ");
			}
			joined.append(method.label());
			i++;
		}
		return joined.toString();
	}

	private String methodTooltip(TeleportMethod method)
	{
		return "<html>" + methodTooltipBody(method) + "</html>";
	}

	private String methodTooltipBody(TeleportMethod method)
	{
		int destination = method.getDestination();
		int x = WorldPointUtil.unpackWorldX(destination);
		int y = WorldPointUtil.unpackWorldY(destination);
		int plane = WorldPointUtil.unpackWorldPlane(destination);
		return "<b>" + escapeHtml(method.category()) + "</b><br>"
			+ escapeHtml(method.label()) + "<br>"
			+ "Arrives at " + x + ", " + y + (plane > 0 ? " (plane " + plane + ")" : "");
	}

	private void makeSelectable(JPanel card, int index)
	{
		addClickRecursively(card, new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				plugin.selectRoute(index);
			}
		});
	}

	/**
	 * Attaches a click listener to a component and its descendants, skipping {@link IconActionLabel}s
	 * so the icon controls keep their own action. Swing only delivers a click to the deepest component
	 * under the cursor, hence the recursion.
	 */
	private void addClickRecursively(Component component, MouseListener listener)
	{
		if (component instanceof IconActionLabel)
		{
			return;
		}
		component.addMouseListener(listener);
		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				addClickRecursively(child, listener);
			}
		}
	}

	private void updateModeButtons()
	{
		AlternativeRoutesMode mode = plugin.getRoutesMode();
		boolean owned = mode.isOwned();
		styleModeButton(ownedButton, owned);
		styleModeButton(allButton, !owned);
		if (owned)
		{
			variantOneButton.setText("Inventory");
			variantOneButton.setToolTipText("Only items you carry (inventory + equipment)");
			variantTwoButton.setText("Inv + bank");
			variantTwoButton.setToolTipText("Also items in your bank — routes walk to a bank to withdraw them");
		}
		else
		{
			variantOneButton.setText("Available");
			variantOneButton.setToolTipText("Ignore item possession, but only methods your character has unlocked (skills, quests, diaries)");
			variantTwoButton.setText("Everything");
			variantTwoButton.setToolTipText("Every method in the game, including ones your character can't use yet");
		}
		styleModeButton(variantOneButton, !mode.isSecondVariant());
		styleModeButton(variantTwoButton, mode.isSecondVariant());
	}

	private static void styleModeButton(JButton button, boolean active)
	{
		button.setForeground(active ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		button.setBackground(active ? ColorScheme.DARKER_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
		button.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(active ? ColorScheme.BRAND_ORANGE : ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(3, 0, 3, 0)));
	}

	/**
	 * A full-width, left-aligned note row for a route card. Bare JLabels must not be added straight
	 * into the vertical BoxLayout: they don't stretch and default to centred alignment, which floats
	 * them into odd positions and clips them at the card edge.
	 */
	private JPanel noteRow(String innerHtml, String tooltip)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		JLabel text = wrappedLabel(innerHtml);
		if (tooltip != null)
		{
			text.setToolTipText(tooltip);
		}
		row.add(text, BorderLayout.WEST);
		return row;
	}

	private JLabel wrappedLabel(String innerHtml)
	{
		JLabel label = new JLabel("<html><body style='width:" + METHOD_TEXT_WIDTH + "px'>" + innerHtml + "</body></html>");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setVerticalAlignment(SwingConstants.TOP);
		return label;
	}

	private static JLabel control(JLabel label)
	{
		label.setPreferredSize(new Dimension(CONTROL_SIZE, CONTROL_SIZE));
		label.setHorizontalAlignment(SwingConstants.CENTER);
		return label;
	}

	// ── "Go to" destination search ──────────────────────────────────────
	private static final int MAX_DESTINATION_RESULTS = 12;

	private JPanel buildDestinationSearch()
	{
		JPanel wrap = new JPanel();
		wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
		wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrap.setBorder(new EmptyBorder(10, 0, 0, 0));

		wrap.add(fullWidth(sectionLabel("Go to a place")));

		destinationSearch.setIcon(IconTextField.Icon.SEARCH);
		destinationSearch.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		destinationSearch.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		destinationSearch.setToolTipText("Search for a city or town by name");
		destinationSearch.setMaximumSize(new Dimension(Integer.MAX_VALUE, destinationSearch.getPreferredSize().height));
		destinationSearch.setAlignmentX(LEFT_ALIGNMENT);
		destinationSearch.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				renderCityResults();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				renderCityResults();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				renderCityResults();
			}
		});
		wrap.add(destinationSearch);

		destinationResults.setLayout(new BoxLayout(destinationResults, BoxLayout.Y_AXIS));
		destinationResults.setBackground(ColorScheme.DARK_GRAY_COLOR);
		destinationResults.setBorder(new EmptyBorder(4, 0, 0, 0));
		destinationResults.setAlignmentX(LEFT_ALIGNMENT);
		destinationResults.setVisible(false);
		wrap.add(destinationResults);

		// "Nearest X": a single button opening a menu of amenity types; picking one routes to the
		// closest of that type using available teleports.
		wrap.add(fullWidth(sectionLabel("Find nearest")));
		wrap.add(buildNearestButton());
		return wrap;
	}

	private JLabel sectionLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(Color.WHITE);
		label.setBorder(new EmptyBorder(6, 0, 4, 0));
		return label;
	}

	private JComponent fullWidth(JComponent component)
	{
		component.setAlignmentX(LEFT_ALIGNMENT);
		component.setMaximumSize(new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height));
		return component;
	}

	private JButton buildNearestButton()
	{
		JButton button = new JButton("Nearest…");
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setForeground(Color.WHITE);
		button.setFocusPainted(false);
		button.setHorizontalAlignment(SwingConstants.LEFT);
		button.setAlignmentX(LEFT_ALIGNMENT);
		button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
		button.setToolTipText("Route to the nearest bank / altar / water source / … using available teleports");
		button.addActionListener(e -> showNearestMenu(button));
		return button;
	}

	private void showNearestMenu(JComponent anchor)
	{
		JPopupMenu menu = new JPopupMenu();
		menu.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		menu.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		for (Destinations.NearestOption option : Destinations.NEAREST_OPTIONS)
		{
			JMenuItem item = new JMenuItem(option.label, RouteIcons.destinationIcon(option.id));
			item.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			item.setForeground(Color.WHITE);
			item.setFont(FontManager.getRunescapeSmallFont());
			item.setIconTextGap(6);
			item.addActionListener(e ->
			{
				Set<Integer> tiles = Destinations.tilesForCategory(option.id, plugin.getTransports());
				plugin.setNearestCategory(tiles, "nearest " + option.label.toLowerCase(java.util.Locale.ROOT));
				destinationSearch.setText("");
			});
			menu.add(item);
		}
		menu.show(anchor, 0, anchor.getHeight());
	}

	private void renderCityResults()
	{
		destinationResults.removeAll();
		String query = destinationSearch.getText().trim().toLowerCase(java.util.Locale.ROOT);
		if (query.isEmpty())
		{
			destinationResults.setVisible(false);
			destinationResults.revalidate();
			destinationResults.repaint();
			return;
		}

		final int player = plugin.getPlayerLocation();
		List<Destinations.Entry> matches = new ArrayList<>();
		for (Destinations.Entry entry : Destinations.places())
		{
			if (entry.name.toLowerCase(java.util.Locale.ROOT).contains(query))
			{
				matches.add(entry);
			}
		}
		if (player != WorldPointUtil.UNDEFINED)
		{
			matches.sort(Comparator.comparingInt(e -> WorldPointUtil.distanceBetween(player, e.packedPosition)));
		}
		else
		{
			matches.sort(Comparator.comparing(e -> e.name));
		}

		int shown = 0;
		for (Destinations.Entry entry : matches)
		{
			destinationResults.add(destinationRow(entry, player));
			if (++shown >= MAX_DESTINATION_RESULTS)
			{
				break;
			}
		}
		if (shown == 0)
		{
			JLabel none = new JLabel("No matching place");
			none.setForeground(Color.GRAY);
			none.setFont(FontManager.getRunescapeSmallFont());
			none.setBorder(new EmptyBorder(2, 4, 2, 0));
			destinationResults.add(none);
		}
		destinationResults.setVisible(true);
		destinationResults.revalidate();
		destinationResults.repaint();
	}

	private JPanel destinationRow(Destinations.Entry entry, int player)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(3, 4, 3, 4));
		row.setCursor(new Cursor(Cursor.HAND_CURSOR));

		JLabel name = new JLabel(entry.name, RouteIcons.destinationIcon(entry.category), SwingConstants.LEADING);
		name.setIconTextGap(3);
		name.setForeground(Color.WHITE);
		name.setFont(FontManager.getRunescapeSmallFont());
		row.add(name, BorderLayout.CENTER);

		if (player != WorldPointUtil.UNDEFINED)
		{
			int distance = WorldPointUtil.distanceBetween(player, entry.packedPosition);
			if (distance != Integer.MAX_VALUE)
			{
				JLabel dist = new JLabel(distance + " tiles");
				dist.setForeground(Color.GRAY);
				dist.setFont(FontManager.getRunescapeSmallFont());
				row.add(dist, BorderLayout.EAST);
			}
		}

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				plugin.setDestination(entry.packedPosition, "search");
				destinationSearch.setText("");
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});
		return row;
	}

	private static Icon categoryDot(String category)
	{
		return dot(categoryColour(category));
	}

	private static Icon dot(Color colour)
	{
		final int s = 9;
		BufferedImage image = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(colour);
		g.fillRoundRect(0, 1, s - 1, s - 2, 4, 4);
		g.dispose();
		return new ImageIcon(image);
	}

	private static Color categoryColour(String category)
	{
		return CATEGORY_PALETTE[Math.floorMod(category.hashCode(), CATEGORY_PALETTE.length)];
	}

	private static String escapeHtml(String text)
	{
		if (text == null)
		{
			return "";
		}
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static Component verticalGap(int height)
	{
		JPanel gap = new JPanel();
		gap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		gap.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		gap.setPreferredSize(new Dimension(1, height));
		return gap;
	}
}
