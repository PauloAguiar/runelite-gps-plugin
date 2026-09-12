package gps;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
import gps.transport.Transport;
import gps.transport.TransportType;

import static gps.PanelWidgets.BANNER_INFO_ACCENT;
import static gps.PanelWidgets.BANNER_OK_ACCENT;
import static gps.PanelWidgets.BANNER_TEXT_WIDTH;
import static gps.PanelWidgets.BANNER_WARN_ACCENT;
import static gps.PanelWidgets.CONTROL_SIZE;
import static gps.PanelWidgets.SAIL_DOT_COLOUR;
import static gps.PanelWidgets.WALK_DOT_COLOUR;
import static gps.PanelWidgets.addClickRecursively;
import static gps.PanelWidgets.banner;
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
	// The header's GitHub mark points at the project home; the Discord mark at the community invite.
	private static final String GITHUB_REPO_URL = "https://github.com/PauloAguiar/runelite-gps-plugin";
	private static final String DISCORD_URL = "https://discord.gg/7VAbrPsUzT";

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
	// The "Travel options" slot: the configuration sections and the method catalog (see TravelOptionsView).
	private final TravelOptionsView travelOptions;
	private final JPanel listPanel = new JPanel();
	// Fixed (non-scrolling) slot for the routes header (count + more/refresh/clear controls),
	// mounted above the route-card scroll area so it stays visible while the cards scroll.
	private final JPanel resultsHeaderHolder = new JPanel();
	// The "Go to" destination search (see DestinationSearchView); built in the constructor.
	private final DestinationSearchView destinationSearch;
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

	/**
	 * Sidebar visibility does NOT change how much the route generator does — every generation runs
	 * the full route budget, so the overlay's route is the same with the panel open or hidden.
	 * Opening it re-checks the auto-compute decision (a budget that grew meanwhile is widened).
	 */
	@Override
	public void onActivate()
	{
		plugin.setAltPanelVisible(true);
	}

	@Override
	public void onDeactivate()
	{
		destinationSearch.hidePopup();
		plugin.setAltPanelVisible(false);
	}

	public ShortestPathPanel(ShortestPathPlugin plugin)
	{
		super(false);
		this.plugin = plugin;
		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		travelOptions = new TravelOptionsView(plugin, this::refreshConfigSections);
		JPanel top = new JPanel(new BorderLayout());
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(buildHeader(), BorderLayout.NORTH);
		// The teleport-methods catalog, then the "Go to" destination search beneath it, then notes.
		JPanel belowHeader = new JPanel(new BorderLayout());
		belowHeader.setBackground(ColorScheme.DARK_GRAY_COLOR);
		belowHeader.add(travelOptions, BorderLayout.NORTH);
		destinationSearch = new DestinationSearchView(plugin);
		belowHeader.add(destinationSearch, BorderLayout.CENTER);
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
		// Report, GitHub and Discord stay in the header row by the owner's decision (the review
		// had suggested tucking them into the burger; reverted 2026-09-07). Occasional actions
		// (snapshot, reset) tuck into the burger.
		JButton reportButton = new JButton("Report an issue");
		reportButton.setFont(FontManager.getRunescapeSmallFont());
		reportButton.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		reportButton.setMargin(new java.awt.Insets(2, 6, 2, 6));
		reportButton.setFocusPainted(false);
		reportButton.setToolTipText("<html>Shows your routes and settings in a box to copy, and opens<br>"
			+ "GitHub: paste the context into the issue.<br>"
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

	private boolean cfgQuestBannerDismissed()
	{
		return plugin.getGpsConfig().questHelperBannerDismissed();
	}

	/** Adds a small persistent-dismiss x to a banner's right edge: clicking writes the given
	 * boolean config key and the next render drops the banner for good. */
	private JPanel withDismiss(JPanel banner, String dismissedConfigKey)
	{
		JLabel close = new JLabel("✕");
		close.setFont(FontManager.getRunescapeSmallFont());
		close.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		close.setToolTipText("Dismiss permanently");
		close.setBorder(new EmptyBorder(0, 4, 0, 2));
		close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		close.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				plugin.setPanelConfig(dismissedConfigKey, true);
			}
		});
		// The x eats ~18px of the CENTER label's fixed HTML width — renarrow the text or
		// its last word clips under the button (screenshot report: 'plugin' -> 'plu').
		for (Component comp : banner.getComponents())
		{
			if (comp instanceof JLabel && ((JLabel) comp).getText() != null
				&& ((JLabel) comp).getText().startsWith("<html>"))
			{
				JLabel text = (JLabel) comp;
				text.setText(text.getText().replace(
					"width:" + BANNER_TEXT_WIDTH + "px",
					"width:" + (BANNER_TEXT_WIDTH - 18) + "px"));
			}
		}
		banner.add(verticallyCentered(close), BorderLayout.EAST);
		// The narrower text may wrap one line further: recompute the height cap.
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
			// Routes exist but every one stops short of the target. Say WHY (plan step N12): the
			// generator tells "reachable with everything, not with what you have" from "no known
			// route" (a sealed tile, or a gap in the map data), and both from success.
			status = plugin.getUnreachableCause() == AlternativeRoutesService.UnreachableCause.MISSING_UNLOCKS
				? "<b>Not reachable with what you have.</b><br>A route exists with items or unlocks you lack: "
					+ "switch to All to see it. Showing the closest reachable point."
				: "<b>No known route to this destination.</b><br>The spot may be sealed off, or the map may be "
					+ "missing a connection. Showing the closest reachable point.";
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
			status = "<b>No destination set.</b><br>Search a place or amenity above, pick a Nearest button, "
				+ "right-click a spot on the world map, or shift right-click a tile in the game.";
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
			modeBankWarning.add(banner(RouteIcons.BANNER_WARNING,
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
			notes.add(banner(statusIcon, status, statusAccent));
		}
		// Warning banners are grouped behind a compact "N warnings" row that toggles them, so a
		// stack of notices doesn't permanently crowd the panel. The sync hints (house, spirit
		// trees, balloon logs) live here at the top — inside their (collapsed) sections they were
		// easy to miss.
		List<JPanel> warnings = new ArrayList<>();
		// Quest Helper only hands its quest-step destinations to GPS when its own
		// "Use Shortest Path plugin" option is on — with it off, quest steps silently never
		// arrive. Dismissable (the x persists via config) for users who prefer it that way.
		if (plugin.isQuestHelperPathingOff() && !cfgQuestBannerDismissed())
		{
			warnings.add(withDismiss(banner(RouteIcons.BANNER_WARNING,
				"Quest Helper isn't routing through GPS",
				"Turn on <b>Use Shortest Path plugin</b> in Quest Helper's settings so quest"
					+ " steps hand their destinations to GPS.",
				BANNER_WARN_ACCENT), "questHelperBannerDismissed"));
		}
		// Running the original Shortest Path plugin alongside GPS doubles the path rendering and
		// the plugin-message integrations (both answer Quest Helper's destinations).
		if (plugin.isShortestPathConflict())
		{
			warnings.add(banner(RouteIcons.BANNER_WARNING,
				"Shortest Path is also enabled",
				"Both plugins draw paths and respond to the same integrations. GPS includes its "
					+ "functionality — disable Shortest Path to avoid doubled rendering.",
				BANNER_WARN_ACCENT));
		}
		// Method toggles no longer recalculate; flag a route list generated with different exclusions.
		if (!cachedCalculating && cachedHasTarget && plugin.isRouteListStale())
		{
			warnings.add(banner(RouteIcons.BANNER_WARNING,
				"Exclusions changed — press \"Refresh routes\" to apply.", BANNER_WARN_ACCENT));
		}
		// Log storage running low at the balloon stations (smart mode, synced, unlocked routes only).
		List<String> lowLogs = plugin.getBalloonLowLogTypes();
		if (!lowLogs.isEmpty())
		{
			warnings.add(travelOptions.balloonLowBanner(lowLogs));
		}
		ShortestPathConfig cfg = plugin.getGpsConfig();
		if (cfg.usePoh() && cfg.pohSmartDetect() && !plugin.isPohScanned())
		{
			warnings.add(banner(RouteIcons.BANNER_WARNING,
				"House furniture not detected",
				"Enter your house once to auto-detect its teleport furniture.",
				BANNER_WARN_ACCENT));
		}
		if (cfg.useSpiritTrees() && cfg.spiritTreeSmartMode() && !plugin.isSpiritTreeSynced())
		{
			warnings.add(banner(RouteIcons.BANNER_WARNING,
				"Planted spirit trees not synced",
				"Open a spirit tree's travel menu once to detect which trees you have planted.",
				BANNER_WARN_ACCENT));
		}
		if (cfg.useHotAirBalloons() && cfg.balloonSmartMode() && !cfg.balloonStorageSynced())
		{
			warnings.add(banner(RouteIcons.BANNER_WARNING,
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
		boolean catalogDirty = travelOptions.needsRebuild(cachedCatalog, cachedExclusions, cachedUnavailable);
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
			// marks WHICH method the detour is for. The tooltip states WHAT gets withdrawn —
			// resolved on the CLIENT thread (item names come from getItemDefinition, which
			// asserts it; the EDT crash of 2026-08-15) and swapped in when ready.
			JLabel bankChip = new JLabel(RouteIcons.IN_BANK);
			// Connectors (a jungle bush, a dig) are bank-gated too but have no method row — the
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
			bankChip.setToolTipText("<html>Walks to a bank first — withdraws the item for: <b>"
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
		// Each method row reveals its OWN exclude control (in red) only while the pointer is over
		// that row — see buildMethodRow.
		for (int m = 0; m < route.getMethods().size(); m++)
		{
			methods.add(buildMethodRow(route.getMethods().get(m), route,
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

		// The best route is the fallback whenever nothing else is selected, so hiding it never
		// shows anything else: say what the click does, not what it cannot (plan step N12).
		card.setToolTipText(selected
			? (index == 0 ? "Showing on map (the best route)" : "Showing on map, click to hide")
			: "Click to show this route on the map");
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
	private JPanel buildMethodRow(TeleportMethod method, RouteOption route, int walkBefore)
	{
		JPanel row = new JPanel(new BorderLayout(5, 0));
		row.setOpaque(false);

		JLabel dot = new JLabel(methodDot(method));
		dot.setAlignmentY(Component.CENTER_ALIGNMENT);
		dot.setToolTipText(method.getType() == TransportType.TELEPORTATION_ITEM
			? (method.isConsumable() ? "Item (charged — consumes a charge or the item)" : "Item (permanent — reusable)")
			: method.category());
		MethodAvailability status = cachedUnavailable.get(method);
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
			bankMarker.setToolTipText(
				"This method needs an item from your bank — the route withdraws it first");
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
		// The availability map now records IN_BANK in every mode; on a route it's already shown by
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
		final IconActionLabel[] excludeHolder = new IconActionLabel[1];
		excludeHolder[0] = new IconActionLabel(
			cardTier == MethodPriority.NORMAL ? RouteIcons.PRIORITY_NEUTRAL_DIM : priorityRestIcon(cardTier),
			priorityHoverIcon(cardTier),
			"Priority: " + cardTier.label + " — click to change",
			() -> travelOptions.showPriorityMenu(excludeHolder[0], method));
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

	/** Rebuilds the Travel options slot for the current inputs (see TravelOptionsView). */
	private void refreshCatalog()
	{
		travelOptions.rebuild(cachedCatalog, cachedExclusions, cachedUnavailable);
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

	/** A click anywhere on a route card (its icon controls aside) shows that route on the map. */
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

	/** Focuses the destination search box (the focus-search hotkey); see DestinationSearchView. */
	public void focusSearch()
	{
		destinationSearch.focusSearch();
	}
}
