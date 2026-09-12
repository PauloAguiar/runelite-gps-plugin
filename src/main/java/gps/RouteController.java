package gps;

import gps.pathfinder.PathfinderConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.ItemContainer;

/**
 * The alternative-routes generation (plan step L35, out of the plugin class): when a generation
 * runs (auto on a target-set change, the panel's Find routes, show more, a mode change), what it
 * runs with (the page budget, the cost band, the mode, the exclusions snapshot) and what happens
 * to its results (the effective order, the session's stream and settle, the panel push, the
 * broadcast to other plugins), plus the catalog-only refresh after an item change and the
 * panel's route pick. The plugin supplies the client-bound facts (the player's tile, the tick,
 * the game state, the round-trip wish) and the collaborators.
 */
@Slf4j
final class RouteController
{
	private final ShortestPathPlugin plugin;
	private final RouteSession session;
	private final MethodExclusions exclusions;
	private final ChoiceStore choices;
	private final PluginMessageBridge messages;
	// Whether and when the catalog is re-classified after an item change (see CatalogRefresher).
	private final CatalogRefresher catalogRefresh = new CatalogRefresher();
	// The async generator; created at startup with the planning copy, null before and after.
	private AlternativeRoutesService service;
	// Which methods the alternatives consider: carried (default), carried + bank, or every teleport.
	private volatile AlternativeRoutesMode mode = AlternativeRoutesMode.OWNED_INVENTORY;
	private volatile List<TeleportMethod> catalog = new ArrayList<>();
	// Catalog methods the player cannot use in the current mode, mapped to why (the panel markers).
	private volatile Map<TeleportMethod, MethodAvailability> unavailable = Map.of();
	// Whether the GPS side panel is shown (sidebar tab selected). It no longer changes how much a
	// generation does (see routeLimitFor); opening the panel re-checks the auto-compute decision.
	private volatile boolean panelVisible;

	RouteController(ShortestPathPlugin plugin, RouteSession session, MethodExclusions exclusions,
		ChoiceStore choices, PluginMessageBridge messages)
	{
		this.plugin = plugin;
		this.session = session;
		this.exclusions = exclusions;
		this.choices = choices;
		this.messages = messages;
	}

	/** Startup, before the panel exists: the saved mode, the generator, the page budget. */
	void start(AlternativeRoutesService generator)
	{
		AlternativeRoutesMode saved = choices.loadRoutesMode();
		if (saved != null)
		{
			mode = saved;
		}
		service = generator;
		session.setLimit(defaultLimit());
	}

	void shutdown()
	{
		if (service != null)
		{
			service.shutdown();
			service = null;
		}
	}

	/** The generator, or null outside the plugin's lifetime. */
	AlternativeRoutesService service()
	{
		return service;
	}

	AlternativeRoutesMode mode()
	{
		return mode;
	}

	List<TeleportMethod> catalog()
	{
		return catalog;
	}

	Map<TeleportMethod, MethodAvailability> unavailable()
	{
		return unavailable;
	}

	/** Panel (EDT) entry point: routing state is client-thread owned, so hop over (invoke runs inline when already there). */
	void setMode(AlternativeRoutesMode newMode)
	{
		plugin.getClientThread().invoke(() ->
		{
			if (newMode == null || mode == newMode)
			{
				return;
			}
			mode = newMode;
			choices.saveRoutesMode(newMode);
			trigger(session.lastStart(), session.lastTargetsCopy());
		});
	}

	/**
	 * Manually (re)computes the routes for whatever destination GPS currently has set, read live
	 * from the player's position; with no target set, just refreshes the methods catalog.
	 */
	void recompute()
	{
		plugin.getClientThread().invokeLater(() ->
		{
			Set<Integer> targets = plugin.getPathTargets();
			if (!targets.isEmpty())
			{
				int start = plugin.altStart();
				log.debug("[alt-routes] Find routes: target set, searchStart={}, target={}",
					WorldPointUtil.unpackWorldPoint(start),
					WorldPointUtil.unpackWorldPoint(targets.iterator().next()));
				session.setLimit(defaultLimit());
				trigger(start, new HashSet<>(targets));
			}
			else
			{
				log.debug("[alt-routes] Find routes: no target set");
				trigger(WorldPointUtil.UNDEFINED, new HashSet<>());
			}
		});
	}

