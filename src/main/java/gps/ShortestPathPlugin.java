package gps;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemContainer;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.ScriptID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.events.WorldChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
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
import net.runelite.client.ui.JagexColors;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathStep;
import gps.pathfinder.Pathfinder;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TransportAvailability;
import gps.transport.Transport;
import gps.transport.TransportType;

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
	protected static final String MESSAGE_NAMESPACE = "gps";
	// Compatibility alias: Quest Helper and other plugins drive the pathfinder through Shortest
	// Path's namespace (set path/target, config overrides) and listen for its path broadcasts.
	// GPS supersedes Shortest Path, so it keeps answering on that channel too — inbound messages
	// are accepted on either, and broadcasts go out on both (no listener subscribes to both today,
	// so nothing double-processes; drop the legacy channel only if that ever changes).
	protected static final String MESSAGE_NAMESPACE_LEGACY = "shortestpath";

	// POH (Player Owned House) bounds for detecting when path goes through POH
	// Note: POH_MIN_X is 1856 to exclude the Daddy's Home miniquest area
	private static final int POH_MIN_X = 1856;
	private static final int POH_MAX_X = 2047;
	private static final int POH_MIN_Y = 5696;
	private static final int POH_MAX_Y = 5767;
	private static final String PLUGIN_MESSAGE_PATH = "path";
	private static final String PLUGIN_MESSAGE_CLEAR = "clear";
	private static final String PLUGIN_MESSAGE_START = "start";
	private static final String PLUGIN_MESSAGE_TARGET = "target";
	private static final String PLUGIN_MESSAGE_CONFIG_OVERRIDE = "config";
	private static final String PLUGIN_MESSAGE_TRANSPORTS = "transports";
	private static final String PLUGIN_MESSAGE_SOURCE = "source";
	private static final String CLEAR = "Clear";
	private static final String PATH = ColorUtil.wrapWithColorTag("Path", JagexColors.MENU_TARGET);
	private static final String SET = "Set";
	private static final String FIND_CLOSEST = "Find closest";
	private static final String FLASH_ICONS = "Flash icons";
	private static final String START = ColorUtil.wrapWithColorTag("Start", JagexColors.MENU_TARGET);
	private static final String TARGET = ColorUtil.wrapWithColorTag("Target", JagexColors.MENU_TARGET);
	private static final BufferedImage MARKER_IMAGE = ImageUtil.loadImageResource(ShortestPathPlugin.class, "/marker.png");
	private static final Pattern TRANSPORT_OPTIONS_REGEX = Pattern.compile("^(avoidWilderness|includeBankPath|currencyThreshold|use\\w+|cost\\w+)$");
	private static final Map<String, Object> configOverride = new HashMap<>(50);
	private static final Pattern SPIRIT_TREE_LABEL_PATTERN_MENU = Pattern.compile("<col=735a28>(.+)</col>: (<col=5f5f5f>)?(.+)");
	private static final Pattern SPIRIT_TREE_LABEL_PATTERN_MENU_NEW = Pattern.compile("<col=ffffff>(.+)</col>: (<col=5f5f5f>)?(.+)");
	private final List<PendingTask> pendingTasks = new ArrayList<>(3);
	private final Object pathfinderMutex = new Object();
	boolean drawCollisionMap;
	boolean drawMap;
	boolean drawMinimap;
	boolean drawTiles;
	boolean drawTransports;
	boolean showTransportInfo;
	boolean showBankPickupInfo;
	Color colourCollisionMap;
	Color colourPath;
	Color colourPathBlocked;
	Color colourPathCalculating;
	Color colourPathUnreachable;
	Color colourText;
	Color colourTransports;
	Color colourTeleportPulse;
	boolean showTeleportPulse;
	boolean showDirections;
	boolean arrivalAutoDismiss;
	int arrivalDismissSeconds;
	int tileCounterStep;
	int unreachableTargetDistance;
	String unreachableText;
	TileCounter showTileCounter;
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
	private DebugOverlayPanel debugOverlayPanel;
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
	// True while the original Shortest Path plugin is also enabled: both plugins draw paths and
	// answer the same plugin-message integrations, so the panel warns and recommends disabling it.
	private volatile boolean shortestPathConflict = false;
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
	// Alternative-routes feature: panel, async route generator, the methods the user has excluded, the
	// generated routes, and which one is currently shown on the map.
	private ShortestPathPanel altPanel;
	private NavigationButton navButton;
	// Whether the sidebar button is currently on the toolbar: GPS is only useful in-game, so the
	// button is shown while logged in and removed on the login screen.
	private boolean navButtonShown = false;
	private AlternativeRoutesService altRoutesService;
	private static final String CONFIG_KEY_EXCLUSIONS = "alternativeRoutesExclusions";
	private static final String CONFIG_KEY_MODE = "alternativeRoutesMode";
	// One-shot marker: seasonal (Leagues) methods are excluded by default, seeded into the user
	// exclusions the first time the catalog appears. After that the player controls them like any
	// other method (so re-enabled ones aren't re-disabled).
	private static final String CONFIG_KEY_SEASONAL_DEFAULTED = "seasonalMethodsDefaultExcluded";
	private volatile boolean seasonalDefaultsApplied = false;
	private final Set<TeleportMethod> userExclusions = ConcurrentHashMap.newKeySet();
	// The exclusions the current route list was generated with; diverging from userExclusions means
	// the list is stale until the user refreshes (method toggles no longer auto-recalculate).
	private volatile Set<TeleportMethod> generatedExclusions = Set.of();
	// Where the current destination came from, for the GPS header: "map pin" for manual targets, the
	// sender's self-declared "source" for plugin messages (else "another plugin"), null when unset.
	private volatile String targetSource;
	// Which methods the alternatives consider: carried (default), carried + bank, or every teleport.
	private volatile AlternativeRoutesMode routesMode = AlternativeRoutesMode.OWNED_INVENTORY;
	// How many alternative routes to generate; grows when the user asks for more.
	// Initialised from config in startUp (config is not injected at field-init time).
	private int routeLimit = AlternativeRoutesService.MAX_ROUTES;
	// The cost cap for a generation, as a multiple of the best route's cost: only routes up to this
	// many times the cheapest are computed (a cheap teleport otherwise floods the map searching for
	// far-worse alternatives). "Show more" raises it; reset to the default on a new destination.
	private static final int DEFAULT_COST_MULTIPLE = 3;
	private static final int COST_MULTIPLE_STEP = 3;
	// Ceiling on the cost band: at this multiple the cap is effectively off (far past any walk
	// cost), so "show more" stops — a backstop against endless widening on an unreachable target.
	private static final int MAX_COST_MULTIPLE = 30;
	private int routeCostMultiple = DEFAULT_COST_MULTIPLE;
	// Whether the last generation's cost cap held routes back (raising the multiple could reveal more).
	private volatile boolean moreRoutesLikely = false;
	private volatile List<RouteOption> alternativeRoutes = new ArrayList<>();
	private volatile List<TeleportMethod> teleportCatalog = new ArrayList<>();
	// Catalog methods the player can't use in the current mode, mapped to why (for the panel markers).
	private volatile Map<TeleportMethod, MethodAvailability> unavailableMethods = Map.of();
	private volatile RouteOption selectedRoute;
	// Start/targets the alternatives were last generated from, reused by exclusion/mode/show-more edits
	// so they re-run against the same destination. Volatile: read/written from client thread + Swing EDT.
	private volatile int lastAltStart = WorldPointUtil.UNDEFINED;
	private volatile Set<Integer> lastAltTargets = Set.of();
	// The route limit the last generation ran with, so opening the panel can tell a primary-only
	// generation (panel was hidden) from a full one and catch up.
	private volatile int lastAltLimit = 0;
	// Whether the GPS side panel is currently shown (sidebar tab selected). While hidden, auto-compute
	// only finds the primary route (the overlay needs it); the extra alternatives are searched
	// automatically when the panel opens. Written from the Swing EDT, read on the client thread.
	private volatile boolean altPanelVisible = false;
	// Whether the client knows the bank's contents this session (the bank container is only populated
	// once the bank has been opened). Used by the panel to explain why Bank mode finds nothing.
	private volatile boolean bankContentsKnown = false;
	// True while a generation is computing for the current target. Used to suppress the classic-path
	// fallback on the map until the first alternative streams in, so the displayed path never flashes
	// a route that the mode's list won't contain (the classic path follows the SP config, not the mode).
	private volatile boolean altGenerationInFlight = false;
	private Point lastMenuOpenedPoint;
	private WorldMapPoint marker;
	private int lastLocation = WorldPointUtil.packWorldPoint(0, 0, 0);
	private Shape minimapClipFixed;
	private Shape minimapClipResizeable;
	private BufferedImage minimapSpriteFixed;
	private BufferedImage minimapSpriteResizeable;
	private Rectangle minimapRectangle = new Rectangle();
	private GameState lastGameState = null;
	private GameState lastLastGameState = null;
	private ExecutorService pathfindingExecutor = Executors.newSingleThreadExecutor();
	private Future<?> pathfinderFuture;
	@Getter
	private Pathfinder pathfinder;
	@Getter
	private PathfinderConfig pathfinderConfig;
	@Getter
	private boolean startPointSet = false;
	// Wall-clock time the current target was set, used to report the journey duration on arrival
	// (~0 when the destination was set while already there, e.g. "nearest bank" at a bank).
	private long targetSetMillis = 0;
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
	private boolean fairyRingPanelOpen = false;

	/**
	 * Checks if the given coordinates are inside the POH (Player Owned House) area.
	 *
	 * @param x The world X coordinate
	 * @param y The world Y coordinate
	 * @return true if inside POH, false otherwise
	 */
	public static boolean isInsidePoh(int x, int y)
	{
		return x >= POH_MIN_X && x <= POH_MAX_X && y >= POH_MIN_Y && y <= POH_MAX_Y;
	}

	public static boolean override(String configOverrideKey, boolean defaultValue)
	{
		if (!configOverride.isEmpty())
		{
			Object value = configOverride.get(configOverrideKey);
			if (value instanceof Boolean)
			{
				return (boolean) value;
			}
		}
		return defaultValue;
	}

	/**
	 * Override for TransportType enabled state using the config key name stored in the enum.
	 */
	public static boolean override(TransportType type, boolean defaultValue)
	{
		String key = type.getEnabledKey();
		return key != null ? override(key, defaultValue) : defaultValue;
	}

	/**
	 * Override for TransportType cost threshold using the config key name stored in the enum.
	 */
	public static int override(TransportType type, int defaultValue)
	{
		String key = type.getCostKey();
		return key != null ? override(key, defaultValue) : defaultValue;
	}

	public static int override(String configOverrideKey, int defaultValue)
	{
		if (!configOverride.isEmpty())
		{
			Object value = configOverride.get(configOverrideKey);
			if (value instanceof Integer)
			{
				return (int) value;
			}
		}
		return defaultValue;
	}

	public static TeleportationItem override(String configOverrideKey, TeleportationItem defaultValue)
	{
		if (!configOverride.isEmpty())
		{
			Object value = configOverride.get(configOverrideKey);
			if (value instanceof String)
			{
				TeleportationItem teleportationItem = TeleportationItem.fromType((String) value);
				if (teleportationItem != null)
				{
					return teleportationItem;
				}
			}
		}
		return defaultValue;
	}

	public static JewelleryBoxTier override(String configOverrideKey, JewelleryBoxTier defaultValue)
	{
		if (!configOverride.isEmpty())
		{
			Object value = configOverride.get(configOverrideKey);
			if (value instanceof String)
			{
				JewelleryBoxTier tier = JewelleryBoxTier.fromType((String) value);
				if (tier != null)
				{
					return tier;
				}
			}
		}
		return defaultValue;
	}

	@Provides
	public ShortestPathConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ShortestPathConfig.class);
	}

	@Override
	protected void startUp()
	{
		cacheConfigValues();

		pathfinderConfig = new PathfinderConfig(client, config);
		if (GameState.LOGGED_IN.equals(client.getGameState()))
		{
			clientThread.invokeLater(pathfinderConfig::refresh);
		}

		overlayManager.add(pathOverlay);
		overlayManager.add(pathMinimapOverlay);
		overlayManager.add(pathMapOverlay);
		overlayManager.add(pathMapTooltipOverlay);
		overlayManager.add(routeDirectionsOverlay);

		if (config.drawDebugPanel())
		{
			overlayManager.add(debugOverlayPanel);
		}

		loadExclusions();
		seasonalDefaultsApplied = Boolean.parseBoolean(
			configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY_SEASONAL_DEFAULTED));
		loadRoutesMode();
		routeLimit = defaultRouteLimit();
		altPanel = new ShortestPathPanel(this);
		altRoutesService = new AlternativeRoutesService(clientThread, pathfinderConfig.copyForPlanning());
		navButton = NavigationButton.builder()
			.tooltip("GPS")
			.icon(RouteIcons.gpsPin())
			.priority(70)
			.panel(altPanel)
			.build();
		// Only mount the sidebar button in-game — it does nothing useful on the login screen.
		setNavButtonShown(GameState.LOGGED_IN.equals(client.getGameState()));

		// Populate the teleport-methods catalog so it's visible before any target is set, and check
		// whether the bank contents are already known this session.
		if (GameState.LOGGED_IN.equals(client.getGameState()))
		{
			clientThread.invokeLater(() ->
			{
				ItemContainer liveBank = client.getItemContainer(InventoryID.BANK);
				if (liveBank != null && liveBank.getItems().length > 0)
				{
					pathfinderConfig.bank = liveBank;
					pathfinderConfig.setBankSnapshot(liveBank.getItems());
					bankContentsKnown = true;
				}
			});
			triggerAlternatives(WorldPointUtil.UNDEFINED, new HashSet<>());
		}

		keyManager.registerKeyListener(clearPathKeylistener);
		mouseManager.registerMouseListener(arrivalDismissListener);
		// Plugins enabled later are caught by the PluginChanged/ExternalPluginsChanged events.
		updateShortestPathConflict();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(pathOverlay);
		overlayManager.remove(pathMinimapOverlay);
		overlayManager.remove(pathMapOverlay);
		overlayManager.remove(pathMapTooltipOverlay);
		overlayManager.remove(routeDirectionsOverlay);
		overlayManager.remove(debugOverlayPanel);

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
			navButtonShown = false;
		}
		if (altRoutesService != null)
		{
			altRoutesService.shutdown();
			altRoutesService = null;
		}

		if (pathfindingExecutor != null)
		{
			pathfindingExecutor.shutdownNow();
			pathfindingExecutor = null;
		}

		keyManager.unregisterKeyListener(clearPathKeylistener);
		mouseManager.unregisterMouseListener(arrivalDismissListener);
	}

	public void restartPathfinding(int start, Set<Integer> ends, boolean canReviveFiltered)
	{
		synchronized (pathfinderMutex)
		{
			if (pathfinder != null)
			{
				pathfinder.cancel();
				pathfinderFuture.cancel(true);
			}

			if (pathfindingExecutor == null)
			{
				ThreadFactory shortestPathNaming = new ThreadFactoryBuilder().setNameFormat("shortest-path-%d").build();
				pathfindingExecutor = Executors.newSingleThreadExecutor(shortestPathNaming);
			}
		}

		getClientThread().invokeLater(() ->
		{
			// The panel's method catalog is the single customization surface: methods the user has
			// excluded there are also excluded from the classic path.
			pathfinderConfig.setExcludedMethods(getUserExclusions());
			pathfinderConfig.refresh();
			pathfinderConfig.filterLocations(ends, canReviveFiltered);
			synchronized (pathfinderMutex)
			{
				if (ends.isEmpty())
				{
					setTarget(WorldPointUtil.UNDEFINED);
				}
				else
				{
					pathfinder = new Pathfinder(pathfinderConfig, start, ends, this::postPluginMessages);
					pathfinderFuture = pathfindingExecutor.submit(pathfinder);
				}
			}
		});
	}

	public void restartPathfinding(int start, Set<Integer> ends)
	{
		restartPathfinding(start, ends, true);
	}

	public boolean isNearPath(int location)
	{
		List<PathStep> path;
		if (pathfinder == null || (path = pathfinder.getPath()) == null || path.isEmpty() ||
			config.recalculateDistance() < 0 || lastLocation == (lastLocation = location))
		{
			return true;
		}

		for (PathStep pathStep : path)
		{
			if (WorldPointUtil.distanceBetween(location, pathStep.getPackedPosition()) < config.recalculateDistance())
			{
				return true;
			}
		}

		return false;
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

	public Color getPathColor()
	{
		// A displayed alternative route is a static snapshot: colour it from its own endpoint,
		// never by the classic pathfinder's background re-anchoring (which would otherwise flash the drawn
		// route blue every time you walk far enough to trigger a recalculation).
		RouteOption displayed = getDisplayedRoute();
		if (displayed != null)
		{
			return isRouteEndTooFar(displayed) ? colourPathUnreachable : colourPath;
		}

		if (pathfinder == null || !pathfinder.isDone())
		{
			return colourPathCalculating;
		}

		List<PathStep> path = pathfinder.getPath();
		if (path == null || path.isEmpty() || pathfinder.getTargets().isEmpty())
		{
			return colourPath;
		}

		if (isPathUnreachable())
		{
			return colourPathUnreachable;
		}

		return colourPath;
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
		Set<Integer> targets = lastAltTargets;
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
		return closestTargetDistance > unreachableTargetDistance;
	}

	public boolean isPathUnreachable()
	{
		if (pathfinder == null || !pathfinder.isDone())
		{
			return false;
		}

		List<PathStep> path = pathfinder.getPath();
		if (path == null || path.isEmpty() || pathfinder.getTargets().isEmpty())
		{
			return false;
		}

		int endPoint = path.get(path.size() - 1).getPackedPosition();
		int closestTargetDistance = Integer.MAX_VALUE;
		for (int target : pathfinder.getTargets())
		{
			closestTargetDistance = Math.min(closestTargetDistance, WorldPointUtil.distanceBetween(target, endPoint));
		}

		return closestTargetDistance > unreachableTargetDistance;
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
		if (path == null || path.isEmpty() || pathfinder == null || pathfinder.getTargets().isEmpty())
		{
			return true;  // not enough information to declare it unreachable
		}
		int endPoint = path.get(path.size() - 1).getPackedPosition();
		int closest = Integer.MAX_VALUE;
		for (int target : pathfinder.getTargets())
		{
			closest = Math.min(closest, WorldPointUtil.distanceBetween(target, endPoint));
		}
		return closest <= unreachableTargetDistance;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}

		cacheConfigValues();

		if ("drawDebugPanel".equals(event.getKey()))
		{
			if (config.drawDebugPanel())
			{
				overlayManager.add(debugOverlayPanel);
			}
			else
			{
				overlayManager.remove(debugOverlayPanel);
			}
			return;
		}

		// Transport option changed; rerun pathfinding
		if ("defaultRouteCount".equals(event.getKey()))
		{
			routeLimit = defaultRouteLimit();
		}

		if (TRANSPORT_OPTIONS_REGEX.matcher(event.getKey()).find())
		{
			if (pathfinder != null)
			{
				restartPathfinding(pathfinder.getStart(), pathfinder.getTargets());
			}
		}
	}

	/** Whether the original Shortest Path plugin is also enabled — the panel shows a warning. */
	public boolean isShortestPathConflict()
	{
		return shortestPathConflict;
	}

	/**
	 * Detects the original Shortest Path plugin running alongside GPS. Both draw paths and answer
	 * the same {@code shortestpath} plugin-message integrations, so running both doubles the
	 * rendering — the panel recommends disabling it. Matched by descriptor name (each hub plugin
	 * has its own classloader, so class identity can't be compared across plugins).
	 */
	private void updateShortestPathConflict()
	{
		boolean conflict = false;
		for (Plugin other : pluginManager.getPlugins())
		{
			if (other == this)
			{
				continue;
			}
			PluginDescriptor descriptor = other.getClass().getAnnotation(PluginDescriptor.class);
			if (descriptor != null && "Shortest Path".equals(descriptor.name())
				&& pluginManager.isPluginEnabled(other))
			{
				conflict = true;
				break;
			}
		}
		if (conflict != shortestPathConflict)
		{
			shortestPathConflict = conflict;
			refreshPanel(altGenerationInFlight);
		}
	}

	@Subscribe
	public void onPluginChanged(net.runelite.client.events.PluginChanged event)
	{
		updateShortestPathConflict();
	}

	@Subscribe
	public void onExternalPluginsChanged(net.runelite.client.events.ExternalPluginsChanged event)
	{
		updateShortestPathConflict();
	}

	/**
	 * Adds/removes the sidebar button so it only appears in-game. Called on every game-state change
	 * before the login-detection guard below (which returns early in most cases). LOADING / HOPPING
	 * / CONNECTION_LOST leave the button as-is, so world hops don't flicker it.
	 */
	private void updateNavButtonVisibility(GameState state)
	{
		switch (state)
		{
			case LOGGED_IN:
				setNavButtonShown(true);
				break;
			case LOGIN_SCREEN:
			case LOGIN_SCREEN_AUTHENTICATOR:
			case STARTING:
				setNavButtonShown(false);
				break;
			default:
				break;
		}
	}

	private void setNavButtonShown(boolean show)
	{
		if (navButton == null || show == navButtonShown)
		{
			return;
		}
		navButtonShown = show;
		if (show)
		{
			clientToolbar.addNavigation(navButton);
		}
		else
		{
			clientToolbar.removeNavigation(navButton);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		updateNavButtonVisibility(event.getGameState());

		if (pathfinderConfig == null
			|| !GameState.LOGGING_IN.equals(lastLastGameState)
			|| !GameState.LOADING.equals(lastLastGameState = lastGameState)
			|| !GameState.LOGGED_IN.equals(lastGameState = event.getGameState()))
		{
			lastLastGameState = lastGameState;
			lastGameState = event.getGameState();
			return;
		}

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
		if (!MESSAGE_NAMESPACE.equals(event.getNamespace())
			&& !MESSAGE_NAMESPACE_LEGACY.equals(event.getNamespace()))
		{
			return;
		}

		String action = event.getName();
		if (PLUGIN_MESSAGE_PATH.equals(action))
		{
			Map<String, Object> data = event.getData();
			Object objStart = data.getOrDefault(PLUGIN_MESSAGE_START, null);
			Object objTarget = data.getOrDefault(PLUGIN_MESSAGE_TARGET, null);
			Object objConfigOverride = data.getOrDefault(PLUGIN_MESSAGE_CONFIG_OVERRIDE, null);

			@SuppressWarnings("unchecked")
			Map<String, Object> configOverride = (objConfigOverride instanceof Map<?, ?>) ? ((Map<String, Object>) objConfigOverride) : null;
			if (configOverride != null && !configOverride.isEmpty())
			{
				ShortestPathPlugin.configOverride.clear();
				for (String key : configOverride.keySet())
				{
					ShortestPathPlugin.configOverride.put(key, configOverride.get(key));
				}
				cacheConfigValues();
			}

			if (objStart == null && objTarget == null)
			{
				return;
			}

			int start = (objStart instanceof WorldPoint) ? WorldPointUtil.packWorldPoint((WorldPoint) objStart)
				: ((objStart instanceof Integer) ? ((int) objStart) : WorldPointUtil.UNDEFINED);
			if (start == WorldPointUtil.UNDEFINED)
			{
				if (client.getLocalPlayer() == null)
				{
					return;
				}
				start = WorldPointUtil.packWorldPoint(client.getLocalPlayer().getWorldLocation());
			}

			Set<Integer> targets = new HashSet<>();
			if (objTarget instanceof Integer)
			{
				int packedPoint = (Integer) objTarget;
				if (packedPoint == WorldPointUtil.UNDEFINED)
				{
					return;
				}
				targets.add(packedPoint);
			}
			else if (objTarget instanceof WorldPoint)
			{
				int packedPoint = WorldPointUtil.packWorldPoint((WorldPoint) objTarget);
				if (packedPoint == WorldPointUtil.UNDEFINED)
				{
					return;
				}
				targets.add(packedPoint);
			}
			else if (objTarget instanceof Set<?>)
			{
				@SuppressWarnings("unchecked")
				Set<Object> objTargets = (Set<Object>) objTarget;
				for (Object obj : objTargets)
				{
					int packedPoint = WorldPointUtil.UNDEFINED;
					if (obj instanceof Integer)
					{
						packedPoint = (Integer) obj;
					}
					else if (obj instanceof WorldPoint)
					{
						packedPoint = WorldPointUtil.packWorldPoint((WorldPoint) obj);
					}
					if (packedPoint == WorldPointUtil.UNDEFINED)
					{
						return;
					}
					targets.add(packedPoint);
				}
			}

			// Attribute the destination for the GPS header. PluginMessage doesn't identify its sender,
			// so honour an optional "source" string in the data (a convention senders can adopt, e.g.
			// "Quest Helper"); otherwise all we can say is that a plugin asked for it.
			Object objSource = data.getOrDefault(PLUGIN_MESSAGE_SOURCE, null);
			targetSource = (objSource instanceof String && !((String) objSource).isEmpty())
				? (String) objSource
				: "another plugin";

			boolean useOld = targets.isEmpty() && pathfinder != null;
			Set<Integer> ends;
			if (useOld)
			{
				ends = new HashSet<>(pathfinder.getTargets());
			}
			else
			{
				// A NEW destination from another plugin: this path bypasses setTargets, so stamp the
				// journey start here too — otherwise the arrival timer measures from whatever manual
				// destination was last set (hours ago, or the epoch), showing absurd elapsed times.
				// Reusing the previous target keeps the running journey's stamp.
				targetSetMillis = System.currentTimeMillis();
				// Quest Helper often targets an NPC's or object's own tile, which isn't walkable — a
				// search targeting only it exhausts the entire map and ends 'closest tile' (captured:
				// ~880ms per search). Expand to the nearest walkable ring, like manual pins.
				ends = new HashSet<>();
				for (int target : targets)
				{
					ends.addAll(Destinations.walkableTargets(
						pathfinderConfig != null ? pathfinderConfig.getMap() : null, target));
				}
			}
			restartPathfinding(start, ends, useOld);
		}
		else if (PLUGIN_MESSAGE_CLEAR.equals(action))
		{
			configOverride.clear();
			cacheConfigValues();
			targetSource = null;
			setTarget(WorldPointUtil.UNDEFINED);
		}
	}

	public void postPluginMessages()
	{
		if (pathfinder == null)
		{
			return;
		}
		if (override("postTransports", config.postTransports()))
		{
			Map<String, Object> data = new HashMap<>();
			List<WorldPoint> transportOrigins = new ArrayList<>();
			List<WorldPoint> transportDestinations = new ArrayList<>();
			List<String> transportObjectInfos = new ArrayList<>();
			List<String> transportDisplayInfos = new ArrayList<>();
			List<PathStep> currentPath = pathfinder.getPath();
			for (int i = 1; i < currentPath.size(); i++)
			{
				PathStep currentStep = currentPath.get(i - 1);
				PathStep nextStep = currentPath.get(i);
				for (Transport transport : transportsForEdge(currentStep, nextStep))
				{
					transportOrigins.add(WorldPointUtil.unpackWorldPoint(currentStep.getPackedPosition()));
					transportDestinations.add(WorldPointUtil.unpackWorldPoint(nextStep.getPackedPosition()));
					transportObjectInfos.add(transport.getObjectInfo());
					transportDisplayInfos.add(transport.getDisplayInfo());
				}
			}
			data.put("origin", transportOrigins);
			data.put("destination", transportDestinations);
			data.put("objectInfo", transportObjectInfos);
			data.put("displayInfo", transportDisplayInfos);
			eventBus.post(new PluginMessage(MESSAGE_NAMESPACE, PLUGIN_MESSAGE_TRANSPORTS, data));
			eventBus.post(new PluginMessage(MESSAGE_NAMESPACE_LEGACY, PLUGIN_MESSAGE_TRANSPORTS, data));
		}
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		lastMenuOpenedPoint = client.getMouseCanvasPosition();
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		for (int i = 0; i < pendingTasks.size(); i++)
		{
			if (pendingTasks.get(i).check(client.getTickCount()))
			{
				pendingTasks.remove(i--).run();
			}
		}

		maybeAutoComputeAlternatives();

		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null || pathfinder == null)
		{
			return;
		}

		int currentLocation = WorldPointUtil.fromLocalInstance(client, localPlayer);
		int reachedDistance = config.reachedDistance();
		if (pathTilesRemaining(currentLocation, reachedDistance) < reachedDistance)
		{
			// Round-trip belt-and-braces: while the round-trip route isn't displayed (a
			// regeneration gap, or still streaming), the measurement above fell back to the
			// CLASSIC path — whose targets are the outbound bank tiles. Standing at the bank is
			// the trip's halfway point, not its end; never complete the journey from the fallback.
			RouteOption displayedRoundTrip = getDisplayedRoute();
			if (altRoundTrip && (displayedRoundTrip == null || !displayedRoundTrip.isRoundTrip()))
			{
				return;
			}
			// Reached the destination (along the path). Show the "Arrived!" panel — including when
			// the destination was set while already there (e.g. "nearest bank" at a bank), where
			// the journey time is ~0 — then clear the target. An unstamped journey (belt-and-braces
			// against any destination-setting path missing the stamp) reports 0 rather than decades.
			long elapsed = targetSetMillis == 0 ? 0 : System.currentTimeMillis() - targetSetMillis;
			if (routeDirectionsOverlay != null)
			{
				routeDirectionsOverlay.markArrived(targetSource, elapsed);
			}
			if (altPanel != null)
			{
				altPanel.markArrived(elapsed);
			}
			setTarget(WorldPointUtil.UNDEFINED);
			return;
		}

		if (!startPointSet && !isNearPath(currentLocation))
		{
			if (config.cancelInstead())
			{
				setTarget(WorldPointUtil.UNDEFINED);
				return;
			}
			restartPathfinding(currentLocation, pathfinder.getTargets());
		}
	}

	/**
	 * Tiles remaining to the goal measured ALONG the calculated path — the straight-line
	 * finish check used to clear the target through walls (the goal one tile away across a
	 * fence read as "reached"). Remaining = walk to the nearest path tile plus the summed
	 * edge lengths from it to the end; transport jumps count their full span, so a pending
	 * teleport never reads as nearly-there. Returns {@link Integer#MAX_VALUE} when the player
	 * is too far off the path (the recalculation logic owns that case) or when the path only
	 * gets close to an unreachable target rather than reaching it. Accumulation stops at
	 * {@code limit}, the only threshold the caller compares against.
	 */
	private int pathTilesRemaining(int currentLocation, int limit)
	{
		// Round-trip routes end where they start, so the arrival scan must anchor on the route's
		// earned progress: an unanchored nearest-tile scan would either match the start copy
		// forever (arrival never fires) or the end copy immediately (instant false arrival).
		RouteOption displayed = getDisplayedRoute();
		boolean roundTrip = displayed != null && displayed.isRoundTrip();
		List<PathStep> path = roundTrip ? displayed.getPath() : pathfinder.getPath();
		if (path == null || path.isEmpty())
		{
			return Integer.MAX_VALUE;
		}
		// The path must actually reach the destination, not merely get close to a truly
		// unreachable one. "Reached" here allows the end to be near the target rather than exactly
		// on it (the same tolerance the plugin uses elsewhere) — object destinations like a bank
		// booth or altar from the search aren't walkable, so the path ends on the tile beside them.
		if (!roundTrip && isPathUnreachable())
		{
			return Integer.MAX_VALUE;
		}
		int progress = roundTrip ? displayedRouteProgress() : 0;
		int scanFrom = roundTrip ? Math.max(0, progress - 8) : 0;
		// Until the turnaround (the destination) is reached, the return leg is out of bounds for
		// the scan: it often retraces the outbound street, so its tail matches the player's tile
		// from the first step out — and matching it would read as being nearly home.
		int turnaround = roundTrip ? displayed.getTurnaroundIndex() : -1;
		int scanTo = (turnaround >= 0 && progress < turnaround - 2)
			? Math.min(path.size(), turnaround + 1) : path.size();
		int best = -1;
		int bestDistance = Integer.MAX_VALUE;
		for (int i = scanFrom; i < scanTo; i++)
		{
			int distance = WorldPointUtil.distanceBetween(path.get(i).getPackedPosition(), currentLocation);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = i;
			}
		}
		if (best < 0 || bestDistance > 20)
		{
			return Integer.MAX_VALUE;
		}
		int remaining = bestDistance;
		for (int i = best + 1; i < path.size() && remaining < limit; i++)
		{
			remaining += Math.max(1, WorldPointUtil.distanceBetween2D(
				path.get(i - 1).getPackedPosition(), path.get(i).getPackedPosition()));
		}
		return remaining;
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (client.isKeyPressed(KeyCode.KC_SHIFT)
			&& event.getType() == MenuAction.WALK.getId())
		{
			addMenuEntry(event, SET, TARGET, 1);
			if (pathfinder != null)
			{
				if (!pathfinder.getTargets().isEmpty())
				{
					addMenuEntry(event, SET, TARGET + ColorUtil.wrapWithColorTag(" " +
						(pathfinder.getTargets().size() + 1), JagexColors.MENU_TARGET), 1);
				}
				for (int target : pathfinder.getTargets())
				{
					if (target != WorldPointUtil.UNDEFINED)
					{
						addMenuEntry(event, SET, START, 1);
						break;
					}
				}
				int selectedTile = getSelectedWorldPoint();
				List<PathStep> path;
				if ((path = pathfinder.getPath()) != null)
				{
					for (PathStep pathStep : path)
					{
						if (pathStep.getPackedPosition() == selectedTile)
						{
							addMenuEntry(event, CLEAR, PATH, 1);
							break;
						}
					}
				}
			}
		}

		final Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);

		if (map != null)
		{
			if (map.getBounds().contains(
				client.getMouseCanvasPosition().getX(),
				client.getMouseCanvasPosition().getY()))
			{
				addMenuEntry(event, SET, TARGET, 0);
				if (pathfinder != null)
				{
					if (!pathfinder.getTargets().isEmpty())
					{
						addMenuEntry(event, SET, TARGET + ColorUtil.wrapWithColorTag(" " +
							(pathfinder.getTargets().size() + 1), JagexColors.MENU_TARGET), 0);
					}
					for (int target : pathfinder.getTargets())
					{
						if (target != WorldPointUtil.UNDEFINED)
						{
							addMenuEntry(event, SET, START, 0);
							addMenuEntry(event, CLEAR, PATH, 0);
						}
					}
				}
			}
			if (event.getOption().equals(FLASH_ICONS) && pathfinderConfig.hasDestination(simplify(event.getTarget())))
			{
				addMenuEntry(event, FIND_CLOSEST, event.getTarget(), 1);
			}
		}

		final Shape minimap = getMinimapClipArea();

		if (minimap != null && pathfinder != null
			&& minimap.contains(
			client.getMouseCanvasPosition().getX(),
			client.getMouseCanvasPosition().getY()))
		{
			addMenuEntry(event, CLEAR, PATH, 0);
		}

		if (minimap != null && pathfinder != null
			&& ("Floating World Map".equals(Text.removeTags(event.getOption()))
			|| "Close Floating panel".equals(Text.removeTags(event.getOption()))))
		{
			addMenuEntry(event, CLEAR, PATH, 1);
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.BANK)
		{
			return;
		}
		pathfinderConfig.bank = event.getItemContainer();
		// Snapshot the items now, while the bank is open: the client may empty the live container
		// (and thereby every reference to it) once the interface closes.
		pathfinderConfig.setBankSnapshot(event.getItemContainer().getItems());
		boolean firstSight = !bankContentsKnown;
		bankContentsKnown = true;
		if (firstSight)
		{
			// First sight of the bank this session: regenerate so the availability map is rebuilt
			// with the bank contents — banked teleports classify IN_BANK (usable in Inv + bank
			// mode) and the catalog header count updates. Also clears the panel warning. NOT
			// during a round trip: opening the bank is the trip's halfway point, and regenerating
			// would discard the displayed route (and with it the way back).
			if (altRoundTrip)
			{
				refreshPanel(altGenerationInFlight);
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
		if (pathfinder != null && event.getGroupId() == InterfaceID.FAIRYRINGS_LOG)
		{
			fairyRingPanelOpen = true;
		}

		// Populate spirit tree cache, but only once.
		// The values here almost never change, we only need to load it once.
		if (pathfinderConfig.availableSpiritTrees == null)
		{
			switch (event.getGroupId())
			{
				case InterfaceID.MENU:
					clientThread.invokeLater(() -> parseSpiritTreeWidget(false));
					break;
				case InterfaceID.MENU_NEW:
					clientThread.invokeLater(() -> parseSpiritTreeWidget(true));
					break;
			}
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.FAIRYRINGS_LOG)
		{
			fairyRingPanelOpen = false;
		}
		// Bank closed: one regeneration per bank session, so items withdrawn or deposited are
		// reflected in the method availability (and the catalog counts) — recomputing on every
		// in-bank container change would run a generation per deposit. NOT during a round trip:
		// banking mid-trip is the whole point, and regenerating would discard the way back.
		if (event.getGroupId() == InterfaceID.BANKMAIN && bankContentsKnown && !altRoundTrip)
		{
			recomputeAlternatives();
		}
	}

	@Subscribe
	public void onPostClientTick(PostClientTick event)
	{
		if (fairyRingPanelOpen && pathfinder != null)
		{
			scrollFairyRingPanel();
		}
	}

	private void parseSpiritTreeWidget(boolean useNewMenu)
	{
		// Referencing
		// https://github.com/trs/runelite-teleport-maps/blob/e006270494500ab8e4826903b377bb945ca9fc96/src/main/java/com/mjhylkema/TeleportMaps/components/adventureLog/SpiritTreeMap.java#L141

		Widget container;
		if (useNewMenu)
		{
			container = client.getWidget(InterfaceID.MENU_NEW, 9);
		}
		else
		{
			container = client.getWidget(InterfaceID.MENU, 3);
		}

		if (container == null)
		{
			return;
		}

		Widget[] children = container.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			return;
		}

		// Tree Gnome Village is always the first row and always available;
		// quick length check before running the regex
		// Expected (old): "<col=735a28>1</col>: Tree Gnome Village" (length 39)
		// Expected (new): "<col=ffffff>1</col>: Tree Gnome Village" (length 39)
		String firstText = children[0].getText();
		if (firstText == null || firstText.length() != 39)
		{
			return;
		}

		Pattern pattern = useNewMenu ? SPIRIT_TREE_LABEL_PATTERN_MENU_NEW : SPIRIT_TREE_LABEL_PATTERN_MENU;

		Set<String> available = new HashSet<>();

		for (Widget child : children)
		{
			Matcher matcher = pattern.matcher(child.getText());
			if (!matcher.matches())
			{
				continue;
			}

			// Group 2 is the disabled color tag; if present, the tree is unavailable
			if (matcher.group(2) != null)
			{
				continue;
			}

			// Group 3 is spirit tree name
			available.add(matcher.group(3));
		}

		pathfinderConfig.availableSpiritTrees = available;

		if (pathfinder != null)
		{
			restartPathfinding(pathfinder.getStart(), pathfinder.getTargets());
		}
	}

	private void scrollFairyRingPanel()
	{
		List<PathStep> path;

		if (pathfinder == null
			|| (path = pathfinder.getPath()) == null)
		{
			return;
		}

		String fairyRingCode = null;

		for (int i = 1; i < path.size(); i++)
		{
			PathStep currentStep = path.get(i - 1);
			PathStep nextStep = path.get(i);
			for (Transport transport : transportsForEdge(currentStep, nextStep))
			{
				if (TransportType.FAIRY_RING.equals(transport.getType()))
				{
					fairyRingCode = transport.getDisplayInfo();
				}
			}
		}
		if (fairyRingCode == null)
		{
			return;
		}

		Widget codeWidget = null;

		Widget favesPanel = client.getWidget(InterfaceID.FairyringsLog.FAVES);
		if (favesPanel != null)
		{
			for (Widget widget : favesPanel.getStaticChildren())
			{
				if (widget != null)
				{
					String widgetText = widget.getText();
					if ((fairyRingCode.equals(widgetText)
						|| ("(GPS) " + fairyRingCode).equals(widgetText)))
					{
						codeWidget = widget;
						break;
					}
				}
			}
		}

		Widget contentsList = client.getWidget(InterfaceID.FairyringsLog.CONTENTS);
		if (contentsList != null && codeWidget == null)
		{
			for (Widget widget : contentsList.getDynamicChildren())
			{
				if (widget != null)
				{
					String widgetText = widget.getText();
					if ((fairyRingCode.equals(widgetText)
						|| ("(GPS) " + fairyRingCode).equals(widgetText)))
					{
						codeWidget = widget;
						break;
					}
				}
			}
		}

		if (codeWidget == null)
		{
			return;
		}

		codeWidget.setTextColor(0x00FF00);
		String codeWidgetText = codeWidget.getText();
		if (codeWidgetText != null && !codeWidgetText.contains("(GPS)"))
		{
			codeWidget.setText("(GPS) " + codeWidgetText);
		}

		if (contentsList == null)
		{
			return;
		}

		int panelScrollY = Math.min(
			codeWidget.getRelativeY(),
			contentsList.getScrollHeight() - contentsList.getHeight()
		);

		contentsList.setScrollY(panelScrollY);
		contentsList.revalidateScroll();

		client.runScript(
			ScriptID.UPDATE_SCROLLBAR,
			InterfaceID.FairyringsLog.SCROLLBAR,
			InterfaceID.FairyringsLog.CONTENTS,
			panelScrollY
		);
	}

	public CollisionMap getMap()
	{
		return pathfinderConfig.getMap();
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
	 * This reconstructs the candidate transports for a rendered path edge from the current path state.
	 * <p>
	 * The important detail is that path display logic is edge-based, not node-based:
	 * - origin position comes from currentStep
	 * - destination position comes from nextStep
	 * - the applicable transport set may depend on whether the edge transitions into banked state
	 * <p>
	 * That last point is the awkward one. Banking is not represented as its own explicit path edge;
	 * instead the "becomes banked" state change is conflated into the movement/transport edge that
	 * reaches the banked destination step. As a result, callers cannot safely resolve transports from
	 * a single PathStep alone: using only currentStep can miss bank-gated transports, while using only
	 * nextStep loses the origin tile of the edge. This helper therefore takes both steps and resolves
	 * transports for the edge between them.
	 * <p>
	 * This is still only a fallback for display code and remains inherently ambiguous when multiple
	 * valid transports share the same origin/destination pair under the same edge state. The more
	 * structural fix would be to model reconstructed paths in terms of explicit edges, or otherwise
	 * carry richer per-edge metadata, instead of repeatedly re-deriving transport candidates from
	 * adjacent path steps.
	 * <p>
	 * Note that this function also performs filtering by the transport target, so callers of this
	 * function can directly iterate over the returned transports.
	 */
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
		if (route == null)
		{
			return null;
		}
		int arriveIndex = fromIndex + 1;
		List<Integer> edges = route.getMethodEdgeIndexes();
		List<TeleportMethod> methods = route.getMethods();
		for (int m = 0; m < edges.size() && m < methods.size(); m++)
		{
			if (edges.get(m) == arriveIndex)
			{
				return methods.get(m);
			}
		}
		return null;
	}

	public Set<Transport> transportsForEdge(PathStep currentStep, PathStep nextStep)
	{
		if (currentStep == null || nextStep == null)
		{
			return Set.of();
		}
		boolean bankVisited = currentStep.isBankVisited() || nextStep.isBankVisited();
		// Get the transports which start from the position of starting step.
		Set<Transport> stepTransports = new HashSet<>(Arrays.asList(
			pathfinderConfig.getTransportsPacked(bankVisited)
				.getOrDefault(currentStep.getPackedPosition(), TransportAvailability.EMPTY_TRANSPORTS)));
		// Add the teleports, which might be used from anywhere.
		stepTransports.addAll(Arrays.asList(pathfinderConfig.getUsableTeleports(bankVisited)));
		// Remove transports which do not target the correct location.
		stepTransports.removeIf(transport -> transport.getDestination() != nextStep.getPackedPosition());
		// Remove teleports that share destinations with a local transport type on this edge.
		// For example, if the path uses a QUETZAL (local) transport, suppress QUETZAL_WHISTLE hints.
		// Also suppress them when the edge distance is within the shared type's radius threshold,
		// which occurs when the path is simply walking to a landing site (not teleporting to it).
		Set<TransportType> localTypes = EnumSet.noneOf(TransportType.class);
		for (Transport t : stepTransports)
		{
			if (t.getOrigin() != Transport.UNDEFINED_ORIGIN && t.getType() != null)
			{
				localTypes.add(t.getType());
			}
		}
		int edgeDistance = WorldPointUtil.distanceBetween2D(currentStep.getPackedPosition(), nextStep.getPackedPosition());
		boolean samePlane = WorldPointUtil.unpackWorldPlane(currentStep.getPackedPosition())
			== WorldPointUtil.unpackWorldPlane(nextStep.getPackedPosition());
		stepTransports.removeIf(t ->
		{
			if (t.getOrigin() != Transport.UNDEFINED_ORIGIN || t.getType() == null)
			{
				return false; // keep local transports
			}
			// A same-plane adjacent edge is a plain walking step — the pathfinder never spends a
			// teleport on a one-tile hop. Any anywhere-teleport matching it is the path merely
			// walking across that teleport's landing tile, so it must not be hinted.
			if (samePlane && edgeDistance <= 1)
			{
				return true;
			}
			TransportType sharedType = t.getType().sharesDestinationsWith();
			if (sharedType == null)
			{
				return false; // not a shared-destination teleport, keep it
			}
			// Suppress if a local transport of the shared type is present on this edge (Issue 1),
			// or if the edge is within the shared type's radius threshold, meaning the path is
			// walking to the landing site rather than teleporting there (Issue 2).
			return localTypes.contains(sharedType)
				|| (sharedType.getRadiusThreshold() != null && edgeDistance <= sharedType.getRadiusThreshold());
		});
		return stepTransports;
	}

	public PathStep nextPathStep(List<PathStep> path, int index)
	{
		if (path == null || index < 0 || index + 1 >= path.size())
		{
			return null;
		}
		return path.get(index + 1);
	}

	/**
	 * Checks if the destination is inside POH and looks ahead in the path to find the exit transport.
	 * If the immediate exit leads to a fairy ring or other notable transport shortly after,
	 * that information is included instead.
	 *
	 * @param destination  The destination point to check
	 * @param path         The full path
	 * @param currentIndex The current index in the path
	 * @return The display info of the POH exit transport, or null if not applicable
	 */
	public String getPohExitInfo(int destination, List<PathStep> path, int currentIndex)
	{
		if (path == null || currentIndex < 0)
		{
			return null;
		}

		int destX = WorldPointUtil.unpackWorldX(destination);
		int destY = WorldPointUtil.unpackWorldY(destination);

		// Check if destination is inside POH
		if (!isInsidePoh(destX, destY))
		{
			return null;
		}

		String immediateExitInfo = null;

		// Look ahead in the path to find the next transport that exits POH
		for (int i = currentIndex + 1; i < path.size() - 1; i++)
		{
			int stepLocation = path.get(i).getPackedPosition();
			int nextLocation = path.get(i + 1).getPackedPosition();

			int stepX = WorldPointUtil.unpackWorldX(stepLocation);
			int stepY = WorldPointUtil.unpackWorldY(stepLocation);
			int nextX = WorldPointUtil.unpackWorldX(nextLocation);
			int nextY = WorldPointUtil.unpackWorldY(nextLocation);

			// Check if this step is inside POH but next step is outside (exit transport)
			boolean stepInsidePoh = isInsidePoh(stepX, stepY);
			boolean nextInsidePoh = isInsidePoh(nextX, nextY);

			if (stepInsidePoh && !nextInsidePoh)
			{
				// Found the exit transport - get its display info using bank-aware lookup
				PathStep currentStep = path.get(i);
				PathStep nextStep = path.get(i + 1);
				for (Transport transport : transportsForEdge(currentStep, nextStep))
				{
					String exitInfo = transport.getDisplayInfo();
					if (exitInfo != null && !exitInfo.isEmpty())
					{
						TransportType exitType = transport.getType();
						if (TransportType.TELEPORTATION_BOX.equals(exitType))
						{
							String objInfo = transport.getObjectInfo();
							if (objInfo != null && objInfo.contains("Amulet of Glory"))
							{
								immediateExitInfo = "Mounted Glory: " + exitInfo;
							}
							else if (objInfo != null && objInfo.contains("Mythical cape"))
							{
								immediateExitInfo = "Mythical Cape: " + exitInfo;
							}
							else if (objInfo != null && objInfo.contains("Xeric's Talisman"))
							{
								immediateExitInfo = "Xeric's Talisman: " + exitInfo;
							}
							else if (objInfo != null && objInfo.contains("Digsite"))
							{
								immediateExitInfo = "Digsite Pendant: " + exitInfo;
							}
							else
							{
								immediateExitInfo = "Jewelry Box: " + exitInfo;
							}
						}
						else if (TransportType.TELEPORTATION_PORTAL_POH.equals(exitType))
						{
							immediateExitInfo = "Nexus: " + exitInfo;
						}
						else if (TransportType.FAIRY_RING.equals(exitType))
						{
							immediateExitInfo = "Fairy Ring " + exitInfo;
						}
						else if (TransportType.SPIRIT_TREE.equals(exitType))
						{
							immediateExitInfo = "Spirit Tree: " + exitInfo;
						}
						else if (TransportType.WILDERNESS_OBELISK.equals(exitType))
						{
							immediateExitInfo = "Obelisk: " + exitInfo;
						}
						else
						{
							immediateExitInfo = exitInfo;
						}
					}
					break;
				}
				break;
			}

			// If we've left POH without finding a transport, stop looking
			if (!stepInsidePoh)
			{
				break;
			}
		}

		return immediateExitInfo;
	}

	private Color override(String configOverrideKey, Color defaultValue)
	{
		if (!configOverride.isEmpty())
		{
			Object value = configOverride.get(configOverrideKey);
			if (value instanceof Color)
			{
				return (Color) value;
			}
		}
		return defaultValue;
	}

	private TileCounter override(String configOverrideKey, TileCounter defaultValue)
	{
		if (!configOverride.isEmpty())
		{
			Object value = configOverride.get(configOverrideKey);
			if (value instanceof String)
			{
				TileCounter tileCounter = TileCounter.fromType((String) value);
				if (tileCounter != null)
				{
					return tileCounter;
				}
			}
		}
		return defaultValue;
	}

	private void cacheConfigValues()
	{
		drawCollisionMap = override("drawCollisionMap", config.drawCollisionMap());
		drawMap = override("drawMap", config.drawMap());
		drawMinimap = override("drawMinimap", config.drawMinimap());
		drawTiles = override("drawTiles", config.drawTiles());
		drawTransports = override("drawTransports", config.drawTransports());
		showTransportInfo = override("showTransportInfo", config.showTransportInfo());
		showBankPickupInfo = override("showBankPickupInfo", config.showBankPickupInfo());

		colourCollisionMap = override("colourCollisionMap", config.colourCollisionMap());
		colourPath = override("colourPath", config.colourPath());
		colourPathBlocked = override("colourPathBlocked", config.colourPathBlocked());
		colourPathCalculating = override("colourPathCalculating", config.colourPathCalculating());
		colourPathUnreachable = override("colourPathUnreachable", config.colourPathUnreachable());
		colourText = override("colourText", config.colourText());
		colourTransports = override("colourTransports", config.colourTransports());
		colourTeleportPulse = override("colourTeleportPulse", config.colourTeleportPulse());

		tileCounterStep = override("tileCounterStep", config.tileCounterStep());
		unreachableTargetDistance = override("unreachableTargetDistanceThreshold", config.unreachableTargetDistance());
		unreachableText = config.unreachableText();

		showTileCounter = override("showTileCounter", config.showTileCounter());
		showTeleportPulse = override("showTeleportPulse", config.showTeleportPulse());
		showDirections = override("showDirections", config.showDirections());
		arrivalAutoDismiss = override("arrivalAutoDismiss", config.arrivalAutoDismiss());
		arrivalDismissSeconds = override("arrivalDismissSeconds", config.arrivalDismissSeconds());
	}

	private String simplify(String text)
	{
		return Text.removeTags(text).toLowerCase()
			.replaceAll("[^a-zA-Z ]", "")
			.replace(" ", "_")
			.replace("__", "_");
	}

	private void onMenuOptionClicked(MenuEntry entry)
	{
		if (entry.getOption().equals(SET) && entry.getTarget().equals(TARGET))
		{
			targetSource = "map pin";
			setTarget(getSelectedWorldPoint());
		}
		else if (entry.getOption().equals(SET) && pathfinder != null && entry.getTarget().equals(TARGET +
			ColorUtil.wrapWithColorTag(" " + (pathfinder.getTargets().size() + 1), JagexColors.MENU_TARGET)))
		{
			targetSource = "map pin";
			setTarget(getSelectedWorldPoint(), true);
		}
		else if (entry.getOption().equals(SET) && entry.getTarget().equals(START))
		{
			setStart(getSelectedWorldPoint());
		}
		else if (entry.getOption().equals(CLEAR) && entry.getTarget().equals(PATH))
		{
			targetSource = null;
			setTarget(WorldPointUtil.UNDEFINED);
		}
		else if (entry.getOption().equals(FIND_CLOSEST))
		{
			targetSource = "map pin";
			setTargets(pathfinderConfig.getDestinations(simplify(entry.getTarget())), true);
		}
	}

	private int getSelectedWorldPoint()
	{
		if (client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER) == null)
		{
			if (client.getTopLevelWorldView().getSelectedSceneTile() != null)
			{
				return WorldPointUtil.fromLocalInstance(client, client.getTopLevelWorldView().getSelectedSceneTile().getLocalLocation());
			}
		}
		else
		{
			return client.isMenuOpen()
				? calculateMapPoint(lastMenuOpenedPoint.getX(), lastMenuOpenedPoint.getY())
				: calculateMapPoint(client.getMouseCanvasPosition().getX(), client.getMouseCanvasPosition().getY());
		}
		return WorldPointUtil.UNDEFINED;
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
				pathfinderConfig != null ? pathfinderConfig.getMap() : null, packedPosition));
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

	/** The player's packed world position, or {@link WorldPointUtil#UNDEFINED} when not logged in. */
	public int getPlayerLocation()
	{
		Player local = client.getLocalPlayer();
		return local == null ? WorldPointUtil.UNDEFINED
			: WorldPointUtil.fromLocalInstance(client, local.getLocalLocation());
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
				pathfinderConfig != null ? pathfinderConfig.getMap() : null, target);
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
		routeCostMultiple = DEFAULT_COST_MULTIPLE;
		if (targets == null || targets.isEmpty())
		{
			synchronized (pathfinderMutex)
			{
				if (pathfinder != null)
				{
					pathfinder.cancel();
				}
				pathfinder = null;
			}

			worldMapPointManager.removeIf(x -> x == marker);
			marker = null;
			startPointSet = false;
			selectedRoute = null;
			routeLimit = defaultRouteLimit();
			// Keep the teleport-methods catalog visible with no target selected.
			triggerAlternatives(WorldPointUtil.UNDEFINED, new HashSet<>());
		}
		else
		{
			Player localPlayer = client.getLocalPlayer();
			if (!startPointSet && localPlayer == null)
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
			lastLocation = start;
			if (startPointSet && pathfinder != null)
			{
				start = pathfinder.getStart();
			}
			Set<Integer> destinations = new HashSet<>(targets);
			if (pathfinder != null && append)
			{
				destinations.addAll(pathfinder.getTargets());
			}
			// Stamp the journey start so arrival can report the elapsed time.
			targetSetMillis = System.currentTimeMillis();
			// Alternatives are computed manually via the panel's "Find routes" button.
			restartPathfinding(start, destinations, append);
		}
	}

	private void setStart(int start)
	{
		if (pathfinder == null)
		{
			return;
		}
		startPointSet = true;
		restartPathfinding(start, pathfinder.getTargets());
	}

	// --- Alternative-routes feature (driven by ShortestPathPanel) ---

	public RouteOption getSelectedRoute()
	{
		return selectedRoute;
	}

	/**
	 * The route currently being displayed: the explicitly selected one, or — when the alternatives
	 * were computed for the pathfinder's current destination — the first (best) route of the list
	 * under the currently selected mode. Null when neither applies (falls back to the classic path).
	 */
	public RouteOption getDisplayedRoute()
	{
		RouteOption route = selectedRoute;
		if (route != null)
		{
			return route;
		}
		List<RouteOption> routes = alternativeRoutes;
		if (routes.isEmpty())
		{
			return null;
		}
		// Only substitute the first alternative when it was computed for the current destination;
		// a stale list (target changed since "Find routes") must not override the live path.
		Pathfinder current = pathfinder;
		if (current == null || !lastAltTargets.equals(current.getTargets()))
		{
			return null;
		}
		return routes.get(0);
	}

	/**
	 * The path the overlays should draw: the displayed alternative route if any (the selected one,
	 * or by default the first route of the current alternatives list, so the drawn path reflects the
	 * chosen mode/exclusions), otherwise the classic live pathfinder path.
	 */
	public List<PathStep> getDisplayPath()
	{
		RouteOption route = getDisplayedRoute();
		if (route != null)
		{
			return route.getPath();
		}
		Pathfinder current = pathfinder;
		if (current == null)
		{
			return List.of();
		}
		// The classic path is only a fallback here: route 1 replaces it as soon as the alternatives
		// stream in. Drawing it in the meantime made the world path visibly jump around after setting
		// a target — the progressive search frontier first, then its final shape, then blank, then
		// route 1. Keep the world empty instead while the classic search is still running, while the
		// alternatives are computing, or while their auto-trigger for this target is still pending
		// (it fires on the next game tick). The classic path still draws when a generation finishes
		// without producing any routes.
		if (!current.getTargets().isEmpty()
			&& (!current.isDone() || altGenerationInFlight || !lastAltTargets.equals(current.getTargets())))
		{
			return List.of();
		}
		return current.getPath();
	}

	public List<RouteOption> getAlternativeRoutes()
	{
		return alternativeRoutes;
	}

	public Set<TeleportMethod> getUserExclusions()
	{
		return new HashSet<>(userExclusions);
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

	/**
	 * The first time the catalog is delivered, seed every seasonal (Leagues) method into the user
	 * exclusions so they're disabled by default — the player then enables them individually like any
	 * other method. A one-shot config marker guards it, so a method the player later re-enables is
	 * never re-disabled. Runs on the alt-routes worker thread; the set and configManager are both
	 * thread-safe, and the panel picks up the change via the {@code getUserExclusions()} it's about
	 * to be handed in the same update.
	 */
	private void applySeasonalDefaultExclusions(List<TeleportMethod> catalog)
	{
		if (seasonalDefaultsApplied || catalog == null || catalog.isEmpty())
		{
			return;
		}
		seasonalDefaultsApplied = true;
		boolean changed = false;
		for (TeleportMethod method : catalog)
		{
			if (method.getType() == gps.transport.TransportType.SEASONAL_TRANSPORTS
				&& userExclusions.add(method))
			{
				changed = true;
			}
		}
		if (changed)
		{
			saveExclusions();
		}
		configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_SEASONAL_DEFAULTED, true);
	}

	public void selectRoute(int index)
	{
		List<RouteOption> routes = alternativeRoutes;
		if (index >= 0 && index < routes.size())
		{
			RouteOption route = routes.get(index);
			// Toggle: clicking the route that's already shown hides it.
			selectedRoute = (selectedRoute == route) ? null : route;
			refreshPanel(false);
		}
	}

	public void excludeMethod(TeleportMethod method)
	{
		if (method != null && userExclusions.add(method))
		{
			saveExclusions();
			// No recalculation here: exclusions apply on the next "Refresh routes to target" (or any
			// other recompute); this just refreshes the panel so the catalog icons and counts update.
			refreshPanel(altGenerationInFlight);
		}
	}

	public void includeMethod(TeleportMethod method)
	{
		if (method != null && userExclusions.remove(method))
		{
			saveExclusions();
			// No recalculation here: exclusions apply on the next "Refresh routes to target" (or any
			// other recompute); this just refreshes the panel so the catalog icons and counts update.
			refreshPanel(altGenerationInFlight);
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
			saveExclusions();
			// No recalculation here: exclusions apply on the next "Refresh routes to target" (or any
			// other recompute); this just refreshes the panel so the catalog icons and counts update.
			refreshPanel(altGenerationInFlight);
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
			saveExclusions();
			// No recalculation here: exclusions apply on the next "Refresh routes to target" (or any
			// other recompute); this just refreshes the panel so the catalog icons and counts update.
			refreshPanel(altGenerationInFlight);
		}
	}

	public void clearExclusions()
	{
		if (!userExclusions.isEmpty())
		{
			userExclusions.clear();
			saveExclusions();
			// No recalculation here: exclusions apply on the next "Refresh routes to target" (or any
			// other recompute); this just refreshes the panel so the catalog icons and counts update.
			refreshPanel(altGenerationInFlight);
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
			Pathfinder current = pathfinder;
			if (current != null && !current.getTargets().isEmpty())
			{
				int start = altStart(current);
				log.debug("[alt-routes] Find routes: SP target set, spStart={}, searchStart={}, target={}",
					WorldPointUtil.unpackWorldPoint(current.getStart()),
					WorldPointUtil.unpackWorldPoint(start),
					WorldPointUtil.unpackWorldPoint(current.getTargets().iterator().next()));
				routeLimit = defaultRouteLimit();
				triggerAlternatives(start, new HashSet<>(current.getTargets()));
			}
			else
			{
				log.debug("[alt-routes] Find routes: no SP target (pathfinder={})",
					current == null ? "null" : "targets-empty");
				triggerAlternatives(WorldPointUtil.UNDEFINED, new HashSet<>());
			}
		});
	}

	/**
	 * The start tile to search alternatives from: the player's current (instance-correct) location,
	 * matching what GPS itself uses for recalculation, falling back to the pathfinder's own
	 * start. Must be called on the client thread.
	 */
	private int altStart(Pathfinder current)
	{
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer != null)
		{
			return WorldPointUtil.fromLocalInstance(client, localPlayer);
		}
		return current.getStart();
	}

	/**
	 * The configured number of routes to search for per query (clamped to the service's hard cap).
	 */
	private int defaultRouteLimit()
	{
		int configured = override("defaultRouteCount", config.defaultRouteCount());
		return Math.max(1, Math.min(configured, 25));
	}

	public boolean canLoadMoreRoutes()
	{
		return moreRoutesLikely;
	}

	public void loadMoreRoutes()
	{
		if (lastAltTargets.isEmpty() || !moreRoutesLikely)
		{
			return;
		}
		// Widen the cost band rather than the route count: reveal routes up to a higher multiple of
		// the best route's cost (the ones the previous cap held back).
		routeCostMultiple = Math.min(routeCostMultiple + COST_MULTIPLE_STEP, MAX_COST_MULTIPLE);
		triggerAlternatives(lastAltStart, new HashSet<>(lastAltTargets));
	}

	// Directions for the currently displayed route, built once per route (the overlay renders every
	// frame; the path scan only reruns when the displayed route object changes).
	private RouteOption directionsRoute;
	private List<RouteDirections.Step> directions = List.of();

	/**
	 * The step-by-step directions for {@code route}, cached per route instance.
	 */
	public List<RouteDirections.Step> getRouteDirections(RouteOption route)
	{
		if (route != directionsRoute)
		{
			directions = RouteDirections.build(this, route);
			directionsRoute = route;
		}
		return directions;
	}

	/**
	 * Where the current destination came from ("map pin", "Quest Helper", ...) or null when unknown.
	 */
	public String getTargetSource()
	{
		return targetSource;
	}

	/**
	 * Writes a JSON snapshot of the current routing state to ~/.runelite/gps-debug/ — everything
	 * needed to reproduce and debug the current path: routes with their full tile paths, methods and
	 * edge data, mode/exclusions, player position, GPS progress state, and the relevant config.
	 * Triggered by the panel's camera button; confirms via a game message.
	 */
	private static List<Object> stepsJson(List<RouteDirections.Step> steps)
	{
		List<Object> stepsJson = new ArrayList<>();
		for (RouteDirections.Step step : steps)
		{
			Map<String, Object> stepJson = new LinkedHashMap<>();
			stepJson.put("text", step.getText());
			stepJson.put("startIndex", step.getStartIndex());
			stepJson.put("endIndex", step.getEndIndex());
			stepJson.put("ticks", step.getTicks());
			stepJson.put("transport", step.isTransport());
			stepJson.put("door", step.isDoor());
			stepJson.put("obstacle", step.isObstacle());
			stepsJson.add(stepJson);
		}
		return stepsJson;
	}

	// Whether the fixed benchmark suite is currently running (one at a time).
	private final java.util.concurrent.atomic.AtomicBoolean benchmarkRunning =
		new java.util.concurrent.atomic.AtomicBoolean(false);

	/**
	 * Runs the fixed performance-benchmark suite ({@link GpsBenchmark}): prepared alternative-routes
	 * trips and nearest-X queries with pinned parameters, reported as JSON under
	 * ~/.runelite/gps-debug/ for comparing profiling data across plugin versions. Triggered by the
	 * panel's stopwatch button; progress and the report path are announced as game messages. The
	 * benchmark's generations supersede any in-flight route search, so the user's routes are
	 * regenerated once it finishes.
	 */
	public void runBenchmark()
	{
		if (altRoutesService == null || !GameState.LOGGED_IN.equals(client.getGameState()))
		{
			log.info("GPS benchmark requires being logged in.");
			return;
		}
		if (!benchmarkRunning.compareAndSet(false, true))
		{
			return;
		}
		GpsBenchmark.standard(altRoutesService, getTransports(), getUserExclusions(), gson,
			message -> clientThread.invokeLater(() ->
			{
				log.info(message);
				if (GameState.LOGGED_IN.equals(client.getGameState()))
				{
					client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", message, null);
				}
			}),
			() ->
			{
				benchmarkRunning.set(false);
				// The benchmark advanced the service's generation counter (cancelling any user
				// generation mid-flight); regenerate so the panel and flags end up consistent.
				clientThread.invokeLater(() ->
				{
					if (!lastAltTargets.isEmpty())
					{
						triggerAlternatives(lastAltStart, new HashSet<>(lastAltTargets));
					}
					else
					{
						triggerAlternatives(WorldPointUtil.UNDEFINED, new HashSet<>());
					}
				});
			})
			.start();
	}

	public void captureDebugSnapshot()
	{
		clientThread.invokeLater(() ->
		{
			try
			{
				Map<String, Object> snapshot = new LinkedHashMap<>();
				snapshot.put("capturedAt", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
				Player local = client.getLocalPlayer();
				snapshot.put("player", local != null ? packedPointJson(WorldPointUtil.packWorldPoint(local.getWorldLocation())) : null);
				snapshot.put("routesMode", String.valueOf(routesMode));
				snapshot.put("routeLimit", routeLimit);
			snapshot.put("routeCostMultiple", routeCostMultiple);
				snapshot.put("targetSource", targetSource);
				snapshot.put("altStart", packedPointJson(lastAltStart));
				List<Object> targets = new ArrayList<>();
				for (int target : lastAltTargets)
				{
					targets.add(packedPointJson(target));
				}
				snapshot.put("targets", targets);
				List<String> exclusions = new ArrayList<>();
				for (TeleportMethod method : userExclusions)
				{
					exclusions.add(method.getType() + "|" + method.getDisplayInfo() + "|" + method.getDestination());
				}
				snapshot.put("userExclusions", exclusions);
				snapshot.put("bankContentsKnown", bankContentsKnown);

				Map<String, Object> configValues = new LinkedHashMap<>();
				configValues.put("avoidWilderness", override("avoidWilderness", config.avoidWilderness()));
				configValues.put("useTeleportationItems", String.valueOf(override("useTeleportationItems", config.useTeleportationItems())));
				configValues.put("includeBankPath", override("includeBankPath", config.includeBankPath()));
				configValues.put("costBankPickup", override("costBankPickup", config.costBankPickup()));
				configValues.put("defaultRouteCount", override("defaultRouteCount", config.defaultRouteCount()));
				snapshot.put("config", configValues);

				RouteOption displayed = getDisplayedRoute();
				List<RouteOption> routes = alternativeRoutes;
				snapshot.put("displayedRouteIndex", displayed != null ? routes.indexOf(displayed) : -1);
				List<Object> routesJson = new ArrayList<>();
				for (RouteOption route : routes)
				{
					Map<String, Object> routeJson = new LinkedHashMap<>();
					routeJson.put("totalCost", route.getTotalCost());
					routeJson.put("rawCost", route.getRawCost());
					routeJson.put("reached", route.isReached());
					routeJson.put("viaBank", route.isViaBank());
					List<String> methods = new ArrayList<>();
					for (TeleportMethod method : route.getMethods())
					{
						methods.add(method.getType() + "|" + method.getDisplayInfo() + "|" + method.getDestination());
					}
					routeJson.put("methods", methods);
					routeJson.put("methodEdgeIndexes", route.getMethodEdgeIndexes());
					routeJson.put("methodDurations", route.getMethodDurations());
					routeJson.put("walkBeforeSteps", route.getWalkBeforeSteps());
					routeJson.put("trailingWalkSteps", route.getTrailingWalkSteps());
					List<Integer> packedPath = new ArrayList<>(route.getPath().size());
					List<Integer> bankFlips = new ArrayList<>();
					for (int i = 0; i < route.getPath().size(); i++)
					{
						packedPath.add(route.getPath().get(i).getPackedPosition());
						if (route.getPath().get(i).isBankVisited()
							&& (i == 0 || !route.getPath().get(i - 1).isBankVisited()))
						{
							bankFlips.add(i);
						}
					}
					routeJson.put("packedPath", packedPath);
					routeJson.put("bankVisitedFrom", bankFlips);
					// Fresh directions build per route, timed — the dashboard renders the step
					// list for every route and charts how long step derivation takes.
					long buildStart = System.nanoTime();
					List<RouteDirections.Step> routeSteps = RouteDirections.build(this, route);
					routeJson.put("directionsBuildMicros", (System.nanoTime() - buildStart) / 1_000);
					routeJson.put("directions", stepsJson(routeSteps));
					routesJson.add(routeJson);
				}
				snapshot.put("routes", routesJson);
				long[] genTiming = altRoutesService != null ? altRoutesService.getLastTimingSummary() : null;
				if (genTiming != null)
				{
					Map<String, Object> timingJson = new LinkedHashMap<>();
					timingJson.put("wallMs", genTiming[0]);
					timingJson.put("clientMs", genTiming[1]);
					timingJson.put("rebuildMs", genTiming[2]);
					timingJson.put("searchCpuMs", genTiming[3]);
					timingJson.put("searches", genTiming[4]);
					if (genTiming.length > 5)
					{
						timingJson.put("fieldMs", genTiming[5]);
					}
					// Per-search profiles, slowest first: which searches the time went to and how much
					// each explored (a flat A* heuristic shows up as a huge node count).
					List<Object> searchDetails = new ArrayList<>();
					for (AlternativeRoutesService.SearchRecord r : altRoutesService.getLastSearchRecords())
					{
						Map<String, Object> detail = new LinkedHashMap<>();
						detail.put("label", r.label);
						detail.put("cpuMs", r.cpuMs);
						detail.put("cost", r.resultCost);
						detail.put("reached", r.reached);
						detail.put("termination", r.termination);
						detail.put("nodes", r.nodesChecked);
						detail.put("transports", r.transportsChecked);
						detail.put("capped", r.capped);
						detail.put("astar", r.astar);
						searchDetails.add(detail);
					}
					timingJson.put("searchDetails", searchDetails);
					snapshot.put("altGenTiming", timingJson);
				}

				if (displayed != null)
				{
					snapshot.put("directions", stepsJson(getRouteDirections(displayed)));
					Map<String, Object> progress = new LinkedHashMap<>();
					progress.put("reachedIndex", routeDirectionsOverlay.getReachedIndex());
					progress.put("liveRemainingTicks", routeDirectionsOverlay.getLiveRemainingTicks());
					progress.put("speedTilesPerSecond", routeDirectionsOverlay.getSpeedTilesPerSecond());
					snapshot.put("progress", progress);
				}

				File dir = new File(net.runelite.client.RuneLite.RUNELITE_DIR, "gps-debug");
				//noinspection ResultOfMethodCallIgnored
				dir.mkdirs();
				File out = new File(dir, "gps-capture-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date()) + ".json");
				try (java.io.Writer writer = new java.io.OutputStreamWriter(
					new java.io.FileOutputStream(out), java.nio.charset.StandardCharsets.UTF_8))
				{
					gson.newBuilder().setPrettyPrinting().create().toJson(snapshot, writer);
				}
				log.info("GPS debug snapshot saved to {}", out.getAbsolutePath());
				if (GameState.LOGGED_IN.equals(client.getGameState()))
				{
					client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "",
						"GPS debug snapshot saved to " + out.getAbsolutePath(), null);
				}
			}
			catch (Exception e)
			{
				log.warn("Failed to capture GPS debug snapshot", e);
			}
		});
	}

	private static Map<String, Object> packedPointJson(int packed)
	{
		if (packed == WorldPointUtil.UNDEFINED)
		{
			return null;
		}
		Map<String, Object> point = new LinkedHashMap<>();
		point.put("packed", packed);
		point.put("x", WorldPointUtil.unpackWorldX(packed));
		point.put("y", WorldPointUtil.unpackWorldY(packed));
		point.put("plane", WorldPointUtil.unpackWorldPlane(packed));
		return point;
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
		return bankContentsKnown;
	}

	public void setRoutesMode(AlternativeRoutesMode mode)
	{
		if (mode == null || this.routesMode == mode)
		{
			return;
		}
		this.routesMode = mode;
		saveRoutesMode();
		triggerAlternatives(lastAltStart, new HashSet<>(lastAltTargets));
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
		Pathfinder current = pathfinder;
		Set<Integer> targets = (current != null) ? current.getTargets() : Set.of();
		// With the panel hidden, only the primary route is computed — one search, the same class of
		// work as the classic pathfinder, and the GPS overlay needs it. The extra alternatives are
		// searched automatically once the panel is opened (see setAltPanelVisible).
		int desiredLimit = altPanelVisible ? defaultRouteLimit() : 1;
		if (!shouldAutoCompute(targets, lastAltTargets, lastAltLimit, desiredLimit))
		{
			return;
		}
		routeLimit = desiredLimit;
		triggerAlternatives(altStart(current), new HashSet<>(targets));
	}

	/**
	 * Whether a new alternatives generation is needed: there is a target, and either it changed since
	 * the last generation or the last generation was allowed fewer routes than wanted now (a
	 * primary-only run while the panel was hidden, caught up when it opens). Pure decision, unit-tested.
	 */
	static boolean shouldAutoCompute(Set<Integer> targets, Set<Integer> lastTargets, int lastLimit, int desiredLimit)
	{
		return !targets.isEmpty() && (!targets.equals(lastTargets) || lastLimit < desiredLimit);
	}

	/**
	 * Called by the panel when the GPS sidebar tab is shown or hidden. Opening the panel catches up:
	 * if the current destination only got its primary route while the panel was hidden, the extra
	 * alternatives are searched now.
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
		lastAltStart = start;
		lastAltTargets = Set.copyOf(ends);
		lastAltLimit = routeLimit;

		// Clear the previous routes immediately (the catalog stays); the new routes stream in one by
		// one as they are found. With no target this still streams just the teleport-methods catalog.
		alternativeRoutes = new ArrayList<>();
		moreRoutesLikely = false;
		altGenerationInFlight = !ends.isEmpty();
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
		altRoutesService.generate(start, ends, userExclusions, routesMode, routeLimit, routeCostMultiple,
			altRoundTrip, this::onAlternativeRoutesUpdate);
	}

	private void onAlternativeRoutesUpdate(List<RouteOption> routes, List<TeleportMethod> catalog,
		Map<TeleportMethod, MethodAvailability> unavailable, boolean done)
	{
		alternativeRoutes = routes;
		teleportCatalog = catalog;
		unavailableMethods = unavailable;
		applySeasonalDefaultExclusions(catalog);
		if (done)
		{
			// "More" is available when the cost cap held routes back — raising the multiple can
			// surface them. Once walking is the ceiling, there's nothing cheaper-than-walk left.
			moreRoutesLikely = !routes.isEmpty() && altRoutesService.wasMoreLikely()
				&& routeCostMultiple < MAX_COST_MULTIPLE;
			// Generation finished; if it produced nothing the classic path may draw again as fallback.
			altGenerationInFlight = false;
		}
		final boolean hasTarget = !lastAltTargets.isEmpty();
		SwingUtilities.invokeLater(() ->
		{
			if (selectedRoute != null && !routes.contains(selectedRoute))
			{
				selectedRoute = null;
			}
			if (altPanel != null)
			{
				altPanel.displayRoutes(routes, catalog, unavailable, getUserExclusions(), !done, hasTarget);
			}
		});
	}


	private void refreshPanel(boolean calculating)
	{
		final boolean hasTarget = !lastAltTargets.isEmpty();
		if (altPanel != null)
		{
			SwingUtilities.invokeLater(() ->
				altPanel.displayRoutes(alternativeRoutes, teleportCatalog, unavailableMethods,
					getUserExclusions(), calculating, hasTarget));
		}
	}

	private void saveExclusions()
	{
		try
		{
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_EXCLUSIONS,
				gson.toJson(new ArrayList<>(userExclusions)));
		}
		catch (Exception e)
		{
			log.warn("Failed to save alternative-route exclusions", e);
		}
	}

	private void loadExclusions()
	{
		try
		{
			String json = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY_EXCLUSIONS);
			if (json == null || json.isEmpty())
			{
				return;
			}
			TeleportMethod[] saved = gson.fromJson(json, TeleportMethod[].class);
			if (saved != null)
			{
				for (TeleportMethod method : saved)
				{
					if (method != null && method.getType() != null)
					{
						userExclusions.add(method);
					}
				}
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to load alternative-route exclusions", e);
		}
	}

	private void saveRoutesMode()
	{
		configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_MODE, routesMode.name());
	}

	private void loadRoutesMode()
	{
		String value = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY_MODE);
		if (value == null || value.isEmpty())
		{
			return;
		}
		try
		{
			routesMode = AlternativeRoutesMode.valueOf(value);
		}
		catch (IllegalArgumentException e)
		{
			// Legacy 3-mode names from before the Owned/All split.
			switch (value)
			{
				case "AVAILABLE":
					routesMode = AlternativeRoutesMode.OWNED_INVENTORY;
					break;
				case "AVAILABLE_WITH_BANK":
					routesMode = AlternativeRoutesMode.OWNED_WITH_BANK;
					break;
				case "ALL_TELEPORTS":
				case "ALL_UNLOCKED":
					// Legacy names; the unlocked-only middle mode was folded into All.
					routesMode = AlternativeRoutesMode.ALL_EVERYTHING;
					break;
				default:
					log.warn("Unknown alternative-routes mode '{}'", value);
					break;
			}
		}
	}

	public int calculateMapPoint(int pointX, int pointY)
	{
		WorldMap worldMap = client.getWorldMap();
		float zoom = worldMap.getWorldMapZoom();
		int mapPoint = WorldPointUtil.packWorldPoint(worldMap.getWorldMapPosition().getX(), worldMap.getWorldMapPosition().getY(), 0);
		int middleX = mapWorldPointToGraphicsPointX(mapPoint);
		int middleY = mapWorldPointToGraphicsPointY(mapPoint);

		if (pointX == Integer.MIN_VALUE || pointY == Integer.MIN_VALUE ||
			middleX == Integer.MIN_VALUE || middleY == Integer.MIN_VALUE)
		{
			return WorldPointUtil.UNDEFINED;
		}

		final int dx = (int) ((pointX - middleX) / zoom);
		final int dy = (int) ((-(pointY - middleY)) / zoom);

		return WorldPointUtil.dxdy(mapPoint, dx, dy);
	}

	public int mapWorldPointToGraphicsPointX(int packedWorldPoint)
	{
		WorldMap worldMap = client.getWorldMap();

		float pixelsPerTile = worldMap.getWorldMapZoom();

		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map != null)
		{
			Rectangle worldMapRect = map.getBounds();

			int widthInTiles = (int) Math.ceil(worldMapRect.getWidth() / pixelsPerTile);

			Point worldMapPosition = worldMap.getWorldMapPosition();

			int xTileOffset = WorldPointUtil.unpackWorldX(packedWorldPoint) + widthInTiles / 2 - worldMapPosition.getX();

			int xGraphDiff = ((int) (xTileOffset * pixelsPerTile));
			xGraphDiff += (int) (pixelsPerTile - Math.ceil(pixelsPerTile / 2));
			xGraphDiff += (int) worldMapRect.getX();

			return xGraphDiff;
		}
		return Integer.MIN_VALUE;
	}

	public int mapWorldPointToGraphicsPointY(int packedWorldPoint)
	{
		WorldMap worldMap = client.getWorldMap();

		float pixelsPerTile = worldMap.getWorldMapZoom();

		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map != null)
		{
			Rectangle worldMapRect = map.getBounds();

			int heightInTiles = (int) Math.ceil(worldMapRect.getHeight() / pixelsPerTile);

			Point worldMapPosition = worldMap.getWorldMapPosition();

			int yTileMax = worldMapPosition.getY() - heightInTiles / 2;
			int yTileOffset = (yTileMax - WorldPointUtil.unpackWorldY(packedWorldPoint) - 1) * -1;

			int yGraphDiff = (int) (yTileOffset * pixelsPerTile);
			yGraphDiff -= (int) (pixelsPerTile - Math.ceil(pixelsPerTile / 2));
			yGraphDiff = worldMapRect.height - yGraphDiff;
			yGraphDiff += (int) worldMapRect.getY();

			return yGraphDiff;
		}
		return Integer.MIN_VALUE;
	}

	private void addMenuEntry(MenuEntryAdded event, String option, String target, int position)
	{
		List<MenuEntry> entries = new LinkedList<>(Arrays.asList(client.getMenu().getMenuEntries()));

		if (entries.stream().anyMatch(e -> e.getOption().equals(option) && e.getTarget().equals(target)))
		{
			return;
		}

		client.getMenu().createMenuEntry(position)
			.setOption(option)
			.setTarget(target)
			.setParam0(event.getActionParam0())
			.setParam1(event.getActionParam1())
			.setIdentifier(event.getIdentifier())
			.setType(MenuAction.RUNELITE)
			.onClick(this::onMenuOptionClicked);
	}

	private Widget getMinimapDrawWidget()
	{
		if (client.isResized())
		{
			if (client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1)
			{
				return client.getWidget(InterfaceID.ToplevelPreEoc.MINIMAP);
			}
			return client.getWidget(InterfaceID.ToplevelOsrsStretch.MINIMAP);
		}
		return client.getWidget(InterfaceID.Toplevel.MINIMAP);
	}

	private Shape getMinimapClipAreaSimple()
	{
		Widget minimapDrawArea = getMinimapDrawWidget();

		if (minimapDrawArea == null || minimapDrawArea.isHidden())
		{
			return null;
		}

		Rectangle bounds = minimapDrawArea.getBounds();

		return new Ellipse2D.Double(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
	}

	public Shape getMinimapClipArea()
	{
		Widget minimapWidget = getMinimapDrawWidget();

		if (minimapWidget == null || minimapWidget.isHidden() || !minimapRectangle.equals(minimapRectangle = minimapWidget.getBounds()))
		{
			minimapClipFixed = null;
			minimapClipResizeable = null;
			minimapSpriteFixed = null;
			minimapSpriteResizeable = null;
		}

		if (minimapWidget == null || minimapWidget.isHidden())
		{
			return null;
		}

		if (client.isResized())
		{
			if (minimapClipResizeable != null)
			{
				return minimapClipResizeable;
			}
			if (minimapSpriteResizeable == null)
			{
				minimapSpriteResizeable = spriteManager.getSprite(SpriteID.RESIZE_MAP_MASK, 0);
			}
			if (minimapSpriteResizeable != null)
			{
				minimapClipResizeable = bufferedImageToPolygon(minimapSpriteResizeable);
				return minimapClipResizeable;
			}
			return getMinimapClipAreaSimple();
		}
		if (minimapClipFixed != null)
		{
			return minimapClipFixed;
		}
		if (minimapSpriteFixed == null)
		{
			minimapSpriteFixed = spriteManager.getSprite(SpriteID.FIXED_MAP_MASK, 0);
		}
		if (minimapSpriteFixed != null)
		{
			minimapClipFixed = bufferedImageToPolygon(minimapSpriteFixed);
			return minimapClipFixed;
		}
		return getMinimapClipAreaSimple();
	}

	private Polygon bufferedImageToPolygon(BufferedImage image)
	{
		Color outsideColour = null;
		Color previousColour;
		final int width = image.getWidth();
		final int height = image.getHeight();
		List<java.awt.Point> points = new ArrayList<>();
		for (int y = 0; y < height; y++)
		{
			previousColour = outsideColour;
			for (int x = 0; x < width; x++)
			{
				int rgb = image.getRGB(x, y);
				int a = (rgb & 0xff000000) >>> 24;
				int r = (rgb & 0x00ff0000) >> 16;
				int g = (rgb & 0x0000ff00) >> 8;
				int b = (rgb & 0x000000ff);
				Color colour = new Color(r, g, b, a);
				if (x == 0 && y == 0)
				{
					outsideColour = colour;
					previousColour = colour;
				}
				if (!colour.equals(outsideColour) && previousColour.equals(outsideColour))
				{
					points.add(new java.awt.Point(x, y));
				}
				if ((colour.equals(outsideColour) || x == (width - 1)) && !previousColour.equals(outsideColour))
				{
					points.add(0, new java.awt.Point(x, y));
				}
				previousColour = colour;
			}
		}
		int offsetX = minimapRectangle.x;
		int offsetY = minimapRectangle.y;
		Polygon polygon = new Polygon();
		for (java.awt.Point point : points)
		{
			polygon.addPoint(point.x + offsetX, point.y + offsetY);
		}
		return polygon;
	}
}
