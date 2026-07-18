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
		final String[] actions;
		final boolean door;

		Finding(TileObject object, int packedTemplateTile, String name, String action,
			String[] actions, boolean door)
		{
			this.object = object;
			this.packedTemplateTile = packedTemplateTile;
			this.name = name;
			this.action = action;
			this.actions = actions;
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

		/** Everything the operator needs to register this object, clipboard-ready. */
		String dossier()
		{
			int x = WorldPointUtil.unpackWorldX(packedTemplateTile);
			int y = WorldPointUtil.unpackWorldY(packedTemplateTile);
			int plane = WorldPointUtil.unpackWorldPlane(packedTemplateTile);
			StringBuilder sb = new StringBuilder();
			sb.append(name).append(" id=").append(object.getId())
				.append(" tile=").append(x).append(',').append(y).append(',').append(plane).append('\n');
			sb.append("actions: ").append(String.join(", ", actions)).append('\n');
			if (door)
			{
				sb.append("door missing from doors.tsv — re-run doorDump in shortest-path-tooling");
			}
			else
			{
				sb.append("transports.tsv template — fill DEST after traversing; ORIGIN is the ")
					.append("object's tile, correct it to the tile you STAND on; Duration in ticks:\n");
				sb.append(x).append(' ').append(y).append(' ').append(plane).append('\t')
					.append("DESTX DESTY PLANE").append('\t')
					.append(action).append(' ').append(name).append(' ').append(object.getId())
					.append("\t\t\t\t\t\t1\t");
			}
			return sb.toString();
		}
	}

	/** Curation state of one finding, driving the panel row and scene outline colors. */
	enum FindingState
	{
		MISSING,
		ARMED,
		DOOR,
		CAPTURED_SESSION,
		CAPTURED_PRIOR
	}

	/** One immutable panel row: a finding, its state, and its distance from the player. */
	static final class Row
	{
		final Finding finding;
		final FindingState state;
		final int distance;

		Row(Finding finding, FindingState state, int distance)
		{
			this.finding = finding;
			this.state = state;
			this.distance = distance;
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
	private net.runelite.client.ui.ClientToolbar clientToolbar;

	private TransportAuditPanel panel;
	private net.runelite.client.ui.NavigationButton navButton;

	// Keyed by packed template tile + id so re-spawns don't duplicate; cleared per scene load.
	private final Map<Long, Finding> findings = new ConcurrentHashMap<>();
	// Packed tiles that any transport row's origin or destination touches.
	private Set<Integer> transportTiles;
	// Packed tiles of EVERY door registry row — including doors the map places open (excluded
	// from ClosedDoors' pricing masks on purpose, but still registered and handled). The scene's
	// open-door variant also anchors its swung leaf on a neighbouring tile, so coverage checks a
	// small radius rather than the exact tile.
	private Set<Integer> doorTiles;
	// The cross-session collection file: every unmapped object ever seen, one row each, for
	// later comparison against the curated data (scripts/audit_diff.py in the tooling repo).
	private java.io.File auditFile;
	// Automatic origin/destination capture: armed when the operator clicks a traversal action on
	// a flagged object, resolved by watching the player's tile until they come to rest.
	private PendingCapture pending;
	private java.io.File capturesFile;
	// origin|dest|id triples already written, seeded from the captures file, so repeated
	// traversals (and each direction) record once.
	private final Set<String> capturedKeys = new HashSet<>();
	// Parsed captured edges {origin, dest, id} for per-finding state: a finding counts as
	// captured when an edge with its object id starts or ends near its tile.
	private final java.util.List<int[]> capturedEdges = new java.util.ArrayList<>();
	// Finding keys captured THIS session (vs. edges seeded from the file = earlier sessions).
	private final Set<Long> capturedThisSession = new HashSet<>();
	private String lastCaptureText;
	private int lastCaptureTick = -1;

	/** One in-flight traversal capture: click → stand in range → depart → come to rest. */
	private static final class PendingCapture
	{
		final Finding finding;
		final int armedTick;
		int originTile = WorldPointUtil.UNDEFINED; // last in-range tile before departure
		int departTick = -1;
		int lastTile = WorldPointUtil.UNDEFINED;
		int stableTicks;

		PendingCapture(Finding finding, int armedTick)
		{
			this.finding = finding;
			this.armedTick = armedTick;
		}
	}
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
		if (auditFile == null)
		{
			auditFile = new java.io.File(
				new java.io.File(net.runelite.client.RuneLite.RUNELITE_DIR, "gps-debug"),
				"transport-audit.tsv");
			seedLoggedFromFile();
		}
		if (capturesFile == null)
		{
			capturesFile = new java.io.File(auditFile.getParentFile(), "transport-captures.tsv");
			seedCapturedFromFile();
		}
		overlayManager.add(sceneOverlay);
		if (panel == null)
		{
			panel = new TransportAuditPanel(this);
			navButton = net.runelite.client.ui.NavigationButton.builder()
				.tooltip("GPS transport audit (dev)")
				.icon(navIcon())
				.priority(71)
				.panel(panel)
				.build();
		}
		clientToolbar.addNavigation(navButton);
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
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}
		findings.clear();
	}

	/** A 16px warning-triangle sidebar icon, drawn here so the dev plugin needs no resources. */
	private static java.awt.image.BufferedImage navIcon()
	{
		java.awt.image.BufferedImage image =
			new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = image.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
			java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(TransportAuditSceneOverlay.UNMAPPED);
		g.fillPolygon(new int[]{8, 15, 1}, new int[]{1, 14, 14}, 3);
		g.setColor(java.awt.Color.WHITE);
		g.fillRect(7, 5, 2, 5);
		g.fillRect(7, 11, 2, 2);
		g.dispose();
		return image;
	}

	/** The curation state coloring a finding everywhere (panel rows and scene outlines). */
	FindingState stateOf(Finding finding)
	{
		PendingCapture current = pending;
		if (current != null && current.finding == finding)
		{
			return FindingState.ARMED;
		}
		if (finding.door)
		{
			return FindingState.DOOR;
		}
		long key = ((long) finding.packedTemplateTile << 20) | finding.object.getId();
		if (capturedThisSession.contains(key))
		{
			return FindingState.CAPTURED_SESSION;
		}
		for (int[] edge : capturedEdges)
		{
			if (edge[2] == finding.object.getId()
				&& (WorldPointUtil.distanceBetween(edge[0], finding.packedTemplateTile) <= 4
				|| WorldPointUtil.distanceBetween(edge[1], finding.packedTemplateTile) <= 4))
			{
				return FindingState.CAPTURED_PRIOR;
			}
		}
		return FindingState.MISSING;
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
			// NB: an armed capture survives LOADING on purpose — dungeon entrances land in a new
			// scene, and the tile tracking uses template coordinates throughout.
		}
		if (GameState.LOGIN_SCREEN.equals(event.getGameState()))
		{
			pending = null;
		}
	}

	private static final String COPY_OPTION = "Copy GPS audit";

	/**
	 * Right-clicking a marked object offers "Copy GPS audit": the finding's dossier — tile,
	 * name, id, every menu action, and the ready-to-fill transports.tsv template — goes to the
	 * system clipboard (and the log, as a fallback).
	 */
	@Subscribe
	public void onMenuEntryAdded(net.runelite.api.events.MenuEntryAdded event)
	{
		if (findings.isEmpty())
		{
			return;
		}
		Finding match = null;
		for (Finding finding : findings.values())
		{
			if (finding.object.getId() == event.getIdentifier()
				&& sceneClose(finding.object, event.getActionParam0(), event.getActionParam1()))
			{
				match = finding;
				break;
			}
		}
		if (match == null)
		{
			return;
		}
		for (net.runelite.api.MenuEntry entry : client.getMenu().getMenuEntries())
		{
			if (COPY_OPTION.equals(entry.getOption()))
			{
				return; // one copy entry per menu is plenty, however many rows the object adds
			}
		}
		final Finding finding = match;
		client.getMenu().createMenuEntry(-1)
			.setOption(COPY_OPTION)
			.setTarget(finding.name)
			.setType(net.runelite.api.MenuAction.RUNELITE)
			.onClick(e -> copyDossier(finding));
	}

	private static boolean sceneClose(TileObject object, int sceneX, int sceneY)
	{
		net.runelite.api.coords.LocalPoint location = object.getLocalLocation();
		return location != null
			&& Math.max(Math.abs(location.getSceneX() - sceneX), Math.abs(location.getSceneY() - sceneY)) <= 2;
	}

	void copyDossier(Finding finding)
	{
		String text = finding.dossier();
		try
		{
			java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(new java.awt.datatransfer.StringSelection(text), null);
			log.info("[audit] copied to clipboard:\n{}", text);
		}
		catch (Exception e)
		{
			log.warn("[audit] clipboard unavailable — dossier:\n{}", text, e);
		}
	}

	/**
	 * Arms a capture when the operator clicks a traversal action on a flagged object; any other
	 * real click cancels (the player changed their mind), so a later stop can't be mistaken for
	 * a landing. Doors are skipped — they have no destination to record.
	 */
	@Subscribe
	public void onMenuOptionClicked(net.runelite.api.events.MenuOptionClicked event)
	{
		if (event.getMenuAction() == net.runelite.api.MenuAction.RUNELITE)
		{
			return; // our own "Copy GPS audit" click — not a movement intent
		}
		net.runelite.api.MenuEntry entry = event.getMenuEntry();
		for (Finding finding : findings.values())
		{
			if (!finding.door && finding.object.getId() == entry.getIdentifier()
				&& sceneClose(finding.object, entry.getParam0(), entry.getParam1())
				&& event.getMenuOption() != null && event.getMenuOption().equalsIgnoreCase(finding.action))
			{
				pending = new PendingCapture(finding, client.getTickCount());
				log.info("[audit] capture armed: {} — traverse it and come to rest", finding.describe());
				return;
			}
		}
		pending = null;
	}

	/**
	 * Drives an armed capture: while the player stands within interaction range (2 tiles, same
	 * plane) of the object, their tile is the origin candidate; the first tick outside that
	 * range starts the traversal; two stationary ticks end it. The rest position is the
	 * destination, and the tick span is the transport's duration.
	 */
	@Subscribe
	public void onGameTick(net.runelite.api.events.GameTick event)
	{
		if (client.getLocalPlayer() == null)
		{
			return;
		}
		pushPanelSnapshot();
		if (pending == null)
		{
			return;
		}
		int now = client.getTickCount();
		if (now - pending.armedTick > 100)
		{
			log.info("[audit] capture timed out: {}", pending.finding.describe());
			pending = null;
			return;
		}
		int tile = WorldPointUtil.fromLocalInstance(client, client.getLocalPlayer());
		// distanceBetween returns MAX_VALUE across planes, so range implies same plane.
		boolean inRange = WorldPointUtil.distanceBetween(tile, pending.finding.packedTemplateTile) <= 2;
		if (pending.departTick < 0)
		{
			if (inRange)
			{
				pending.originTile = tile;
			}
			else if (pending.originTile != WorldPointUtil.UNDEFINED && tile != pending.originTile)
			{
				pending.departTick = now; // left the object's side — traversal has begun
			}
		}
		if (pending.departTick >= 0)
		{
			if (tile == pending.lastTile)
			{
				if (++pending.stableTicks >= 2)
				{
					completeCapture(tile, now);
				}
			}
			else
			{
				pending.stableTicks = 0;
			}
		}
		pending.lastTile = tile;
	}

	private void completeCapture(int destTile, int now)
	{
		PendingCapture capture = pending;
		pending = null;
		if (destTile == capture.originTile || capture.originTile == WorldPointUtil.UNDEFINED)
		{
			return;
		}
		int arrivalTick = now - capture.stableTicks + 1;
		int duration = Math.max(1, arrivalTick - capture.departTick);
		String row = tileText(capture.originTile) + "\t" + tileText(destTile) + "\t"
			+ capture.finding.action + " " + capture.finding.name + " " + capture.finding.object.getId()
			+ "\t\t\t\t\t\t" + duration + "\t";
		String key = tileText(capture.originTile) + "|" + tileText(destTile) + "|"
			+ capture.finding.object.getId();
		lastCaptureText = capture.finding.name + " " + tileText(capture.originTile)
			+ " -> " + tileText(destTile) + " (" + duration + "t)";
		lastCaptureTick = now;
		capturedThisSession.add(((long) capture.finding.packedTemplateTile << 20)
			| capture.finding.object.getId());
		if (!capturedKeys.add(key))
		{
			log.info("[audit] captured (already recorded): {}", lastCaptureText);
			return;
		}
		capturedEdges.add(new int[]{capture.originTile, destTile, capture.finding.object.getId()});
		log.info("[audit] captured — review then paste into transports.tsv:\n{}", row);
		try
		{
			boolean fresh = !capturesFile.exists();
			try (java.io.FileWriter writer = new java.io.FileWriter(capturesFile, true))
			{
				if (fresh)
				{
					writer.write("# Auto-captured by the GPS dev transport audit — REVIEW before "
						+ "pasting into transports.tsv (origin/dest observed live; requirements "
						+ "and one-way-ness are yours to verify)\n");
					writer.write("# Origin\tDestination\tmenuOption menuTarget objectID\tSkills\t"
						+ "Items\tQuests\tVarbits\tVarPlayers\tDuration\tDisplay info\n");
				}
				writer.write(row + "\n");
			}
		}
		catch (Exception e)
		{
			log.warn("[audit] could not append to {}", capturesFile, e);
		}
	}

	private static String tileText(int packedTile)
	{
		return WorldPointUtil.unpackWorldX(packedTile) + " " + WorldPointUtil.unpackWorldY(packedTile)
			+ " " + WorldPointUtil.unpackWorldPlane(packedTile);
	}

	/** Parses "x y plane" back into a packed tile, or UNDEFINED. */
	private static int parseTile(String text)
	{
		String[] parts = text.trim().split(" ");
		if (parts.length < 3)
		{
			return WorldPointUtil.UNDEFINED;
		}
		try
		{
			return WorldPointUtil.packWorldPoint(Integer.parseInt(parts[0]),
				Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
		}
		catch (NumberFormatException e)
		{
			return WorldPointUtil.UNDEFINED;
		}
	}

	/** Seeds the dedupe set so repeated traversals across sessions don't duplicate rows. */
	private void seedCapturedFromFile()
	{
		if (!capturesFile.exists())
		{
			return;
		}
		try (java.util.Scanner scanner = new java.util.Scanner(capturesFile, "UTF-8"))
		{
			while (scanner.hasNextLine())
			{
				String line = scanner.nextLine();
				if (line.startsWith("#"))
				{
					continue;
				}
				String[] fields = line.split("\t");
				if (fields.length >= 3)
				{
					String[] menu = fields[2].split(" ");
					capturedKeys.add(fields[0] + "|" + fields[1] + "|" + menu[menu.length - 1]);
					int origin = parseTile(fields[0]);
					int dest = parseTile(fields[1]);
					try
					{
						if (origin != WorldPointUtil.UNDEFINED && dest != WorldPointUtil.UNDEFINED)
						{
							capturedEdges.add(new int[]{origin, dest,
								Integer.parseInt(menu[menu.length - 1])});
						}
					}
					catch (NumberFormatException ignored)
					{
						// malformed row — dedupe key still recorded above
					}
				}
			}
			log.info("[audit] captures file: {} previously captured edges ({})",
				capturedKeys.size(), capturesFile);
		}
		catch (Exception e)
		{
			log.warn("[audit] could not read {}", capturesFile, e);
		}
	}

	/** Client thread. Builds an immutable snapshot for the sidebar panel and hands it to the EDT. */
	private void pushPanelSnapshot()
	{
		if (panel == null)
		{
			return;
		}
		int playerTile = WorldPointUtil.fromLocalInstance(client, client.getLocalPlayer());
		java.util.List<Row> rows = new java.util.ArrayList<>();
		for (Finding finding : findings.values())
		{
			rows.add(new Row(finding, stateOf(finding),
				WorldPointUtil.distanceBetween(playerTile, finding.packedTemplateTile)));
		}
		rows.sort(java.util.Comparator
			.comparingInt((Row row) -> statePriority(row.state))
			.thenComparingInt(row -> row.distance));

		String captureLine = null;
		java.awt.Color captureColor = null;
		if (pending != null)
		{
			captureLine = "Capture armed: " + pending.finding.name + " — traverse it now";
			captureColor = java.awt.Color.YELLOW;
		}
		else if (lastCaptureText != null && client.getTickCount() - lastCaptureTick < 25)
		{
			captureLine = "Captured: " + lastCaptureText;
			captureColor = TransportAuditPanel.stateColor(FindingState.CAPTURED_SESSION);
		}
		final String line = captureLine;
		final java.awt.Color color = captureColor;
		javax.swing.SwingUtilities.invokeLater(() -> panel.update(rows, line, color));
	}

	private static int statePriority(FindingState state)
	{
		switch (state)
		{
			case ARMED:
				return 0;
			case MISSING:
				return 1;
			case DOOR:
				return 2;
			case CAPTURED_SESSION:
				return 3;
			default:
				return 4;
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

		String[] actions = new String[0];
		if (composition.getActions() != null)
		{
			actions = java.util.Arrays.stream(composition.getActions())
				.filter(java.util.Objects::nonNull).toArray(String[]::new);
		}
		long key = ((long) templateTile << 20) | object.getId();
		Finding finding = new Finding(object, templateTile, composition.getName(), action, actions, door);
		boolean firstEver = findings.put(key, finding) == null && logged.add(key);
		if (firstEver)
		{
			record(finding);
		}
		if (firstEver && !door)
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
	 * Reads the collection file so objects recorded in earlier sessions aren't appended again —
	 * the file grows only by genuinely new (id, tile) pairs.
	 */
	private void seedLoggedFromFile()
	{
		if (!auditFile.exists())
		{
			return;
		}
		try (java.util.Scanner scanner = new java.util.Scanner(auditFile, "UTF-8"))
		{
			while (scanner.hasNextLine())
			{
				// firstSeen kind id name action allActions x y plane
				String[] fields = scanner.nextLine().split("\t");
				if (fields.length < 9)
				{
					continue;
				}
				try
				{
					int packed = WorldPointUtil.packWorldPoint(Integer.parseInt(fields[6]),
						Integer.parseInt(fields[7]), Integer.parseInt(fields[8]));
					logged.add(((long) packed << 20) | Integer.parseInt(fields[2]));
				}
				catch (NumberFormatException ignored)
				{
					// header
				}
			}
			log.info("[audit] collection file: {} previously recorded objects ({})",
				logged.size(), auditFile);
		}
		catch (Exception e)
		{
			log.warn("[audit] could not read {}", auditFile, e);
		}
	}

	/** Appends one collection-file row per newly discovered unmapped object. */
	private void record(Finding finding)
	{
		try
		{
			java.io.File dir = auditFile.getParentFile();
			boolean fresh = !auditFile.exists();
			if (fresh && !dir.exists() && !dir.mkdirs())
			{
				log.warn("[audit] could not create {}", dir);
				return;
			}
			try (java.io.FileWriter writer = new java.io.FileWriter(auditFile, true))
			{
				if (fresh)
				{
					writer.write("firstSeen\tkind\tid\tname\taction\tallActions\tx\ty\tplane\n");
				}
				writer.write(java.time.LocalDate.now() + "\t"
					+ (finding.door ? "door" : "transport") + "\t"
					+ finding.object.getId() + "\t"
					+ finding.name + "\t"
					+ finding.action + "\t"
					+ String.join("|", finding.actions) + "\t"
					+ WorldPointUtil.unpackWorldX(finding.packedTemplateTile) + "\t"
					+ WorldPointUtil.unpackWorldY(finding.packedTemplateTile) + "\t"
					+ WorldPointUtil.unpackWorldPlane(finding.packedTemplateTile) + "\n");
			}
		}
		catch (Exception e)
		{
			log.warn("[audit] could not append to {}", auditFile, e);
		}
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