	/** The configured number of routes to search for per query (clamped to the service's hard cap). */
	int defaultLimit()
	{
		return routeLimitFor(panelVisible, ConfigOverrides.override("defaultRouteCount", plugin.getGpsConfig().defaultRouteCount()));
	}

	/**
	 * The route budget a generation runs with: the SAME whether the side panel is shown or
	 * hidden. A panel-hidden run used to search only the primary route (one search, a handful of
	 * seeds) and found a different "best" often enough that opening the panel visibly changed
	 * the overlay's route (issue #18, field reports). A full run costs tens to a few hundred
	 * milliseconds more and streams its first route at the same moment, so the overlay shows that
	 * one provisionally and settles once, consistently, with or without the panel. The panel
	 * flag is taken only to state the rule where it is decided. Pure, unit-tested.
	 */
	static int routeLimitFor(boolean panelVisible, int configured)
	{
		return Math.max(1, Math.min(configured, 25));
	}

	boolean canLoadMore()
	{
		return session.canLoadMore();
	}

	/** "Search for more routes": widens the cost band and the route budget, then regenerates. */
	void loadMore()
	{
		plugin.getClientThread().invoke(() ->
		{
			// Each poll grows both dimensions of the cap so genuinely more routes surface (see
			// RouteSession.widen): the cost band and the route-count budget, toward the service's
			// runaway backstop. A new destination resets both.
			if (session.widen(defaultLimit(), AlternativeRoutesService.MAX_ROUTES_CAP))
			{
				trigger(session.lastStart(), session.lastTargetsCopy());
			}
		});
	}

	/**
	 * Light auto-detect, run each game tick: when GPS's destination changes (a new target set
	 * manually, by Quest Helper, on reaching the previous one) compute the alternatives once.
	 * Deliberately keyed on the target SET only, never on start or movement, so live path recalcs
	 * cannot thrash it. If it ever misses, the panel's Find routes button forces a recompute.
	 */
	void maybeAutoCompute()
	{
		if (service == null)
		{
			return;
		}
		Set<Integer> targets = plugin.getPathTargets();
		// The full route budget, panel shown or hidden (see routeLimitFor).
		int desiredLimit = defaultLimit();
		if (!RouteSession.shouldAutoCompute(targets, session.lastTargets(), session.lastLimit(), desiredLimit))
		{
			return;
		}
		session.setLimit(desiredLimit);
		trigger(plugin.altStart(), new HashSet<>(targets));
	}

	/**
	 * The panel's sidebar tab was shown or hidden. Every generation runs with the full route
	 * budget regardless (see routeLimitFor); opening the panel only re-checks the auto-compute
	 * decision, so a generation that ran under a smaller budget is widened.
	 */
	void setPanelVisible(boolean visible)
	{
		panelVisible = visible;
		if (visible)
		{
			plugin.getClientThread().invokeLater(this::maybeAutoCompute);
		}
	}

	/** Starts a generation for {@code targets} from {@code start}; with no target, the catalog alone streams. */
	void trigger(int start, Set<Integer> targets)
	{
		if (service == null)
		{
			return;
		}
		Set<Integer> ends = (targets == null) ? new HashSet<>() : new HashSet<>(targets);
		// The session clears the committed route for a NEW destination (so the overlay stays blank
		// until the fresh routes settle) and keeps it for the same one; the previous routes go
		// immediately (the catalog stays) and the new ones stream in as they are found.
		session.begin(start, ends);
		// Snapshot the exclusions this generation runs with, so the panel can flag the route list as
		// stale once the user toggles methods afterwards (recalculation is manual via Refresh).
		exclusions.markGenerated();
		final List<TeleportMethod> currentCatalog = catalog;
		final boolean hasTarget = !ends.isEmpty();
		ShortestPathPanel panel = plugin.panel();
		if (panel != null)
		{
			final Map<TeleportMethod, MethodAvailability> currentUnavailable = unavailable;
			SwingUtilities.invokeLater(() ->
				panel.displayRoutes(List.of(), currentCatalog, currentUnavailable, plugin.getUserExclusions(), true, hasTarget));
		}
		service.generate(start, ends, exclusions.live(), mode, session.limit(), session.costMultiple(),
			plugin.isRoundTripWanted(), this::onUpdate);
	}

