package gps;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
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
import static gps.PanelWidgets.banner;
import static gps.PanelWidgets.control;
import static gps.PanelWidgets.verticalGap;
import static gps.PanelWidgets.verticallyCentered;

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
	// The routes header and the scrolling route cards (see RouteListView); built in the constructor.
	private final RouteListView routeList;
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

		routeList = new RouteListView(plugin, travelOptions::showPriorityMenu);
		add(routeList, BorderLayout.CENTER);

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

		// The routes header and cards (see RouteListView); the highlighted card is the route drawn
		// on the map: the explicitly selected one, or route 1 by default.
		routeList.show(cachedRoutes, plugin.getDisplayedRoute(), cachedCalculating, cachedHasTarget, cachedUnavailable);
	}

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
