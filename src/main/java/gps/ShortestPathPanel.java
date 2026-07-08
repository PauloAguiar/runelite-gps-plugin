package gps;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Point;
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
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import gps.transport.TransportType;

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
	// Wrap width for message-banner text: the sidebar content (~192px after the panel's outer
	// padding and scrollbar) minus the banner's accent bar, paddings, icon and gap (~40px), with
	// slack for font-metric variance — wrapping a line early is invisible, clipping is not.
	private static final int BANNER_TEXT_WIDTH = 138;
	private static final Color BANNER_INFO_ACCENT = new Color(0x4C, 0x8B, 0xF5);   // GPS blue
	private static final Color BANNER_WARN_ACCENT = new Color(0xFF, 0x98, 0x1F);   // amber
	private static final Color BANNER_OK_ACCENT = new Color(0x4C, 0xAF, 0x50);     // green
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
	// Message-banner container below the header; repopulated each render with the status banner
	// (routes found / calculating / none) plus any warnings (bank unknown, stale exclusions).
	private final JPanel notes = new JPanel();
	// Set by the plugin the instant it clears the target on arrival, so the status shows an arrival
	// banner rather than "No destination set". Cleared when a new destination is set.
	private boolean showingArrival;
	private boolean arrivalImmediate;
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
	// The search results float over the panel in a non-focusable popup anchored under the search
	// field (autocomplete-style) — inline results pushed the whole panel down while typing.
	private final JPopupMenu destinationPopup = new JPopupMenu();
	// The name-search index (places + dungeons + minigames), built once the transport data is
	// available: it's session-static, so caching avoids rescanning transports on every keystroke.
	private List<Destinations.Entry> destinationIndex;
	private JButton inventoryModeButton;
	private JButton bankModeButton;
	private JButton allModeButton;

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

	/**
	 * Sidebar visibility drives how much work the route generator does: while this panel is hidden
	 * only the primary route is computed; opening it searches the extra alternatives automatically.
	 */
	@Override
	public void onActivate()
	{
		plugin.setAltPanelVisible(true);
	}

	@Override
	public void onDeactivate()
	{
		destinationPopup.setVisible(false);
		plugin.setAltPanelVisible(false);
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
		actions.add(control(new IconActionLabel(RouteIcons.BENCHMARK, RouteIcons.BENCHMARK_HOVER,
			"<html>Run the fixed performance benchmark (prepared trips + nearest-X queries)<br>"
				+ "and save a profiling report for comparing plugin versions.<br>"
				+ "Takes a minute or two and cancels any in-progress route search.</html>",
			plugin::runBenchmark)));
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
		// One segmented row, ordered by inclusiveness (each step considers strictly more methods):
		// what you carry -> plus your bank -> everything in the game. Replaces the old two-level
		// family/variant picker, whose nesting read as two unrelated button rows.
		JPanel modeRow = new JPanel(new GridLayout(1, 3, 4, 0));
		modeRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		modeRow.setBorder(new EmptyBorder(8, 0, 0, 0));
		inventoryModeButton = new JButton("Inventory");
		inventoryModeButton.setToolTipText("<html><b>Available now</b> — only methods usable with what you carry<br>"
			+ "(inventory + equipment).</html>");
		inventoryModeButton.setFont(FontManager.getRunescapeSmallFont());
		inventoryModeButton.setFocusPainted(false);
		inventoryModeButton.addActionListener(e -> plugin.setRoutesMode(AlternativeRoutesMode.OWNED_INVENTORY));
		bankModeButton = new JButton("+ Bank");
		bankModeButton.setToolTipText("<html><b>Available via your bank</b> — also counts banked items;<br>"
			+ "routes detour to a bank to withdraw them.<br>"
			+ "Open your bank once per session so its contents are known.</html>");
		bankModeButton.setFont(FontManager.getRunescapeSmallFont());
		bankModeButton.setFocusPainted(false);
		bankModeButton.addActionListener(e -> plugin.setRoutesMode(AlternativeRoutesMode.OWNED_WITH_BANK));
		allModeButton = new JButton("All");
		allModeButton.setToolTipText("<html><b>Every method in the game</b>, regardless of items or unlocks —<br>"
			+ "the planning view. Markers in the catalog show what each one is missing.</html>");
		allModeButton.setFont(FontManager.getRunescapeSmallFont());
		allModeButton.setFocusPainted(false);
		allModeButton.addActionListener(e -> plugin.setRoutesMode(AlternativeRoutesMode.ALL_EVERYTHING));
		modeRow.add(inventoryModeButton);
		modeRow.add(bankModeButton);
		modeRow.add(allModeButton);

		bottom.add(modeRow, BorderLayout.NORTH);

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
	 * The message-banner strip below the header: the status banner ("N routes found", "Calculating…",
	 * "No destination set") plus warning banners (bank contents unknown, stale exclusions). Filled by
	 * {@link #render()}; shown directly above the route cards.
	 */
	private JPanel buildNotes()
	{
		notes.setLayout(new BoxLayout(notes, BoxLayout.Y_AXIS));
		notes.setBackground(ColorScheme.DARK_GRAY_COLOR);
		notes.setBorder(new EmptyBorder(4, 0, 6, 0));
		return notes;
	}

	/**
	 * A message banner: a coloured left accent bar, an icon, and wrapped text — used for status and
	 * warnings instead of loose labels.
	 */
	/**
	 * A titled banner: a bold white title on the first line, the description beneath it. For
	 * warnings/notices that read better as heading + body than one run.
	 */
	private JPanel buildBanner(Icon icon, String title, String body, Color accent)
	{
		String html = "<font color='#FFFFFF'><b>" + escapeHtml(title) + "</b></font>";
		if (body != null && !body.isEmpty())
		{
			html += "<br>" + body;
		}
		return buildBanner(icon, html, accent);
	}

	private JPanel buildBanner(Icon icon, String innerHtml, Color accent)
	{
		JPanel banner = new JPanel(new BorderLayout(7, 0));
		banner.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		banner.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, accent),
			new EmptyBorder(5, 7, 5, 6)));
		banner.setAlignmentX(Component.LEFT_ALIGNMENT);

		// The icon sits vertically centred against the (possibly multi-line) text.
		banner.add(verticallyCentered(new JLabel(icon)), BorderLayout.WEST);

		JLabel text = new JLabel("<html><body style='width:" + BANNER_TEXT_WIDTH + "px'>" + innerHtml + "</body></html>");
		text.setFont(FontManager.getRunescapeSmallFont());
		text.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		banner.add(text, BorderLayout.CENTER);

		banner.setMaximumSize(new Dimension(Integer.MAX_VALUE, banner.getPreferredSize().height));
		return banner;
	}

	/**
	 * Stores the latest data and re-renders. Must be called on the Swing EDT.
	 */
	/**
	 * Called by the plugin the moment it reaches (or clears an already-at) destination, so the status
	 * shows an arrival banner instead of "No destination set". {@code elapsedMillis} is ~0 when the
	 * destination was set while already there. Marshalled onto the EDT; the flag is consumed by the
	 * {@link #render()} that the target-clear then triggers.
	 */
	public void markArrived(long elapsedMillis)
	{
		SwingUtilities.invokeLater(() ->
		{
			showingArrival = true;
			arrivalImmediate = elapsedMillis < 3000;
		});
	}

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

		// Banners are for NOTICES only (warnings, arrival, nothing-to-show); routine result state
		// ("N routes", "calculating…") lives in the results section header instead — a status
		// banner as the results header read as a warning strip above the cards.
		String status = null;
		Icon statusIcon = null;
		Color statusAccent = null;
		// A live destination (or its routes) supersedes any lingering arrival banner.
		if (cachedHasTarget)
		{
			showingArrival = false;
		}
		if (!cachedCalculating && !cachedRoutes.isEmpty()
			&& cachedRoutes.stream().noneMatch(plugin::routeReachesTarget))
		{
			// Routes exist but every one stops short of the target — it can't actually be reached
			// (e.g. a tile on an island with no connecting path or teleport). Say so, don't imply success.
			status = "<b>Destination can't be reached.</b><br>Showing the route to the closest reachable point.";
			statusIcon = RouteIcons.BANNER_WARNING;
			statusAccent = BANNER_WARN_ACCENT;
		}
		else if (!cachedCalculating && cachedRoutes.isEmpty() && cachedHasTarget)
		{
			// A search ran for the current target but produced nothing — distinct from "no target set".
			status = "<b>No routes found to the target.</b>"
				+ (plugin.getRoutesMode() == AlternativeRoutesMode.ALL_EVERYTHING ? "" : "<br>Try a broader mode (+ Bank, or All).");
			statusIcon = RouteIcons.BANNER_WARNING;
			statusAccent = BANNER_WARN_ACCENT;
		}
		else if (!cachedCalculating && cachedRoutes.isEmpty() && showingArrival)
		{
			// Reached (or set while already at) the destination — say so rather than "No destination set".
			status = arrivalImmediate ? "You're already at your destination." : "Arrived at your destination.";
			statusIcon = RouteIcons.CHECK;
			statusAccent = BANNER_OK_ACCENT;
		}
		else if (!cachedCalculating && cachedRoutes.isEmpty())
		{
			// GPS has no active target. (Quest Helper draws its own line for some steps and
			// doesn't hand GPS a destination — set one on the map to find routes.)
			status = "No destination set.";
			statusIcon = RouteIcons.BANNER_INFO;
			statusAccent = BANNER_INFO_ACCENT;
		}

		notes.removeAll();
		// Running the original Shortest Path plugin alongside GPS doubles the path rendering and
		// the plugin-message integrations (both answer Quest Helper's destinations).
		if (plugin.isShortestPathConflict())
		{
			notes.add(buildBanner(RouteIcons.BANNER_WARNING,
				"Shortest Path is also enabled",
				"Both plugins draw paths and respond to the same integrations. GPS includes its "
					+ "functionality — disable Shortest Path to avoid doubled rendering.",
				BANNER_WARN_ACCENT));
			notes.add(verticalGap(4));
		}
		// The bank container is only populated once the bank has been opened this session; without it
		// Bank mode cannot see banked items (same constraint as Shortest Path itself).
		if (plugin.getRoutesMode() == AlternativeRoutesMode.OWNED_WITH_BANK && !plugin.isBankContentsKnown())
		{
			notes.add(buildBanner(RouteIcons.BANNER_WARNING,
				"Bank contents unknown",
				"Open your bank once so banked items can be found.",
				ColorScheme.PROGRESS_ERROR_COLOR));
			notes.add(verticalGap(4));
		}
		if (status != null)
		{
			notes.add(buildBanner(statusIcon, status, statusAccent));
		}
		// Method toggles no longer recalculate; flag a route list generated with different exclusions.
		if (!cachedCalculating && cachedHasTarget && plugin.isRouteListStale())
		{
			notes.add(verticalGap(4));
			notes.add(buildBanner(RouteIcons.BANNER_WARNING,
				"Exclusions changed — press \"Refresh routes\" to apply.", BANNER_WARN_ACCENT));
		}
		// With no notices at all (the common "routes found" case) the strip collapses entirely
		// instead of leaving its padding as a dead gap.
		notes.setVisible(notes.getComponentCount() > 0);
		notes.revalidate();
		notes.repaint();

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

		// The results get a proper section header (like "Teleport methods"): the route count, plus
		// a quiet busy note while the generation streams. Routes are shown as they stream in; the
		// previous list was cleared when this generation started, so only the new routes appear.
		// The highlighted card is the route actually drawn on the map — the explicitly selected
		// one, or route 1 by default.
		if (cachedCalculating || !cachedRoutes.isEmpty())
		{
			listPanel.add(buildResultsHeader(cachedRoutes.size(), cachedCalculating));
		}
		RouteOption selected = plugin.getDisplayedRoute();
		for (int i = 0; i < cachedRoutes.size(); i++)
		{
			listPanel.add(buildRouteCard(i, cachedRoutes.get(i), cachedRoutes.get(i) == selected));
			listPanel.add(verticalGap(6));
		}
		// Only offer "show more" once this generation has finished.
		if (!cachedCalculating && !cachedRoutes.isEmpty() && plugin.canLoadMoreRoutes())
		{
			listPanel.add(buildShowMoreButton());
			listPanel.add(verticalGap(6));
		}

		listPanel.revalidate();
		listPanel.repaint();
	}

	/**
	 * The results section header: bold orange "Routes (N)" like the teleport-methods header, with
	 * a quiet grey "calculating…" note (busy glyph) on the right while the generation streams —
	 * routine result state belongs here, not in a notice banner.
	 */
	private JPanel buildResultsHeader(int count, boolean calculating)
	{
		JPanel row = new JPanel(new BorderLayout(5, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(new EmptyBorder(2, 0, 5, 0));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

		JLabel title = new JLabel(calculating && count == 0 ? "Routes" : "Routes (" + count + ")");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		row.add(title, BorderLayout.WEST);

		if (calculating)
		{
			JLabel busy = new JLabel("calculating…", RouteIcons.BANNER_BUSY, SwingConstants.LEADING);
			busy.setIconTextGap(4);
			busy.setFont(FontManager.getRunescapeSmallFont());
			busy.setForeground(Color.GRAY);
			row.add(busy, BorderLayout.EAST);
		}
		return row;
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
		// Selection reads as a filled state: slightly lighter card + a 3px orange edge stripe,
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
		// quiet rank chip, then the ETA — the decision-making number.
		JPanel left = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		left.setOpaque(false);
		// Pin + rank read as one unit ("📍1", no gap); the clock + ETA sit a space apart.
		JLabel rank = new JLabel(Integer.toString(index + 1),
			selected ? RouteIcons.SHOW_ACTIVE : RouteIcons.SHOW, SwingConstants.LEADING);
		rank.setIconTextGap(1);
		rank.setFont(FontManager.getRunescapeSmallFont());
		rank.setForeground(Color.GRAY);
		left.add(rank);
		boolean weighted = route.getRawCost() != route.getTotalCost();
		JLabel eta = new JLabel(formatDuration(routeEtaSeconds(route)), RouteIcons.CLOCK, SwingConstants.LEADING);
		eta.setIconTextGap(3);
		eta.setBorder(new EmptyBorder(0, 12, 0, 0));
		eta.setFont(FontManager.getRunescapeBoldFont());
		eta.setForeground(selected ? ColorScheme.BRAND_ORANGE : Color.WHITE);
		eta.setToolTipText("<html>Estimated travel time, assuming you run.<br>"
			+ (weighted
				? "Ordering also counts your method weights: adjusted cost " + route.getTotalCost()
					+ " vs " + route.getRawCost() + " unweighted (run-tiles, 0.3s each)."
				: "Routes are ordered by this time.")
			+ "</html>");
		if (!reaches)
		{
			eta.setToolTipText("The target can't be reached — this ends at the closest reachable tile");
		}
		left.add(eta);
		topRow.add(left, BorderLayout.WEST);

		JPanel right = new JPanel(new FlowLayout(FlowLayout.TRAILING, 5, 0));
		right.setOpaque(false);
		if (route.isViaBank())
		{
			// The bank detour as a compact header chip; the coin glyph on the method row below
			// marks WHICH method the detour is for.
			JLabel bankChip = new JLabel(RouteIcons.IN_BANK);
			bankChip.setToolTipText("<html>Walks to a bank first — withdraws the item for: <b>"
				+ escapeHtml(joinLabels(route.getBankMethods())) + "</b></html>");
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
			methods.add(noteRow("<font color='#FF981F'>Can't reach the target — ends at the closest point.</font>",
				"This destination isn't reachable; the route stops at the nearest tile GPS can get to."));
		}
		// Each method row reveals its OWN exclude control (in red) only while the pointer is over
		// that row — see buildMethodRow.
		for (int m = 0; m < route.getMethods().size(); m++)
		{
			methods.add(buildMethodRow(route.getMethods().get(m), route.getBankMethods(),
				route.walkBefore(m)));
		}
		// One walking row for the WHOLE route: every leg between methods plus the trailing leg —
		// per-method walk counts live in the method tooltips instead of cluttering each row.
		int totalWalk = route.getTrailingWalkSteps();
		for (int m = 0; m < route.getMethods().size(); m++)
		{
			totalWalk += route.walkBefore(m);
		}
		if (totalWalk > 0 || route.isWalkOnly())
		{
			methods.add(buildWalkRow(totalWalk));
		}
		card.add(methods, BorderLayout.CENTER);

		card.setToolTipText(selected ? "Showing on map — click to hide" : "Click to show this route on the map");
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		makeSelectable(card, index);
		return card;
	}

	/**
	 * Fires the handler with true when the pointer enters the component tree and false when it
	 * truly leaves it (Swing fires exit when moving onto a CHILD, so exits are checked against the
	 * root's bounds). Used to reveal a route row's exclude control only while hovering that row.
	 */
	private static void addHoverRecursively(Component root, java.util.function.Consumer<Boolean> handler)
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

	/**
	 * The route's estimated travel time in seconds: its unweighted cost is time-normalized (one
	 * cost unit = one run-tile = 0.3s), so the ETA is a direct conversion — and because routes are
	 * ordered by that same cost (plus any configured preference weights), the displayed ETAs can
	 * never disagree with the ordering.
	 */
	private int routeEtaSeconds(RouteOption route)
	{
		return (int) Math.ceil(route.getRawCost() * gps.pathfinder.CostUnits.SECONDS_PER_UNIT);
	}

	private static String formatDuration(int seconds)
	{
		if (seconds < 60)
		{
			return seconds + "s";
		}
		return (seconds / 60) + "m " + (seconds % 60) + "s";
	}

	// Neutral dot colour for walking legs; deliberately outside the category palette so walking
	// doesn't masquerade as a teleport category.
	private static final Color WALK_DOT_COLOUR = new Color(0x9E, 0x9E, 0x9E);
	// Teleport-item dots are coloured by charge model — permanent (reusable) vs charged (consumes a
	// charge or the item) — so the two read apart in a route card.
	private static final Color PERMANENT_ITEM_DOT = new Color(0x4D, 0xB6, 0xAC); // teal
	private static final Color CHARGED_ITEM_DOT = new Color(0xF2, 0xC1, 0x4E);   // amber

	/**
	 * Wraps a component so that, in a BorderLayout WEST/EAST cell (stretched to the row's full
	 * height), it sits vertically centred against the — possibly two-line — label in CENTER, while
	 * staying left-aligned horizontally.
	 */
	private static JPanel verticallyCentered(Component content)
	{
		JPanel wrap = new JPanel(new GridBagLayout());
		wrap.setOpaque(false);
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.anchor = GridBagConstraints.WEST;
		wrap.add(content, gbc);
		return wrap;
	}

	/** The category dot for a route-card method, splitting teleport items by charge model. */
	private static Icon methodDot(TeleportMethod method)
	{
		if (method.getType() == TransportType.TELEPORTATION_ITEM)
		{
			return dot(method.isConsumable() ? CHARGED_ITEM_DOT : PERMANENT_ITEM_DOT);
		}
		return categoryDot(method.category());
	}

	/**
	 * A walking-leg row, shaped exactly like a method row: a neutral grey dot in the category-dot
	 * column, then the step count.
	 */
	private JPanel buildWalkRow(int steps)
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
		text.setToolTipText("Total walking across this route — every leg between methods plus the final stretch");
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

		JLabel dot = new JLabel(methodDot(method));
		dot.setAlignmentY(Component.CENTER_ALIGNMENT);
		dot.setToolTipText(method.getType() == TransportType.TELEPORTATION_ITEM
			? (method.isConsumable() ? "Item (charged — consumes a charge or the item)" : "Item (permanent — reusable)")
			: method.category());
		MethodAvailability status = cachedUnavailable.get(method);
		boolean bankGated = bankMethods.contains(method);
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
			bankMarker.setToolTipText("This method needs an item from your bank — the route walks to a bank to withdraw it first");
			west.add(bankMarker);
		}
		// The availability map now records IN_BANK in every mode; on a route it's already shown by
		// the bank marker above, so only add the status marker for other, distinct reasons.
		if (status != null && !bankGated)
		{
			JLabel statusMarker = statusLabel(status);
			statusMarker.setAlignmentY(Component.CENTER_ALIGNMENT);
			statusMarker.setBorder(new EmptyBorder(0, 3, 0, 0));
			west.add(statusMarker);
		}
		row.add(verticallyCentered(west), BorderLayout.WEST);

		// No per-row step counts: the card's walk row totals every leg, and this row's tooltip
		// still carries its own walk-to-reach detail.
		JLabel text = wrappedLabel(escapeHtml(method.label()));
		text.setVerticalAlignment(SwingConstants.CENTER);
		text.setToolTipText(walkBefore > 0
			? "<html>Walk " + walkBefore + " tiles to reach this method.<br>" + methodTooltipBody(method) + "</html>"
			: methodTooltip(method));
		row.add(text, BorderLayout.CENTER);

		// Nearly invisible at rest (always present, so the row never resizes), it reveals in red
		// while the pointer is over THIS row — and redder still directly over the icon.
		IconActionLabel exclude = new IconActionLabel(RouteIcons.EXCLUDE_DIM, RouteIcons.EXCLUDE_HOVER,
			"Exclude \"" + method.label() + "\" from teleportation methods", () -> plugin.excludeMethod(method));
		JPanel actionWrap = new JPanel(new GridBagLayout());
		actionWrap.setOpaque(false);
		actionWrap.setPreferredSize(new Dimension(CONTROL_SIZE, CONTROL_SIZE));
		actionWrap.add(control(exclude));
		row.add(actionWrap, BorderLayout.EAST);

		// Reveal only this row's control on hover — not the whole card.
		addHoverRecursively(row, hovered ->
			exclude.setRestIcon(hovered ? RouteIcons.EXCLUDE_HOVER : RouteIcons.EXCLUDE_DIM));
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

	/**
	 * Whether a method is usable in the CURRENT mode. The availability map is mode-independent
	 * (a banked item is always recorded IN_BANK); a banked item counts as usable in the
	 * "Inventory + bank" mode, whose route walks to a bank to withdraw it.
	 */
	private boolean isUsable(TeleportMethod method)
	{
		MethodAvailability status = cachedUnavailable.get(method);
		return status == null
			|| (status == MethodAvailability.IN_BANK
				&& plugin.getRoutesMode() == AlternativeRoutesMode.OWNED_WITH_BANK);
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
		// The headline count is the methods a search can ACTUALLY use: usable right now (not
		// missing an item/level/quest/unlock) AND not excluded — so it responds to the toggles,
		// including the seasonal set that starts excluded. Broken down into permanent (unlimited
		// use) and charged (consumes a charge or the item itself — tabs, charged jewellery).
		int enabled = 0;
		int usable = 0;
		int included = 0;
		int permanent = 0;
		int charged = 0;
		for (TeleportMethod method : cachedCatalog)
		{
			boolean canUse = isUsable(method);
			boolean isIncluded = !cachedExclusions.contains(method);
			if (canUse)
			{
				usable++;
			}
			if (isIncluded)
			{
				included++;
			}
			if (canUse && isIncluded)
			{
				enabled++;
				if (method.isConsumable())
				{
					charged++;
				}
				else
				{
					permanent++;
				}
			}
		}
		JLabel title = new JLabel("Teleport methods (" + enabled + "/" + cachedCatalog.size() + ")");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		title.setToolTipText(enabled + " enabled (usable and included) · " + usable + " usable now · "
			+ included + " included in searches · " + cachedCatalog.size() + " total");
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

		if (!catalogExpanded)
		{
			catalogRowsPanel = null;
			catalogRowsScroll = null;
			section.setBorder(new EmptyBorder(0, 0, 4, 0));
			return section;
		}

		// Enabled breakdown — permanent (unlimited) vs charged (consumes a charge/the item). Only shown
		// while expanded, where the split matters; the header count already carries the total collapsed.
		if (enabled > 0)
		{
			JLabel breakdown = new JLabel(permanent + " permanent · " + charged + " charged");
			breakdown.setFont(FontManager.getRunescapeSmallFont());
			breakdown.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			breakdown.setToolTipText("Of the enabled methods: " + permanent + " permanent (unlimited use) · "
				+ charged + " charged (teleport tabs, charged jewellery — consumed or lose a charge)");
			breakdown.setAlignmentX(Component.LEFT_ALIGNMENT);
			breakdown.setBorder(new EmptyBorder(0, 0, 4, 0));
			section.add(breakdown);
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
	 * The catalog section a method is grouped under. Teleport items are split into two sections —
	 * "Items (permanent)" (reusable jewellery/staves) and "Items (charged)" (tabs, charged jewellery
	 * that consume a charge or the item) — since that distinction drives how freely they're used.
	 */
	private static String catalogGroupKey(TeleportMethod method)
	{
		if (method.getType() == TransportType.TELEPORTATION_ITEM)
		{
			return method.isConsumable() ? "Items (charged)" : "Items (permanent)";
		}
		return method.category();
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
				grouped.computeIfAbsent(catalogGroupKey(method), k -> new ArrayList<>()).add(method);
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
		styleModeButton(inventoryModeButton, mode == AlternativeRoutesMode.OWNED_INVENTORY);
		styleModeButton(bankModeButton, mode == AlternativeRoutesMode.OWNED_WITH_BANK);
		styleModeButton(allModeButton, mode == AlternativeRoutesMode.ALL_EVERYTHING);
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
		destinationSearch.setToolTipText("Search places, dungeons and minigames by name");
		destinationSearch.setMaximumSize(new Dimension(Integer.MAX_VALUE, destinationSearch.getPreferredSize().height));
		destinationSearch.setAlignmentX(LEFT_ALIGNMENT);
		destinationSearch.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				renderDestinationResults();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				renderDestinationResults();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				renderDestinationResults();
			}
		});
		wrap.add(destinationSearch);

		destinationResults.setLayout(new BoxLayout(destinationResults, BoxLayout.Y_AXIS));
		destinationResults.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		// Hosted in a floating popup under the search field, NOT in the panel flow — inline
		// results pushed everything below down on every keystroke. Non-focusable so typing stays
		// in the search field while the popup is showing.
		destinationPopup.setFocusable(false);
		destinationPopup.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		destinationPopup.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		destinationPopup.setLayout(new BorderLayout());
		destinationPopup.add(destinationResults, BorderLayout.CENTER);

		// "Find nearest": a single button opening a menu of amenity types; picking one routes to the
		// closest of that type using available teleports.
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
		// A proper call-to-action: pin icon, centred full-weight label, padded and outlined, with
		// a hover lift — the plain left-aligned small-font button read as another loose label.
		JButton button = new JButton("Find nearest…", RouteIcons.destinationIcon("pin"));
		button.setIconTextGap(6);
		button.setFont(FontManager.getRunescapeFont());
		button.setForeground(Color.WHITE);
		button.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
		button.setFocusPainted(false);
		button.setHorizontalAlignment(SwingConstants.CENTER);
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		button.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(6, 10, 6, 10)));
		button.setAlignmentX(LEFT_ALIGNMENT);
		button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
		button.setToolTipText("Route to the nearest bank / altar / water source / … using available teleports");
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
				boolean roundTrip = "bank_round_trip".equals(option.id);
				plugin.setNearestCategory(tiles,
					"nearest " + option.label.toLowerCase(java.util.Locale.ROOT), roundTrip);
				destinationSearch.setText("");
			});
			menu.add(item);
		}
		menu.show(anchor, 0, anchor.getHeight());
	}

	/** The cached name-search index; (re)built only once the transport data is available. */
	private List<Destinations.Entry> destinationIndex()
	{
		List<Destinations.Entry> cached = destinationIndex;
		if (cached != null)
		{
			return cached;
		}
		List<Destinations.Entry> built = Destinations.searchable(plugin.getTransports());
		if (plugin.getTransports() != null)
		{
			destinationIndex = built;
		}
		return built;
	}

	private void renderDestinationResults()
	{
		destinationResults.removeAll();
		String query = destinationSearch.getText().trim().toLowerCase(java.util.Locale.ROOT);
		if (query.isEmpty())
		{
			destinationPopup.setVisible(false);
			return;
		}

		final int player = plugin.getPlayerLocation();
		List<Destinations.Entry> matches = new ArrayList<>();
		for (Destinations.Entry entry : destinationIndex())
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
			JLabel none = new JLabel("No matching destination");
			none.setForeground(Color.GRAY);
			none.setFont(FontManager.getRunescapeSmallFont());
			none.setBorder(new EmptyBorder(2, 4, 2, 4));
			destinationResults.add(none);
		}

		// Float the results over the panel, matching the search field's width. Re-showing on every
		// keystroke would flicker and can steal the caret, so a visible popup is resized in place.
		int width = Math.max(destinationSearch.getWidth(), 180);
		destinationPopup.setPreferredSize(new Dimension(width,
			destinationResults.getPreferredSize().height + 2));
		if (destinationPopup.isVisible())
		{
			destinationPopup.revalidate();
			destinationPopup.repaint();
			destinationPopup.pack();
		}
		else
		{
			destinationPopup.show(destinationSearch, 0, destinationSearch.getHeight());
		}
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

	/**
	 * Fixed, deliberately-distinct colour per known category (the old hash assignment gave
	 * "Boats & ships" the exact same teal as the permanent-item dot). Items are teal to match the
	 * permanent-item dot; charged items get the amber dot via {@link #methodDot}. The hashed
	 * palette remains only as a fallback for categories added later.
	 */
	private static Color categoryColour(String category)
	{
		switch (category)
		{
			case "Spells": return new Color(0x5B, 0x9B, 0xD5);          // blue
			case "Items": return PERMANENT_ITEM_DOT;                     // teal (charged = amber)
			case "Jewellery box": return new Color(0xB4, 0x6F, 0xD4);   // purple
			case "Levers": return new Color(0xD1, 0x5B, 0x5B);          // red
			case "Minigame teleports": return new Color(0xE5, 0x73, 0x99); // pink
			case "Portals": return new Color(0x9C, 0x7B, 0xE8);         // violet
			case "Fairy rings": return new Color(0x4C, 0xAF, 0x50);     // green
			case "Spirit trees": return new Color(0x8B, 0xC3, 0x4A);    // lime
			case "Gnome gliders": return new Color(0xC9, 0x69, 0xC9);   // magenta
			case "Hot air balloons": return new Color(0xE9, 0x7D, 0x3B); // orange
			case "Magic carpets": return new Color(0xB0, 0x3A, 0x5B);   // wine
			case "Mushtrees": return new Color(0xE0, 0x60, 0x60);       // light red
			case "Minecarts": return new Color(0x60, 0x7D, 0x8B);       // slate
			case "Quetzals": return new Color(0x4A, 0xC6, 0xE0);        // cyan
			case "Obelisks": return new Color(0x9A, 0xA5, 0xB1);        // steel
			case "Boats & ships": return new Color(0x5C, 0x6B, 0xC0);   // indigo — NOT the item teal
			case "Canoes": return new Color(0xB5, 0x79, 0x3B);          // wood brown
			case "Seasonal": return new Color(0x94, 0xB4, 0x4A);        // olive
			default: return CATEGORY_PALETTE[Math.floorMod(category.hashCode(), CATEGORY_PALETTE.length)];
		}
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