	/** A generation's stream (partial lists as routes are found) and its settle ({@code done}). */
	void onUpdate(List<RouteOption> routes, List<TeleportMethod> newCatalog,
		Map<TeleportMethod, MethodAvailability> newUnavailable, boolean done)
	{
		// Priorities re-rank the list (effective ETA = cost + tier adjustments): everything
		// downstream (panel, default display pick, rematch) sees the effective order.
		final List<RouteOption> ordered = plugin.sortByEffectiveOrder(routes);
		catalog = newCatalog;
		unavailable = newUnavailable;
		if (done)
		{
			// The session re-matches or drops the pick and commits the overlay's route in one step
			// (see RouteSession.settle); the tracker's progress is the pick's progress.
			session.settle(ordered, service.wasMoreLikely(), AlternativeRoutesService.MAX_ROUTES_CAP,
				plugin::displayedRouteProgress);
			// The displayed route just settled: publish it to other plugins (postTransports).
			messages.postTransports();
		}
		else
		{
			// Mid-stream updates deliberately do NOT clear a stale selection: the overlay keeps
			// drawing the picked route steadily while the new list streams in.
			session.stream(ordered);
		}
		final boolean hasTarget = !session.lastTargets().isEmpty();
		SwingUtilities.invokeLater(() ->
		{
			ShortestPathPanel panel = plugin.panel();
			if (panel != null)
			{
				panel.displayRoutes(ordered, newCatalog, newUnavailable, plugin.getUserExclusions(), !done, hasTarget);
			}
		});
	}

	/** The panel's pick: toggles the shown route, re-arms the journey and republishes the path. */
	void select(int index)
	{
		plugin.getClientThread().invoke(() ->
		{
			// Toggle: clicking the route that is already shown hides it (RouteSession.select).
			if (session.select(index))
			{
				// Picking a different path starts a new journey: time it from here, not from the
				// original destination (re-arm; the timer restarts on the next movement).
				plugin.armJourney();
				// The displayed path changed: republish it to other plugins (postTransports).
				messages.postTransports();
				refreshPanel(false);
			}
		});
	}

	/**
	 * An inventory or equipment change: only the routing-relevant slice of the items dirties the
	 * catalog (see CatalogRefresher); without a dependency index yet, every change does. Whether
	 * the catalog became dirty.
	 */
	boolean itemsChanged(PathfinderConfig pathfinderConfig, ItemContainer inventory, ItemContainer equipment)
	{
		if (pathfinderConfig == null)
		{
			catalogRefresh.markDirty();
			return true;
		}
		return catalogRefresh.noteItems(pathfinderConfig.getRoutingItemDependencies().fingerprint(inventory, equipment));
	}

	/** Whether a catalog re-classification is pending. */
	boolean isCatalogDirty()
	{
		return catalogRefresh.isDirty();
	}

	/** Catalog-only re-classification after an item change, when due (see CatalogRefresher); each tick. */
	void refreshCatalogIfDue()
	{
		if (service == null || plugin.panel() == null || !catalogRefresh.claim(plugin.getClient().getTickCount(),
			panelVisible, session.inFlight(), GameState.LOGGED_IN.equals(plugin.getClient().getGameState())))
		{
			return;
		}
		service.refreshCatalog(mode, (newCatalog, newUnavailable) ->
		{
			catalog = newCatalog;
			unavailable = newUnavailable;
			refreshPanel(session.inFlight());
		});
	}

	/** Pushes the current routes and catalog to the panel (EDT). */
	void refreshPanel(boolean calculating)
	{
		final boolean hasTarget = !session.lastTargets().isEmpty();
		ShortestPathPanel panel = plugin.panel();
		if (panel != null)
		{
			SwingUtilities.invokeLater(() ->
				panel.displayRoutes(session.routes(), catalog, unavailable, plugin.getUserExclusions(), calculating, hasTarget));
		}
	}
}
