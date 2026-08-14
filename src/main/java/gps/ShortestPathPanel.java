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
import java.awt.Insets;
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
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
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
	// The header's GitHub mark points at the project home; the Discord mark at the community invite.
	private static final String GITHUB_REPO_URL = "https://github.com/PauloAguiar/runelite-gps-plugin";
	private static final String DISCORD_URL = "https://discord.gg/7VAbrPsUzT";

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
	// The "bank contents unknown" warning, sitting directly under the mode buttons (it's about the
	// "+ Bank" mode) rather than down in the general notes strip. Repopulated each render.
	private final JPanel modeBankWarning = new JPanel();
	private final JPanel reportBox = new JPanel(new BorderLayout(0, 4));
	private final javax.swing.JTextArea reportContext = new javax.swing.JTextArea();
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
	// Fixed (non-scrolling) slot for the routes header (count + more/refresh/clear controls),
	// mounted above the route-card scroll area so it stays visible while the cards scroll.
	private final JPanel resultsHeaderHolder = new JPanel();
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
	// The currently-shown search result rows and their entries (parallel), plus the keyboard-
	// selected index into them (-1 = none). Up/Down move it, Enter picks it; mouse hover keeps it
	// in sync so both input methods share one highlight.
	private final List<JPanel> resultRows = new ArrayList<>();
	private final List<Destinations.Entry> resultEntries = new ArrayList<>();
	private int selectedResult = -1;
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
	// Whether the whole "Travel methods" catalog section (shown at the top) is expanded. Collapsed
	// by default so the routes stay the focus; the user opens it to browse/toggle methods.
	private boolean catalogExpanded = false;
	private boolean travelSectionExpanded = false;
	private boolean pohSectionExpanded = false;
	private boolean wildernessSectionExpanded = false;
	private boolean walkingSectionExpanded = false;
	private boolean bankSectionExpanded = false;
	private boolean balloonSectionExpanded = false;
	private boolean sailingSectionExpanded = false;
	private boolean spiritTreeSectionExpanded = false;
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
		// The routes header (count + the more/refresh/clear controls) sits in a fixed slot ABOVE
		// the scroll area, so it stays visible while the route cards scroll beneath it.
		resultsHeaderHolder.setLayout(new BoxLayout(resultsHeaderHolder, BoxLayout.Y_AXIS));
		resultsHeaderHolder.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JPanel results = new JPanel(new BorderLayout());
		results.setBackground(ColorScheme.DARK_GRAY_COLOR);
		results.add(resultsHeaderHolder, BorderLayout.NORTH);
		results.add(scroll, BorderLayout.CENTER);
		add(results, BorderLayout.CENTER);

		render();
	}

	/**
	 * Unwrapped panels (super(false)) ARE the component the client UI mounts, so the height this
	 * returns flows into the frame's layout minimum — BorderLayout sums the fixed top block plus
	 * every expanded catalog section, and once that passes the window height the client grows to
	 * obey it (issue #13: "Sidebar modifies client height"). Wrapped panels never have this
	 * problem because RuneLite mounts their scroll pane, whose minimum is tiny. Report the same:
	 * a small fixed height, and let the internal scroll areas absorb any shortage.
	 */
	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(super.getMinimumSize().width, 100);
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

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
		actions.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// A compact red button shows the routing context in a copy box below the header and opens
		// GitHub's new-issue page (a bare link — nothing rides in the URL, no clipboard API).
		// Occasional actions tuck into the burger.
		JButton reportButton = new JButton("Report an issue");
		reportButton.setFont(FontManager.getRunescapeSmallFont());
		reportButton.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		reportButton.setMargin(new java.awt.Insets(2, 6, 2, 6));
		reportButton.setFocusPainted(false);
		reportButton.setToolTipText("<html>Shows your routes and settings in a box to copy, and opens<br>"
			+ "GitHub — paste the context into the issue.<br>"
			+ "First calculate the route that's misbehaving, so the report captures it.</html>");
		reportButton.addActionListener(e -> plugin.reportIssue());
		actions.add(reportButton);
		actions.add(control(new IconActionLabel(RouteIcons.GITHUB, RouteIcons.GITHUB,
			"View the project on GitHub", () -> LinkBrowser.browse(GITHUB_REPO_URL))));
		actions.add(control(new IconActionLabel(RouteIcons.DISCORD, RouteIcons.DISCORD,
			"Join the GPS Discord", () -> LinkBrowser.browse(DISCORD_URL))));
		JPopupMenu actionsMenu = new JPopupMenu();
		JMenuItem debugItem = new JMenuItem("Save debug snapshot", RouteIcons.DEBUG);
		debugItem.setToolTipText("Save a debug snapshot of the current routes to disk (for reproducing issues)");
		debugItem.addActionListener(e -> plugin.captureDebugSnapshot());
		actionsMenu.add(debugItem);
		JMenuItem resetItem = new JMenuItem("Reset excluded methods", RouteIcons.CLEAR);
		resetItem.setToolTipText("Re-include every method you've disabled");
		resetItem.addActionListener(e -> plugin.clearExclusions());
		actionsMenu.add(resetItem);
		IconActionLabel[] menuButton = new IconActionLabel[1];
		menuButton[0] = new IconActionLabel(RouteIcons.MENU, RouteIcons.MENU_HOVER, "More actions",
			() -> actionsMenu.show(menuButton[0], 0, menuButton[0].getHeight()));
		actions.add(control(menuButton[0]));
		titleRow.add(actions, BorderLayout.EAST);

		header.add(titleRow, BorderLayout.NORTH);

		JPanel bottom = new JPanel(new BorderLayout());
		bottom.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// The "Report an issue" copy box: the routing context appears here for the player to copy
		// BY HAND into the GitHub issue that just opened — no clipboard API, no data in the URL,
		// and they see exactly what they're sharing. Hidden until the button is used.
		reportBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		reportBox.setBorder(new EmptyBorder(6, 6, 6, 6));
		reportBox.setVisible(false);
		JPanel reportTitleRow = new JPanel(new BorderLayout());
		reportTitleRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JLabel reportTitle = new JLabel("<html>Select all (Ctrl+A), copy (Ctrl+C), and paste into the GitHub issue:</html>");
		reportTitle.setFont(FontManager.getRunescapeSmallFont());
		reportTitle.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
		reportTitleRow.add(reportTitle, BorderLayout.CENTER);
		JButton reportHide = new JButton("Hide");
		reportHide.setFont(FontManager.getRunescapeSmallFont());
		reportHide.setMargin(new java.awt.Insets(0, 4, 0, 4));
		reportHide.setFocusable(false);
		reportHide.addActionListener(e ->
		{
			reportBox.setVisible(false);
			reportBox.revalidate();
		});
		reportTitleRow.add(reportHide, BorderLayout.EAST);
		reportBox.add(reportTitleRow, BorderLayout.NORTH);
		reportContext.setEditable(false);
		reportContext.setLineWrap(true);
		reportContext.setWrapStyleWord(true);
		reportContext.setBackground(ColorScheme.DARK_GRAY_COLOR);
		reportContext.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		reportContext.setFont(FontManager.getRunescapeSmallFont());
		JScrollPane reportScroll = new JScrollPane(reportContext);
		reportScroll.setBorder(null);
		// Fixed height, zero preferred width: a text area reports its longest line as preferred
		// width even when wrapping, which would stretch the whole sidebar (audit-panel lesson).
		reportScroll.setPreferredSize(new Dimension(0, 150));
		reportBox.add(reportScroll, BorderLayout.CENTER);

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

		// Refresh + clear moved under the route list (see buildRouteActions).
		JPanel northStack = new JPanel();
		northStack.setLayout(new BoxLayout(northStack, BoxLayout.Y_AXIS));
		northStack.setBackground(ColorScheme.DARK_GRAY_COLOR);
		reportBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		reportBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));
		modeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		northStack.add(reportBox);
		northStack.add(modeRow);
		bottom.add(northStack, BorderLayout.NORTH);

		// The bank-contents warning belongs with the mode buttons it explains (+ Bank mode).
		modeBankWarning.setLayout(new BoxLayout(modeBankWarning, BoxLayout.Y_AXIS));
		modeBankWarning.setBackground(ColorScheme.DARK_GRAY_COLOR);
		modeBankWarning.setBorder(new EmptyBorder(6, 0, 0, 0));
		bottom.add(modeBankWarning, BorderLayout.SOUTH);

		header.add(bottom, BorderLayout.SOUTH);

		updateModeButtons();
		return header;
	}

	/**
	 * Shows the routing context in the copy box under the header, pre-selected so a single
	 * Ctrl+C carries it to the GitHub issue. Stays until the player hides it. EDT only.
	 */
	void showReportContext(String context)
	{
		reportContext.setText(context);
		reportBox.setVisible(true);
		reportBox.revalidate();
		reportContext.requestFocusInWindow();
		reportContext.selectAll();
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
		// The bank container is only populated once the bank has been opened this session; without it
		// Bank mode cannot see banked items (same constraint as Shortest Path itself). This warning
		// lives directly under the mode buttons (it's about "+ Bank" mode), not in the notes strip.
		modeBankWarning.removeAll();
		if (plugin.getRoutesMode() == AlternativeRoutesMode.OWNED_WITH_BANK && !plugin.isBankContentsKnown())
		{
			modeBankWarning.add(buildBanner(RouteIcons.BANNER_WARNING,
				"Bank contents unknown",
				plugin.getGpsConfig().rememberBank()
					? "Open your bank once so banked items can be found. GPS will remember it for future sessions."
					: "Open your bank once so banked items can be found.",
				ColorScheme.PROGRESS_ERROR_COLOR));
		}
		modeBankWarning.setVisible(modeBankWarning.getComponentCount() > 0);
		modeBankWarning.revalidate();
		modeBankWarning.repaint();
		if (status != null)
		{
			notes.add(buildBanner(statusIcon, status, statusAccent));
		}
		// Warning banners are grouped behind a compact "N warnings" row that toggles them, so a
		// stack of notices doesn't permanently crowd the panel. The sync hints (house, spirit
		// trees, balloon logs) live here at the top — inside their (collapsed) sections they were
		// easy to miss.
		List<JPanel> warnings = new ArrayList<>();
		// Running the original Shortest Path plugin alongside GPS doubles the path rendering and
		// the plugin-message integrations (both answer Quest Helper's destinations).
		if (plugin.isShortestPathConflict())
		{
			warnings.add(buildBanner(RouteIcons.BANNER_WARNING,
				"Shortest Path is also enabled",
				"Both plugins draw paths and respond to the same integrations. GPS includes its "
					+ "functionality — disable Shortest Path to avoid doubled rendering.",
				BANNER_WARN_ACCENT));
		}
		// Method toggles no longer recalculate; flag a route list generated with different exclusions.
		if (!cachedCalculating && cachedHasTarget && plugin.isRouteListStale())
		{
			warnings.add(buildBanner(RouteIcons.BANNER_WARNING,
				"Exclusions changed — press \"Refresh routes\" to apply.", BANNER_WARN_ACCENT));
		}
		// Log storage running low at the balloon stations (smart mode, synced, unlocked routes only).
		List<String> lowLogs = plugin.getBalloonLowLogTypes();
		if (!lowLogs.isEmpty())
		{
			warnings.add(buildBalloonLowBanner(lowLogs));
		}
		ShortestPathConfig cfg = plugin.getGpsConfig();
		if (cfg.usePoh() && cfg.pohSmartDetect() && !plugin.isPohScanned())
		{
			warnings.add(buildBanner(RouteIcons.BANNER_WARNING,
				"House furniture not detected",
				"Enter your house once to auto-detect its teleport furniture.",
				BANNER_WARN_ACCENT));
		}
		if (cfg.useSpiritTrees() && cfg.spiritTreeSmartMode() && !plugin.isSpiritTreeSynced())
		{
			warnings.add(buildBanner(RouteIcons.BANNER_WARNING,
				"Planted spirit trees not synced",
				"Open a spirit tree's travel menu once to detect which trees you have planted.",
				BANNER_WARN_ACCENT));
		}
		if (cfg.useHotAirBalloons() && cfg.balloonSmartMode() && !cfg.balloonStorageSynced())
		{
			warnings.add(buildBanner(RouteIcons.BANNER_WARNING,
				"Balloon log storage not synced",
				"Check the Log storage at a balloon station once so flights can be paid from it.",
				BANNER_WARN_ACCENT));
		}
		if (!warnings.isEmpty())
		{
			boolean hidden = cfg.hideWarningBanners();
			if (notes.getComponentCount() > 0)
			{
				notes.add(verticalGap(4));
			}
			notes.add(buildWarningToggleRow(warnings.size(), hidden));
			if (!hidden)
			{
				for (JPanel warning : warnings)
				{
					notes.add(verticalGap(4));
					notes.add(warning);
				}
			}
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

		// The results get a proper section header (like "Travel methods"): the route count, plus
		// a quiet busy note while the generation streams. It lives in its fixed slot above the
		// scroll area so the count and controls stay visible while the cards scroll. Routes are
		// shown as they stream in; the previous list was cleared when this generation started, so
		// only the new routes appear. The highlighted card is the route actually drawn on the map —
		// the explicitly selected one, or route 1 by default.
		resultsHeaderHolder.removeAll();
		if (cachedHasTarget || cachedCalculating || !cachedRoutes.isEmpty())
		{
			resultsHeaderHolder.add(buildResultsHeader(cachedRoutes.size(), cachedCalculating));
		}
		resultsHeaderHolder.revalidate();
		resultsHeaderHolder.repaint();
		RouteOption selected = plugin.getDisplayedRoute();
		for (int i = 0; i < cachedRoutes.size(); i++)
		{
			listPanel.add(buildRouteCard(i, cachedRoutes.get(i), cachedRoutes.get(i) == selected));
			listPanel.add(verticalGap(6));
		}

		listPanel.revalidate();
		listPanel.repaint();
	}

	/**
	 * The results section: a bold orange "Routes (N)" title (with a quiet "calculating…" note while
	 * the generation streams) over a centred control panel — bordered, coloured icon buttons for
	 * more routes (green +), refresh (blue) and clear (red). Tooltips explain each.
	 */
	// The found routes now span more than this multiple of the cheapest — the good options are in,
	// the search is grinding out longer alternatives.
	private static final int LONG_ROUTE_MULTIPLE = 3;

	private static boolean searchingLongerRoutes(List<RouteOption> routes)
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

	private JPanel buildResultsHeader(int count, boolean calculating)
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// Extra top inset separates the routes header from the search controls / notes above it.
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
			// Once the found routes span more than LONG_ROUTE_MULTIPLE x the cheapest, the good ones
			// are all in (and fully usable) — the search is now grinding out longer alternatives, so
			// say so instead of a bare "calculating".
			boolean longer = searchingLongerRoutes(cachedRoutes);
			JLabel busy = new JLabel(longer ? "longer routes…" : "calculating…",
				RouteIcons.BANNER_BUSY, SwingConstants.LEADING);
			busy.setIconTextGap(4);
			busy.setFont(FontManager.getRunescapeSmallFont());
			busy.setForeground(Color.GRAY);
			busy.setToolTipText(longer
				? "Your best routes are ready to use — still searching for longer alternatives"
				: "Calculating routes…");
			titleRow.add(busy, BorderLayout.EAST);
		}
		section.add(titleRow);

		JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		controls.setAlignmentX(Component.LEFT_ALIGNMENT);
		controls.setBorder(new EmptyBorder(6, 0, 0, 0));
		controls.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		if (!calculating && !cachedRoutes.isEmpty() && plugin.canLoadMoreRoutes())
		{
			controls.add(controlButton(RouteIcons.SHOW_MORE, RouteIcons.SHOW_MORE_HOVER,
				"Search for more alternative routes", plugin::loadMoreRoutes));
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
	private JButton controlButton(ImageIcon icon, ImageIcon hover, String tooltip, Runnable action)
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
		// The ETA counts travel + the bank detour; ordering additionally counts preference
		// modifiers (transport type, currency), so a route can be faster yet ranked lower.
		JLabel eta = new JLabel(formatDuration(routeEtaSeconds(route)), RouteIcons.CLOCK, SwingConstants.LEADING);
		eta.setIconTextGap(3);
		eta.setBorder(new EmptyBorder(0, 12, 0, 0));
		eta.setFont(FontManager.getRunescapeBoldFont());
		eta.setForeground(selected ? ColorScheme.BRAND_ORANGE : Color.WHITE);
		eta.setToolTipText("<html>Estimated time, assuming you run"
			+ (route.isViaBank() ? " — includes the bank detour" : "")
			+ ".<br>Includes your cost modifiers — real-world corrections for the clicks and"
			+ "<br>menus a method costs beyond raw travel (charged items, transport type,"
			+ "<br>currency). Routes are ordered by this plus the green/red priority chips.</html>");
		if (!reaches)
		{
			eta.setToolTipText("The target can't be reached — this ends at the closest reachable tile");
		}
		left.add(eta);
		// Explicit-priority chip beside the ETA. Implicit cost modifiers are inside the ETA
		// itself now, so the chip is purely the user's prefer/avoid/walk/bank bias — and list
		// order is always ETA + chip, nothing hidden. Green = ranks as if faster, red = slower.
		int adjustment = plugin.routeAdjustmentSeconds(route);
		if (adjustment != 0)
		{
			JLabel priorityChip = new JLabel((adjustment > 0 ? "+" : "−") + Math.abs(adjustment) + "s");
			priorityChip.setFont(FontManager.getRunescapeSmallFont());
			priorityChip.setBorder(new EmptyBorder(0, 4, 0, 0));
			priorityChip.setForeground(adjustment < 0
				? new Color(70, 200, 90) : ColorScheme.PROGRESS_ERROR_COLOR);
			priorityChip.setToolTipText("Your priority bias — changes this route's position, not its ETA");
			left.add(priorityChip);
		}
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
			methods.add(buildSailRow(totalSail));
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
	 * The route's time in cost units (run-tiles, 0.3s each): the unweighted travel cost plus the
	 * bank-detour cost when the route banks. The bank pickup is real time (walking to a bank and
	 * withdrawing), so it belongs in the ETA — unlike the transport-type and currency modifiers,
	 * which are pure ordering preferences. A negative bank modifier is a "favour banking" preference,
	 * not negative time, so it's clamped out.
	 */
	/**
	 * The ETA is the route's full configured cost: travel time, the bank detour, AND the
	 * implicit cost modifiers (charged items, transport type, currency). The modifiers are
	 * REAL-WORLD corrections, not preferences: tick-optimal cost is a lower bound no human hits,
	 * and interacting with a method (finding the item, its menu, the confirm click) has latency
	 * the raw path math can't see — so the corrected number is the honest estimate. Explicit
	 * priorities stay outside (they're the chip): list order = this ETA + priority chips,
	 * nothing hidden. Static for tests.
	 */
	static int routeEtaUnits(RouteOption route)
	{
		return route.getTotalCost();
	}

	private int routeEtaSeconds(RouteOption route)
	{
		return (int) Math.ceil(routeEtaUnits(route) * gps.pathfinder.CostUnits.SECONDS_PER_UNIT);
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
	private static final Color SAIL_DOT_COLOUR = new Color(0x2E, 0x86, 0xC1);
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
	/** The sailing twin of the walk row: sea-blue dot, total tiles sailed across the route. */
	private JPanel buildSailRow(int tiles)
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
			JLabel statusMarker = statusLabel(status, method);
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
		final IconActionLabel[] excludeHolder = new IconActionLabel[1];
		excludeHolder[0] = new IconActionLabel(
			cardTier == MethodPriority.NORMAL ? RouteIcons.PRIORITY_NEUTRAL_DIM : priorityRestIcon(cardTier),
			priorityHoverIcon(cardTier),
			"Priority: " + cardTier.label + " — click to change",
			() -> showPriorityMenu(excludeHolder[0], method));
		IconActionLabel exclude = excludeHolder[0];
		JPanel actionWrap = new JPanel(new GridBagLayout());
		actionWrap.setOpaque(false);
		actionWrap.setPreferredSize(new Dimension(CONTROL_SIZE, CONTROL_SIZE));
		actionWrap.add(control(exclude));
		row.add(actionWrap, BorderLayout.EAST);

		// Reveal only this row's control on hover — not the whole card.
		final MethodPriority hoverTier = cardTier;
		addHoverRecursively(row, hovered ->
			exclude.setRestIcon(hovered
				? priorityHoverIcon(hoverTier)
				: (hoverTier == MethodPriority.NORMAL
					? RouteIcons.PRIORITY_NEUTRAL_DIM : priorityRestIcon(hoverTier))));
		return row;
	}

	// --- Method priority menu (RimWorld-style tiers; see MethodPriority) ----------------------

	static javax.swing.ImageIcon priorityRestIcon(MethodPriority tier)
	{
		switch (tier)
		{
			case PREFER_1:
				return RouteIcons.PRIORITY_UP_ICONS[0];
			case PREFER_2:
				return RouteIcons.PRIORITY_UP_ICONS[1];
			case PREFER_3:
				return RouteIcons.PRIORITY_UP_ICONS[2];
			case AVOID_1:
				return RouteIcons.PRIORITY_DOWN_ICONS[0];
			case AVOID_2:
				return RouteIcons.PRIORITY_DOWN_ICONS[1];
			case AVOID_3:
				return RouteIcons.PRIORITY_DOWN_ICONS[2];
			case EXCLUDED:
				return RouteIcons.CROSS;
			default:
				return RouteIcons.PRIORITY_NEUTRAL;
		}
	}

	private static javax.swing.ImageIcon priorityHoverIcon(MethodPriority tier)
	{
		switch (tier)
		{
			case PREFER_1:
				return RouteIcons.PRIORITY_UP_HOVER_ICONS[0];
			case PREFER_2:
				return RouteIcons.PRIORITY_UP_HOVER_ICONS[1];
			case PREFER_3:
				return RouteIcons.PRIORITY_UP_HOVER_ICONS[2];
			case AVOID_1:
				return RouteIcons.PRIORITY_DOWN_HOVER_ICONS[0];
			case AVOID_2:
				return RouteIcons.PRIORITY_DOWN_HOVER_ICONS[1];
			case AVOID_3:
				return RouteIcons.PRIORITY_DOWN_HOVER_ICONS[2];
			case EXCLUDED:
				return RouteIcons.CROSS_HOVER;
			default:
				return RouteIcons.PRIORITY_NEUTRAL_HOVER;
		}
	}

	private static String priorityTooltip(String label, MethodPriority tier)
	{
		String state = tier == MethodPriority.NORMAL
			? "Normal priority"
			: tier.label + (tier.chipText().isEmpty() ? "" : " (" + tier.chipText() + " on ranking)");
		return "<html><b>" + escapeHtml(label) + "</b>: " + state
			+ "<br>Click for priority options — prefer/avoid shift the ranking, exclude removes it.</html>";
	}

	/**
	 * The tier context menu. Preferences re-rank the current list instantly (no recalculation);
	 * Exclude keeps its existing semantics (applies on the next refresh). {@code walkMode} swaps
	 * the target: tiers set the walking preference instead of a method tier, and Exclude is
	 * omitted (walking can't be excluded).
	 */
	private void showPriorityMenu(Component anchor, TeleportMethod method)
	{
		javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
		MethodPriority current = plugin.getMethodPriority(method);
		for (MethodPriority tier : new MethodPriority[]{
			MethodPriority.PREFER_3, MethodPriority.PREFER_2, MethodPriority.PREFER_1,
			MethodPriority.NORMAL,
			MethodPriority.AVOID_1, MethodPriority.AVOID_2, MethodPriority.AVOID_3})
		{
			String text = tier.label + (tier.chipText().isEmpty() ? "" : "  " + tier.chipText());
			javax.swing.JMenuItem entry = new javax.swing.JMenuItem(text, priorityRestIcon(tier));
			entry.setFont(tier == current
				? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeSmallFont());
			entry.addActionListener(e -> plugin.setMethodPriority(method, tier));
			menu.add(entry);
		}
		menu.addSeparator();
		javax.swing.JMenuItem exclude = new javax.swing.JMenuItem(
			MethodPriority.EXCLUDED.label, RouteIcons.CROSS);
		exclude.setFont(current == MethodPriority.EXCLUDED
			? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeSmallFont());
		exclude.addActionListener(e -> plugin.setMethodPriority(method, MethodPriority.EXCLUDED));
		menu.add(exclude);
		menu.show(anchor, 0, anchor.getHeight());
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
		// The rebuild replaces the method-rows scroll pane; carry its position over so toggling a
		// method or category (which regenerates routes and re-renders) doesn't jump the list back
		// to the top. Applied after the rebuilt pane has been laid out (nested invokeLater), since
		// a fresh scrollbar clamps everything to 0 until validation.
		final int rowsScrollPosition = catalogRowsScroll != null
			? catalogRowsScroll.getVerticalScrollBar().getValue() : 0;
		catalogHolder.removeAll();
		catalogHolder.add(buildTravelSection());
		catalogHolder.revalidate();
		catalogHolder.repaint();
		if (rowsScrollPosition > 0 && catalogRowsScroll != null)
		{
			final JScrollPane scroll = catalogRowsScroll;
			SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(
				() -> scroll.getVerticalScrollBar().setValue(rowsScrollPosition)));
		}
		renderedCatalog = cachedCatalog;
		renderedExclusions = cachedExclusions;
		renderedUnavailable = cachedUnavailable;
		renderedCatalogExpanded = catalogExpanded;
	}

	/**
	 * Rebuilds the configuration sections after one of their mirrored config keys changed outside
	 * the panel (the RuneLite config UI, or the balloon chat parser updating stored log counts).
	 * A full render follows so the notes strip (the Log storage low banner) tracks the change too.
	 */
	public void refreshConfigSections()
	{
		refreshCatalog();
		render();
	}

	/**
	 * Collapsible shell shared by the configuration sections: a clickable header row (chevron,
	 * title, colored state text) that flips the given expanded flag. The caller adds the body when
	 * expanded.
	 */
	private JPanel configSectionShell(String title, String tooltip, boolean expanded, Runnable toggle,
		String stateText, Color stateColor)
	{
		return configSectionShell(title, tooltip, expanded, toggle, stateText, stateColor, false);
	}

	/**
	 * As above; {@code headline} styles the title like the panel's top-level section headers
	 * (bold, brand orange) — used by the "Travel options" section that groups the others.
	 */
	private JPanel configSectionShell(String title, String tooltip, boolean expanded, Runnable toggle,
		String stateText, Color stateColor, boolean headline)
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		section.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel titleRow = new JPanel(new BorderLayout(5, 0));
		titleRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		titleRow.setBorder(new EmptyBorder(0, 0, 4, 0));
		titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		titleRow.add(control(new JLabel(expanded ? RouteIcons.CHEVRON_DOWN : RouteIcons.CHEVRON_RIGHT)),
			BorderLayout.WEST);
		JLabel titleLabel = new JLabel(title);
		if (headline)
		{
			titleLabel.setFont(FontManager.getRunescapeBoldFont());
			titleLabel.setForeground(ColorScheme.BRAND_ORANGE);
		}
		else
		{
			titleLabel.setForeground(Color.WHITE);
		}
		titleRow.add(titleLabel, BorderLayout.CENTER);
		JLabel state = new JLabel(stateText);
		state.setForeground(stateColor);
		titleRow.add(state, BorderLayout.EAST);
		titleRow.setToolTipText(tooltip);
		titleRow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		titleRow.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				toggle.run();
				refreshCatalog();
			}
		});
		section.add(titleRow);
		return section;
	}

	/**
	 * The "Travel options" section: one collapsible home for everything routing may use — the
	 * player-stated configuration (POH, wilderness, balloons) and the teleport-methods catalog —
	 * all sharing the same header style.
	 */
	private JPanel buildTravelSection()
	{
		int enabled = 0;
		for (TeleportMethod method : cachedCatalog)
		{
			if (isUsable(method) && !cachedExclusions.contains(method))
			{
				enabled++;
			}
		}
		JPanel section = configSectionShell("Travel options",
			"Everything routing may use: your house, wilderness policy, bank, balloons and the travel methods",
			travelSectionExpanded, () -> travelSectionExpanded = !travelSectionExpanded,
			cachedCatalog.isEmpty() ? "" : enabled + "/" + cachedCatalog.size(),
			ColorScheme.LIGHT_GRAY_COLOR, true);
		if (!travelSectionExpanded)
		{
			catalogRowsPanel = null;
			catalogRowsScroll = null;
			return section;
		}

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.setBorder(new EmptyBorder(0, 8, 0, 0));
		body.add(buildPohSection());
		body.add(buildWildernessSection());
		body.add(buildWalkingSection());
		body.add(buildBankSection());
		body.add(buildBalloonSection());
		body.add(buildSailingSection());
		body.add(buildSpiritTreeSection());
		if (!cachedCatalog.isEmpty())
		{
			body.add(buildCatalogSection());
		}
		section.add(body);
		return section;
	}

	/** The body box of an expanded configuration section. */
	private static JPanel configSectionBody()
	{
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		body.setBorder(new EmptyBorder(4, 6, 6, 6));
		body.setAlignmentX(Component.LEFT_ALIGNMENT);
		return body;
	}

	/**
	 * A status line at the top of a configuration section body ("Your house: …", "Bank contents:
	 * …"). HTML-wrapped so text longer than the narrow panel wraps instead of clipping to "…".
	 */
	private static JLabel configStatusLabel(String text, Color color)
	{
		JLabel label = new JLabel("<html>" + text + "</html>");
		label.setForeground(color);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		label.setBorder(new EmptyBorder(0, 0, 4, 0));
		return label;
	}

	/** A small wrapped note line inside a configuration section body. */
	private static JLabel configNote(String text, Color color)
	{
		JLabel note = new JLabel("<html>" + text + "</html>");
		note.setFont(FontManager.getRunescapeSmallFont());
		note.setForeground(color);
		note.setAlignmentX(Component.LEFT_ALIGNMENT);
		note.setBorder(new EmptyBorder(2, 18, 2, 0));
		return note;
	}

	/**
	 * The compact row heading the notes strip's warning group: a warning glyph, the count, and a
	 * chevron. Clicking it hides the banners below (leaving just this row as the reminder that
	 * warnings exist) or shows them again; the choice persists in config.
	 */
	private JPanel buildWarningToggleRow(int count, boolean hidden)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(3, 8, 3, 8));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel label = new JLabel(count + (count == 1 ? " warning" : " warnings") + (hidden ? " hidden" : ""),
			RouteIcons.BANNER_WARNING, JLabel.LEFT);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		row.add(label, BorderLayout.WEST);
		row.add(new JLabel(hidden ? RouteIcons.CHEVRON_RIGHT : RouteIcons.CHEVRON_DOWN), BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setToolTipText(hidden ? "Show the warnings" : "Hide the warnings (the count stays visible)");
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				plugin.setPanelConfig("hideWarningBanners", !hidden);
				render();
			}
		});
		return row;
	}

	/**
	 * An attention note inside a configuration section rendered as a warning banner (amber accent
	 * bar + warning glyph), matching the panel's other banners — used for the "needs a sync" hints.
	 */
	private JPanel configWarningBanner(String text)
	{
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		wrap.setBorder(new EmptyBorder(3, 0, 1, 0));
		JPanel banner = buildBanner(RouteIcons.BANNER_WARNING, text, BANNER_WARN_ACCENT);
		wrap.add(banner, BorderLayout.CENTER);
		wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, banner.getPreferredSize().height + 4));
		return wrap;
	}

	/**
	 * The player-owned-house declarations: which POH teleport features GPS should assume exist.
	 * Unlike the catalog's include/exclude (what the user WANTS used), these describe what is BUILT
	 * in the house — facts GPS cannot detect from outside the house, so the player states them once.
	 * The controls mirror the plugin's config items (same keys, kept in sync through the
	 * ConfigManager); any change regenerates the current routes.
	 */
	private JPanel buildPohSection()
	{
		final boolean pohOn = plugin.getGpsConfig().usePoh();
		JPanel section = configSectionShell("Player-owned house",
			"Declare which teleport features are built in your house so routes can use them",
			pohSectionExpanded, () -> pohSectionExpanded = !pohSectionExpanded,
			pohOn ? "on" : "off",
			pohOn ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		if (!pohSectionExpanded)
		{
			return section;
		}

		JPanel body = configSectionBody();

		// What GPS detected on its own: the house location (varbit) — a confidence hint that the
		// location-gated entries/exits will resolve correctly.
		String house = plugin.getHouseLocationName();
		body.add(configStatusLabel(house != null
				? "Your house: " + house
				: "No house detected (log in, or you don't own one)",
			house != null ? ColorScheme.LIGHT_GRAY_COLOR : ColorScheme.MEDIUM_GRAY_COLOR));

		JCheckBox master = configCheckBox("Use my house for routes", pohOn,
			"Master switch: with this off, no POH teleport is ever routed",
			v -> plugin.setPanelConfig("usePoh", v));
		body.add(iconRow("house_portal", 0, master));

		// Smart detection: while inside your house GPS auto-fills the furniture it can recognise.
		final boolean smartDetect = plugin.getGpsConfig().pohSmartDetect();
		JCheckBox smartBox = configCheckBox("Auto-detect furniture", smartDetect,
			"<html><body style='width:220px'>While you are inside your house, fill the checkboxes below"
				+ " from the furniture GPS recognises — jewellery box, fairy ring, spirit tree and"
				+ " obelisk. It only ever ticks boxes (never unticks), and you can still edit any of"
				+ " them.<br><br>Portals &amp; nexus and mounted items can't be auto-detected — set those"
				+ " yourself.</body></html>",
			v -> plugin.setPanelConfig("pohSmartDetect", v));
		body.add(iconRow("house_portal", 18, smartBox));
		if (smartDetect)
		{
			List<String> detected = plugin.getDetectedPohFurniture();
			if (!plugin.isPohScanned())
			{
				body.add(configWarningBanner("Enter your house once to auto-detect its furniture."));
			}
			else if (detected.isEmpty())
			{
				body.add(configNote("No auto-detectable furniture found in your house.",
					ColorScheme.MEDIUM_GRAY_COLOR));
			}
			else
			{
				body.add(configNote("Detected: " + String.join(", ", detected),
					ColorScheme.LIGHT_GRAY_COLOR));
			}
		}

		JPanel tierInner = new JPanel(new BorderLayout(5, 0));
		tierInner.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JLabel tierLabel = new JLabel("Jewellery box:");
		tierLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		tierInner.add(tierLabel, BorderLayout.WEST);
		JComboBox<JewelleryBoxTier> tierBox = new JComboBox<>(JewelleryBoxTier.values());
		tierBox.setSelectedItem(plugin.getGpsConfig().pohJewelleryBoxTier());
		tierBox.setEnabled(pohOn);
		tierBox.setToolTipText("The tier built in your house (each tier includes the ones below it)");
		tierBox.addActionListener(e -> plugin.setPanelConfig("pohJewelleryBoxTier", tierBox.getSelectedItem()));
		tierInner.add(tierBox, BorderLayout.CENTER);
		body.add(iconRow("jewellery_box", 18, tierInner));

		JCheckBox portals = configCheckBox("Teleport portals & nexus", plugin.getGpsConfig().useTeleportationPortalsPoh(),
			"Portal chamber and portal nexus destinations",
			v -> plugin.setPanelConfig("useTeleportationPortalsPoh", v));
		JCheckBox mounted = configCheckBox("Mounted items", plugin.getGpsConfig().usePohMountedItems(),
			"Mounted glory, Xeric's talisman, digsite pendant, mythical cape",
			v -> plugin.setPanelConfig("usePohMountedItems", v));
		JCheckBox fairy = configCheckBox("Fairy ring", plugin.getGpsConfig().usePohFairyRing(),
			"Requires 85 Construction to build",
			v -> plugin.setPanelConfig("usePohFairyRing", v));
		JCheckBox spirit = configCheckBox("Spirit tree", plugin.getGpsConfig().usePohSpiritTree(),
			"Requires 75 Construction and 83 Farming to build",
			v -> plugin.setPanelConfig("usePohSpiritTree", v));
		JCheckBox obelisk = configCheckBox("Wilderness obelisk", plugin.getGpsConfig().usePohObelisk(),
			"Requires 80 Construction to build",
			v -> plugin.setPanelConfig("usePohObelisk", v));
		String[] icons = {"portal_chamber", "mounted_glory", "fairy_ring", "spirit_tree", "obelisk"};
		JCheckBox[] boxes = {portals, mounted, fairy, spirit, obelisk};
		for (int i = 0; i < boxes.length; i++)
		{
			boxes[i].setEnabled(pohOn);
			body.add(iconRow(icons[i], 18, boxes[i]));
		}

		section.add(body);
		return section;
	}

	/** The wilderness travel policy: whether routes may cross the wilderness at all. */
	private JPanel buildWildernessSection()
	{
		final boolean avoid = plugin.getGpsConfig().avoidWilderness();
		JPanel section = configSectionShell("Wilderness",
			"Choose whether routes may cross the wilderness",
			wildernessSectionExpanded, () -> wildernessSectionExpanded = !wildernessSectionExpanded,
			avoid ? "avoided" : "allowed",
			avoid ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_INPROGRESS_COLOR);
		if (!wildernessSectionExpanded)
		{
			return section;
		}

		JPanel body = configSectionBody();
		body.add(configCheckBox("Avoid the wilderness", avoid,
			"Route around the wilderness whenever possible (e.g. skip the Edgeville lever to Ardougne)",
			v -> plugin.setPanelConfig("avoidWilderness", v)));
		body.add(configNote("Routes still enter the wilderness when the destination itself is inside it.",
			ColorScheme.MEDIUM_GRAY_COLOR));
		section.add(body);
		return section;
	}

	/**
	 * The seconds chip for a Travel-options section header, in the SAME sign convention as the
	 * route cards' adjustment chips: green −15s = "ranks as if 15s cheaper". A preference of
	 * +15s therefore displays as −15s — showing the raw preference read as a surcharge.
	 */
	private static String biasChip(int preferenceSeconds)
	{
		if (preferenceSeconds == 0)
		{
			return "neutral";
		}
		int adjustment = -preferenceSeconds;
		return (adjustment > 0 ? "+" : "−") + Math.abs(adjustment) + "s";
	}

	private static Color biasColor(int seconds)
	{
		if (seconds == 0)
		{
			return ColorScheme.LIGHT_GRAY_COLOR;
		}
		return seconds > 0 ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_INPROGRESS_COLOR;
	}

	/** A "Bias (seconds)" spinner row: −120..120 in steps of 5, wired to the given setter. */
	private JPanel biasSpinnerRow(String caption, String tooltip, int value,
		java.util.function.IntConsumer setter)
	{
		JPanel row = new JPanel(new BorderLayout(5, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel label = new JLabel(caption);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setToolTipText(tooltip);
		row.add(label, BorderLayout.CENTER);
		JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, -120, 120, 5));
		spinner.setToolTipText(tooltip);
		spinner.addChangeListener(e -> setter.accept((Integer) spinner.getValue()));
		row.add(spinner, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, spinner.getPreferredSize().height + 4));
		return row;
	}

	/**
	 * Walking as a travel POLICY (not a method): a seconds bias with finer control than the
	 * method tiers. Positive = walking keeps the top spot unless a method beats it by more than
	 * the bias; negative penalises the pure-walk route the same way.
	 */
	private JPanel buildWalkingSection()
	{
		int bias = plugin.getWalkPreferenceSeconds();
		JPanel section = configSectionShell("Walking",
			"How plain walking ranks against travel methods",
			walkingSectionExpanded, () -> walkingSectionExpanded = !walkingSectionExpanded,
			biasChip(bias), biasColor(bias));
		if (!walkingSectionExpanded)
		{
			return section;
		}
		JPanel body = configSectionBody();
		body.add(configStatusLabel(bias == 0
				? "No preference — routes rank purely by time."
				: (bias > 0
					? "Walking wins unless a method is more than " + bias + "s faster."
					: "Walking is ranked as if " + (-bias) + "s slower."),
			ColorScheme.LIGHT_GRAY_COLOR));
		body.add(biasSpinnerRow("Prefer walking by (s):",
			"<html>Ranking bias for the pure-walk route, in seconds.<br>"
				+ "Positive: walking keeps the top spot unless a method beats it by more.<br>"
				+ "Negative: walking ranks as if slower. Changes re-sort instantly.</html>",
			bias, v ->
			{
				plugin.setWalkPreferenceSeconds(v);
				SwingUtilities.invokeLater(this::refreshConfigSections);
			}));
		body.add(configNote("Ranking only — the walk route's ETA and path never change.",
			ColorScheme.MEDIUM_GRAY_COLOR));
		section.add(body);
		return section;
	}

	/**
	 * Bank memory: whether GPS saves the bank's contents when the bank closes and restores them at
	 * login, so "+ Bank" routes and in-bank availability work without opening the bank first. GPS
	 * only ever sees the bank while it's open — this fills the gap between sessions.
	 */
	private JPanel buildBankSection()
	{
		final boolean remember = plugin.getGpsConfig().rememberBank();
		// The header chip is the ranking bias (same convention as Walking and the card chips);
		// the remember-between-sessions state is a detail inside the body.
		int headerBias = plugin.getBankPreferenceSeconds();
		JPanel section = configSectionShell("Bank",
			"How bank-detour routes rank, and remembering your bank between sessions",
			bankSectionExpanded, () -> bankSectionExpanded = !bankSectionExpanded,
			biasChip(headerBias), biasColor(headerBias));
		if (!bankSectionExpanded)
		{
			return section;
		}

		JPanel body = configSectionBody();

		// What GPS currently knows, and where that knowledge came from — a restored snapshot can be
		// stale if the bank changed on another client since it was saved.
		String state;
		Color stateColor;
		if (!plugin.isBankContentsKnown())
		{
			state = "Bank contents: unknown — open your bank once";
			stateColor = ColorScheme.MEDIUM_GRAY_COLOR;
		}
		else if (plugin.isBankRestored())
		{
			state = "Bank contents: restored from your last session";
			stateColor = ColorScheme.LIGHT_GRAY_COLOR;
		}
		else
		{
			state = "Bank contents: seen this session";
			stateColor = ColorScheme.LIGHT_GRAY_COLOR;
		}
		body.add(configStatusLabel(state, stateColor));

		body.add(configCheckBox("Remember between sessions", remember,
			"<html><body style='width:220px'>Save a snapshot of your bank each time you close it, and"
				+ " load it back at login — so \"+ Bank\" routes can see banked items without opening"
				+ " the bank first. Saved per character in your RuneLite profile.</body></html>",
			v -> plugin.setPanelConfig("rememberBank", v)));
		body.add(configNote("Opening the bank always refreshes the snapshot; turning this off deletes it.",
			ColorScheme.MEDIUM_GRAY_COLOR));

		// Ranking bias for via-bank routes — finer control than the method tiers, and separate
		// from the withdrawal time already counted inside those routes' ETAs.
		int bankBias = plugin.getBankPreferenceSeconds();
		body.add(biasSpinnerRow("Prefer bank routes by (s):",
			"<html>Ranking bias for routes that detour via a bank, in seconds.<br>"
				+ "Positive: bank routes rank as if faster; negative: as if slower.<br>"
				+ "Separate from the withdrawal time, which is already in their ETA.</html>",
			bankBias, v ->
			{
				plugin.setBankPreferenceSeconds(v);
				SwingUtilities.invokeLater(this::refreshConfigSections);
			}));
		section.add(body);
		return section;
	}

	/**
	 * Balloon travel: the master toggle plus smart mode, which tracks the stations' log storage
	 * from chat so flights can be paid from storage — including a low-storage warning (threshold
	 * configurable; only routes the player has unlocked are considered) and a first-time sync hint,
	 * since the counts only become known once a storage message has been seen.
	 */
	private JPanel buildBalloonSection()
	{
		final ShortestPathConfig config = plugin.getGpsConfig();
		final boolean balloonsOn = config.useHotAirBalloons();
		final boolean smart = config.balloonSmartMode();
		List<String> lowTypes = plugin.getBalloonLowLogTypes();

		String stateText = !balloonsOn ? "off" : (lowTypes.isEmpty() ? "on" : "low logs");
		Color stateColor = !balloonsOn ? ColorScheme.LIGHT_GRAY_COLOR
			: (lowTypes.isEmpty() ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_INPROGRESS_COLOR);
		JPanel section = configSectionShell("Hot air balloons",
			"Balloon travel and smart tracking of the stations' Log storage",
			balloonSectionExpanded, () -> balloonSectionExpanded = !balloonSectionExpanded,
			stateText, stateColor);
		if (!balloonSectionExpanded)
		{
			return section;
		}

		JPanel body = configSectionBody();

		JCheckBox master = configCheckBox("Use balloon routes", balloonsOn,
			"<html><body style='width:220px'>Master switch: include hot air balloon flights in routes"
				+ " (requires Enlightened Journey).<br><br>Each flight consumes one log of its"
				+ " destination's type, paid from your inventory or from the stations' Log"
				+ " storage.</body></html>",
			v -> plugin.setPanelConfig("useHotAirBalloons", v));
		body.add(master);

		JCheckBox smartBox = configCheckBox("Smart Log storage", smart,
			"<html><body style='width:220px'>Detect and keep track of the logs in the stations' Log"
				+ " storage (read from chat messages); flights can then be paid from storage without"
				+ " carrying logs.<br><br>When off, GPS ignores the Log storage: a flight is only"
				+ " routed while you carry its log type (the All modes assume flights are available"
				+ " either way).</body></html>",
			v -> plugin.setPanelConfig("balloonSmartMode", v));
		smartBox.setEnabled(balloonsOn);
		smartBox.setBorder(new EmptyBorder(2, 18, 2, 0));
		body.add(smartBox);

		JPanel warnRow = new JPanel(new BorderLayout(5, 0));
		warnRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		warnRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		warnRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		warnRow.setBorder(new EmptyBorder(2, 28, 2, 0));
		String warnTooltip = "Warn when an unlocked route's Log storage count falls below this (0 = never warn)";
		// Deliberately terse — the full wording clipped at this indent on the sidebar's width.
		JLabel warnLabel = new JLabel("Warn below:");
		warnLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		warnLabel.setToolTipText(warnTooltip);
		warnRow.add(warnLabel, BorderLayout.CENTER);
		JSpinner warnSpinner = new JSpinner(
			new SpinnerNumberModel(config.balloonLogWarningThreshold(), 0, 100, 1));
		warnSpinner.setEnabled(balloonsOn && smart);
		warnSpinner.setToolTipText(warnTooltip);
		warnSpinner.addChangeListener(e -> plugin.setPanelConfig("balloonLogWarningThreshold", warnSpinner.getValue()));
		warnRow.add(warnSpinner, BorderLayout.EAST);
		body.add(warnRow);

		if (balloonsOn && smart)
		{
			if (!config.balloonStorageSynced())
			{
				body.add(configWarningBanner("Not synced yet — check the Log storage at a balloon station"
					+ " once to import your stored log counts."));
			}
			else
			{
				body.add(configNote("Log storage:", ColorScheme.LIGHT_GRAY_COLOR));
				int[] counts = plugin.getBalloonStoredCounts();
				JPanel storageRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
				storageRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				storageRow.setAlignmentX(Component.LEFT_ALIGNMENT);
				storageRow.setBorder(new EmptyBorder(0, 14, 2, 0));
				for (int i = 0; i < counts.length; i++)
				{
					storageRow.add(logIcon(i, counts[i]));
				}
				storageRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, storageRow.getPreferredSize().height));
				body.add(storageRow);
			}
		}

		section.add(body);
		return section;
	}

	/**
	 * Planted spirit trees: ONLY the five farmable patches — the permanent spirit trees are always
	 * available and toggled in the Travel methods catalog like every other method. Smart tracking
	 * reads the travel menu to learn which farmable trees you have grown; the detected list shows
	 * here (or a sync hint, since GPS can't see a farming patch until the menu has been opened).
	 */
	private JPanel buildSailingSection()
	{
		final ShortestPathConfig config = plugin.getGpsConfig();
		final boolean sailingOn = config.useSailing();
		JPanel section = configSectionShell("Sailing (beta)",
			"Sail your own boat between mooring points and port berths",
			sailingSectionExpanded, () -> sailingSectionExpanded = !sailingSectionExpanded,
			sailingOn ? "on" : "off",
			sailingOn ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		if (!sailingSectionExpanded)
		{
			return section;
		}

		JPanel body = configSectionBody();
		JCheckBox master = configCheckBox("Use sailing routes", sailingOn,
			"<html><body style='width:220px'>Master switch: include sailing your own boat between"
				+ " mooring points and port berths.<br><br>Assumes you own a boat; travel times"
				+ " assume a mid-tier hull speed. Where routes may board is governed by your boat's"
				+ " detected berth and the Summon Boat assumption below.</body></html>",
			v -> plugin.setPanelConfig("useSailing", v));
		body.add(master);
		JCheckBox abandon = configCheckBox("Teleports may abandon the boat",
			config.sailingTeleportAbandon(),
			"<html><body style='width:220px'>Aboard, teleport routes leave the boat where it"
				+ " floats.<br><br>Off: routes from the water only disembark at moorings and port"
				+ " berths — the boat is never left at sea.</body></html>",
			v -> plugin.setPanelConfig("sailingTeleportAbandon", v));
		abandon.setEnabled(sailingOn);
		abandon.setBorder(new EmptyBorder(2, 18, 2, 0));
		body.add(abandon);

		JCheckBox summon = configCheckBox("Assume Summon Boat spell",
			plugin.getGpsConfig().sailingAssumeSummon(),
			"<html><body style='width:220px'>Routes may board at ANY mooring — the boat is"
				+ " summoned there first (56 Magic, Pandemonium, teleport focus).<br><br>Off:"
				+ " sailing legs start only where a boat is actually moored, and Teleport to"
				+ " Boat (67 Magic, greater focus) covers the distance.</body></html>",
			v -> plugin.setPanelConfig("sailingAssumeSummon", v));
		summon.setEnabled(sailingOn);
		summon.setBorder(new EmptyBorder(2, 18, 2, 0));
		body.add(summon);

		// Latest known berths: live varbits once seen this session, the stored snapshot
		// from the last one before that.
		List<String[]> banner = plugin.getBoatBanner();
		String berths;
		if (banner == null)
		{
			berths = "No boat seen yet — berths appear after login.";
		}
		else if (banner.isEmpty())
		{
			berths = "No owned boat detected.";
		}
		else
		{
			StringBuilder sb = new StringBuilder();
			for (String[] row : banner)
			{
				sb.append(sb.length() > 0 ? "<br>" : "").append("⛵ ").append(row[0])
					.append(" <font color='#9E9E9E'>— ").append(row[1]).append("</font>");
			}
			if (!plugin.isBoatBannerLive())
			{
				sb.append("<br><font color='#9E9E9E'>(from last session)</font>");
			}
			berths = sb.toString();
		}
		JLabel berthLabel = wrappedLabel(berths);
		berthLabel.setBorder(new EmptyBorder(4, 18, 2, 0));
		body.add(berthLabel);

		section.add(body);
		return section;
	}

	private JPanel buildSpiritTreeSection()
	{
		final boolean smart = plugin.getGpsConfig().spiritTreeSmartMode();
		List<String> planted = smart && plugin.isSpiritTreeSynced()
			? plugin.getAvailablePlantedSpiritTrees() : List.of();

		String stateText = !smart ? "all" : (plugin.isSpiritTreeSynced()
			? (planted.isEmpty() ? "none" : planted.size() + " planted") : "on");
		Color stateColor = smart && !planted.isEmpty()
			? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.LIGHT_GRAY_COLOR;
		JPanel section = configSectionShell("Planted spirit trees",
			"Smart detection of the farmable spirit trees you have grown (permanent trees are in Travel methods)",
			spiritTreeSectionExpanded, () -> spiritTreeSectionExpanded = !spiritTreeSectionExpanded,
			stateText, stateColor);
		if (!spiritTreeSectionExpanded)
		{
			return section;
		}

		JPanel body = configSectionBody();

		JCheckBox smartBox = configCheckBox("Smart tracking", smart,
			"<html><body style='width:220px'>Detect which farmable spirit trees you have planted and"
				+ " grown (read from the travel menu) and route only through those.<br><br>When off, all"
				+ " farmable spirit trees are assumed available — the Spirit trees category in Travel"
				+ " methods still turns them on or off.</body></html>",
			v -> plugin.setPanelConfig("spiritTreeSmartMode", v));
		body.add(iconRow("spirit_tree", 0, smartBox));

		if (!smart)
		{
			body.add(configNote("All farmable spirit trees assumed available.",
				ColorScheme.MEDIUM_GRAY_COLOR));
		}
		else if (!plugin.isSpiritTreeSynced())
		{
			body.add(configWarningBanner("Not synced yet — open a spirit tree travel menu once to detect"
				+ " your planted trees."));
		}
		else if (planted.isEmpty())
		{
			body.add(configNote("No planted spirit trees detected.", ColorScheme.MEDIUM_GRAY_COLOR));
		}
		else
		{
			body.add(configNote("Detected:", ColorScheme.LIGHT_GRAY_COLOR));
			for (String name : planted)
			{
				JLabel label = new JLabel(name);
				label.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
				label.setFont(FontManager.getRunescapeSmallFont());
				body.add(iconRow("spirit_tree", 18, label));
			}
		}

		section.add(body);
		return section;
	}

	/**
	 * A POH construction icon (bundled OSRS-wiki furniture images under resources/poh/), scaled to
	 * fit the row height and centred in a fixed-width slot so the row labels align.
	 */
	private static JLabel pohIcon(String name)
	{
		JLabel label = new JLabel();
		label.setPreferredSize(new Dimension(26, 20));
		label.setHorizontalAlignment(SwingConstants.CENTER);
		BufferedImage img = ImageUtil.loadImageResource(ShortestPathPanel.class, "/poh/" + name + ".png");
		double scale = Math.min(1.0, Math.min(26.0 / img.getWidth(), 20.0 / img.getHeight()));
		if (scale < 1.0)
		{
			img = ImageUtil.resizeImage(img,
				(int) Math.round(img.getWidth() * scale), (int) Math.round(img.getHeight() * scale));
		}
		label.setIcon(new ImageIcon(img));
		return label;
	}

	/** A configuration row decorated with a small icon to the left of its control. */
	private JPanel iconRow(String pohIconName, int leftInset, JComponent control)
	{
		JPanel row = new JPanel(new BorderLayout(5, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		row.setBorder(new EmptyBorder(2, leftInset, 2, 0));
		row.add(pohIcon(pohIconName), BorderLayout.WEST);
		row.add(control, BorderLayout.CENTER);
		return row;
	}

	/**
	 * An item icon for one Log storage log type, with the stored count drawn as the stack quantity
	 * (the same rendering the inventory uses) and spelled out in the tooltip.
	 */
	private JLabel logIcon(int typeIndex, int count)
	{
		JLabel icon = new JLabel();
		icon.setToolTipText(count + " " + BalloonLogStorage.TYPE_NAMES[typeIndex] + " logs in storage");
		plugin.getItemManager().getImage(BalloonLogStorage.ITEM_IDS[typeIndex], count, true).addTo(icon);
		return icon;
	}

	/**
	 * The Log-storage-low warning banner: like the bank warning, it lives in the notes strip so it
	 * is visible even while the balloon section is collapsed. Shows the low types as item icons.
	 */
	private JPanel buildBalloonLowBanner(List<String> lowTypes)
	{
		JPanel banner = buildBanner(RouteIcons.BANNER_WARNING,
			"Log storage low", "Restock logs at a balloon station:", BANNER_WARN_ACCENT);
		int[] counts = plugin.getBalloonStoredCounts();
		JPanel icons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		icons.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		for (int i = 0; i < BalloonLogStorage.TYPE_NAMES.length; i++)
		{
			if (lowTypes.contains(BalloonLogStorage.TYPE_NAMES[i]))
			{
				icons.add(logIcon(i, counts[i]));
			}
		}
		banner.add(icons, BorderLayout.SOUTH);
		banner.setMaximumSize(new Dimension(Integer.MAX_VALUE, banner.getPreferredSize().height));
		return banner;
	}

	/** A configuration checkbox: writes its config key on change; the ConfigChanged regenerates. */
	private JCheckBox configCheckBox(String label, boolean value, String tooltip,
		java.util.function.Consumer<Boolean> onChange)
	{
		JCheckBox box = new JCheckBox(label, value);
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		box.setToolTipText(tooltip);
		box.setAlignmentX(Component.LEFT_ALIGNMENT);
		box.setFocusPainted(false);
		// The look-and-feel's box is nearly invisible on the dark background — use the catalog's
		// toggle glyphs instead (green check = on, grey cross = off, red on hover), dimmed while
		// disabled.
		box.setIcon(RouteIcons.CROSS);
		box.setRolloverIcon(RouteIcons.CROSS_HOVER);
		box.setSelectedIcon(RouteIcons.CHECK);
		box.setRolloverSelectedIcon(RouteIcons.CHECK_HOVER);
		box.setDisabledIcon(RouteIcons.CROSS_DIM);
		box.setDisabledSelectedIcon(RouteIcons.CHECK_DIM);
		box.addActionListener(e -> onChange.accept(box.isSelected()));
		return box;
	}

	private JPanel buildCatalogSection()
	{
		// The headline count is the methods a search can ACTUALLY use: usable right now (not
		// missing an item/level/quest/unlock) AND not excluded — so it responds to the toggles.
		// Broken down into permanent (unlimited use) and charged (consumes a charge or the item
		// itself — tabs, charged jewellery).
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
		// Same collapsible shell as the other Travel options sub-sections; the enabled count is
		// the state text.
		JPanel section = configSectionShell("Travel methods",
			enabled + " enabled (usable and included) · " + usable + " usable now · "
				+ included + " included in searches · " + cachedCatalog.size() + " total",
			catalogExpanded, () -> catalogExpanded = !catalogExpanded,
			enabled + "/" + cachedCatalog.size(), ColorScheme.LIGHT_GRAY_COLOR);

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
		// Chevron on the left (matching the section headers above), the include/exclude toggle at
		// the row's right edge — with the toggle up front it sat exactly where users click to
		// expand, so category toggles kept getting flipped by accident.
		row.add(control(new JLabel(expanded ? RouteIcons.CHEVRON_DOWN : RouteIcons.CHEVRON_RIGHT)),
			BorderLayout.WEST);

		String count = allIncluded
			? " (" + items.size() + ")"
			: " (" + (items.size() - excludedCount) + "/" + items.size() + ")";
		JLabel name = new JLabel(category + count);
		name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		row.add(name, BorderLayout.CENTER);

		row.add(control(new IconActionLabel(icon, hover, tip, action)), BorderLayout.EAST);

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

		// Priority control (replaces the old include/exclude toggle): the icon shows the current
		// tier — check = normal, stacked arrows = prefer/avoid, cross = excluded — and clicking
		// opens the tier menu (exclude is its bottom entry).
		MethodPriority tier = plugin.getMethodPriority(item);
		final IconActionLabel[] toggleHolder = new IconActionLabel[1];
		toggleHolder[0] = new IconActionLabel(priorityRestIcon(tier), priorityHoverIcon(tier),
			priorityTooltip(item.label(), tier),
			() -> showPriorityMenu(toggleHolder[0], item));
		IconActionLabel toggle = toggleHolder[0];
		// The status marker (lock/bank) stays by the name; the toggle sits at the row's right edge,
		// aligned with the category toggles, away from where users click to expand.
		MethodAvailability status = cachedUnavailable.get(item);
		if (status != null)
		{
			JLabel statusMarker = statusLabel(status, item);
			statusMarker.setBorder(new EmptyBorder(0, 0, 0, 3));
			row.add(verticallyCentered(statusMarker), BorderLayout.WEST);
		}

		JLabel text = wrappedLabel(escapeHtml(item.label()));
		text.setToolTipText(methodTooltip(item));
		if (excluded)
		{
			text.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		}
		// Centre the label at its preferred height instead of letting BorderLayout stretch it: a
		// stretched html JLabel top-anchors its text (the html view claims the full height), which
		// left the text floating high beside the vertically-centred icons.
		row.add(verticallyCentered(text), BorderLayout.CENTER);

		row.add(verticallyCentered(control(toggle)), BorderLayout.EAST);

		return row;
	}

	/**
	 * Marker for a method the player can't use in the current mode: a bank glyph for an item that's only
	 * in the bank, a padlock for everything else, each with a reason tooltip.
	 */
	private JLabel statusLabel(MethodAvailability status, TeleportMethod method)
	{
		JLabel label = new JLabel(status == MethodAvailability.IN_BANK ? RouteIcons.IN_BANK : RouteIcons.LOCKED);
		// Name exactly what is missing when the classification recorded it ("Requires 60 Mining",
		// "Missing item: Willow logs"); the per-status generic wording is the fallback.
		String detail = plugin.methodUnavailabilityDetail(method);
		if (detail == null)
		{
			label.setToolTipText(statusReason(status));
		}
		else
		{
			label.setToolTipText(status == MethodAvailability.IN_BANK
				? detail + " — switch to \"Inventory + bank\" or withdraw it"
				: detail);
		}
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

	/**
	 * Focuses the destination search box and selects any existing text, so the focus-search hotkey
	 * lands the caret ready to type. Marshalled to the EDT and deferred so the panel (just opened by
	 * the hotkey) is laid out and focusable first.
	 */
	public void focusSearch()
	{
		SwingUtilities.invokeLater(() ->
		{
			destinationSearch.requestFocusInWindow();
			javax.swing.JTextField inner = innerTextField(destinationSearch);
			if (inner != null)
			{
				inner.selectAll();
			}
			// Surface the recent-searches list (the focus listener does this too, but requesting
			// focus on an already-focused field won't re-fire it).
			renderDestinationResults();
		});
	}

	/** The first JTextField inside a composite component (IconTextField hides its own). */
	private static javax.swing.JTextField innerTextField(Container root)
	{
		for (Component component : root.getComponents())
		{
			if (component instanceof javax.swing.JTextField)
			{
				return (javax.swing.JTextField) component;
			}
			if (component instanceof Container)
			{
				javax.swing.JTextField inner = innerTextField((Container) component);
				if (inner != null)
				{
					return inner;
				}
			}
		}
		return null;
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

		// Header: the section label plus the favourite-saving heart on the right.
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.add(sectionLabel("Go to a place"), BorderLayout.CENTER);
		IconActionLabel saveFavorite = new IconActionLabel(RouteIcons.FAVORITE, RouteIcons.FAVORITE_HOVER,
			"Save a favourite position with a label (your current tile, or any coordinates)",
			this::toggleFavoriteEditor);
		header.add(verticallyCentered(control(saveFavorite)), BorderLayout.EAST);

		// Inline favourite editor, its own titled section ABOVE the "Go to a place" header: two
		// labelled fields — Name and At (coordinates, prefilled with the current tile;
		// "3221 3218", "3221,3218,1" and "3221, 3218 0" all parse; empty = current tile). Tab
		// moves between them, Enter saves from either, Esc closes.
		java.awt.event.KeyAdapter escapeCloses = new java.awt.event.KeyAdapter()
		{
			@Override
			public void keyPressed(java.awt.event.KeyEvent e)
			{
				if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE)
				{
					favoriteEditor.setVisible(false);
				}
			}
		};
		for (JTextField field : new JTextField[]{favoriteLabelInput, favoritePositionInput})
		{
			field.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			field.setForeground(Color.WHITE);
			field.setCaretColor(Color.WHITE);
			field.setFont(FontManager.getRunescapeSmallFont());
			field.addActionListener(e -> attemptSaveFavorite());
			field.addKeyListener(escapeCloses);
		}
		favoriteLabelInput.setToolTipText("The favourite's name, shown in search results");
		favoritePositionInput.setToolTipText("<html>Where it is — \"3221 3218\", \"3221,3218,1\" or"
			+ " \"3221, 3218 0\".<br>Empty = your current tile.</html>");
		JButton favoriteSave = new JButton("Save");
		favoriteSave.setMargin(new Insets(2, 8, 2, 8));
		favoriteSave.setFont(FontManager.getRunescapeSmallFont());
		favoriteSave.addActionListener(e -> attemptSaveFavorite());
		JPanel positionRow = new JPanel(new BorderLayout(4, 0));
		positionRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		positionRow.add(favoritePositionInput, BorderLayout.CENTER);
		positionRow.add(favoriteSave, BorderLayout.EAST);
		favoriteError.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		favoriteError.setFont(FontManager.getRunescapeSmallFont());
		favoriteError.setVisible(false);
		favoriteEditor.setLayout(new BoxLayout(favoriteEditor, BoxLayout.Y_AXIS));
		favoriteEditor.setBackground(ColorScheme.DARK_GRAY_COLOR);
		favoriteEditor.setBorder(new EmptyBorder(0, 0, 6, 0));
		favoriteEditor.setAlignmentX(LEFT_ALIGNMENT);
		favoriteError.setAlignmentX(LEFT_ALIGNMENT);
		// Title row: the section label plus a red ✕ to close (the heart also toggles, Esc too).
		JPanel favoriteTitleRow = new JPanel(new BorderLayout());
		favoriteTitleRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		favoriteTitleRow.setAlignmentX(LEFT_ALIGNMENT);
		favoriteTitleRow.add(sectionLabel("Save a favourite"), BorderLayout.CENTER);
		IconActionLabel favoriteClose = new IconActionLabel(RouteIcons.CROSS_RED, RouteIcons.CROSS_RED_HOVER,
			"Close without saving", () -> favoriteEditor.setVisible(false));
		favoriteTitleRow.add(verticallyCentered(control(favoriteClose)), BorderLayout.EAST);
		favoriteTitleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,
			favoriteTitleRow.getPreferredSize().height));
		favoriteEditor.add(favoriteTitleRow);
		favoriteEditor.add(favoriteFieldRow("Name", favoriteLabelInput));
		favoriteEditor.add(javax.swing.Box.createVerticalStrut(3));
		favoriteEditor.add(favoriteFieldRow("At", positionRow));
		favoriteEditor.add(favoriteError);
		favoriteEditor.setVisible(false);
		// Above the "Go to a place" header — saving a favourite is its own little task, not part
		// of the search flow below it.
		wrap.add(fullWidth(favoriteEditor));
		wrap.add(fullWidth(header));

		destinationSearch.setIcon(IconTextField.Icon.SEARCH);
		destinationSearch.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		destinationSearch.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		destinationSearch.setToolTipText("Search places, dungeons and minigames by name,"
			+ " or type coordinates (\"3221, 3218\" — optional plane: \"3221 3218 1\")");
		// Taller than the default field height for an easier click target and more presence — it's
		// the section's primary control.
		final int searchHeight = 32;
		destinationSearch.setPreferredSize(new Dimension(destinationSearch.getPreferredSize().width, searchHeight));
		destinationSearch.setMinimumSize(new Dimension(0, searchHeight));
		destinationSearch.setMaximumSize(new Dimension(Integer.MAX_VALUE, searchHeight));
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
		// Clicking into the empty box offers the recent searches; IconTextField doesn't expose its
		// inner text field, so find it in the component tree to hear focus.
		javax.swing.JTextField inner = innerTextField(destinationSearch);
		if (inner != null)
		{
			inner.addFocusListener(new java.awt.event.FocusAdapter()
			{
				@Override
				public void focusGained(java.awt.event.FocusEvent e)
				{
					renderDestinationResults();
				}
			});
			// Up/Down move the highlighted result, Enter picks it, Escape closes the popup — so a
			// destination can be chosen without leaving the keyboard.
			inner.addKeyListener(new java.awt.event.KeyAdapter()
			{
				@Override
				public void keyPressed(java.awt.event.KeyEvent e)
				{
					if (!destinationPopup.isVisible())
					{
						return;
					}
					switch (e.getKeyCode())
					{
						case java.awt.event.KeyEvent.VK_DOWN:
							moveSelection(1);
							e.consume();
							break;
						case java.awt.event.KeyEvent.VK_UP:
							moveSelection(-1);
							e.consume();
							break;
						case java.awt.event.KeyEvent.VK_ENTER:
							if (selectedResult >= 0 && selectedResult < resultEntries.size())
							{
								selectEntry(resultEntries.get(selectedResult));
								e.consume();
							}
							break;
						case java.awt.event.KeyEvent.VK_ESCAPE:
							destinationPopup.setVisible(false);
							e.consume();
							break;
						default:
							break;
					}
				}
			});
		}
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
		wrap.add(buildNearestRow());
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

	/**
	 * The nearest-X row: a compact, content-hugging "Find nearest…" opener plus icon-only quick
	 * buttons for the most common targets (bank, bank-and-back) — the full-width button pulled
	 * attention away from the search box above, the section's primary control.
	 */
	private JPanel buildNearestRow()
	{
		// Full width: the "Find nearest…" opener stretches to fill the row, the icon-only quick
		// buttons keep their natural size on the right.
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(new EmptyBorder(4, 0, 0, 0));
		row.setAlignmentX(LEFT_ALIGNMENT);

		JButton menuButton = subtleButton(new JButton("Find nearest…"));
		menuButton.setHorizontalAlignment(SwingConstants.CENTER);
		menuButton.setToolTipText("Route to the nearest altar / water source / furnace / … using available teleports");
		menuButton.addActionListener(e -> showNearestMenu(menuButton));
		row.add(menuButton, BorderLayout.CENTER);

		JPanel quick = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
		quick.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JButton bank = nearestQuickButton("bank");
		if (bank != null)
		{
			quick.add(bank);
		}
		JButton bankAndBack = nearestQuickButton("bank_round_trip");
		if (bankAndBack != null)
		{
			quick.add(bankAndBack);
		}
		row.add(quick, BorderLayout.EAST);

		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** An icon-only quick button running one nearest-X option directly (tooltip names it). */
	private JButton nearestQuickButton(String optionId)
	{
		for (Destinations.NearestOption option : Destinations.NEAREST_OPTIONS)
		{
			if (option.id.equals(optionId))
			{
				JButton button = subtleButton(new JButton(RouteIcons.destinationIcon(option.id)));
				button.setToolTipText("Nearest " + option.label.toLowerCase(java.util.Locale.ROOT));
				button.addActionListener(e -> runNearestOption(option));
				return button;
			}
		}
		return null;
	}

	/** Shared subtle-button chrome: small font, outline, tight padding, hand cursor, hover lift. */
	private static JButton subtleButton(JButton button)
	{
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setForeground(Color.WHITE);
		button.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
		button.setFocusPainted(false);
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		button.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(3, 8, 3, 8)));
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

	/** Runs one nearest-X option — shared by the menu items and the quick buttons. */
	private void runNearestOption(Destinations.NearestOption option)
	{
		Set<Integer> tiles = Destinations.tilesForCategory(option.id, plugin.getTransports());
		boolean roundTrip = "bank_round_trip".equals(option.id);
		if ("bank".equals(option.id) || roundTrip)
		{
			// Union in the engine's accessible-bank tiles: the amenity dump misses oddly-named
			// bank objects (e.g. Slepe's "Bank Chest-wreck"), and "nearest bank" must never
			// disagree with where the engine itself can bank.
			tiles.addAll(plugin.getEngineBankTiles());
		}
		plugin.setNearestCategory(tiles,
			"nearest " + option.label.toLowerCase(java.util.Locale.ROOT), roundTrip);
		destinationSearch.setText("");
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
			item.addActionListener(e -> runNearestOption(option));
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
		resultRows.clear();
		resultEntries.clear();
		selectedResult = -1;
		String query = destinationSearch.getText().trim();
		final int player = plugin.getLastKnownPlayerLocation();
		if (query.isEmpty())
		{
			// An empty box offers the saved favourites and the recent selections instead of hiding —
			// reopening a frequent destination without retyping it.
			List<Destinations.Entry> favorites = plugin.getFavoriteDestinations();
			List<Destinations.Entry> history = plugin.getSearchHistory();
			if (favorites.isEmpty() && history.isEmpty())
			{
				destinationPopup.setVisible(false);
				return;
			}
			if (!favorites.isEmpty())
			{
				destinationResults.add(resultsHeader("Favourites"));
				for (Destinations.Entry entry : favorites)
				{
					addResultRow(entry, player);
				}
			}
			if (!history.isEmpty())
			{
				destinationResults.add(resultsHeader("Recent searches"));
				for (Destinations.Entry entry : history)
				{
					addResultRow(entry, player);
				}
			}
			preselectFirstResult();
			showDestinationPopup();
			return;
		}

		// A typed coordinate pair ("3221, 3218", "3221 3218", optional plane "3221 3218 1") becomes
		// a direct route-to-tile result ahead of the name matches.
		int coordinate = parseCoordinateQuery(query);
		if (coordinate != WorldPointUtil.UNDEFINED)
		{
			int plane = WorldPointUtil.unpackWorldPlane(coordinate);
			addResultRow(new Destinations.Entry("coordinates",
				"Tile (" + WorldPointUtil.unpackWorldX(coordinate) + ", " + WorldPointUtil.unpackWorldY(coordinate)
					+ (plane > 0 ? ", plane " + plane : "") + ")",
				coordinate), player);
		}

		// Fuzzy match, best first: the score tiers (exact > prefix > word prefixes > substring >
		// subsequence) rank the list; proximity to the player breaks ties within a tier. Saved
		// favourites are part of the pool, matched by their label.
		List<Destinations.Entry> pool = new ArrayList<>(plugin.getFavoriteDestinations());
		pool.addAll(destinationIndex());
		List<Destinations.Entry> matches = new ArrayList<>();
		Map<Destinations.Entry, Integer> scores = new java.util.HashMap<>();
		for (Destinations.Entry entry : pool)
		{
			int score = SearchMatcher.score(entry.name, query);
			if (score > 0)
			{
				matches.add(entry);
				scores.put(entry, score);
			}
		}
		Comparator<Destinations.Entry> byScore =
			Comparator.comparingInt(e -> -scores.getOrDefault(e, 0));
		if (player != WorldPointUtil.UNDEFINED)
		{
			matches.sort(byScore.thenComparingInt(
				e -> WorldPointUtil.distanceBetween(player, e.packedPosition)));
		}
		else
		{
			matches.sort(byScore.thenComparing(e -> e.name));
		}

		int shown = 0;
		for (Destinations.Entry entry : matches)
		{
			addResultRow(entry, player);
			if (++shown >= MAX_DESTINATION_RESULTS)
			{
				break;
			}
		}
		if (resultEntries.isEmpty())
		{
			JLabel none = new JLabel("No matching destination");
			none.setForeground(Color.GRAY);
			none.setFont(FontManager.getRunescapeSmallFont());
			none.setBorder(new EmptyBorder(2, 4, 2, 4));
			destinationResults.add(none);
		}
		preselectFirstResult();
		showDestinationPopup();
	}

	// "x, y" or "x y", with an optional plane (0-3): x is 4 digits (the playable range is roughly
	// 1000-4600), y 4-5 digits (surface ~3000-4200; dungeon/instance planes reach past 10000).
	private static final java.util.regex.Pattern COORDINATE_QUERY =
		java.util.regex.Pattern.compile("(\\d{4})[,;\\s]+(\\d{4,5})(?:[,;\\s]+([0-3]))?");

	/**
	 * Parses a typed coordinate query into a packed world point, or {@link WorldPointUtil#UNDEFINED}
	 * when the text isn't a plausible in-world coordinate pair.
	 */
	static int parseCoordinateQuery(String query)
	{
		java.util.regex.Matcher matcher = COORDINATE_QUERY.matcher(query);
		if (!matcher.matches())
		{
			return WorldPointUtil.UNDEFINED;
		}
		int x = Integer.parseInt(matcher.group(1));
		int y = Integer.parseInt(matcher.group(2));
		int plane = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;
		if (x > 4600 || y > 12900)
		{
			return WorldPointUtil.UNDEFINED;
		}
		return WorldPointUtil.packWorldPoint(x, y, plane);
	}

	/** Builds a result row, adds it to the popup and tracks it for keyboard navigation. */
	private void addResultRow(Destinations.Entry entry, int player)
	{
		JPanel row = destinationRow(entry, player);
		resultEntries.add(entry);
		resultRows.add(row);
		destinationResults.add(row);
	}

	/** A small grey group header inside the results popup ("Favourites", "Recent searches"). */
	private static JLabel resultsHeader(String text)
	{
		JLabel header = new JLabel(text);
		header.setForeground(Color.GRAY);
		header.setFont(FontManager.getRunescapeSmallFont());
		header.setBorder(new EmptyBorder(2, 4, 2, 4));
		return header;
	}

	// Inline favourite editor components (built in buildDestinationSearch).
	private final JPanel favoriteEditor = new JPanel();
	private final JTextField favoriteLabelInput = new JTextField();
	private final JTextField favoritePositionInput = new JTextField();
	private final JLabel favoriteError = new JLabel();

	/** A small captioned row for the favourite editor (caption west, component center). */
	private static JPanel favoriteFieldRow(String caption, java.awt.Component component)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);
		JLabel label = new JLabel(caption);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setPreferredSize(new Dimension(34, label.getPreferredSize().height));
		row.add(label, BorderLayout.WEST);
		row.add(component, BorderLayout.CENTER);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height + 4));
		return row;
	}

	/** The heart button: shows (or hides) the inline favourite editor, prefilled with the current tile. */
	private void toggleFavoriteEditor()
	{
		if (favoriteEditor.isVisible())
		{
			favoriteEditor.setVisible(false);
			return;
		}
		int player = plugin.getLastKnownPlayerLocation();
		String prefill = "";
		if (player != WorldPointUtil.UNDEFINED)
		{
			int plane = WorldPointUtil.unpackWorldPlane(player);
			prefill = WorldPointUtil.unpackWorldX(player) + " " + WorldPointUtil.unpackWorldY(player)
				+ (plane > 0 ? " " + plane : "");
		}
		favoriteLabelInput.setText("");
		favoritePositionInput.setText(prefill);
		favoriteError.setVisible(false);
		favoriteEditor.setVisible(true);
		favoriteEditor.revalidate();
		// Name first: type the label, Tab to adjust the (prefilled) position, Enter to save.
		favoriteLabelInput.requestFocusInWindow();
	}

	/** Enter/Save in the inline editor: label + position (empty position = current tile). */
	private void attemptSaveFavorite()
	{
		String label = favoriteLabelInput.getText().trim();
		if (label.isEmpty())
		{
			favoriteInputError("Name the favourite — it's what search results show.");
			favoriteLabelInput.requestFocusInWindow();
			return;
		}
		String positionText = favoritePositionInput.getText().trim();
		int position = positionText.isEmpty()
			? plugin.getLastKnownPlayerLocation() : parseCoordinateQuery(positionText);
		if (position == WorldPointUtil.UNDEFINED)
		{
			favoriteInputError(positionText.isEmpty()
				? "No position: log in, or type coordinates (\"3221 3218\", optional plane)."
				: "Not a coordinate: \"3221 3218\", \"3221,3218,1\" and \"3221, 3218 0\" all work.");
			favoritePositionInput.requestFocusInWindow();
			return;
		}
		plugin.addFavoriteDestination(label, position);
		favoriteEditor.setVisible(false);
		if (destinationPopup.isVisible())
		{
			renderDestinationResults();
		}
	}

	private void favoriteInputError(String message)
	{
		favoriteError.setText(message);
		favoriteError.setVisible(true);
		favoriteEditor.revalidate();
	}

	/** Preselects the top result so Enter works immediately; -1 when there are none. */
	private void preselectFirstResult()
	{
		selectedResult = resultRows.isEmpty() ? -1 : 0;
		applySelectionHighlight();
	}

	// Selected search-result row: a blue-tinted background plus a GPS-blue accent bar — the two
	// near-identical dark greys the highlight used before were invisible when arrowing through.
	private static final Color RESULT_SELECTED_BG = new Color(0x2E, 0x3E, 0x5E);

	/** Highlights the selected row (shared by keyboard and mouse) and resets the rest. */
	private void applySelectionHighlight()
	{
		for (int i = 0; i < resultRows.size(); i++)
		{
			boolean selected = i == selectedResult;
			JPanel row = resultRows.get(i);
			row.setBackground(selected ? RESULT_SELECTED_BG : ColorScheme.DARKER_GRAY_COLOR);
			// The accent bar replaces 3px of the left padding, so the row text doesn't shift.
			row.setBorder(selected
				? BorderFactory.createCompoundBorder(
					BorderFactory.createMatteBorder(0, 3, 0, 0, BANNER_INFO_ACCENT),
					new EmptyBorder(3, 1, 3, 4))
				: new EmptyBorder(3, 4, 3, 4));
		}
	}

	/** Moves the keyboard selection by {@code delta}, wrapping around the result list. */
	private void moveSelection(int delta)
	{
		if (resultRows.isEmpty())
		{
			return;
		}
		selectedResult = ((selectedResult + delta) % resultRows.size() + resultRows.size()) % resultRows.size();
		applySelectionHighlight();
	}

	/** Commits a destination selection (from a click or Enter): route to it, remember it, close. */
	private void selectEntry(Destinations.Entry entry)
	{
		plugin.setDestination(entry.packedPosition, "search");
		plugin.recordSearchSelection(entry);
		// Clearing the text re-renders the popup with the recent list; a selection should end the
		// interaction instead.
		destinationSearch.setText("");
		destinationPopup.setVisible(false);
	}

	/**
	 * Floats the results over the panel, matching the search field's width. Re-showing on every
	 * keystroke would flicker and can steal the caret, so a visible popup is resized in place.
	 */
	private void showDestinationPopup()
	{
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

		JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		east.setOpaque(false);
		if (player != WorldPointUtil.UNDEFINED)
		{
			int distance = WorldPointUtil.distanceBetween(player, entry.packedPosition);
			if (distance != Integer.MAX_VALUE)
			{
				JLabel dist = new JLabel(distance + " tiles");
				dist.setForeground(Color.GRAY);
				dist.setFont(FontManager.getRunescapeSmallFont());
				east.add(dist);
			}
		}
		if ("favorite".equals(entry.category))
		{
			east.add(new IconActionLabel(RouteIcons.CROSS, RouteIcons.CROSS_HOVER,
				"Remove this favourite", () ->
			{
				plugin.removeFavoriteDestination(entry);
				renderDestinationResults();
			}));
		}
		if (east.getComponentCount() > 0)
		{
			row.add(verticallyCentered(east), BorderLayout.EAST);
		}

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				selectEntry(entry);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				// Move the shared selection to the hovered row so keyboard and mouse agree.
				selectedResult = resultRows.indexOf(row);
				applySelectionHighlight();
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
			case "Mountain guides": return new Color(0x8D, 0x6E, 0x63); // mountain brown
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
