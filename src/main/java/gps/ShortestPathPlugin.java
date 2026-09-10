package gps;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.events.WorldChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import net.runelite.client.util.ImageUtil;
import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathStep;
import gps.pathfinder.PathfinderConfig;
import gps.transport.Transport;

@Slf4j
@SuppressWarnings("SameParameterValue")
// configName is REQUIRED here: without it RuneLite keys the on/off state by the class's simple
// name, and the original Shortest Path plugin's main class has the same one — the two plugins
// were reading and writing each other's enabled state (a disabled Shortest Path read as enabled
// while GPS was on, and toggling one toggled the other's stored state).
@PluginDescriptor(name = "GPS", configName = "GpsPlugin", description = "Turn-by-turn navigation for Gielinor:<br>"
	+
	"live directions with ETA, alternative teleport routes and closed-door hints.<br>"
	+
	"Right click on the world map or shift right click a tile to set a destination", tags = {"gps", "navigation",
	"directions", "route", "pathfinder", "map", "waypoint", "shortest", "path", "teleport", "eta"})
public class ShortestPathPlugin extends Plugin
{
	protected static final String CONFIG_GROUP = "gps";
	// GPS's own plugin-message namespace: new integrations should target this one.
	protected static final String MESSAGE_NAMESPACE = PluginMessageCodec.NAMESPACE;
	// Compatibility alias: Quest Helper and other plugins drive the pathfinder through Shortest
	// Path's namespace (set path/target, config overrides) and listen for its path broadcasts.
	// GPS supersedes Shortest Path, so it keeps answering on that channel too — inbound messages
	// are accepted on either, and broadcasts go out on both (no listener subscribes to both today,
	// so nothing double-processes; drop the legacy channel only if that ever changes).
	protected static final String MESSAGE_NAMESPACE_LEGACY = PluginMessageCodec.NAMESPACE_LEGACY;

	private static final BufferedImage MARKER_IMAGE = ImageUtil.loadImageResource(ShortestPathPlugin.class, "/marker.png");
	private final List<PendingTask> pendingTasks = new ArrayList<>(3);
	@Getter
	@Inject
	private Client client;
	@Getter
	@Inject
	private ClientThread clientThread;
	@Inject
	private ShortestPathConfig config;
	@Inject
	private ConfigManager configManager;
	@Inject
	private Gson gson;
	@Inject
	private EventBus eventBus;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private PathTileOverlay pathOverlay;
	@Inject
	private PathMinimapOverlay pathMinimapOverlay;
	@Inject
	private PathMapOverlay pathMapOverlay;
	@Inject
	private PathMapTooltipOverlay pathMapTooltipOverlay;
	@Inject
	private RouteDirectionsOverlay routeDirectionsOverlay;
	@Inject
	private SpriteManager spriteManager;
	@Inject
	private WorldMapPointManager worldMapPointManager;
	@Inject
	private KeyManager keyManager;
	@Inject
	private MouseManager mouseManager;
	@Inject
	private net.runelite.client.plugins.PluginManager pluginManager;
	// The Shortest Path conflict and the Quest Helper option (see CompanionPlugins); startup.
	private CompanionPlugins companions;
	// Click-to-dismiss for the GPS overlay's lingering "Arrived!" panel.
	private final MouseAdapter arrivalDismissListener = new MouseAdapter()
	{
		@Override
		public java.awt.event.MouseEvent mousePressed(java.awt.event.MouseEvent event)
		{
			if (routeDirectionsOverlay != null && routeDirectionsOverlay.dismissArrivalAt(event.getPoint()))
			{
				event.consume();
			}
			return event;
		}
	};
	@Inject
	private ClientToolbar clientToolbar;
	// Item images for the panel's Log storage icons.
	@Inject
	private net.runelite.client.game.ItemManager itemManager;
	// Alternative-routes feature: panel, async route generator, the methods the user has excluded, the
	// generated routes, and which one is currently shown on the map.
	private ShortestPathPanel altPanel;
	// The sidebar button, mounted in-game only (see SidebarButton); startup.
	private SidebarButton sidebar;
	private AlternativeRoutesService altRoutesService;
	// The panel's boat banner (see BoatBannerService); constructed at startup, before the panel.
	private BoatBannerService boatBannerService;
	// Smart house furniture detection (see PohDetectionService); constructed at startup, before the panel.
	private PohDetectionService pohDetection;

	// The persisted choices (exclusions, mode, history, favourites; see ChoiceStore). Suppliers:
	// the injected services arrive after field initialisation.
	private final ChoiceStore choices = new ChoiceStore(() -> configManager, () -> gson, CONFIG_GROUP);
	private volatile List<Destinations.Entry> favoriteDestinations = new ArrayList<>();
	private volatile List<Destinations.Entry> searchHistory = new ArrayList<>();
	private final Set<TeleportMethod> userExclusions = ConcurrentHashMap.newKeySet();
	// The exclusions the current route list was generated with; diverging from userExclusions means
	// the list is stale until the user refreshes (method toggles no longer auto-recalculate).
	private volatile Set<TeleportMethod> generatedExclusions = Set.of();
	// Where the current destination came from, for the GPS header: "map pin" for manual targets, the
	// sender's self-declared "source" for plugin messages (else "another plugin"), null when unset.
	private volatile String targetSource;
	// Which methods the alternatives consider: carried (default), carried + bank, or every teleport.
	private volatile AlternativeRoutesMode routesMode = AlternativeRoutesMode.OWNED_INVENTORY;
	// The alternative-routes session (see RouteSession): the page, the pick, the displayed route,
	// the in-flight flag, the last generation's inputs and the "more" budget. The limit is
	// initialised from config in startUp (config is not injected at field-init time).
	private final RouteSession session = new RouteSession();
	private volatile List<TeleportMethod> teleportCatalog = new ArrayList<>();
	// Catalog methods the player can't use in the current mode, mapped to why (for the panel markers).
	private volatile Map<TeleportMethod, MethodAvailability> unavailableMethods = Map.of();
	// Inventory / equipment changed since the catalog was last classified (issue #5): the
	// usable count and per-method reasons were a per-generation snapshot — consumed on the
	// next tick by a catalog-only refresh, never during a generation.
	private volatile boolean catalogDirty;
	/** Earliest tick the next inventory-driven catalog refresh may run (see maybeRefreshCatalog). */
	private int catalogRefreshBackoffTick;
	private static final int CATALOG_REFRESH_COOLDOWN_TICKS = 5;
	/** Fingerprint of the routing-relevant inventory/equipment slice at the last dirty mark. */
	private long routingItemsFingerprint;
	private boolean routingItemsFingerprintValid;
	// Whether the GPS side panel is currently shown (sidebar tab selected). It no longer changes how
	// much a generation does (see routeLimitFor); opening the panel re-checks the auto-compute decision.
	private volatile boolean altPanelVisible = false;
	// The bank knowledge and its cross-session snapshot (see BankSnapshotService); constructed at
	// startup with the pathfinder config.
	private BankSnapshotService bankSnapshots;
	// The right-click menu entries (see MapMenu); startup, after the map projection and minimap clip.
	private MapMenu mapMenu;
	private WorldMapPoint marker;
	// Off-route bands, the transport-jump grace and the distance from the path (see OffRouteTracker).
	private final OffRouteTracker offRoute = new OffRouteTracker();
	// Passive sea-obstacle learning from live scene collision (see SeaObstacleLearner).
	private SeaObstacleLearner seaObstacles;
	// World-map pixel projection and the minimap clip shape (see WorldMapProjection, MinimapClip);
	// constructed at startup (they read injected client services).
	private WorldMapProjection worldMap;
	private MinimapClip minimapClip;
	private GameState lastGameState = null;
	private GameState lastLastGameState = null;
	// The current destination — the single source of truth the retired classic background search
	// used to hold. Written on the client thread (setDestination); read from ticks and overlays.
	// All route computation happens in the alternative-routes generation, whose heuristic-guided
	// searches replaced the classic uninformed one (which cost 40-160 ms per target change).
	private volatile int pathStart = WorldPointUtil.UNDEFINED;
	private volatile Set<Integer> pathTargets = Set.of();
	@Getter
	private PathfinderConfig pathfinderConfig;
	// The journey wall-clock reported on arrival (see JourneyTracker): armed by a new destination
	// or a newly chosen path, started by the player's first action after that.
	private final JourneyTracker journey = new JourneyTracker();
	// One-shot world-map pin override for the next setTargets call: the destination a perimeter
	// expansion is centred on (the searched bank booth), where the pin belongs. UNDEFINED = default
	// behaviour (pin on a single target, none for multi-target sets).
	private int markerTarget = WorldPointUtil.UNDEFINED;
	// Whether the current destination is a round trip (out and back, e.g. "nearest bank (and
	// back)"). Set by setNearestCategory after setTargets (which resets it), carried into every
	// generation for this destination (refresh, show-more), cleared when a new target is set.
	private volatile boolean altRoundTrip = false;
	private final KeyListener clearPathKeylistener = new KeyListener()
	{
		@Override
		public void keyTyped(KeyEvent e)
		{
		}

		@Override
		public void keyPressed(KeyEvent e)
		{
			if (config.clearPathHotkey().matches(e))
			{
				setTarget(WorldPointUtil.UNDEFINED);
			}
		}

		@Override
		public void keyReleased(KeyEvent e)
		{
		}
	};

	// Opens the GPS side panel (if it isn't already) and focuses its destination search box, so a
	// place can be searched without first opening the panel by hand.
	private final KeyListener focusSearchKeyListener = new KeyListener()
	{
		@Override
		public void keyTyped(KeyEvent e)
		{
		}

		@Override
		public void keyPressed(KeyEvent e)
		{
			if (!config.focusSearchHotkey().matches(e) || altPanel == null || sidebar == null)
			{
				return;
			}
			SwingUtilities.invokeLater(() ->
			{
				sidebar.open();
				altPanel.focusSearch();
			});
		}

		@Override
		public void keyReleased(KeyEvent e)
		{
		}
	};
	// The planted spirit trees (see SpiritTreeSync) and the fairy-ring log helper (see
	// FairyRingHighlighter); constructed at startup with the pathfinder config.
	private SpiritTreeSync spiritTrees;
	private FairyRingHighlighter fairyRingLog;

