package gps;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * The GPS side panel: the composition of its views and the render that keeps them current.
 * Top to bottom: the header (title, actions, mode picker; see PanelHeaderView), the Travel
 * options slot (configuration sections and the method catalog; see TravelOptionsView), the
 * "Go to" destination search (see DestinationSearchView), the notices strip (see NoticesView),
 * and the scrolling route list (see RouteListView). The route cards and the catalog share one
 * exclusion set: a route's method menu and the catalog row flip the same state. Built on the
 * tile-packs style: small icon controls with hover states and tooltips (see PanelWidgets).
 */
public class ShortestPathPanel extends PluginPanel
{
	private final ShortestPathPlugin plugin;
	private final PanelHeaderView header;
	private final TravelOptionsView travelOptions;
	private final DestinationSearchView destinationSearch;
	private final NoticesView notices;
	private final RouteListView routeList;

	// Cached last render input so expand/collapse can re-render without a round-trip to the plugin.
	private List<RouteOption> cachedRoutes = List.of();
	private List<TeleportMethod> cachedCatalog = List.of();
	private Map<TeleportMethod, MethodAvailability> cachedUnavailable = Map.of();
	private Set<TeleportMethod> cachedExclusions = Set.of();
	private boolean cachedCalculating = false;
	private boolean cachedHasTarget = false;

	public ShortestPathPanel(ShortestPathPlugin plugin)
	{
		super(false);
		this.plugin = plugin;
		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		header = new PanelHeaderView(plugin);
		travelOptions = new TravelOptionsView(plugin, this::refreshConfigSections);
		destinationSearch = new DestinationSearchView(plugin);
		notices = new NoticesView(plugin, this::render, travelOptions::balloonLowBanner);
		routeList = new RouteListView(plugin, travelOptions::showPriorityMenu);

		// Fixed top area: the header, the Travel options slot (the catalog scrolls inside its own
		// bounded box instead of pushing the route list down), the destination search, the
		// notices. Only the routes scroll in the main area below.
		JPanel top = new JPanel(new BorderLayout());
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(header, BorderLayout.NORTH);
		JPanel belowHeader = new JPanel(new BorderLayout());
		belowHeader.setBackground(ColorScheme.DARK_GRAY_COLOR);
		belowHeader.add(travelOptions, BorderLayout.NORTH);
		belowHeader.add(destinationSearch, BorderLayout.CENTER);
		top.add(belowHeader, BorderLayout.CENTER);
		top.add(notices, BorderLayout.SOUTH);
		add(top, BorderLayout.NORTH);
		add(routeList, BorderLayout.CENTER);

		render();
	}

	/**
	 * Sidebar visibility does NOT change how much the route generator does: every generation runs
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

	/**
	 * Unwrapped panels (super(false)) ARE the component the client UI mounts, so the height this
	 * returns flows into the frame's layout minimum: BorderLayout sums the fixed top block plus
	 * every expanded section, and once that passes the window height the client grows to obey it
	 * (issue #13: "Sidebar modifies client height"). Wrapped panels never have this problem
	 * because RuneLite mounts their scroll pane, whose minimum is tiny. Report the same: a small
	 * fixed height, and let the internal scroll areas absorb any shortage.
	 */
	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(super.getMinimumSize().width, 100);
	}

	/** Stores the latest data and re-renders. Must be called on the Swing EDT. */
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

	/**
	 * Called by the plugin the moment it reaches (or clears an already-at) destination, so the
	 * status shows an arrival banner instead of "No destination set" (see NoticesView).
	 */
	public void markArrived(long elapsedMillis)
	{
		notices.markArrived(elapsedMillis);
	}

	/** Shows the issue report's routing context in the header's copy box (see PanelHeaderView). EDT only. */
	void showReportContext(String context)
	{
		header.showReportContext(context);
	}

	/**
	 * Rebuilds the configuration sections after one of their mirrored config keys changed outside
	 * the panel (the RuneLite config UI, or the balloon chat parser updating stored log counts).
	 * A full render follows so the notices strip (the Log storage low banner) tracks the change too.
	 */
	public void refreshConfigSections()
	{
		refreshCatalog();
		render();
	}

	/** Focuses the destination search box (the focus-search hotkey); see DestinationSearchView. */
	public void focusSearch()
	{
		destinationSearch.focusSearch();
	}

	private void render()
	{
		header.refresh();
		notices.show(cachedRoutes, cachedCalculating, cachedHasTarget);
		// The Travel options slot is rebuilt only when its inputs changed: streamed route updates
		// leave it untouched so its toggles stay responsive while a generation is running.
		if (travelOptions.needsRebuild(cachedCatalog, cachedExclusions, cachedUnavailable))
		{
			refreshCatalog();
		}
		// The highlighted card is the route drawn on the map: the explicitly selected one, or
		// route 1 by default.
		routeList.show(cachedRoutes, plugin.getDisplayedRoute(), cachedCalculating, cachedHasTarget, cachedUnavailable);
	}

	/** Rebuilds the Travel options slot for the current inputs (see TravelOptionsView). */
	private void refreshCatalog()
	{
		travelOptions.rebuild(cachedCatalog, cachedExclusions, cachedUnavailable);
	}
}
