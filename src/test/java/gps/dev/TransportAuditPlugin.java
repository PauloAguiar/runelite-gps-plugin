package gps.dev;

import gps.ClosedDoors;
import gps.WorldPointUtil;
import gps.transport.Transport;
import gps.transport.TransportLoader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEVELOPMENT-ONLY companion plugin (test sources — never packaged for the plugin hub): audits
 * the loaded scene for traversal-looking objects that GPS's data doesn't know, and highlights
 * them with instructions for the operator.
 *
 * An object is a candidate when any of its (impostor-resolved) menu actions is a traversal verb
 * — Cross, Climb, Descend, Enter, Swing, ... — plus Open/Close on wall objects (doors). It is
 * UNMAPPED when:
 * <ul>
 *   <li>door: its tile has no entry in the door registry (doors.tsv) — re-run doorDump;</li>
 *   <li>anything else: no transport row's origin or destination lies within
 *       {@value #COVERAGE_RADIUS} tiles — the operator should add a transports.tsv row, and the
 *       log prints a ready-to-paste template with the origin/menu columns pre-filled.</li>
 * </ul>
 *
 * Loaded by {@link gps.ShortestPathPluginTest}'s dev client only. See TransportAuditOverlay for
 * the on-screen rendering.
 */
@PluginDescriptor(
	name = "GPS Transport Audit (dev)",
	description = "Highlights traversal objects missing from GPS's transport/door data",
	enabledByDefault = true
)
public class TransportAuditPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(TransportAuditPlugin.class);

	/** How far (Chebyshev tiles) a transport origin/destination may sit from an object and still count as covering it. */
	static final int COVERAGE_RADIUS = 3;

	// Menu-action prefixes that mean "using this object moves the player somewhere".
	private static final String[] TRAVERSAL_VERBS = {
		"climb", "cross", "enter", "exit", "descend", "ascend", "jump", "swing", "squeeze",
		"crawl", "balance", "vault", "leap", "scale", "grapple", "board", "travel", "ride",
		"walk-across", "walk-over", "go-through", "pass-through", "teleport",
	};

	/** A traversal object the data doesn't cover, plus what the operator should do about it. */
	static final class Finding
	{
		final TileObject object;
		final int packedTemplateTile; // template coords in instances — what the data files use
		final String name;
		final String action;
		final boolean door;

		Finding(TileObject object, int packedTemplateTile, String name, String action, boolean door)
		{
			this.object = object;
			this.packedTemplateTile = packedTemplateTile;
			this.name = name;
			this.action = action;
			this.door = door;
		}

		String describe()
		{
			return name + " (" + action + ") id=" + object.getId()
				+ " @" + WorldPointUtil.unpackWorldX(packedTemplateTile)
				+ "," + WorldPointUtil.unpackWorldY(packedTemplateTile)
				+ "," + WorldPointUtil.unpackWorldPlane(packedTemplateTile);
		}

		/** One line of operator guidance, shown in the overlay panel. */
		String instruction()
		{
			return door
				? "door not in registry — re-run doorDump"
				: "add transports.tsv row (template in log)";
		}
	}

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private TransportAuditSceneOverlay sceneOverlay;
	@Inject
	private TransportAuditPanelOverlay panelOverlay;

	// Keyed by packed template tile + id so re-spawns don't duplicate; cleared per scene load.
	private final Map<Long, Finding> findings = new ConcurrentHashMap<>();
	// Packed tiles that any transport row's origin or destination touches.
	private Set<Integer> transportTiles;
	// Packed tiles of EVERY door registry row — including doors the map places open (excluded
	// from ClosedDoors' pricing masks on purpose, but still registered and handled). The scene's
	// open-door variant also anchors its swung leaf on a neighbouring tile, so coverage checks a
	// small radius rather than the exact tile.
	private Set<Integer> doorTiles;
	// (id, tile) pairs already logged this session, so the template prints once, not per scene.
	private final Set<Long> logged = new HashSet<>();

	@Override
	protected void startUp()
	{
		if (transportTiles == null)
		{
			transportTiles = new HashSet<>();
			HashMap<Integer, Set<Transport>> all = TransportLoader.loadAllFromResources();
			for (Map.Entry<Integer, Set<Transport>> entry : all.entrySet())
			{
				transportTiles.add(entry.getKey());
				for (Transport transport : entry.getValue())
				{
					if (transport.getOrigin() != WorldPointUtil.UNDEFINED)
					{
						transportTiles.add(transport.getOrigin());
					}
					if (transport.getDestination() != WorldPointUtil.UNDEFINED)
					{
						transportTiles.add(transport.getDestination());
					}
				}
			}
			log.info("[audit] transport coverage set: {} tiles", transportTiles.size());
		}
		if (doorTiles == null)
		{
			doorTiles = loadDoorTiles();
			log.info("[audit] door registry set: {} tiles", doorTiles.size());
		}
		overlayManager.add(sceneOverlay);
		overlayManager.add(panelOverlay);
		// Plugin toggled on with a scene already loaded: sweep it once (spawn events only cover
		// objects loaded after this point).
		if (GameState.LOGGED_IN.equals(client.getGameState()))
		{
			clientThread.invokeLater(this::sweepScene);
		}
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(sceneOverlay);
		overlayManager.remove(panelOverlay);
		findings.clear();
	}

	Iterable<Finding> findings()
	{
		return findings.values();
	}

	int findingCount()
	{
		return findings.size();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (GameState.LOADING.equals(event.getGameState()))
		{
			findings.clear(); // scene rebuild: stale TileObject references must not be drawn
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		inspect(event.getGameObject(), false);
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned event)
	{
		inspect(event.getWallObject(), true);
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned event)
	{
		inspect(event.getGroundObject(), false);
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned event)
	{
		inspect(event.getDecorativeObject(), false);
	}

	private void sweepScene()
	{
		Scene scene = client.getTopLevelWorldView().getScene();
		for (Tile[][] plane : scene.getTiles())
		{
			if (plane == null)
			{
				continue;
			}
			for (Tile[] column : plane)
			{
				if (column == null)
				{
					continue;
				}
				for (Tile tile : column)
				{
					if (tile == null)
					{
						continue;
					}
					if (tile.getWallObject() != null)
					{
						inspect(tile.getWallObject(), true);
					}
					if (tile.getGroundObject() != null)
					{
						inspect(tile.getGroundObject(), false);
					}
					if (tile.getDecorativeObject() != null)
					{
						inspect(tile.getDecorativeObject(), false);
					}
					if (tile.getGameObjects() != null)
					{
						for (TileObject object : tile.getGameObjects())
						{
							if (object != null)
							{
								inspect(object, false);
							}
						}
					}
				}
			}
		}
	}

	/** Client thread. Adds a finding when the object looks like traversal and the data doesn't know it. */
	private void inspect(TileObject object, boolean wall)
	{
		ObjectComposition composition = client.getObjectDefinition(object.getId());
		if (composition == null)
		{
			return;
		}
		if (composition.getImpostorIds() != null)
		{
			try
			{
				composition = composition.getImpostor();
			}
			catch (Exception e)
			{
				return; // varbit not loaded yet; the object respawns visible states later
			}
			if (composition == null)
			{
				return;
			}
		}
		String action = traversalAction(composition, wall);
		if (action == null)
		{
			return;
		}

		int templateTile = WorldPointUtil.fromLocalInstance(client, object.getLocalLocation());
		boolean door = wall && (action.equalsIgnoreCase("Open") || action.equalsIgnoreCase("Close"));
		if (door ? doorCovered(templateTile) : transportCovered(templateTile))
		{
			return;
		}

		long key = ((long) templateTile << 20) | object.getId();
		Finding finding = new Finding(object, templateTile, composition.getName(), action, door);
		if (findings.put(key, finding) == null && logged.add(key) && !door)
		{
			int x = WorldPointUtil.unpackWorldX(templateTile);
			int y = WorldPointUtil.unpackWorldY(templateTile);
			int plane = WorldPointUtil.unpackWorldPlane(templateTile);
			// Ready-to-paste transports.tsv template: Origin, Destination, "menuOption menuTarget
			// objectID", Skills, Items, Quests, Varbits, VarPlayers, Duration, Display info.
			log.info("[audit] unmapped: {} — operator: use the object, note where you land, then "
					+ "fill DEST below; ORIGIN is pre-filled with the OBJECT's tile — correct it to "
					+ "the tile you STAND on to use it, and set Duration to the ticks it took:\n"
					+ "{} {} {}\t<DESTX DESTY PLANE>\t{} {} {}\t\t\t\t\t\t1\t",
				finding.describe(), x, y, plane, action, composition.getName(), object.getId());
		}
	}

	private String traversalAction(ObjectComposition composition, boolean wall)
	{
		String[] actions = composition.getActions();
		if (actions == null)
		{
			return null;
		}
		for (String action : actions)
		{
			if (action == null)
			{
				continue;
			}
			String lower = action.toLowerCase(Locale.ROOT);
			if (wall && (lower.equals("open") || lower.equals("close")))
			{
				return action;
			}
			for (String verb : TRAVERSAL_VERBS)
			{
				if (lower.startsWith(verb))
				{
					return action;
				}
			}
		}
		return null;
	}

	/**
	 * Every doors.tsv row's tile, straight from the resource. NOT ClosedDoors.edgeMasks(): the
	 * masks intentionally drop doors the map places open (unpriced, unhinted — but registered),
	 * which must not show as audit findings.
	 */
	private static Set<Integer> loadDoorTiles()
	{
		Set<Integer> tiles = new HashSet<>();
		try (java.io.InputStream in = ClosedDoors.class.getResourceAsStream("/doors.tsv");
			java.util.Scanner scanner = new java.util.Scanner(in, "UTF-8"))
		{
			boolean header = true;
			while (scanner.hasNextLine())
			{
				String[] fields = scanner.nextLine().split("\t");
				if (header || fields.length < 5)
				{
					header = false;
					continue;
				}
				try
				{
					tiles.add(WorldPointUtil.packWorldPoint(Integer.parseInt(fields[2]),
						Integer.parseInt(fields[3]), Integer.parseInt(fields[4])));
				}
				catch (NumberFormatException ignored)
				{
					// comment or malformed row
				}
			}
		}
		catch (Exception e)
		{
			log.warn("[audit] could not load doors.tsv", e);
		}
		return tiles;
	}

	private boolean doorCovered(int packedTile)
	{
		// Radius 2: an OPEN door in the scene is a different object whose swung leaf anchors on
		// a tile next to the registered (closed) placement.
		return near(doorTiles, packedTile, 2);
	}

	private boolean transportCovered(int packedTile)
	{
		return near(transportTiles, packedTile, COVERAGE_RADIUS);
	}

	private static boolean near(Set<Integer> tiles, int packedTile, int radius)
	{
		int x = WorldPointUtil.unpackWorldX(packedTile);
		int y = WorldPointUtil.unpackWorldY(packedTile);
		int plane = WorldPointUtil.unpackWorldPlane(packedTile);
		for (int dx = -radius; dx <= radius; dx++)
		{
			for (int dy = -radius; dy <= radius; dy++)
			{
				if (tiles.contains(WorldPointUtil.packWorldPoint(x + dx, y + dy, plane)))
				{
					return true;
				}
			}
		}
		return false;
	}
}