	@Provides
	public ShortestPathConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ShortestPathConfig.class);
	}

	@Override
	protected void startUp()
	{
		clearUnsurfacedTypeToggles();
		cacheConfigValues();
		boatBannerService = new BoatBannerService(client, configManager, CONFIG_GROUP, () ->
		{
			if (altPanel != null)
			{
				SwingUtilities.invokeLater(altPanel::refreshConfigSections);
			}
		});
		// (Named API constant: POH_BUILDING_MODE is 1 while building.)
		pohDetection = new PohDetectionService(() -> PlayerOwnedHouse.scene(client.getTopLevelWorldView()),
			() -> client.getVarbitValue(net.runelite.api.gameval.VarbitID.POH_BUILDING_MODE) == 1,
			() -> config.pohSmartDetect(), PlayerOwnedHouse.declarations(config, this::setPanelConfig),
			configManager, CONFIG_GROUP, () ->
		{
			if (altPanel != null)
			{
				SwingUtilities.invokeLater(altPanel::refreshConfigSections);
			}
		});

		worldMap = new WorldMapProjection(client);
		minimapClip = new MinimapClip(client, spriteManager);
		seaObstacles = new SeaObstacleLearner(client, this::getLastKnownPlayerLocation);
		mapMenu = new MapMenu(client, worldMap, minimapClip, this);

		pathfinderConfig = new PathfinderConfig(client, config);
		bankSnapshots = new BankSnapshotService(configManager, CONFIG_GROUP, config::rememberBank, pathfinderConfig);
		spiritTrees = new SpiritTreeSync(client, clientThread, configManager, CONFIG_GROUP, pathfinderConfig, () ->
		{
			// The panel's Spirit trees section shows the detected planted trees / sync state.
			if (altPanel != null)
			{
				SwingUtilities.invokeLater(altPanel::refreshConfigSections);
			}
			if (hasPathTargets())
			{
				// Spirit-tree availability just became known: refresh the live config and
				// regenerate so the displayed route can use (or drop) spirit trees accordingly.
				setDestination(pathStart, new HashSet<>(pathTargets));
				recomputeAlternatives();
			}
		});
		fairyRingLog = new FairyRingHighlighter(client, this::getDisplayPath, this::transportsForEdge);
		if (GameState.LOGGED_IN.equals(client.getGameState()))
		{
			clientThread.invokeLater(pathfinderConfig::refresh);
		}

		overlayManager.add(pathOverlay);
		overlayManager.add(pathMinimapOverlay);
		overlayManager.add(pathMapOverlay);
		overlayManager.add(pathMapTooltipOverlay);
		overlayManager.add(routeDirectionsOverlay);


		userExclusions.addAll(choices.loadExclusions());
		preferences.load();
		searchHistory = choices.loadSearchHistory();
		favoriteDestinations = choices.loadFavorites();
		AlternativeRoutesMode savedMode = choices.loadRoutesMode();
		if (savedMode != null)
		{
			routesMode = savedMode;
		}
		session.setLimit(defaultRouteLimit());
		altPanel = new ShortestPathPanel(this);
		altRoutesService = new AlternativeRoutesService(clientThread, pathfinderConfig.copyForPlanning());
		sidebar = new SidebarButton(clientToolbar, NavigationButton.builder()
			.tooltip("GPS")
			.icon(RouteIcons.gpsPin())
			.priority(70)
			.panel(altPanel)
			.build());
		sidebar.show(GameState.LOGGED_IN.equals(client.getGameState()));

		// Populate the teleport-methods catalog so it's visible before any target is set, and check
		// whether the bank contents are already known this session.
		if (GameState.LOGGED_IN.equals(client.getGameState()))
		{
			clientThread.invokeLater(() ->
			{
				bankSnapshots.adoptLive(client.getItemContainer(InventoryID.BANK));
				// Anything not visible live right now (bank, spirit trees, house furniture) falls
				// back to the previous session's saved detections.
				restoreDetectionsFromConfig();
			});
			triggerAlternatives(WorldPointUtil.UNDEFINED, new HashSet<>());
		}

		keyManager.registerKeyListener(clearPathKeylistener);
		keyManager.registerKeyListener(focusSearchKeyListener);
		mouseManager.registerMouseListener(arrivalDismissListener);
		// Plugins enabled later are caught by the PluginChanged/ExternalPluginsChanged events.
		companions = new CompanionPlugins(pluginManager, configManager, this, () -> refreshPanel(session.inFlight()));
		companions.refresh();
	}

	@Override
	protected void shutDown()
	{
		if (bankSnapshots != null)
		{
			bankSnapshots.persist();
		}
		overlayManager.remove(pathOverlay);
		overlayManager.remove(pathMinimapOverlay);
		overlayManager.remove(pathMapOverlay);
		overlayManager.remove(pathMapTooltipOverlay);
		overlayManager.remove(routeDirectionsOverlay);

		if (sidebar != null)
		{
			sidebar.remove();
			sidebar = null;
		}
		if (altRoutesService != null)
		{
			altRoutesService.shutdown();
			altRoutesService = null;
		}

		keyManager.unregisterKeyListener(clearPathKeylistener);
		keyManager.unregisterKeyListener(focusSearchKeyListener);
		mouseManager.unregisterMouseListener(arrivalDismissListener);
	}

	/**
	 * Records the current destination (after the wilderness filter) and refreshes the live config
	 * so display lookups (transport labels, POH exits) see current availability. Route computation
	 * itself happens in the alternative-routes generation, auto-triggered on the next game tick by
	 * the target-set change — the classic background search this used to start is retired.
	 */
	public void setDestination(int start, Set<Integer> ends, boolean canReviveFiltered)
	{
		getClientThread().invokeLater(() ->
		{
			// The panel's method catalog is the single customization surface: methods the user has
			// excluded there are also excluded here.
			pathfinderConfig.setExcludedMethods(getUserExclusions());
			pathfinderConfig.refresh();
			pathfinderConfig.filterLocations(ends, canReviveFiltered);
			if (ends.isEmpty())
			{
				setTarget(WorldPointUtil.UNDEFINED);
			}
			else
			{
				pathStart = start;
				pathTargets = Set.copyOf(ends);
			}
		});
	}

	public void setDestination(int start, Set<Integer> ends)
	{
		setDestination(start, ends, true);
	}

	/** Whether a destination is currently set (what {@code pathfinder != null} used to mean). */
	public boolean hasPathTargets()
	{
		return !pathTargets.isEmpty();
	}

	/** The current destination tiles (empty when no destination is set). */
	public Set<Integer> getPathTargets()
	{
		return pathTargets;
	}

	/** The recalculate distance (outer off-route band), or -1 when recalculation is disabled. */
	public int getRecalculateDistance()
	{
		return config.recalculateDistance();
	}

	/** Whether drifting past the recalculate distance recomputes (or cancels) the route. */
	public boolean isAutoRecalculateEnabled()
	{
		return config.autoRecalculate() && config.recalculateDistance() >= 0;
	}

	/** The off-route warning distance (inner band), clamped below the recalculate distance. */
	public int getOffRouteWarnDistance()
	{
		return Math.max(0, Math.min(config.offRouteWarnDistance(), Math.max(0, config.recalculateDistance())));
	}

	/** How far the player is from the path (-1 = no path / unknown), updated each tick. */
	public int getPathDistance()
	{
		return offRoute.distance();
	}

	/** Whether the player is in the warning band (>= warn, < recalculate), shown in red. */
	public boolean isOffRouteWarning()
	{
		return offRoute.isWarning();
	}

	// The arrival zone around the displayed path's end (see ArrivalZone); the map arrives with the
	// pathfinder config at startup.
	private final ArrivalZone arrivalZone = new ArrivalZone(() -> pathfinderConfig == null ? null : pathfinderConfig.getMap());

	/**
	 * The arrival zone: every tile within the finish distance of the destination in walking steps
	 * (see ArrivalZone). Standing on any of these tiles completes the journey; the debug overlay
	 * renders exactly this set.
	 */
	public Set<Integer> getArrivalTiles()
	{
		return arrivalZone.tiles(getDisplayPath(), config.reachedDistance());
	}

	/**
	 * Whether the player has arrived: standing inside the arrival zone (within the finish distance of
	 * the destination over walkable tiles). Guards: a round trip only completes once its turnaround has
	 * been reached (the zone centres on home, so it would otherwise fire at departure), and while a
	 * round trip is regenerating (no round-trip route displayed) arrival is suspended rather than
	 * measured against the outbound fallback path; an unreachable target never completes.
	 */
	private boolean hasArrived(int currentLocation)
	{
		Set<Integer> zone = getArrivalTiles();
		boolean inZone = !zone.isEmpty() && zone.contains(currentLocation);
		if (!inZone)
		{
			// Wet arrival: the arrival zone floods over WALKABLE tiles and the ocean is
			// sealed, so a water pin's zone is empty and on-foot arrival can never fire at
			// sea. A boat parked within the sea finish distance of a sailable target IS
			// arrival — wider than the land radius because a hull is several tiles of
			// entity and moors off the mark (configurable, default 12).
			for (int target : pathTargets)
			{
				if (SailingSea.isSailable(target)
					&& WorldPointUtil.distanceBetween(currentLocation, target)
						<= config.seaReachedDistance())
				{
					inZone = true;
					break;
				}
			}
		}
		if (!inZone)
		{
			return false;
		}
		RouteOption displayed = getDisplayedRoute();
		boolean roundTrip = displayed != null && displayed.isRoundTrip();
		if (altRoundTrip && !roundTrip)
		{
			return false;
		}
		if (roundTrip)
		{
			int turnaround = displayed.getTurnaroundIndex();
			if (turnaround >= 0 && displayedRouteProgress() < turnaround - 2)
			{
				return false;
			}
		}
		else if (isPathUnreachable())
		{
			return false;
		}
		return true;
	}

	/**
	 * Progress (path index) along the currently displayed route, from the directions overlay's
	 * tracker — 0 when that route isn't the one being tracked.
	 */
	public int displayedRouteProgress()
	{
		RouteOption displayed = getDisplayedRoute();
		return displayed == null || routeDirectionsOverlay == null
			? 0 : routeDirectionsOverlay.reachedIndexFor(displayed);
	}

	/**
	 * The first path index the player cannot click-walk to yet: at or beyond the first obstacle
	 * ahead of route progress they must interact with to cross — an agility shortcut, stairs, or a
	 * door not seen open. The path from there is drawn blocked (in the scene and minimap). Only
	 * meaningful for a displayed route; the classic path has no step data and is never blocked.
	 */
	public int blockedFromIndex(List<PathStep> path)
	{
		RouteOption route = getDisplayedRoute();
		if (route == null || route.getPath() != path)
		{
			return Integer.MAX_VALUE;
		}
		int progress = displayedRouteProgress();
		for (RouteDirections.Step step : getRouteDirections(route))
		{
			if (!step.gatesWalk() || step.getEndIndex() <= progress)
			{
				continue;
			}
			if (step.isDoor())
			{
				ClosedDoors.Door door = ClosedDoors.doorBetween(
					path.get(step.getStartIndex()).getPackedPosition(),
					path.get(step.getEndIndex()).getPackedPosition());
				if (door == null || ClosedDoors.state(client, door) == ClosedDoors.State.OPEN)
				{
					continue;
				}
			}
			return step.getEndIndex();
		}
		return Integer.MAX_VALUE;
	}

	/** Colour for the sailed portions of the displayed route (world-map sea tracks). */
	public Color getSailingPathColor()
	{
		return display.colourPathSailing;
	}

	public Color getPathColor()
	{
		// The displayed route is a static snapshot: colour it from its own endpoint.
		RouteOption displayed = getDisplayedRoute();
		if (displayed != null)
		{
			return isRouteEndTooFar(displayed) ? display.colourPathUnreachable : display.colourPath;
		}
		return session.inFlight() ? display.colourPathCalculating : display.colourPath;
	}

	/**
	 * Whether a destination is set and its routes are still computing with nothing on the overlay
	 * yet — the HUD's "Finding the best route" state. False as soon as a route is displayed (a
	 * same-destination regeneration keeps the previous route on screen instead).
	 */
	public boolean isFindingRoute()
	{
		return session.inFlight() && !pathTargets.isEmpty() && getDisplayedRoute() == null;
	}

	/**
	 * Mirrors {@link #isPathUnreachable()}'s tolerance for a displayed alternative route: a route that
	 * stops at the closest reachable tile (e.g. because the exact target tile is an NPC/object spot)
	 * still counts as reached for colouring while its endpoint is within the configured
	 * unreachable-distance threshold — only genuinely far endpoints get the unreachable colour.
	 */
	private boolean isRouteEndTooFar(RouteOption route)
	{
		if (route.isReached())
		{
			return false;
		}
		List<PathStep> path = route.getPath();
		Set<Integer> targets = session.lastTargets();
		if (path == null || path.isEmpty() || targets.isEmpty())
		{
			return false;
		}
		int endPoint = path.get(path.size() - 1).getPackedPosition();
		int closestTargetDistance = Integer.MAX_VALUE;
		for (int target : targets)
		{
			closestTargetDistance = Math.min(closestTargetDistance, WorldPointUtil.distanceBetween(target, endPoint));
		}
		return closestTargetDistance > display.unreachableTargetDistance;
	}

	public boolean isPathUnreachable()
	{
		RouteOption displayed = getDisplayedRoute();
		return displayed != null && isRouteEndTooFar(displayed);
	}

	/**
	 * Whether an alternative route actually gets to the target: the exact tile, or — for object and
	 * other adjacent destinations that legitimately end beside the goal (a bank booth, an altar) —
	 * within the unreachable-distance threshold of it. False means the destination can't be reached
	 * and the route only got to the closest reachable tile. Unlike {@link #isPathUnreachable()} this
	 * judges the alt-route's own endpoint, so a target reachable only by a teleport isn't misreported.
	 */
	public boolean routeReachesTarget(RouteOption route)
	{
		if (route == null)
		{
			return false;
		}
		if (route.isReached())
		{
			return true;
		}
		List<PathStep> path = route.getPath();
		Set<Integer> targets = pathTargets;
		if (path == null || path.isEmpty() || targets.isEmpty())
		{
			return true;  // not enough information to declare it unreachable
		}
		int endPoint = path.get(path.size() - 1).getPackedPosition();
		int closest = Integer.MAX_VALUE;
		for (int target : targets)
		{
			closest = Math.min(closest, WorldPointUtil.distanceBetween(target, endPoint));
		}
		return closest <= display.unreachableTargetDistance;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		// Quest Helper's own "Use Shortest Path plugin" toggle governs whether quest steps
		// reach GPS at all — flipping it shows/clears the panel's integration banner live.
		// Turning it ON re-arms a dismissed banner: the dismissal covered THIS off-period,
		// not a future regression.
		if ("questhelper".equals(event.getGroup()) && "useShortestPath".equals(event.getKey()))
		{
			if (Boolean.parseBoolean(event.getNewValue()))
			{
				configManager.unsetConfiguration(CONFIG_GROUP, "questHelperBannerDismissed");
			}
			companions.refresh();
			return;
		}
		if (!CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}

		cacheConfigValues();


		// Transport option changed; rerun pathfinding
		if ("defaultRouteCount".equals(event.getKey()))
		{
			session.setLimit(defaultRouteLimit());
		}

		// Display-order only: the keep-sailing preference re-ranks the routes it already has.
		if ("sailingKeepSailing".equals(event.getKey()))
		{
			resortRoutesByPriority();
		}

		if (ConfigOverrides.affectsRouting(event.getKey()))
		{
			if (hasPathTargets())
			{
				// Refresh the live config's availability and regenerate the routes with it — the
				// classic restart this used to do left the displayed (alternative) route stale.
				setDestination(pathStart, new HashSet<>(pathTargets));
				recomputeAlternatives();
			}
		}

		if ("rememberBank".equals(event.getKey()))
		{
			if (config.rememberBank())
			{
				// Turned on with the bank already seen this session: save it right away, so the
				// benefit does not depend on opening the bank again before logging out.
				bankSnapshots.rememberNow(client.getGameState() == GameState.LOGGED_IN);
			}
			else if (bankSnapshots.forgetStored())
			{
				// Turned off: the stored snapshot is gone, and so is this session's knowledge
				// when it came from the snapshot rather than the bank being opened.
				recomputeAlternatives();
			}
		}

		// Keys mirrored by the panel's configuration sections (POH, wilderness, balloons): rebuild
		// those sections so their labels track changes made from chat parsing or the config UI.
		if (altPanel != null
			&& (event.getKey().startsWith("balloon") || "pohSmartDetect".equals(event.getKey())
			|| "rememberBank".equals(event.getKey())
			|| ConfigOverrides.affectsRouting(event.getKey())))
		{
			SwingUtilities.invokeLater(altPanel::refreshConfigSections);
		}
	}

	/** Whether the original Shortest Path plugin is also enabled: the panel shows a warning. */
	public boolean isShortestPathConflict()
	{
		return companions != null && companions.isShortestPathConflict();
	}

	/**
	 * Whether Quest Helper runs WITHOUT its "Use Shortest Path plugin" option: the panel shows a
	 * dismissable banner explaining quest steps will not reach GPS until it is on.
	 */
	public boolean isQuestHelperPathingOff()
	{
		return companions != null && companions.isQuestHelperPathingOff();
	}

	@Subscribe
	public void onPluginChanged(net.runelite.client.events.PluginChanged event)
	{
		companions.refresh();
	}

	@Subscribe
	public void onExternalPluginsChanged(net.runelite.client.events.ExternalPluginsChanged event)
	{
		companions.refresh();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		sidebar.onGameState(event.getGameState());

		// Scene rebuild: the spawn-evidence set belongs to the old scene (LOADING fires before the
		// new scene's object spawns), and the once-per-scene chunk-dump log re-arms.
		if (GameState.LOADING.equals(event.getGameState()))
		{
			pohDetection.onSceneLoading();
		}

		// Logout: save any unsaved bank snapshot (with the profile key captured while logged in) and
		// forget everything this session detected about the character — bank, planted spirit trees,
		// house scan — so a different character logging in next doesn't inherit it. The right
		// character's snapshots are restored at the next login.
		if (GameState.LOGIN_SCREEN.equals(event.getGameState()) && pathfinderConfig != null)
		{
			bankSnapshots.forget();
			spiritTrees.reset();
			pohDetection.reset();
			boatBannerService.reset();
		}

		if (pathfinderConfig == null
			|| !GameState.LOGGING_IN.equals(lastLastGameState)
			|| !GameState.LOADING.equals(lastLastGameState = lastGameState)
			|| !GameState.LOGGED_IN.equals(lastGameState = event.getGameState()))
		{
			lastLastGameState = lastGameState;
			lastGameState = event.getGameState();
			return;
		}

		// Restored before the catalog refresh below, so in-bank availability, planted spirit trees
		// and the house scan state are right first time.
		pendingTasks.add(new PendingTask(client.getTickCount() + 1, this::restoreDetectionsFromConfig));
		pendingTasks.add(new PendingTask(client.getTickCount() + 1, pathfinderConfig::refresh));
		// Refresh the teleport-methods catalog (and any current routes) now that game state is available.
		pendingTasks.add(new PendingTask(client.getTickCount() + 1, this::recomputeAlternatives));
	}

	/**
	 * Refresh the pathfinder when the player hops worlds. The new world's type
	 * (e.g. seasonal) is what drives league-mode auto-detection in
	 * {@link gps.leagues.LeagueModeState}, so we need a fresh
	 * {@code PathfinderConfig.refresh()} pass after every hop.
	 */
	@Subscribe
	public void onWorldChanged(WorldChanged event)
	{
		if (pathfinderConfig == null)
		{
			return;
		}
		pendingTasks.add(new PendingTask(client.getTickCount() + 1, pathfinderConfig::refresh));
	}

	@Subscribe
	public void onPluginMessage(PluginMessage event)
	{
		if (!PluginMessageCodec.isOurs(event.getNamespace()))
		{
			return;
		}
		String action = event.getName();
		if (PluginMessageCodec.ACTION_PATH.equals(action))
		{
			Map<String, Object> data = event.getData();
			Map<String, Object> overrides = PluginMessageCodec.configOverrideOf(data);
			if (!overrides.isEmpty())
			{
				ConfigOverrides.apply(overrides);
				cacheConfigValues();
			}

			PluginMessageCodec.PathRequest request = PluginMessageCodec.parsePath(data);
			if (request == null)
			{
				return;
			}
			int start = request.start;
			if (start == WorldPointUtil.UNDEFINED)
			{
				start = getPlayerLocation();
				if (start == WorldPointUtil.UNDEFINED)
				{
					return;
				}
			}
			Set<Integer> targets = request.targets;
			// Attribute the destination for the GPS header (a "source" the sender chose, else
			// all we can say is that a plugin asked for it).
			targetSource = request.source;

			boolean useOld = targets.isEmpty() && hasPathTargets();
			Set<Integer> ends;
			if (useOld)
			{
				ends = new HashSet<>(pathTargets);
			}
			else
			{
				// A NEW destination from another plugin: this path bypasses setTargets, so arm the
				// journey timer here too — otherwise the arrival time carries over from whatever manual
				// destination was last set. Reusing the previous target keeps the running journey.
				armJourney();
				// An NPC's or object's own tile expands like a map pin; a transport origin among
				// the expansion is the interactable side (see Destinations.externalTargets).
				ends = Destinations.externalTargets(targets,
					pathfinderConfig != null ? pathfinderConfig.getMap() : null,
					pathfinderConfig != null ? pathfinderConfig::isTransportOrigin : null);
			}
			setDestination(start, ends, useOld);
		}
		else if (PluginMessageCodec.ACTION_CLEAR.equals(action))
		{
			ConfigOverrides.clear();
			cacheConfigValues();
			targetSource = null;
			setTarget(WorldPointUtil.UNDEFINED);
		}
	}

	/**
	 * Publishes the displayed route's transports to other plugins (the {@code postTransports}
	 * integration). Called when the displayed route settles or changes — it used to stream the
	 * classic search's path; the displayed route is what the player actually follows.
	 */
	public void postPluginMessages()
	{
		if (!hasPathTargets())
		{
			return;
		}
		if (ConfigOverrides.override("postTransports", config.postTransports()))
		{
			List<PathStep> currentPath = getDisplayPath();
			if (currentPath.isEmpty())
			{
				return;
			}
			Map<String, Object> data = PluginMessageCodec.encodeTransports(currentPath, this::transportsForEdge);
			eventBus.post(new PluginMessage(MESSAGE_NAMESPACE, PluginMessageCodec.ACTION_TRANSPORTS, data));
			eventBus.post(new PluginMessage(MESSAGE_NAMESPACE_LEGACY, PluginMessageCodec.ACTION_TRANSPORTS, data));
		}
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		mapMenu.onMenuOpened();
	}

	/**
	 * Tracks the balloon log storage from its chat messages — the crates' contents have no varbit,
	 * chat is the game's only client-side signal (the same approach the dedicated tictac7x-balloon
	 * plugin uses). Counts persist in config and let balloon flights be paid from storage.
	 */
	@Subscribe
	public void onChatMessage(net.runelite.api.events.ChatMessage event)
	{
		if (!config.balloonSmartMode()
			|| (event.getType() != net.runelite.api.ChatMessageType.SPAM
				&& event.getType() != net.runelite.api.ChatMessageType.MESBOX))
		{
			return;
		}
		Map<String, Integer> updates = BalloonLogStorage.parse(event.getMessage());
		for (Map.Entry<String, Integer> update : updates.entrySet())
		{
			configManager.setConfiguration(CONFIG_GROUP, update.getKey(), update.getValue());
		}
		if (!updates.isEmpty() && !config.balloonStorageSynced())
		{
			configManager.setConfiguration(CONFIG_GROUP, "balloonStorageSynced", true);
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		// The tick as named steps (plan step L10), in the order they always ran.
		maybeRefreshCatalog();
		cachePlayerLocation();
		boatBannerService.onTick();
		seaObstacles.onTick();
		runPendingTasks();
		maybeAutoComputeAlternatives();
		panelVarbits.onTick(client);
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return;
		}
		pohDetection.onTick();
		if (!hasPathTargets())
		{
			return;
		}
		int currentLocation = WorldPointUtil.fromLocalInstance(client, localPlayer);
		if (trackJourneyAndArrival(localPlayer, currentLocation))
		{
			return;
		}
		trackOffRoute(currentLocation);
	}

	/**
	 * Tick-cached position for Swing-thread consumers (the panel's destination search): live
	 * resolution walks player.getWorldView(), a client-thread-only call since the boat-position
	 * fix; the EDT reads this cache instead and can never trip it.
	 */
	private void cachePlayerLocation()
	{
		lastKnownPlayerLocation = getPlayerLocation();
	}

	private void runPendingTasks()
	{
		for (int i = 0; i < pendingTasks.size(); i++)
		{
			if (pendingTasks.get(i).check(client.getTickCount()))
			{
				pendingTasks.remove(i--).run();
			}
		}
	}

	/**
	 * Advances the journey clock and handles arrival. True when the player reached the
	 * destination (inside the arrival zone): the "Arrived!" panel is shown, including when the
	 * destination was set while already there (a never-started journey reports 0 rather than a
	 * stale duration), and the target is cleared.
	 */
	private boolean trackJourneyAndArrival(Player localPlayer, int currentLocation)
	{
		// The journey clock starts on the first move or animation after arming (JourneyTracker).
		journey.tick(currentLocation, localPlayer.getAnimation() != -1, System.currentTimeMillis());
		if (!hasArrived(currentLocation))
		{
			return false;
		}
		long elapsed = journey.elapsedMillis(System.currentTimeMillis());
		if (routeDirectionsOverlay != null)
		{
			routeDirectionsOverlay.markArrived(targetSource, elapsed);
		}
		if (altPanel != null)
		{
			altPanel.markArrived(elapsed);
		}
		setTarget(WorldPointUtil.UNDEFINED);
		return true;
	}

	/**
	 * The off-route bands (see OffRouteTracker): a warning the overlays show, a recalculation
	 * from the player's current position (one at a time: distance is measured against the OLD
	 * path until the new routes land, so a player who keeps walking would otherwise restart the
	 * generation every moved tick), or cancelling the route when the player prefers that.
	 */
	private void trackOffRoute(int currentLocation)
	{
		boolean aboard = client.getVarbitValue(net.runelite.api.gameval.VarbitID.SAILING_BOARDED_BOAT) != 0;
		OffRouteTracker.Verdict verdict = offRoute.tick(currentLocation,
			() -> OffRouteTracker.distanceFromPath(currentLocation, getDisplayPath(), getDisplayedRoute()),
			config.recalculateDistance(), config.offRouteWarnDistance(), config.autoRecalculate(),
			config.cancelInstead(), aboard);
		switch (verdict)
		{
			case CANCEL:
				setTarget(WorldPointUtil.UNDEFINED);
				break;
			case RECALCULATE:
				if (!session.inFlight())
				{
					recalculateFrom(currentLocation, pathTargets);
				}
				break;
			default:
				break;
		}
	}

	/**
	 * Recompute the route from a new start (the player's current, off-route position) to the same
	 * targets. Triggered explicitly because the tick-level auto-compute is keyed on the target SET —
	 * which hasn't changed here — so it would not refire on its own. The stale selection is dropped
	 * so the fresh generation's route takes over rather than the overlay clinging to the old line.
	 */
	private void recalculateFrom(int start, Set<Integer> targets)
	{
		session.clearSelection();
		session.resetBudget(defaultRouteLimit());
		Set<Integer> ends = new HashSet<>(targets);
		pathStart = start;
		triggerAlternatives(start, ends);
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		mapMenu.onMenuEntryAdded(event);
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.INV || event.getContainerId() == InventoryID.WORN)
		{
			// Only mark the catalog dirty when the routing-relevant slice of the inventory and
			// equipment actually changed: the dependency index knows every item id (and quantity
			// threshold) any transport requirement can read, so logs, ore, food and loot pass
			// through without ever scheduling a refresh (issues #23/#24).
			if (pathfinderConfig == null)
			{
				catalogDirty = true;
				return;
			}
			long fingerprint = pathfinderConfig.getRoutingItemDependencies().fingerprint(
				client.getItemContainer(InventoryID.INV), client.getItemContainer(InventoryID.WORN));
			if (!routingItemsFingerprintValid || fingerprint != routingItemsFingerprint)
			{
				routingItemsFingerprint = fingerprint;
				routingItemsFingerprintValid = true;
				catalogDirty = true;
			}
			return;
		}
		if (event.getContainerId() != InventoryID.BANK)
		{
			return;
		}
		if (bankSnapshots.bankOpened(event.getItemContainer()))
		{
			// First sight of the bank this session: regenerate so the availability map is rebuilt
			// with the bank contents — banked teleports classify IN_BANK (usable in Inv + bank
			// mode) and the catalog header count updates. Also clears the panel warning. NOT
			// during a round trip: opening the bank is the trip's halfway point, and regenerating
			// would discard the displayed route (and with it the way back).
			if (altRoundTrip)
			{
				refreshPanel(session.inFlight());
			}
			else
			{
				recomputeAlternatives();
			}
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		fairyRingLog.widgetLoaded(event.getGroupId(), hasPathTargets());
		spiritTrees.widgetLoaded(event.getGroupId());
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		fairyRingLog.widgetClosed(event.getGroupId());
		// Bank closed: one regeneration per bank session, so items withdrawn or deposited are
		// reflected in the method availability (and the catalog counts) — recomputing on every
		// in-bank container change would run a generation per deposit. NOT during a round trip:
		// banking mid-trip is the whole point, and regenerating would discard the way back.
		if (event.getGroupId() == InterfaceID.BANKMAIN && bankSnapshots.isKnown() && !altRoundTrip)
		{
			recomputeAlternatives();
		}
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankSnapshots.persist();
		}
	}

	/**
	 * Restores everything this character's previous sessions detected — bank contents, planted
	 * spirit trees, house furniture — so routing starts from the known state instead of asking for
	 * a fresh sync of each. Every piece is superseded by its live source the moment that source is
	 * seen (bank opened, travel menu read, house entered).
	 */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (boatBannerService != null && boatBannerService.tracks(event.getVarbitId()))
		{
			boatBannerService.markDirty();
		}
	}

	/** Owned boats as {name, port label} rows for the panel's sailing section; null = never
	 * collected for this character. */
	public List<String[]> getBoatBanner()
	{
		return boatBannerService == null ? null : boatBannerService.banner();
	}

	/** Whether the banner reflects this session's live varbits rather than a restored snapshot. */
	public boolean isBoatBannerLive()
	{
		return boatBannerService != null && boatBannerService.isLive();
	}

	private void restoreDetectionsFromConfig()
	{
		bankSnapshots.restore();
		spiritTrees.restore();
		pohDetection.restore();
		boatBannerService.restore();
		// The panel's sections label their sync state — reflect what was just restored.
		if (altPanel != null)
		{
			SwingUtilities.invokeLater(altPanel::refreshConfigSections);
		}
	}

	@Subscribe
	public void onPostClientTick(PostClientTick event)
	{
		fairyRingLog.onPostClientTick(hasPathTargets());
	}

	/**
	 * WARNING: This is a legacy wrapper for coarse display-oriented callers only.
	 * <p>
	 * It collapses banked/unbanked transport availability into a single view via
	 * PathfinderConfig.getTransports(), which is not valid for path-state-sensitive logic.
	 * <p>
	 * Do not use this for reasoning about which transports are available at a specific
	 * step of a path. Use PathfinderConfig.getTransportAvailability(boolean) and the
	 * path's PathStep state instead.
	 */
	public PrimitiveIntHashMap<Transport[]> getTransports()
	{
		return pathfinderConfig.getTransports();
	}

	/**
	 * Whether the DISPLAYED route uses a teleport method to reach the tile after {@code fromIndex}
	 * (edge {@code fromIndex} → {@code fromIndex + 1}). Drives the teleport pulse straight from the
	 * shown route's method edges — {@link #transportsForEdge} re-derives transports from the classic
	 * config, whose teleport-item setting (e.g. "Inventory (perm)") excludes charged jewellery, so a
	 * charged-item leg on an alternative route never pulsed.
	 */
	public boolean displayedRouteTeleportsAt(int fromIndex)
	{
		TeleportMethod method = displayedRouteMethodAt(fromIndex);
		return method != null && method.getType() != null && method.getType().isTeleport();
	}

	/**
	 * The method the DISPLAYED route uses to reach the tile after {@code fromIndex}, or null when
	 * that edge is plain walking. Lets the world overlay label a leg (e.g. "Varrock tablet") that
	 * {@link #transportsForEdge} can't re-derive because the classic config's teleport-item setting
	 * excludes it (charged/consumable items under a perm-only setting).
	 */
	public TeleportMethod displayedRouteMethodAt(int fromIndex)
	{
		RouteOption route = getDisplayedRoute();
		return route == null ? null : route.methodArrivingAt(fromIndex + 1);
	}

	/** The transports a rendered path edge rides (see EdgeTransports), for the overlays and directions. */
	public Set<Transport> transportsForEdge(PathStep currentStep, PathStep nextStep)
	{
		return EdgeTransports.forEdge(pathfinderConfig, currentStep, nextStep);
	}


	// The helm-preference toggle, cached for the comparator (read on the service thread).
	private volatile boolean cachedKeepSailing = true;
	// The overlays' display settings, one snapshot per config change (see OverlaySettings).
	private volatile OverlaySettings display;

	/** The display settings the overlays read; a fresh snapshot after every config change. */
	OverlaySettings display()
	{
		return display;
	}

	private void cacheConfigValues()
	{
		cachedKeepSailing = ConfigOverrides.override("sailingKeepSailing", config.sailingKeepSailing());
		display = OverlaySettings.from(config);
	}

	/** "Set GPS Target" from the map menu: the pick is attributed to the map pin. */
	void pinTarget(int packed)
	{
		targetSource = "map pin";
		setTarget(packed);
	}

	/** "Clear Path" from the map menu. */
	void clearPinnedTarget()
	{
		targetSource = null;
		setTarget(WorldPointUtil.UNDEFINED);
	}

	/** "Find closest" from a world-map icon: every destination of that kind, the nearest wins. */
	void findClosest(String destinationType)
	{
		targetSource = "map pin";
		setTargets(pathfinderConfig.getDestinations(destinationType), true);
	}

	private void setTarget(int target)
	{
		setTarget(target, false);
	}

	/**
	 * Sets the GPS destination to a searched place/amenity (from the panel search box), recording
	 * where it came from for the directions header. Runs on the client thread.
	 */
	public void setDestination(int packedPosition, String source)
	{
		clientThread.invokeLater(() ->
		{
			targetSource = source;
			// Searched destinations can sit on unwalkable tiles (a place label on a fountain):
			// expand to the nearest walkable ring, like map pins — walkable tiles stay exact.
			// The world-map pin stays on the destination itself.
			Set<Integer> targets = new HashSet<>(Destinations.walkableTargets(
				pathfinderConfig != null ? pathfinderConfig.getMap() : null, packedPosition,
				pathfinderConfig != null ? pathfinderConfig::isTransportOrigin : null));
			if (targets.size() > 1)
			{
				markerTarget = packedPosition;
			}
			setTargets(targets, false);
		});
	}

	/**
	 * Routes to the NEAREST of an amenity category (bank, altar, ...): sets every tile of the
	 * category as a target and generates the ranked alternative routes, so the shortest paths —
	 * with the teleports currently available — surface first, whichever site they reach.
	 */
	public void setNearestCategory(Set<Integer> tiles, String source)
	{
		setNearestCategory(tiles, source, false);
	}

	/**
	 * The round-trip variant additionally routes BACK to the current position: every produced
	 * route goes out to a site and home again, ranked by the combined cost — the best round-trip
	 * bank is not necessarily the nearest one-way bank.
	 */
	public void setNearestCategory(Set<Integer> tiles, String source, boolean roundTrip)
	{
		if (tiles == null || tiles.isEmpty())
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			targetSource = source;
			setTargets(new HashSet<>(tiles), false);
			// After setTargets: it resets the round-trip flag for ordinary destinations.
			altRoundTrip = roundTrip;
			recomputeAlternatives();
		});
	}

	/**
	 * The player's packed world position, or {@link WorldPointUtil#UNDEFINED} when not logged
	 * in — BOAT-AWARE: aboard, the raw local position lives in the boat's sub-WorldView
	 * (template-band coordinates that broke progress tracking and hid the route overlays the
	 * moment the player boarded); the Player overload resolves through the boat WorldEntity,
	 * returning UNDEFINED transiently during view swaps.
	 */
	private volatile int lastKnownPlayerLocation = WorldPointUtil.UNDEFINED;

	/** Where the player was as of the last game tick — safe from ANY thread (see onGameTick). */
	public int getLastKnownPlayerLocation()
	{
		return lastKnownPlayerLocation;
	}

	public int getPlayerLocation()
	{
		Player local = client.getLocalPlayer();
		return local == null ? WorldPointUtil.UNDEFINED
			: WorldPointUtil.fromLocalInstance(client, local);
	}

	private void setTarget(int target, boolean append)
	{
		Set<Integer> targets = new HashSet<>();
		if (target != WorldPointUtil.UNDEFINED)
		{
			// A pin on an unwalkable tile (furniture, a fence, an NPC's tile from Quest Helper) can
			// never be settled by the search — it would explore the entire map and fall back to a
			// closest-tile path (captured in-game: 11 exhausted searches, 8.2s). Target the nearest
			// walkable ring instead; walkable pins stay exact, and the map pin stays on the tile.
			Set<Integer> walkable = Destinations.walkableTargets(
				pathfinderConfig != null ? pathfinderConfig.getMap() : null, target,
				pathfinderConfig != null ? pathfinderConfig::isTransportOrigin : null);
			if (walkable.size() > 1)
			{
				markerTarget = target;
			}
			targets.addAll(walkable);
		}
		setTargets(targets, append);
	}

	private void setTargets(Set<Integer> targets, boolean append)
	{
		// Ordinary destinations are one-way; the round-trip entry point re-sets this after.
		altRoundTrip = false;
		// A fresh destination starts at the default cost band; "show more" widens it from there.
		// (loadMoreRoutes bumps the multiple and regenerates without going through setTargets.)
		session.resetCostMultiple();
		if (targets == null || targets.isEmpty())
		{
			pathStart = WorldPointUtil.UNDEFINED;
			pathTargets = Set.of();

			worldMapPointManager.removeIf(x -> x == marker);
			marker = null;
			session.clearSelection();
			session.setLimit(defaultRouteLimit());
			// Keep the teleport-methods catalog visible with no target selected.
			triggerAlternatives(WorldPointUtil.UNDEFINED, new HashSet<>());
		}
		else
		{
			Player localPlayer = client.getLocalPlayer();
			if (localPlayer == null)
			{
				return;
			}
			worldMapPointManager.removeIf(x -> x == marker);
			// A destination expanded to its walkable perimeter (a searched bank booth and its
			// surround) still gets its pin: on the expansion's centre, not the single-target tile.
			int markerTile = markerTarget != WorldPointUtil.UNDEFINED ? markerTarget
				: (targets.size() == 1 ? targets.iterator().next() : WorldPointUtil.UNDEFINED);
			markerTarget = WorldPointUtil.UNDEFINED;
			if (markerTile != WorldPointUtil.UNDEFINED)
			{
				marker = new WorldMapPoint(WorldPointUtil.unpackWorldPoint(markerTile), MARKER_IMAGE);
				marker.setName("Target");
				marker.setTarget(marker.getWorldPoint());
				marker.setJumpOnClick(true);
				worldMapPointManager.add(marker);
			}

			int start = WorldPointUtil.fromLocalInstance(client, localPlayer);
			offRoute.reset(start);
			Set<Integer> destinations = new HashSet<>(targets);
			if (append)
			{
				destinations.addAll(pathTargets);
			}
			// Arm the journey timer: it starts counting from the player's first movement.
			armJourney();
			// The routes themselves are generated by the tick-level auto-compute (keyed on the
			// target-set change) or the panel's "Find routes" button.
			setDestination(start, destinations, append);
		}
	}

	// --- Alternative-routes feature (driven by ShortestPathPanel) ---

	/** The journey wall-clock start, or 0 while it hasn't begun (armed, waiting for movement). */
	public long getJourneyStartMillis()
	{
		return journey.startMillis();
	}

	/**
	 * Hidden type-toggle keys with NO control in the panel: a value stored by an old build (or by
	 * upstream Shortest Path's visible config, pre-fork) is unreachable and silently overrides the
	 * on-by-default decision — a field report had "useCharterShips=false" with no checkbox
	 * anywhere to see or undo it, and charters simply never appeared. Cleared at startup so the
	 * defaults apply; per-method control is the catalog's exclusions. Panel-backed toggles
	 * (sailing, balloons, POH and its variants, spirit trees) and the deliberate seasonal master
	 * switch are NOT listed here.
	 */
	static final String[] UNSURFACED_TYPE_TOGGLES = {
		"useAgilityShortcuts", "useGrappleShortcuts", "useBoats", "useCanoes", "useCharterShips",
		"useShips", "useFairyRings", "useGnomeGliders", "useMagicCarpets", "useMagicMushtrees",
		"useMinecarts", "useMountainGuides", "useQuetzals", "useTeleportationLevers",
		"useTeleportationPortals", "useTeleportationSpells", "useTeleportationMinigames",
		"useWildernessObelisks"};

	private void clearUnsurfacedTypeToggles()
	{
		for (String key : UNSURFACED_TYPE_TOGGLES)
		{
			if (configManager.getConfiguration(CONFIG_GROUP, key) != null)
			{
				log.info("clearing stranded hidden toggle {} (no panel control; the default applies)", key);
				configManager.unsetConfiguration(CONFIG_GROUP, key);
			}
		}
	}

	/** Re-arms the journey timer so it recounts from the player's next movement. */
	private void armJourney()
	{
		journey.arm();
	}

	/**
	 * The live collision map, for the progress tracker's wall-aware checks and the dev audit's
	 * capture lane expansion. Null until loaded.
	 */
	public gps.pathfinder.CollisionMap getCollisionMap()
	{
		PathfinderConfig config = pathfinderConfig;
		return config != null ? config.getMap() : null;
	}

	/** Why every route of the current page stops short, for the panel's status (plan step N12). */
	public AlternativeRoutesService.UnreachableCause getUnreachableCause()
	{
		AlternativeRoutesService service = altRoutesService;
		return service != null ? service.lastUnreachableCause() : AlternativeRoutesService.UnreachableCause.NONE;
	}

	public RouteOption getDisplayedRoute()
	{
		return session.displayed(pathTargets);
	}

	/**
	 * The path the overlays should draw: the displayed route's (the selected one, or by default the
	 * first route of the current alternatives list, so the drawn path reflects the chosen
	 * mode/exclusions). Empty when no route is displayed.
	 */
	public List<PathStep> getDisplayPath()
	{
		RouteOption route = getDisplayedRoute();
		return route != null ? route.getPath() : List.of();
	}

	/**
	 * Path indexes of the displayed route where a SAILING leg departs — the overlays draw
	 * those jumps as real sea tracks ({@link SailingSea#seaPath}) instead of dashed lines.
	 */
	public Set<Integer> getDisplaySailingEdges()
	{
		RouteOption route = getDisplayedRoute();
		if (route == null)
		{
			return Set.of();
		}
		return route.sailingJumpDepartures();
	}

	public Set<TeleportMethod> getUserExclusions()
	{
		return new HashSet<>(userExclusions);
	}

	// --- Method priorities and preference biases (see RoutePreferences) ------------------------

	// Suppliers: the plugin's injected services arrive after field initialisation, and tests
	// drive the effective order on a bare plugin.
	private final RoutePreferences preferences = new RoutePreferences(userExclusions, this::keepSailingFirst,
		() -> configManager, () -> gson, CONFIG_GROUP);

	/** The method's tier: EXCLUDED when in the exclusion set, else its stored tier or NORMAL. */
	public MethodPriority getMethodPriority(TeleportMethod method)
	{
		return preferences.priorityOf(method);
	}

	/**
	 * Sets a method's tier. EXCLUDED delegates to the exclusion set (search-affecting, flags the
	 * stale banner); every other tier is ranking-only: the current list re-sorts immediately.
	 * Choosing a non-EXCLUDED tier for an excluded method also un-excludes it.
	 */
	public void setMethodPriority(TeleportMethod method, MethodPriority priority)
	{
		clientThread.invoke(() -> setMethodPriorityOnClientThread(method, priority));
	}

	private void setMethodPriorityOnClientThread(TeleportMethod method, MethodPriority priority)
	{
		if (priority == MethodPriority.EXCLUDED)
		{
			// Exclusion is a MASK over the stored tier, not a replacement: the tier stays stored
			// (shadowed by the EXCLUDED read-back) so re-including, via this menu, the category
			// toggle, or clearExclusions, restores the user's tuning. This matches the
			// section-toggle path, which never touched the tiers in the first place.
			excludeMethod(method);
			return;
		}
		if (userExclusions.contains(method))
		{
			includeMethod(method);
		}
		preferences.setTier(method, priority);
		resortRoutesByPriority();
	}

	/** The walk-preference bias in seconds (negative effective ETA for the pure-walk route). */
	public int getWalkPreferenceSeconds()
	{
		return preferences.walkPreferenceSeconds();
	}

	public void setWalkPreferenceSeconds(int seconds)
	{
		preferences.setWalkPreferenceSeconds(seconds);
		resortRoutesByPriority();
	}

	/** The bank-detour bias in seconds: positive prefers via-bank routes, negative avoids them. */
	public int getBankPreferenceSeconds()
	{
		return preferences.bankPreferenceSeconds();
	}

	public void setBankPreferenceSeconds(int seconds)
	{
		preferences.setBankPreferenceSeconds(seconds);
		resortRoutesByPriority();
	}

	/** The route's ranking adjustment in seconds (see RoutePreferences.adjustmentSeconds). */
	public int routeAdjustmentSeconds(RouteOption route)
	{
		return preferences.adjustmentSeconds(route);
	}

	boolean keepSailingFirst()
	{
		PathfinderConfig pathConfig = pathfinderConfig;
		return cachedKeepSailing && pathConfig != null && pathConfig.isOnSailingBoat();
	}

	/** Stable re-sort of the current list (tiers changed): display-only, no regeneration. */
	private void resortRoutesByPriority()
	{
		session.resort(preferences.effectiveOrder());
		refreshPanel(session.inFlight());
	}

	/** Applies the effective order to a freshly generated list (called from the update stream). */
	List<RouteOption> sortByEffectiveOrder(List<RouteOption> routes)
	{
		return preferences.sorted(routes);
	}

	// The house location and balloon unlock varbits the panel shows (see PanelVarbits), cached each tick.
	private final PanelVarbits panelVarbits = new PanelVarbits();

	/** The player's house location name (varbit 2187), or null when no house is detected. */
	public String getHouseLocationName()
	{
		return panelVarbits.houseLocationName();
	}

	/**
	 * A recognised piece of POH furniture spawning is unambiguous "we're inside a house" evidence
	 * (see {@link PohScanner#isRecognised}), independent of any coordinate math.
	 */
	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		pohDetection.furnitureSpawned(event.getGameObject().getId());
	}

	/** Whether the player's house has been scanned this session (its furniture is known). */
	public boolean isPohScanned()
	{
		return pohDetection != null && pohDetection.isScanned();
	}

	/** The furniture the last house scan recognised, as display names (empty until scanned). */
	public List<String> getDetectedPohFurniture()
	{
		return pohDetection == null ? List.of() : pohDetection.detectedNames();
	}

	/**
	 * The balloon log types that warrant a low-storage warning: routes the player has unlocked
	 * (per the cached varbits) whose stored count sits below the configured threshold. Empty when
	 * smart mode is off, the threshold is 0, the storage was never synced, or nothing is low.
	 */
	public List<String> getBalloonLowLogTypes()
	{
		return panelVarbits.balloonLowLogTypes(config);
	}

	/** The chat-parsed stored log counts, in {@link BalloonLogStorage#TYPE_NAMES} order. */
	public int[] getBalloonStoredCounts()
	{
		return PanelVarbits.balloonStoredCounts(config);
	}

	/** Item images for the panel's Log storage icons. */
	public net.runelite.client.game.ItemManager getItemManager()
	{
		return itemManager;
	}

	/**
	 * The specific reason a catalog method is unavailable ("Requires 60 Mining", "Missing item:
	 * Willow logs"), or null when nothing more specific than its status is known.
	 */
	public String methodUnavailabilityDetail(TeleportMethod method)
	{
		AlternativeRoutesService service = altRoutesService;
		return service == null ? null : service.getAvailabilityDetails().get(method);
	}

	/** The live config, for panel controls that mirror config items (the configuration sections). */
	public ShortestPathConfig getGpsConfig()
	{
		return config;
	}

	/**
	 * Whether the spirit-tree travel menu has been seen this session, so the planted-tree set is
	 * known. Until then the panel shows a sync hint and farmable trees are treated conservatively.
	 */
	public boolean isSpiritTreeSynced()
	{
		return spiritTrees != null && spiritTrees.isSynced();
	}

	/**
	 * The farmable spirit trees currently detected as planted-and-grown (menu order), or empty when
	 * not synced. For the panel's Spirit trees section.
	 */
	public List<String> getAvailablePlantedSpiritTrees()
	{
		return spiritTrees == null ? List.of() : spiritTrees.planted();
	}

	/**
	 * Writes a setting from the panel's configuration sections (POH, wilderness, balloons).
	 * Persisting through the ConfigManager keeps the panel and the RuneLite config UI in sync (same
	 * keys), and the resulting ConfigChanged event re-caches values and regenerates the routes
	 * (route-affecting keys per ConfigOverrides.affectsRouting).
	 */
	public void setPanelConfig(String key, Object value)
	{
		configManager.setConfiguration(CONFIG_GROUP, key, value);
	}

	/**
	 * The engine's own accessible-bank standing tiles (upstream-curated; the same set that flips
	 * bank-detour routing). Unioned into "nearest bank" targets so the feature can never disagree
	 * with what the engine considers a bank — the amenity dump misses oddly-named bank objects
	 * (Slepe's "Bank Chest-wreck" defeated its name matching).
	 */
	public Set<Integer> getEngineBankTiles()
	{
		if (pathfinderConfig == null)
		{
			return Set.of();
		}
		Set<Integer> tiles = pathfinderConfig.getDestinations("bank");
		return tiles == null ? Set.of() : tiles;
	}

	public void selectRoute(int index)
	{
		clientThread.invoke(() -> selectRouteOnClientThread(index));
	}

	private void selectRouteOnClientThread(int index)
	{
		// Toggle: clicking the route that's already shown hides it (RouteSession.select).
		if (session.select(index))
		{
			// Picking a different path starts a new journey: time it from here, not from the
			// original destination (re-arm; the timer restarts on the next movement).
			armJourney();
			// The displayed path changed: republish it to other plugins (postTransports).
			postPluginMessages();
			refreshPanel(false);
		}
	}

	public void excludeMethod(TeleportMethod method)
	{
		clientThread.invoke(() -> excludeMethodOnClientThread(method));
	}

	private void excludeMethodOnClientThread(TeleportMethod method)
	{
		if (method != null && userExclusions.add(method))
		{
			choices.saveExclusions(userExclusions);
			// No recalculation here: exclusions apply on the next "Refresh routes to target" (or any
			// other recompute); this just refreshes the panel so the catalog icons and counts update.
			refreshPanel(session.inFlight());
		}
	}

	public void includeMethod(TeleportMethod method)
	{
		clientThread.invoke(() -> includeMethodOnClientThread(method));
	}

	private void includeMethodOnClientThread(TeleportMethod method)
	{
		if (method != null && userExclusions.remove(method))
		{
			choices.saveExclusions(userExclusions);
			// No recalculation here: exclusions apply on the next "Refresh routes to target" (or any
			// other recompute); this just refreshes the panel so the catalog icons and counts update.
			refreshPanel(session.inFlight());
		}
	}

	public void excludeMethods(Collection<TeleportMethod> methods)
	{
		boolean changed = false;
		if (methods != null)
		{
			for (TeleportMethod method : methods)
			{
				changed |= userExclusions.add(method);
			}
		}
		if (changed)
		{
			choices.saveExclusions(userExclusions);
			// No recalculation here: exclusions apply on the next "Refresh routes to target" (or any
			// other recompute); this just refreshes the panel so the catalog icons and counts update.
			refreshPanel(session.inFlight());
		}
	}

	public void includeMethods(Collection<TeleportMethod> methods)
	{
		boolean changed = false;
		if (methods != null)
		{
			for (TeleportMethod method : methods)
			{
				changed |= userExclusions.remove(method);
			}
		}
		if (changed)
		{
			choices.saveExclusions(userExclusions);
			// No recalculation here: exclusions apply on the next "Refresh routes to target" (or any
			// other recompute); this just refreshes the panel so the catalog icons and counts update.
			refreshPanel(session.inFlight());
		}
	}

	/** The search box's recent selections, most recent first. */
	public List<Destinations.Entry> getSearchHistory()
	{
		return searchHistory;
	}

	/** Records a search selection at the front of the persisted history (deduplicated, capped). */
	public void recordSearchSelection(Destinations.Entry entry)
	{
		List<Destinations.Entry> updated = SearchHistory.push(searchHistory, entry);
		searchHistory = updated;
		choices.saveSearchHistory(updated);
	}

	/** The player's saved favourite positions, in saved order. */
	public List<Destinations.Entry> getFavoriteDestinations()
	{
		return favoriteDestinations;
	}

	/** Saves a favourite position; a favourite with the same label is replaced. */
	public void addFavoriteDestination(String label, int packedPosition)
	{
		List<Destinations.Entry> updated = new ArrayList<>();
		for (Destinations.Entry entry : favoriteDestinations)
		{
			if (!entry.name.equals(label))
			{
				updated.add(entry);
			}
		}
		if (updated.size() < ChoiceStore.FAVORITES_LIMIT)
		{
			updated.add(new Destinations.Entry("favorite", label, packedPosition));
		}
		favoriteDestinations = updated;
		choices.saveFavorites(updated);
	}

	public void removeFavoriteDestination(Destinations.Entry favorite)
	{
		List<Destinations.Entry> updated = new ArrayList<>();
		for (Destinations.Entry entry : favoriteDestinations)
		{
			if (!entry.name.equals(favorite.name) || entry.packedPosition != favorite.packedPosition)
			{
				updated.add(entry);
			}
		}
		favoriteDestinations = updated;
		choices.saveFavorites(updated);
	}

	public void clearExclusions()
	{
		clientThread.invoke(this::clearExclusionsOnClientThread);
	}

	private void clearExclusionsOnClientThread()
	{
		if (!userExclusions.isEmpty())
		{
			// Seasonal (Leagues) methods are gated by their own "Enable seasonal transports" toggle,
			// not the exclusion set, so clearing exclusions no longer needs to re-seed them.
			userExclusions.clear();
			choices.saveExclusions(userExclusions);
			// No recalculation here: exclusions apply on the next "Refresh routes to target" (or any
			// other recompute); this just refreshes the panel so the catalog icons and counts update.
			refreshPanel(session.inFlight());
		}
	}

	/**
	 * Manually (re)compute the alternative routes for whatever destination GPS currently has
	 * set — read live from the active pathfinder. With no target set, just refreshes the methods catalog.
	 */
	/** Clears the current destination and its route (panel Clear button / clear-path hotkey). */
	public void clearTarget()
	{
		getClientThread().invokeLater(() -> setTarget(WorldPointUtil.UNDEFINED));
	}

	public void recomputeAlternatives()
	{
		getClientThread().invokeLater(() ->
		{
			Set<Integer> targets = pathTargets;
			if (!targets.isEmpty())
			{
				int start = altStart();
				log.debug("[alt-routes] Find routes: target set, searchStart={}, target={}",
					WorldPointUtil.unpackWorldPoint(start),
					WorldPointUtil.unpackWorldPoint(targets.iterator().next()));
				session.setLimit(defaultRouteLimit());
				triggerAlternatives(start, new HashSet<>(targets));
			}
			else
			{
				log.debug("[alt-routes] Find routes: no target set");
				triggerAlternatives(WorldPointUtil.UNDEFINED, new HashSet<>());
			}
		});
	}

	/**
	 * The start tile to search alternatives from: the player's current (instance-correct) location,
	 * matching what GPS itself uses for recalculation, falling back to the destination's recorded
	 * start. Must be called on the client thread.
	 */
	private int altStart()
	{
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer != null)
		{
			return WorldPointUtil.fromLocalInstance(client, localPlayer);
		}
		return pathStart;
	}

	/**
	 * The configured number of routes to search for per query (clamped to the service's hard cap).
	 */
	private int defaultRouteLimit()
	{
		return routeLimitFor(altPanelVisible, ConfigOverrides.override("defaultRouteCount", config.defaultRouteCount()));
	}

	/**
	 * The route budget a generation runs with — the SAME whether the side panel is shown or
	 * hidden. A panel-hidden run used to search only the primary route (one search, a handful of
	 * seeds) and found a different "best" often enough that opening the panel visibly changed
	 * the overlay's route (issue #18, field reports). A full run costs tens to a few hundred
	 * milliseconds more and streams its first route at the same moment, so the overlay shows that
	 * one provisionally and settles once — consistently, with or without the panel. The panel
	 * flag is taken only to state the rule where it is decided. Pure, unit-tested.
	 */
	static int routeLimitFor(boolean panelVisible, int configured)
	{
		return Math.max(1, Math.min(configured, 25));
	}

	public boolean canLoadMoreRoutes()
	{
		return session.canLoadMore();
	}

	public void loadMoreRoutes()
	{
		clientThread.invoke(this::loadMoreRoutesOnClientThread);
	}

	private void loadMoreRoutesOnClientThread()
	{
		// Each poll grows both dimensions of the cap so genuinely more routes surface (see
		// RouteSession.widen): the cost band and the route-count budget, toward the service's
		// runaway backstop. A new destination resets both.
		if (!session.widen(defaultRouteLimit(), AlternativeRoutesService.MAX_ROUTES_CAP))
		{
			return;
		}
		triggerAlternatives(session.lastStart(), session.lastTargetsCopy());
	}

	// Directions for the currently displayed route, built once per route (the overlay renders every
	// frame; the path scan only reruns when the displayed route object changes). One immutable
	// holder, not two fields: the render thread and the client thread both read this, and a
	// two-field cache could publish route A's key beside route B's steps.
	private static final class DirectionsCache
	{
		final RouteOption route;
		final List<RouteDirections.Step> steps;

		DirectionsCache(RouteOption route, List<RouteDirections.Step> steps)
		{
			this.route = route;
			this.steps = steps;
		}
	}

	private volatile DirectionsCache directionsCache = new DirectionsCache(null, List.of());

	/**
	 * The step-by-step directions for {@code route}, cached per route instance.
	 */
	public List<RouteDirections.Step> getRouteDirections(RouteOption route)
	{
		DirectionsCache cached = directionsCache;
		if (route != cached.route)
		{
			cached = new DirectionsCache(route, RouteDirections.build(this, route));
			directionsCache = cached;
		}
		return cached.steps;
	}

	/**
	 * Where the current destination came from ("map pin", "Quest Helper", ...) or null when unknown.
	 */
	public String getTargetSource()
	{
		return targetSource;
	}

	/**
	 * Reports an issue WITHOUT sending or touching anything outside the panel: the routing
	 * context (see IssueReport) is shown in a text box at the top of the panel for the player to
	 * copy BY HAND, and a plain, static GitHub new-issue link opens (the repo's issue template
	 * says where to paste). No pre-filled URL, no clipboard API: nothing for the hub review to
	 * flag, and the player sees exactly what they share.
	 */
	public void reportIssue()
	{
		// Item names come from the item definitions, which are client-thread-only: build the
		// whole body there; the panel work then happens on the EDT.
		clientThread.invokeLater(() ->
		{
			final String context = new IssueReport(this).body();
			SwingUtilities.invokeLater(() ->
			{
				if (altPanel != null)
				{
					altPanel.showReportContext(context);
				}
				net.runelite.client.util.LinkBrowser.browse(BuildInfo.GITHUB_NEW_ISSUE);
			});
		});
	}

	/** Writes the routing-state snapshot (see DebugSnapshot); the panel's "Save debug snapshot". */
	public void captureDebugSnapshot()
	{
		clientThread.invokeLater(() -> new DebugSnapshot(this).capture());
	}

	/** World-map pixel projection, for the map overlays. */
	WorldMapProjection worldMap()
	{
		return worldMap;
	}

	/** The minimap clip shape, for the minimap overlay. */
	MinimapClip minimapClip()
	{
		return minimapClip;
	}

	// ---- State the diagnostics classes (IssueReport, DebugSnapshot) read; package-private ----

	RouteSession session()
	{
		return session;
	}

	List<TeleportMethod> teleportCatalog()
	{
		return teleportCatalog;
	}

	Map<TeleportMethod, MethodAvailability> unavailableMethods()
	{
		return unavailableMethods;
	}

	PohDetectionService pohDetection()
	{
		return pohDetection;
	}

	boolean spiritTreesParsedLive()
	{
		return spiritTrees != null && spiritTrees.isParsedLive();
	}

	AlternativeRoutesService altRoutesService()
	{
		return altRoutesService;
	}

	RouteDirectionsOverlay routeDirectionsOverlay()
	{
		return routeDirectionsOverlay;
	}

	Gson gson()
	{
		return gson;
	}

	/**
	 * Whether the displayed route list was generated with different method exclusions than are
	 * currently selected — i.e. the user toggled methods since and hasn't pressed Refresh yet.
	 */
	public boolean isRouteListStale()
	{
		return !userExclusions.equals(generatedExclusions);
	}

	public AlternativeRoutesMode getRoutesMode()
	{
		return routesMode;
	}

	/**
	 * Whether the bank's contents are known this session (false until the bank has been opened once).
	 * Bank mode cannot see banked teleports until this is true — same constraint as the classic Shortest Path engine's
	 * own INVENTORY_AND_BANK setting.
	 */
	public boolean isBankContentsKnown()
	{
		return bankSnapshots != null && bankSnapshots.isKnown();
	}

	/**
	 * Whether the known bank contents were restored from a previous session's saved snapshot rather
	 * than seen live — the panel labels the source, since a restored snapshot can be stale.
	 */
	public boolean isBankRestored()
	{
		return bankSnapshots != null && bankSnapshots.isRestored();
	}

	public void setRoutesMode(AlternativeRoutesMode mode)
	{
		// Panel (EDT) entry point: routing state is client-thread owned, so hop over - invoke()
		// runs inline when already there.
		clientThread.invoke(() ->
		{
			if (mode == null || this.routesMode == mode)
			{
				return;
			}
			this.routesMode = mode;
			choices.saveRoutesMode(mode);
			triggerAlternatives(session.lastStart(), session.lastTargetsCopy());
		});
	}

	/**
	 * Light auto-detect, run each game tick: when GPS's destination changes (a new target set
	 * manually, by Quest Helper, on reaching the previous one, etc.) compute the alternatives once.
	 * Deliberately keyed on the target SET only — never on start/movement — so the live path recalcs
	 * that thrashed the old approach are ignored. If it ever misses, the panel's "Find routes" button
	 * forces a recompute.
	 */
	private void maybeAutoComputeAlternatives()
	{
		if (altRoutesService == null)
		{
			return;
		}
		Set<Integer> targets = pathTargets;
		// The full route budget, panel shown or hidden (see routeLimitFor).
		int desiredLimit = defaultRouteLimit();
		if (!RouteSession.shouldAutoCompute(targets, session.lastTargets(), session.lastLimit(), desiredLimit))
		{
			return;
		}
		session.setLimit(desiredLimit);
		triggerAlternatives(altStart(), new HashSet<>(targets));
	}

	/**
	 * Called by the panel when the GPS sidebar tab is shown or hidden. Every generation runs with
	 * the full route budget regardless (see routeLimitFor); opening the panel only re-checks the
	 * auto-compute decision, so a generation that ran under a smaller budget is widened.
	 */
	void setAltPanelVisible(boolean visible)
	{
		altPanelVisible = visible;
		if (visible)
		{
			clientThread.invokeLater(this::maybeAutoComputeAlternatives);
		}
	}

	private void triggerAlternatives(int start, Set<Integer> targets)
	{
		if (altRoutesService == null)
		{
			return;
		}
		Set<Integer> ends = (targets == null) ? new HashSet<>() : new HashSet<>(targets);
		// The session clears the committed route for a NEW destination (so the overlay stays blank
		// until the fresh routes settle) and keeps it for the same one; the previous routes go
		// immediately (the catalog stays) and the new ones stream in as they are found. With no
		// target this still streams just the teleport-methods catalog.
		session.begin(start, ends);
		// Snapshot the exclusions this generation runs with, so the panel can flag the route list as
		// stale once the user toggles methods afterwards (recalculation is manual via Refresh).
		generatedExclusions = getUserExclusions();
		final List<TeleportMethod> catalog = teleportCatalog;
		final boolean hasTarget = !ends.isEmpty();
		if (altPanel != null)
		{
			final Map<TeleportMethod, MethodAvailability> unavailable = unavailableMethods;
			SwingUtilities.invokeLater(() ->
				altPanel.displayRoutes(List.of(), catalog, unavailable, getUserExclusions(), true, hasTarget));
		}
		altRoutesService.generate(start, ends, userExclusions, routesMode, session.limit(), session.costMultiple(),
			altRoundTrip, this::onAlternativeRoutesUpdate);
	}

	private void onAlternativeRoutesUpdate(List<RouteOption> routes, List<TeleportMethod> catalog,
		Map<TeleportMethod, MethodAvailability> unavailable, boolean done)
	{
		// Priorities re-rank the list (effective ETA = cost + tier adjustments) — everything
		// downstream (panel, default display pick, rematch) sees the effective order.
		final List<RouteOption> ordered = sortByEffectiveOrder(routes);
		teleportCatalog = catalog;
		unavailableMethods = unavailable;
		if (done)
		{
			// The session re-matches or drops the pick and commits the overlay's route in one step
			// (see RouteSession.settle); the tracker's progress is the pick's progress.
			session.settle(ordered, altRoutesService.wasMoreLikely(), AlternativeRoutesService.MAX_ROUTES_CAP,
				this::displayedRouteProgress);
			// The displayed route just settled: publish it to other plugins (postTransports).
			postPluginMessages();
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
			if (altPanel != null)
			{
				altPanel.displayRoutes(ordered, catalog, unavailable, getUserExclusions(), !done, hasTarget);
			}
		});
	}


	/**
	 * Catalog-only re-classification after an inventory/equipment change (issue #5). Skipped
	 * while a generation is in flight — that generation re-snapshots anyway — and with no
	 * service or panel to inform.
	 */
	private void maybeRefreshCatalog()
	{
		if (!catalogDirty || session.inFlight() || altRoutesService == null || altPanel == null
			|| !GameState.LOGGED_IN.equals(client.getGameState()))
		{
			return;
		}
		// The catalog exists for the sidebar; while the panel is hidden the dirty flag just waits
		// (issues #23/#24: every pickup, drop and gear switch ran a full planning refresh -
		// hundreds of quest clientscripts - on the client thread, a per-action micro stutter for
		// players who never open the panel). Route generations rebuild the catalog themselves, so
		// routing never sees this deferral. Bursts while the panel IS open coalesce through a
		// short cooldown; the flag stays set, so no change is lost, only delayed a few ticks.
		if (!altPanelVisible || client.getTickCount() < catalogRefreshBackoffTick)
		{
			return;
		}
		catalogRefreshBackoffTick = client.getTickCount() + CATALOG_REFRESH_COOLDOWN_TICKS;
		catalogDirty = false;
		altRoutesService.refreshCatalog(routesMode, (catalog, unavailable) ->
		{
			teleportCatalog = catalog;
			unavailableMethods = unavailable;
			refreshPanel(session.inFlight());
		});
	}

	private void refreshPanel(boolean calculating)
	{
		final boolean hasTarget = !session.lastTargets().isEmpty();
		if (altPanel != null)
		{
			SwingUtilities.invokeLater(() ->
				altPanel.displayRoutes(session.routes(), teleportCatalog, unavailableMethods,
					getUserExclusions(), calculating, hasTarget));
		}
	}

}
