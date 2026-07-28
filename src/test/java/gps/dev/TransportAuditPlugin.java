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

	// Menu-action prefixes that mean "using this object moves the player somewhere". Derived
	// from a full cache scan of object action verbs (ActionVerbScanTest in the tooling repo) —
	// notable inclusions: plain "pass" (Pass Sticks), "leave"/"escape" (arena and dungeon
	// exits), "pull"/"push" (levers, hidden walls), "dock"/"navigate" (Sailing). Deliberately
	// excluded as too generic for an audit that flags red: open/close (door path handles walls),
	// use, operate, touch, activate, search, dig.
	private static final String[] TRAVERSAL_VERBS = {
		"climb", "cross", "enter", "exit", "leave", "escape", "descend", "ascend", "jump",
		"swing", "squeeze", "crawl", "balance", "vault", "leap", "scale", "grapple", "board",
		"travel", "ride", "pass", "pull", "push", "step", "dock", "navigate",
		"walk-", "go-", "teleport",
		// "activate" is noisy (~60 objects) but a REAL transport hid behind it: the Mount
		// Karuulm elevator, whose absence made the whole dungeon unreachable (the Kingdom
		// Divided field report). Ignorable noise beats an invisible island.
		"activate",
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
		// Transport rows nearby cover only ONE direction — traversing the other way while the
		// audit is armed captures the missing reverse.
		final boolean oneWayData;
		// The multiloc controlling varbit (-1 when none): quest doors/caves swap impostors on
		// the quest's progress varbit, so this is a strong gating hint for the dossier.
		final int gatingVarbit;
		// "Standing at it" distance from the object's CENTER tile: 2 for single-tile objects,
		// plus the footprint half-span for large ones (a 5x7 object's edge is 3 tiles out).
		final int interactRadius;
		// Exact post-rotation footprint in scene tiles (1x1 when unknown): a 4-wide lava gap is
		// jumpable from any of its 4 lanes, so one capture can derive the sibling lanes.
		final int footprintX;
		final int footprintY;

		Finding(TileObject object, int packedTemplateTile, String name, String action,
			String[] actions, boolean door, int gatingVarbit, boolean oneWayData)
		{
			this.oneWayData = oneWayData;
			this.gatingVarbit = gatingVarbit;
			this.object = object;
			this.packedTemplateTile = packedTemplateTile;
			this.name = name;
			this.action = action;
			this.actions = actions;
			this.door = door;
			int halfSpan = 0;
			int spanX = 1;
			int spanY = 1;
			if (object instanceof net.runelite.api.GameObject)
			{
				net.runelite.api.GameObject gameObject = (net.runelite.api.GameObject) object;
				net.runelite.api.Point min = gameObject.getSceneMinLocation();
				net.runelite.api.Point max = gameObject.getSceneMaxLocation();
				net.runelite.api.coords.LocalPoint center = object.getLocalLocation();
				if (min != null && max != null)
				{
					spanX = max.getX() - min.getX() + 1;
					spanY = max.getY() - min.getY() + 1;
				}
				if (min != null && center != null)
				{
					halfSpan = Math.max(0, Math.max(center.getSceneX() - min.getX(),
						center.getSceneY() - min.getY()));
				}
			}
			this.interactRadius = 2 + halfSpan;
			this.footprintX = spanX;
			this.footprintY = spanY;
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
			return dossierText(name, object.getId(), packedTemplateTile, action, actions, door,
				gatingVarbit, footprintX, footprintY);
		}
	}

	/** The clipboard dossier, buildable from plain fields (live findings and recorded entries). */
	static String dossierText(String name, int id, int packedTile, String action,
		String[] actions, boolean door, int gatingVarbit, int footprintX, int footprintY)
	{
		int x = WorldPointUtil.unpackWorldX(packedTile);
		int y = WorldPointUtil.unpackWorldY(packedTile);
		int plane = WorldPointUtil.unpackWorldPlane(packedTile);
		StringBuilder sb = new StringBuilder();
		sb.append(name).append(" id=").append(id)
			.append(" tile=").append(x).append(',').append(y).append(',').append(plane).append('\n');
		sb.append("actions: ").append(String.join(", ", actions)).append('\n');
		if (footprintX > 1 || footprintY > 1)
		{
			sb.append("footprint ").append(footprintX).append('x').append(footprintY)
				.append(" — capture ONE lane straight across; sibling lanes are derived")
				.append(" automatically (~geometry)\n");
		}
		if (door)
		{
			sb.append("door missing from doors.tsv — re-run doorDump in shortest-path-tooling");
		}
		else
		{
			sb.append("transports.tsv template — fill DEST and TICKS after traversing; ORIGIN is ")
				.append("the object's tile, correct it to the tile you STAND on. TICKS = full ")
				.append("animation, not tile arrival (auto-capture measures this for you):\n");
			sb.append(x).append(' ').append(y).append(' ').append(plane).append('\t')
				.append("DESTX DESTY PLANE").append('\t')
				.append(action).append(' ').append(name).append(' ').append(id)
				.append("\t\t\t\t\t\tTICKS\t");
		}
		return sb.toString();
	}

	/**
	 * Sibling-lane rows for a capture across a multi-tile object: a 4-wide lava gap is jumpable
	 * from any of its 4 lanes, but a traversal only records the lane actually used. The capture
	 * is translated sideways to every other lane of the footprint, keeping direction, distance
	 * and per-endpoint plane. Returns {origin, dest} packed pairs, never the traversed lane.
	 * Skipped entirely when the travel axis is ambiguous (perfect diagonal) or the origin was
	 * not lined up with a footprint lane (an angled walk-in approach).
	 */
	static java.util.List<int[]> expandCaptureLanes(int origin, int dest,
		int laneMinWorld, int laneMaxWorld, java.util.function.IntPredicate blockedTile)
	{
		java.util.List<int[]> lanes = new java.util.ArrayList<>();
		int dx = Math.abs(WorldPointUtil.unpackWorldX(dest) - WorldPointUtil.unpackWorldX(origin));
		int dy = Math.abs(WorldPointUtil.unpackWorldY(dest) - WorldPointUtil.unpackWorldY(origin));
		if (dx == dy || laneMinWorld >= laneMaxWorld || laneMaxWorld - laneMinWorld > 8)
		{
			return lanes;
		}
		// Travel along x means the parallel lanes differ in y, and vice versa.
		boolean lanesAlongY = dx > dy;
		int originLane = lanesAlongY
			? WorldPointUtil.unpackWorldY(origin) : WorldPointUtil.unpackWorldX(origin);
		if (originLane < laneMinWorld || originLane > laneMaxWorld)
		{
			return lanes;
		}
		for (int lane = laneMinWorld; lane <= laneMaxWorld; lane++)
		{
			int delta = lane - originLane;
			if (delta == 0)
			{
				continue;
			}
			int newOrigin = translateLane(origin, lanesAlongY, delta);
			int newDest = translateLane(dest, lanesAlongY, delta);
			if (blockedTile.test(newOrigin) || blockedTile.test(newDest))
			{
				continue;
			}
			lanes.add(new int[]{newOrigin, newDest});
		}
		return lanes;
	}

	private static int translateLane(int packed, boolean lanesAlongY, int delta)
	{
		return WorldPointUtil.packWorldPoint(
			WorldPointUtil.unpackWorldX(packed) + (lanesAlongY ? 0 : delta),
			WorldPointUtil.unpackWorldY(packed) + (lanesAlongY ? delta : 0),
			WorldPointUtil.unpackWorldPlane(packed));
	}

	/** Curation state of one finding, driving the panel row and scene outline colors. */
	enum FindingState
	{
		MISSING,
		ARMED,
		DOOR,
		/** A Meta-tagged transport row (machine-derived values) — traverse/use it to confirm. */
		CONFIRM,
		/** Captured, but no reverse edge exists anywhere — walk the return trip to complete it. */
		CAPTURED_ONE_WAY,
		/** The DATA covers one direction only (promoted rows without a reverse) — same cure. */
		DATA_ONE_WAY,
		/** A curated transport row shown by the known-data browser (debugging aid, cyan). */
		KNOWN,
		CAPTURED_SESSION,
		CAPTURED_PRIOR,
		/** The curated data now covers it (row added since it was recorded) — prunable. */
		RESOLVED
	}

	/** A curated transport row, browsable for debugging: where the DATA says you can go. */
	static final class KnownEntry
	{
		final int origin;
		final int destination;
		final String label;
		final int duration;

		KnownEntry(int origin, int destination, String label, int duration)
		{
			this.origin = origin;
			this.destination = destination;
			this.label = label;
			this.duration = duration;
		}
	}

	/**
	 * A traversal of a KNOWN transport being timed: armed by clicking a traversal action while
	 * standing by a curated row's origin. Where the unmapped-capture flow discovers new rows,
	 * this enriches existing ones — measured ticks accumulate in duration-samples.tsv and
	 * scripts/apply_duration_samples.py folds the modes back into the data (the Trollheim
	 * rocks shipped at a default 1 tick for months because nobody ever re-measured them).
	 */
	private static final class DurationWatch
	{
		final java.util.List<KnownEntry> candidates;
		final int clickTick;
		final int tileAtClick;
		boolean wasNearOrigin;
		int departTick = -1;
		int lastTile = WorldPointUtil.UNDEFINED;
		int stableTicks;

		DurationWatch(java.util.List<KnownEntry> candidates, int clickTick, int tileAtClick)
		{
			this.candidates = candidates;
			this.clickTick = clickTick;
			this.tileAtClick = tileAtClick;
		}
	}

	/**
	 * One immutable panel row — plain fields only, so rows can come from live scene findings or
	 * from collection-file entries recorded in other areas/sessions.
	 */
	static final class Row
	{
		final String name;
		final String action;
		final int id;
		final int packedTile;
		final FindingState state;
		final int distance;
		final boolean live;
		final String dossier;

		Row(String name, String action, int id, int packedTile, FindingState state,
			int distance, boolean live, String dossier)
		{
			this.name = name;
			this.action = action;
			this.id = id;
			this.packedTile = packedTile;
			this.state = state;
			this.distance = distance;
			this.live = live;
			this.dossier = dossier;
		}
	}

	/** One collection-file row: an unmapped object recorded in this or an earlier session. */
	private static final class RecordedEntry
	{
		final boolean door;
		final int id;
		final String name;
		final String action;
		final String[] actions;
		final int packedTile;
		final boolean coverageResolved; // curated data now covers it (checked once at load)

		RecordedEntry(boolean door, int id, String name, String action, String[] actions,
			int packedTile, boolean coverageResolved)
		{
			this.door = door;
			this.id = id;
			this.name = name;
			this.action = action;
			this.actions = actions;
			this.packedTile = packedTile;
			this.coverageResolved = coverageResolved;
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
	// Packed tiles that transport rows LEAVE from / ARRIVE at, kept separate: an object with
	// only one direction nearby is half-mapped (the Karuulm rocks were southbound-only and the
	// old combined set silenced the flag while routing north was impossible).
	private Set<Integer> transportOriginTiles;
	private Set<Integer> transportDestTiles;
	// The known-data browser: every curated transport row, listable and highlightable in the
	// world for debugging ("what does the data think is here?"). Toggled from the panel.
	private final java.util.List<KnownEntry> knownEntries = new java.util.ArrayList<>();
	volatile boolean showKnown = false;
	// In-world collision rendering (the old shortest-path debug view, upgraded): static map
	// verdicts AND live-vs-static disagreements, rebuilt per tick around the player.
	volatile boolean showCollision = false;
	// RuneLite's own model-outline renderer traced around every boat part (proving ground for
	// the mask: get the outline right on its own before clipping anything to it).
	volatile boolean showBoatOutline = false;
	// The wake + predicted-course ribbons (the navigation view).
	volatile boolean showBoatWake = false;
	// The deck footprint, perimeter, nose, hull box and the red true tile (the diagnostic view).
	volatile boolean showBoatTiles = false;
	// The text dump of the position resolution chain, one line per link — separate toggle:
	// it's occasional diagnostics, not something to sail with.
	volatile boolean showBoatText = false;
	/**
	 * The boat's WAKE: recent hull position + orientation samples, drawn as a swept-path curve.
	 * The visual counterpart of the speed sampler — the real turning radius and the area the
	 * hull actually sweeps, which is what the water map's clearance has to respect.
	 *
	 * Samples are TOP-LEVEL LOCAL coordinates ({localX, localY, orientation, tick}), so they
	 * are scene-relative: cleared on scene load and whenever the boat changes.
	 */
	private static final int WAKE_SAMPLES = 80;
	/**
	 * How far back the wake is KEPT, in tiles — trimmed by distance so speed can't stretch it.
	 * Deliberately longer than the rendered length: the overlay cuts the tail mid-segment for a
	 * smooth recede, which needs a sample beyond the cut to interpolate towards.
	 */
	private static final int WAKE_MAX_TILES = 20;
	private final java.util.ArrayDeque<int[]> boatWake = new java.util.ArrayDeque<>();
	private int wakeViewId = -1;

	java.util.List<int[]> boatWake()
	{
		return new java.util.ArrayList<>(boatWake);
	}

	/** Measured hull speed in TILES PER TICK, straight off the wake — 0 when parked. */
	volatile double boatSpeedTiles = 0;
	private volatile java.util.List<int[]> collisionCells = java.util.List.of();
	static final int COLLISION_VIEW_RADIUS = 12;
	// tileState values in collisionCells rows {packedTile, tileState, staticEdgeMask, mismatchEdgeMask}
	static final int COLLISION_BOTH_BLOCKED = 1;
	static final int COLLISION_STATIC_ONLY = 2; // phantom: shipped map blocks, live game doesn't
	static final int COLLISION_LIVE_ONLY = 3;   // missing: live game blocks, shipped map doesn't
	static final int COLLISION_WATER = 4;       // blocked-in-both AND the tile-settings water bit
	// Panel search text (lowercase). Filtering is panel-side except for known-data rows, whose
	// candidate set is pre-trimmed here: nearby-only while browsing, name-matched when searching.
	volatile String listFilter = "";
	private volatile int knownHighlightOrigin = WorldPointUtil.UNDEFINED;
	private volatile int knownHighlightDest = WorldPointUtil.UNDEFINED;
	@Inject
	private net.runelite.client.plugins.PluginManager pluginManager;
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
	private DurationWatch durationWatch;
	private java.io.File durationSamplesFile;
	private java.io.File capturesFile;
	// origin|dest|id triples already written, seeded from the captures file, so repeated
	// traversals (and each direction) record once.
	private final Set<String> capturedKeys = new HashSet<>();
	// Parsed captured edges {origin, dest, id} for per-finding state: a finding counts as
	// captured when an edge with its object id starts or ends near its tile.
	final java.util.List<int[]> capturedEdges = new java.util.ArrayList<>();
	// edgePaired[i]: capturedEdges[i] has a reverse edge SOMEWHERE (matched by coordinates, not
	// object id — a cave entrance's reverse is the separate cave-EXIT object's edge). Recomputed
	// whenever capturedEdges changes.
	private boolean[] edgePaired = new boolean[0];
	// Finding keys captured THIS session (vs. edges seeded from the file = earlier sessions).
	final Set<Long> capturedThisSession = new HashSet<>();
	// The parsed collection file: recorded unmapped objects from all sessions, shown in the
	// panel even when their area isn't loaded.
	private final java.util.List<RecordedEntry> recorded = new java.util.ArrayList<>();
	// Operator-declared false positives (shift right-click -> "Not a transport"): (tile,id)
	// keys never flagged again, persisted to transport-ignore.tsv.
	private final Set<Long> ignoredKeys = ConcurrentHashMap.newKeySet();
	private java.io.File ignoreFile;
	// Operator-declared genuine one-ways ("no reverse exists"): edge keys treated as paired so
	// the row completes instead of asking for a return trip forever. transport-oneway.tsv.
	final Set<String> noReverseKeys = ConcurrentHashMap.newKeySet();
	private java.io.File oneWayFile;
	// Meta-tagged transport rows (the TSVs' Meta column: ~estimated / ?unconfirmed values) —
	// shown as a "needs confirmation" group so field sessions can verify them.
	private java.util.List<MetaEdges.Entry> metaEdges = java.util.List.of();
	// Manual transport builder (shift right-click sets the pieces; the panel edits and saves).
	private volatile int builderOrigin = WorldPointUtil.UNDEFINED;
	private volatile int builderDest = WorldPointUtil.UNDEFINED;
	private volatile String builderMenu = "";
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
		if (transportOriginTiles == null)
		{
			transportOriginTiles = new HashSet<>();
			transportDestTiles = new HashSet<>();
			HashMap<Integer, Set<Transport>> all = TransportLoader.loadAllFromResources();
			for (Map.Entry<Integer, Set<Transport>> entry : all.entrySet())
			{
				transportOriginTiles.add(entry.getKey());
				for (Transport transport : entry.getValue())
				{
					if (transport.getOrigin() != WorldPointUtil.UNDEFINED)
					{
						transportOriginTiles.add(transport.getOrigin());
					}
					if (transport.getDestination() != WorldPointUtil.UNDEFINED)
					{
						transportDestTiles.add(transport.getDestination());
					}
					String label = transport.getDisplayInfo() != null && !transport.getDisplayInfo().isEmpty()
						? transport.getDisplayInfo()
						: (transport.getObjectInfo() != null ? transport.getObjectInfo() : "(transport)");
					knownEntries.add(new KnownEntry(
						transport.getOrigin(), transport.getDestination(), label,
						transport.getDuration()));
				}
			}
			log.info("[audit] transport coverage: {} origin tiles, {} dest tiles",
				transportOriginTiles.size(), transportDestTiles.size());
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
		if (durationSamplesFile == null)
		{
			durationSamplesFile = new java.io.File(auditFile.getParentFile(), "duration-samples.tsv");
		}
		if (capturesFile == null)
		{
			capturesFile = new java.io.File(auditFile.getParentFile(), "transport-captures.tsv");
			seedCapturedFromFile();
		}
		if (ignoreFile == null)
		{
			ignoreFile = new java.io.File(auditFile.getParentFile(), "transport-ignore.tsv");
			seedIgnoredFromFile();
		}
		if (metaEdges.isEmpty())
		{
			metaEdges = MetaEdges.load();
			log.info("[audit] meta-tagged transport rows awaiting confirmation: {}", metaEdges.size());
		}
		if (oneWayFile == null)
		{
			oneWayFile = new java.io.File(auditFile.getParentFile(), "transport-oneway.tsv");
			try (java.util.Scanner scanner = oneWayFile.exists()
				? new java.util.Scanner(oneWayFile, "UTF-8") : null)
			{
				while (scanner != null && scanner.hasNextLine())
				{
					String line = scanner.nextLine().trim();
					if (!line.isEmpty() && !line.startsWith("#"))
					{
						noReverseKeys.add(line);
					}
				}
			}
			catch (Exception e)
			{
				log.warn("[audit] could not read {}", oneWayFile, e);
			}
			recomputeEdgePairs();
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

	/**
	 * Recomputes reverse-pairing for every captured edge: edge A->B is paired when any edge's
	 * origin is within 5 tiles of B and destination within 5 tiles of A (plane-aware distances).
	 * Coordinate-based on purpose: return trips often go through a DIFFERENT object.
	 */
	private static String edgeKey(int[] edge)
	{
		return tileText(edge[0]) + "|" + tileText(edge[1]) + "|" + edge[2];
	}

	void recomputeEdgePairs()
	{
		boolean[] paired = new boolean[capturedEdges.size()];
		for (int i = 0; i < capturedEdges.size(); i++)
		{
			int[] edge = capturedEdges.get(i);
			if (noReverseKeys.contains(edgeKey(edge)))
			{
				paired[i] = true; // operator declared: no reverse exists, edge is complete as-is
				continue;
			}
			for (int[] other : capturedEdges)
			{
				// A reverse must be a DIFFERENT edge travelling the OPPOSITE way: without the
				// direction check, a same-plane short hop (rockslide: origin and dest within the
				// 5-tile tolerance) paired with ITSELF or with a same-direction duplicate.
				if (other != edge
					&& opposingDirections(edge, other)
					&& WorldPointUtil.distanceBetween(other[0], edge[1]) <= 5
					&& WorldPointUtil.distanceBetween(other[1], edge[0]) <= 5)
				{
					paired[i] = true;
					break;
				}
			}
		}
		edgePaired = paired;
	}

	/** Whether two edges travel opposite ways (plane movement opposed; else 2D headings oppose). */
	private static boolean opposingDirections(int[] edge, int[] other)
	{
		int edgePlanes = WorldPointUtil.unpackWorldPlane(edge[1]) - WorldPointUtil.unpackWorldPlane(edge[0]);
		int otherPlanes = WorldPointUtil.unpackWorldPlane(other[1]) - WorldPointUtil.unpackWorldPlane(other[0]);
		if (edgePlanes != -otherPlanes)
		{
			return false;
		}
		if (edgePlanes != 0)
		{
			return true; // up vs down is opposition enough
		}
		int edgeDx = WorldPointUtil.unpackWorldX(edge[1]) - WorldPointUtil.unpackWorldX(edge[0]);
		int edgeDy = WorldPointUtil.unpackWorldY(edge[1]) - WorldPointUtil.unpackWorldY(edge[0]);
		int otherDx = WorldPointUtil.unpackWorldX(other[1]) - WorldPointUtil.unpackWorldX(other[0]);
		int otherDy = WorldPointUtil.unpackWorldY(other[1]) - WorldPointUtil.unpackWorldY(other[0]);
		return edgeDx * otherDx + edgeDy * otherDy < 0;
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
		if (finding.oneWayData)
		{
			return FindingState.DATA_ONE_WAY;
		}
		long key = ((long) finding.packedTemplateTile << 20) | finding.object.getId();
		return capturedState(finding.object.getId(), finding.packedTemplateTile,
			capturedThisSession.contains(key));
	}

	/**
	 * Captured/missing state for an object: fully paired edges keep the session/prior color;
	 * any unpaired edge demotes to CAPTURED_ONE_WAY ("walk the reverse"). No edges = MISSING.
	 */
	FindingState capturedState(int objectId, int packedTile, boolean thisSession)
	{
		boolean any = false;
		boolean allPaired = true;
		for (int i = 0; i < capturedEdges.size(); i++)
		{
			int[] edge = capturedEdges.get(i);
			if (edge[2] == objectId
				&& (WorldPointUtil.distanceBetween(edge[0], packedTile) <= 4
				|| WorldPointUtil.distanceBetween(edge[1], packedTile) <= 4))
			{
				any = true;
				if (i >= edgePaired.length || !edgePaired[i])
				{
					allPaired = false;
				}
			}
		}
		if (!any)
		{
			return thisSession ? FindingState.CAPTURED_SESSION : FindingState.MISSING;
		}
		if (!allPaired)
		{
			return FindingState.CAPTURED_ONE_WAY;
		}
		return thisSession ? FindingState.CAPTURED_SESSION : FindingState.CAPTURED_PRIOR;
	}

	java.util.List<KnownEntry> knownEntries()
	{
		return knownEntries;
	}

	int knownHighlightOrigin()
	{
		return knownHighlightOrigin;
	}

	int knownHighlightDest()
	{
		return knownHighlightDest;
	}

	/** Panel: a KNOWN row was selected — spotlight its endpoints in the world. */
	void highlightKnown(int origin, int destination)
	{
		knownHighlightOrigin = origin;
		knownHighlightDest = destination;
	}

	/** Panel selection: spotlight the known entry anchored at this tile (first match wins). */
	void spotlightKnownAt(int packedTile)
	{
		for (KnownEntry entry : knownEntries)
		{
			int anchor = entry.origin != WorldPointUtil.UNDEFINED ? entry.origin : entry.destination;
			if (anchor == packedTile)
			{
				highlightKnown(entry.origin, entry.destination);
				return;
			}
		}
	}

	/** Panel "Go": route GPS to a tile (any row — finding, meta, or known). */
	void routeTo(int packedTile)
	{
		for (net.runelite.client.plugins.Plugin other : pluginManager.getPlugins())
		{
			if (other instanceof gps.ShortestPathPlugin)
			{
				final gps.ShortestPathPlugin gpsPlugin = (gps.ShortestPathPlugin) other;
				clientThread.invokeLater(() -> gpsPlugin.setDestination(packedTile, "audit panel"));
				return;
			}
		}
		log.warn("[audit] GPS plugin not found — cannot route");
	}

	java.util.List<MetaEdges.Entry> metaEdges()
	{
		return metaEdges;
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
			boatWake.clear(); // local coords are scene-relative — a rebuild invalidates them
			// NB: an armed capture survives LOADING on purpose — dungeon entrances land in a new
			// scene, and the tile tracking uses template coordinates throughout.
		}
		if (GameState.LOGIN_SCREEN.equals(event.getGameState()))
		{
			pending = null;
		}
	}

	private static final String COPY_OPTION = "Copy GPS audit";
	private static final String IGNORE_OPTION = "Audit: not a transport";
	private static final String BUILDER_ORIGIN_OPTION = "Builder: set origin";
	private static final String BUILDER_DEST_OPTION = "Builder: set destination";
	private static final String BUILDER_OBJECT_OPTION = "Builder: use object";
	private static final String BUILDER_ITEM_OPTION = "Builder: add item req";

	/**
	 * Menu additions. Plain right-click on a marked object: "Copy GPS audit". With SHIFT held:
	 * "Audit: not a transport" on marked objects (persists a false-positive), and the transport
	 * builder's origin/destination/object pickers on any object or walkable tile.
	 */
	@Subscribe
	public void onMenuEntryAdded(net.runelite.api.events.MenuEntryAdded event)
	{
		boolean shift = client.isKeyPressed(net.runelite.api.KeyCode.KC_SHIFT);

		if (shift && "Walk here".equals(event.getOption()) && !hasOption(BUILDER_ORIGIN_OPTION))
		{
			net.runelite.api.Tile selected = client.getTopLevelWorldView().getSelectedSceneTile();
			if (selected != null)
			{
				int tile = templateTileAt(selected.getLocalLocation());
				addBuilderEntry(BUILDER_ORIGIN_OPTION, tileText(tile), () -> builderOrigin = tile);
				addBuilderEntry(BUILDER_DEST_OPTION, tileText(tile), () -> builderDest = tile);
			}
			return;
		}

		Finding match = null;
		if (!findings.isEmpty())
		{
			for (Finding finding : findings.values())
			{
				if (finding.object.getId() == event.getIdentifier()
					&& sceneClose(finding.object, event.getActionParam0(), event.getActionParam1()))
				{
					match = finding;
					break;
				}
			}
		}
		if (match != null && !hasOption(COPY_OPTION))
		{
			final Finding finding = match;
			client.getMenu().createMenuEntry(-1)
				.setOption(COPY_OPTION)
				.setTarget(finding.name)
				.setType(net.runelite.api.MenuAction.RUNELITE)
				.onClick(e -> copyDossier(finding));
			if (shift)
			{
				client.getMenu().createMenuEntry(-1)
					.setOption(IGNORE_OPTION)
					.setTarget(finding.name)
					.setType(net.runelite.api.MenuAction.RUNELITE)
					.onClick(e -> ignoreFinding(finding));
			}
		}

		// Builder item requirement: shift on an inventory item appends "id=1" to the Items field
		// (the transports.tsv format — & joins ANDs, | joins ORs; quantity editable in the panel).
		if (shift && event.getMenuEntry().getItemId() > 0 && !hasOption(BUILDER_ITEM_OPTION))
		{
			final int itemId = event.getMenuEntry().getItemId();
			net.runelite.api.ItemComposition item = client.getItemDefinition(itemId);
			final String itemName = item != null ? item.getName() : String.valueOf(itemId);
			client.getMenu().createMenuEntry(-1)
				.setOption(BUILDER_ITEM_OPTION)
				.setTarget(itemName)
				.setType(net.runelite.api.MenuAction.RUNELITE)
				.onClick(e -> {
					log.info("[audit] builder item requirement: {} ({})", itemName, itemId);
					javax.swing.SwingUtilities.invokeLater(() ->
						panel.appendBuilderItem(itemId, itemName));
				});
		}

		// Builder pickers on ANY object (not just findings): origin/dest at the object's tile,
		// plus "use object" to fill the row's menu column from its definition.
		if (shift && isObjectAction(event.getMenuEntry().getType()) && !hasOption(BUILDER_OBJECT_OPTION))
		{
			final int objectId = event.getIdentifier();
			net.runelite.api.coords.LocalPoint location = net.runelite.api.coords.LocalPoint.fromScene(
				event.getActionParam0(), event.getActionParam1(), client.getTopLevelWorldView());
			final int tile = templateTileAt(location);
			addBuilderEntry(BUILDER_ORIGIN_OPTION, tileText(tile), () -> builderOrigin = tile);
			addBuilderEntry(BUILDER_DEST_OPTION, tileText(tile), () -> builderDest = tile);
			addBuilderEntry(BUILDER_OBJECT_OPTION, String.valueOf(objectId),
				() -> builderMenu = builderMenuFor(objectId));
		}
	}

	private boolean hasOption(String option)
	{
		for (net.runelite.api.MenuEntry entry : client.getMenu().getMenuEntries())
		{
			if (option.equals(entry.getOption()))
			{
				return true;
			}
		}
		return false;
	}

	private void addBuilderEntry(String option, String target, Runnable action)
	{
		client.getMenu().createMenuEntry(-1)
			.setOption(option)
			.setTarget(target)
			.setType(net.runelite.api.MenuAction.RUNELITE)
			.onClick(e -> {
				action.run();
				log.info("[audit] {} {}", option, target);
			});
	}

	private static boolean isObjectAction(net.runelite.api.MenuAction type)
	{
		switch (type)
		{
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case EXAMINE_OBJECT:
				return true;
			default:
				return false;
		}
	}

	/** The template tile for a local point, honouring the current render plane. */
	private int templateTileAt(net.runelite.api.coords.LocalPoint location)
	{
		net.runelite.api.coords.WorldPoint worldPoint = net.runelite.api.coords.WorldPoint
			.fromLocalInstance(client, location, client.getTopLevelWorldView().getPlane());
		return WorldPointUtil.packWorldPoint(worldPoint);
	}

	/** "action Name id" for the transports.tsv menu column, preferring a traversal action. */
	private String builderMenuFor(int objectId)
	{
		ObjectComposition composition = client.getObjectDefinition(objectId);
		if (composition == null)
		{
			return String.valueOf(objectId);
		}
		if (composition.getImpostorIds() != null)
		{
			try
			{
				ObjectComposition impostor = composition.getImpostor();
				if (impostor != null)
				{
					composition = impostor;
				}
			}
			catch (Exception ignored)
			{
				// varbit not loaded; base def is fine for naming
			}
		}
		String action = traversalAction(composition, true);
		if (action == null && composition.getActions() != null)
		{
			for (String candidate : composition.getActions())
			{
				if (candidate != null)
				{
					action = candidate;
					break;
				}
			}
		}
		return (action == null ? "" : action + " ") + composition.getName() + " " + objectId;
	}

	/** Panel "1-way": declares every unpaired captured edge at this object a genuine one-way. */
	String markNoReverse(int objectId, int packedTile)
	{
		int marked = 0;
		StringBuilder lines = new StringBuilder();
		for (int i = 0; i < capturedEdges.size(); i++)
		{
			int[] edge = capturedEdges.get(i);
			if (edge[2] == objectId
				&& (WorldPointUtil.distanceBetween(edge[0], packedTile) <= 4
				|| WorldPointUtil.distanceBetween(edge[1], packedTile) <= 4)
				&& (i >= edgePaired.length || !edgePaired[i])
				&& noReverseKeys.add(edgeKey(edge)))
			{
				lines.append(edgeKey(edge)).append('\n');
				marked++;
			}
		}
		if (marked == 0)
		{
			return "No unpaired edges here";
		}
		try (java.io.FileWriter writer = new java.io.FileWriter(oneWayFile, true))
		{
			writer.write(lines.toString());
		}
		catch (Exception e)
		{
			log.warn("[audit] could not append to {}", oneWayFile, e);
		}
		recomputeEdgePairs();
		log.info("[audit] marked {} edge(s) as no-reverse at object {}", marked, objectId);
		return "Marked " + marked + " edge(s) one-way (no reverse)";
	}

	/** Panel "Ignore" (and the in-world shift entry): persists a false positive by plain fields. */
	void ignoreEntry(int objectId, int packedTile, String name)
	{
		long key = ((long) packedTile << 20) | objectId;
		if (!ignoredKeys.add(key))
		{
			return;
		}
		findings.remove(key);
		try
		{
			boolean fresh = !ignoreFile.exists();
			try (java.io.FileWriter writer = new java.io.FileWriter(ignoreFile, true))
			{
				if (fresh)
				{
					writer.write("id\tx\ty\tplane\tname\tdate\n");
				}
				writer.write(objectId + "\t"
					+ WorldPointUtil.unpackWorldX(packedTile) + "\t"
					+ WorldPointUtil.unpackWorldY(packedTile) + "\t"
					+ WorldPointUtil.unpackWorldPlane(packedTile) + "\t"
					+ name + "\t" + java.time.LocalDate.now() + "\n");
			}
		}
		catch (Exception e)
		{
			log.warn("[audit] could not append to {}", ignoreFile, e);
		}
		log.info("[audit] marked not-a-transport: {} id={}", name, objectId);
	}

	/** Persists a false positive and stops flagging it everywhere, this session and future ones. */
	private void ignoreFinding(Finding finding)
	{
		ignoreEntry(finding.object.getId(), finding.packedTemplateTile, finding.name);
	}

	private void seedIgnoredFromFile()
	{
		if (!ignoreFile.exists())
		{
			return;
		}
		try (java.util.Scanner scanner = new java.util.Scanner(ignoreFile, "UTF-8"))
		{
			while (scanner.hasNextLine())
			{
				String[] fields = scanner.nextLine().split("\t");
				if (fields.length < 4)
				{
					continue;
				}
				try
				{
					int packed = WorldPointUtil.packWorldPoint(Integer.parseInt(fields[1]),
						Integer.parseInt(fields[2]), Integer.parseInt(fields[3]));
					ignoredKeys.add(((long) packed << 20) | Integer.parseInt(fields[0]));
				}
				catch (NumberFormatException ignored)
				{
					// header
				}
			}
			log.info("[audit] ignore file: {} false positives ({})", ignoredKeys.size(), ignoreFile);
		}
		catch (Exception e)
		{
			log.warn("[audit] could not read {}", ignoreFile, e);
		}
	}

	/**
	 * Whether a menu entry's scene params refer to this object. Menu params carry the object's
	 * SOUTH-WEST corner, but a GameObject's local location is its CENTER — for a large object
	 * (e.g. the 5x7 Underground Pass cave exit) they differ by several tiles, so compare against
	 * the game object's own scene-min location when available.
	 */
	private static boolean sceneClose(TileObject object, int sceneX, int sceneY)
	{
		if (object instanceof net.runelite.api.GameObject)
		{
			net.runelite.api.Point min = ((net.runelite.api.GameObject) object).getSceneMinLocation();
			if (min != null)
			{
				return Math.max(Math.abs(min.getX() - sceneX), Math.abs(min.getY() - sceneY)) <= 1;
			}
		}
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
		// A click after DEPARTURE usually means the traversal is over — but only complete the
		// in-flight capture if the player is genuinely AT REST: menu clicks can also happen
		// mid-crossing (impatient re-click on the next rockslide), and the last observed tile is
		// then somewhere on the obstacle's footprint — captured rows with non-walkable
		// destinations came from exactly this. At rest = action animation over and the current
		// tile unchanged since the last game tick.
		if (pending != null && pending.departTick >= 0 && pending.lastTile != WorldPointUtil.UNDEFINED)
		{
			int tile = client.getLocalPlayer() != null
				? WorldPointUtil.fromLocalInstance(client, client.getLocalPlayer()) : WorldPointUtil.UNDEFINED;
			if (client.getLocalPlayer() != null && client.getLocalPlayer().getAnimation() == -1
				&& tile == pending.lastTile)
			{
				completeCapture(tile, client.getTickCount());
			}
			else
			{
				log.info("[audit] capture dropped (clicked mid-traversal): {}", pending.finding.describe());
				pending = null;
			}
		}
		net.runelite.api.MenuEntry entry = event.getMenuEntry();
		for (Finding finding : findings.values())
		{
			if (!finding.door && finding.object.getId() == entry.getIdentifier()
				&& sceneClose(finding.object, entry.getParam0(), entry.getParam1())
				&& event.getMenuOption() != null && event.getMenuOption().equalsIgnoreCase(finding.action))
			{
				if (pending != null)
				{
					log.info("[audit] re-armed before departure — previous capture dropped: {}",
						pending.finding.describe());
				}
				pending = new PendingCapture(finding, client.getTickCount());
				log.info("[audit] capture armed: {} — traverse it and come to rest", finding.describe());
				return;
			}
		}
		if (pending != null)
		{
			log.info("[audit] capture cancelled (clicked something else): {}", pending.finding.describe());
			pending = null;
		}
		armDurationWatch(event);
	}

	/**
	 * A traversal click near a KNOWN row's origin arms a duration sample: no new row to
	 * discover, but the measured ticks enrich the data as the operator plays.
	 */
	private void armDurationWatch(net.runelite.api.events.MenuOptionClicked event)
	{
		durationWatch = null;
		if (client.getLocalPlayer() == null || event.getMenuOption() == null
			|| !isObjectAction(event.getMenuAction()))
		{
			return;
		}
		String lower = event.getMenuOption().toLowerCase(java.util.Locale.ROOT);
		boolean traversal = false;
		for (String verb : TRAVERSAL_VERBS)
		{
			if (lower.startsWith(verb))
			{
				traversal = true;
				break;
			}
		}
		if (!traversal)
		{
			return;
		}
		int playerTile = WorldPointUtil.fromLocalInstance(client, client.getLocalPlayer());
		java.util.List<KnownEntry> candidates = new java.util.ArrayList<>();
		for (KnownEntry entry : knownEntries)
		{
			if (entry.origin != WorldPointUtil.UNDEFINED
				&& entry.destination != WorldPointUtil.UNDEFINED
				&& WorldPointUtil.unpackWorldPlane(entry.origin) == WorldPointUtil.unpackWorldPlane(playerTile)
				&& WorldPointUtil.distanceBetween2D(playerTile, entry.origin) <= 3)
			{
				candidates.add(entry);
			}
		}
		if (!candidates.isEmpty())
		{
			durationWatch = new DurationWatch(candidates, client.getTickCount(), playerTile);
		}
	}

	/**
	 * Drives an armed duration watch, mirroring the capture state machine's semantics so
	 * samples are comparable with captured durations: departure = animation while by the
	 * origin, or the first tick after stepping off it; arrival = two stationary ticks at a
	 * candidate row's destination.
	 */
	private void tickDurationWatch(int now, int tile, int animation)
	{
		DurationWatch watch = durationWatch;
		if (watch == null)
		{
			return;
		}
		if (now - watch.clickTick > 60)
		{
			durationWatch = null;
			return;
		}
		if (watch.departTick < 0)
		{
			boolean nearOrigin = false;
			for (KnownEntry entry : watch.candidates)
			{
				if (WorldPointUtil.unpackWorldPlane(tile) == WorldPointUtil.unpackWorldPlane(entry.origin)
					&& WorldPointUtil.distanceBetween2D(tile, entry.origin) <= 1)
				{
					nearOrigin = true;
					break;
				}
			}
			if (nearOrigin)
			{
				watch.wasNearOrigin = true;
				if (animation != -1)
				{
					watch.departTick = now; // in-place transports animate before moving
				}
			}
			else if (watch.wasNearOrigin)
			{
				watch.departTick = now; // stepped off the origin: the traversal is under way
			}
			return;
		}
		if (tile == watch.lastTile)
		{
			watch.stableTicks++;
		}
		else
		{
			watch.stableTicks = 1;
			watch.lastTile = tile;
		}
		if (watch.stableTicks < 2 || animation != -1)
		{
			return;
		}
		for (KnownEntry entry : watch.candidates)
		{
			if (WorldPointUtil.unpackWorldPlane(tile) == WorldPointUtil.unpackWorldPlane(entry.destination)
				&& WorldPointUtil.distanceBetween2D(tile, entry.destination) <= 1)
			{
				int arrivalTick = now - watch.stableTicks + 1;
				recordDurationSample(entry, Math.max(1, arrivalTick - watch.departTick));
				durationWatch = null;
				return;
			}
		}
		if (watch.stableTicks >= 6)
		{
			durationWatch = null; // at rest, but nowhere the data expected — not this transport
		}
	}

	/** Appends one measured traversal to duration-samples.tsv and confirms it in the panel. */
	private void recordDurationSample(KnownEntry entry, int measured)
	{
		try
		{
			boolean fresh = !durationSamplesFile.exists();
			try (java.io.FileWriter writer = new java.io.FileWriter(durationSamplesFile, true))
			{
				if (fresh)
				{
					writer.write("# Measured traversals of KNOWN transports (audit duration watch)."
						+ " Fold into the data with scripts/apply_duration_samples.py\n");
					writer.write("date\torigin\tdestination\tlabel\tdataTicks\tmeasuredTicks\n");
				}
				writer.write(new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date())
					+ "\t" + tileText(entry.origin) + "\t" + tileText(entry.destination)
					+ "\t" + entry.label + "\t" + entry.duration + "\t" + measured + "\n");
			}
		}
		catch (Exception e)
		{
			log.warn("[audit] could not append duration sample", e);
		}
		boolean agrees = measured == entry.duration;
		lastCaptureText = "Sampled " + entry.label + ": " + measured + "t (data "
			+ entry.duration + "t" + (agrees ? " ✓" : " ✗") + ")";
		lastCaptureTick = client.getTickCount();
		log.info("[audit] duration sample: {} measured {}t, data {}t", entry.label, measured, entry.duration);
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
		refreshCollisionCells();
		sampleBoatWake();
		if (durationWatch != null)
		{
			tickDurationWatch(client.getTickCount(),
				WorldPointUtil.fromLocalInstance(client, client.getLocalPlayer()),
				client.getLocalPlayer().getAnimation());
		}
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
		int animation = client.getLocalPlayer().getAnimation();
		// distanceBetween returns MAX_VALUE across planes, so range implies same plane. The radius
		// accounts for the object's footprint — its center can be several tiles from its edge.
		boolean inRange = WorldPointUtil.distanceBetween(tile, pending.finding.packedTemplateTile)
			<= pending.finding.interactRadius;
		if (pending.departTick < 0)
		{
			// Two departure triggers: an ACTION animation while adjacent (forced moves play one,
			// walking doesn't — catches short same-plane hops like rockslides whose landing is
			// still within range), or leaving the object's range (teleports, ladders, long
			// bridges). Either way the origin locks at the last calm in-range tile.
			if (inRange && animation != -1 && pending.originTile != WorldPointUtil.UNDEFINED)
			{
				pending.departTick = now;
			}
			else if (inRange)
			{
				pending.originTile = tile;
			}
			else if (pending.originTile != WorldPointUtil.UNDEFINED && tile != pending.originTile)
			{
				pending.departTick = now;
			}
		}
		if (pending.departTick >= 0)
		{
			// Rest = stationary AND the traversal animation is over (multi-stage obstacles pause
			// the player mid-animation; that must not count as arrival).
			if (tile == pending.lastTile && animation == -1)
			{
				if (++pending.stableTicks >= 2)
				{
					completeCapture(tile, now); // nulls pending — nothing left to track this tick
					return;
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
		capturedKeys.remove(key); // appendCaptureRow re-adds; keep the dedupe in one place
		if (appendCaptureRow(row, key))
		{
			capturedEdges.add(new int[]{capture.originTile, destTile, capture.finding.object.getId()});
			// Recompute NOW: without this, live-captured edges sat beyond edgePaired.length and
			// read as one-way until the next restart, however many reverses were walked.
			recomputeEdgePairs();
			log.info("[audit] captured — review then paste into transports.tsv:\n{}", row);
			int derived = deriveSiblingLanes(capture.finding, capture.originTile, destTile, duration);
			if (derived > 0)
			{
				lastCaptureText += " (+" + derived + " lanes derived)";
			}
		}
	}

	/**
	 * After a verified capture across a multi-lane object, writes the sibling-lane rows to the
	 * captures file tagged {@code ~geometry ~duration} — verified for the traversed lane only.
	 * Lanes whose translated endpoints are collision-blocked are dropped; without a loaded GPS
	 * collision map nothing is derived (better no row than an unwalkable one).
	 */
	private int deriveSiblingLanes(Finding finding, int origin, int dest, int duration)
	{
		if (finding.footprintX <= 1 && finding.footprintY <= 1
			|| !(finding.object instanceof net.runelite.api.GameObject))
		{
			return 0;
		}
		int dx = Math.abs(WorldPointUtil.unpackWorldX(dest) - WorldPointUtil.unpackWorldX(origin));
		int dy = Math.abs(WorldPointUtil.unpackWorldY(dest) - WorldPointUtil.unpackWorldY(origin));
		if (dx == dy)
		{
			return 0;
		}
		net.runelite.api.GameObject gameObject = (net.runelite.api.GameObject) finding.object;
		net.runelite.api.Point sceneMin = gameObject.getSceneMinLocation();
		net.runelite.api.Point sceneMax = gameObject.getSceneMaxLocation();
		net.runelite.api.coords.LocalPoint center = gameObject.getLocalLocation();
		gps.pathfinder.CollisionMap map = gpsCollisionMap();
		if (sceneMin == null || sceneMax == null || center == null || map == null)
		{
			return 0;
		}
		// World coordinate range of the footprint along the lane axis, anchored on the object's
		// (template) tile so instances stay in transport-data coordinate space.
		boolean lanesAlongY = dx > dy;
		int centerLaneScene = lanesAlongY ? center.getSceneY() : center.getSceneX();
		int minLaneScene = lanesAlongY ? sceneMin.getY() : sceneMin.getX();
		int maxLaneScene = lanesAlongY ? sceneMax.getY() : sceneMax.getX();
		int centerLaneWorld = lanesAlongY
			? WorldPointUtil.unpackWorldY(finding.packedTemplateTile)
			: WorldPointUtil.unpackWorldX(finding.packedTemplateTile);
		int laneMinWorld = centerLaneWorld + (minLaneScene - centerLaneScene);
		int laneMaxWorld = laneMinWorld + (maxLaneScene - minLaneScene);
		int written = 0;
		for (int[] lane : expandCaptureLanes(origin, dest, laneMinWorld, laneMaxWorld,
			p -> map.isBlocked(WorldPointUtil.unpackWorldX(p), WorldPointUtil.unpackWorldY(p),
				WorldPointUtil.unpackWorldPlane(p))))
		{
			String laneKey = tileText(lane[0]) + "|" + tileText(lane[1]) + "|"
				+ finding.object.getId();
			if (capturedKeys.contains(laneKey))
			{
				continue; // that lane was genuinely traversed at some point — keep the real row
			}
			String laneRow = tileText(lane[0]) + "\t" + tileText(lane[1]) + "\t"
				+ finding.action + " " + finding.name + " " + finding.object.getId()
				+ "\t\t\t\t\t\t" + duration + "\t\t\t~geometry ~duration";
			if (appendCaptureRow(laneRow, "~" + laneKey))
			{
				written++;
			}
		}
		if (written > 0)
		{
			log.info("[audit] derived {} sibling lane row(s) for {} (footprint {}x{})",
				written, finding.name, finding.footprintX, finding.footprintY);
		}
		return written;
	}

	/** The GPS plugin's live collision map, or null when unavailable. */
	private gps.pathfinder.CollisionMap gpsCollisionMap()
	{
		for (net.runelite.client.plugins.Plugin other : pluginManager.getPlugins())
		{
			if (other instanceof gps.ShortestPathPlugin)
			{
				return ((gps.ShortestPathPlugin) other).getCollisionMap();
			}
		}
		return null;
	}

	/** Appends one review-ready transports.tsv row to the captures file. False when a duplicate. */
	private boolean appendCaptureRow(String row, String key)
	{
		if (!capturedKeys.add(key))
		{
			return false;
		}
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
		return true;
	}

	/**
	 * Panel "Save row": writes the builder's transports.tsv row — and, for two-way transports,
	 * its reverse (rows are DIRECTIONAL: one-way transports are simply a single row, which is
	 * also why auto-capture records each direction separately as it is actually traversed).
	 */
	String saveBuilderRow(String skills, String items, String quests, String duration,
		String displayInfo, String note, boolean bothWays)
	{
		int origin = builderOrigin;
		int dest = builderDest;
		if (origin == WorldPointUtil.UNDEFINED || dest == WorldPointUtil.UNDEFINED)
		{
			return "Set origin and destination first (shift right-click)";
		}
		String menu = builderMenu == null ? "" : builderMenu;
		String tail = "\t" + skills + "\t" + items + "\t" + quests + "\t\t\t" + duration + "\t" + displayInfo;
		String row = tileText(origin) + "\t" + tileText(dest) + "\t" + menu + tail;
		int id = menuObjectId(menu);
		boolean wrote = appendCaptureRow(row, tileText(origin) + "|" + tileText(dest) + "|" + id);
		if (wrote)
		{
			log.info("[audit] builder row saved:\n{}", row);
			if (id > 0)
			{
				capturedEdges.add(new int[]{origin, dest, id});
			}
		}
		if (bothWays)
		{
			String reverse = tileText(dest) + "\t" + tileText(origin) + "\t" + menu + tail;
			if (appendCaptureRow(reverse, tileText(dest) + "|" + tileText(origin) + "|" + id) && id > 0)
			{
				capturedEdges.add(new int[]{dest, origin, id});
			}
		}
		recomputeEdgePairs();
		builderOrigin = WorldPointUtil.UNDEFINED;
		builderDest = WorldPointUtil.UNDEFINED;
		builderMenu = "";
		return wrote ? "Saved to transport-captures.tsv" : "Already recorded — nothing written";
	}

	void clearBuilder()
	{
		builderOrigin = WorldPointUtil.UNDEFINED;
		builderDest = WorldPointUtil.UNDEFINED;
		builderMenu = "";
	}

	/** Panel entry point: the dump reads scene state, so it must hop to the client thread. */
	void requestLiveCollisionDump(java.util.function.Consumer<String> feedback)
	{
		clientThread.invokeLater(() -> feedback.accept(dumpLiveCollision()));
	}

	/** CLIENT THREAD. One wake sample per tick while aboard, deduplicated when idle. */
	private void sampleBoatWake()
	{
		net.runelite.api.WorldEntity boat = playerBoat();
		if (boat == null)
		{
			return;
		}
		net.runelite.api.coords.LocalPoint location = boat.getLocalLocation();
		net.runelite.api.WorldView playerView = client.getLocalPlayer().getWorldView();
		if (location == null || playerView == null)
		{
			return;
		}
		if (playerView.getId() != wakeViewId)
		{
			boatWake.clear();
			wakeViewId = playerView.getId();
		}
		int tick = client.getTickCount();
		int[] sample = {location.getX(), location.getY(), boat.getOrientation(), tick};
		int[] last = boatWake.peekLast();
		if (last != null && last[0] == sample[0] && last[1] == sample[1] && last[2] == sample[2])
		{
			if (tick - last[3] >= 2)
			{
				boatSpeedTiles = 0; // unchanged for a couple of ticks: parked, not gliding
			}
			return; // moored or drifting in place: don't fill the trail with duplicates
		}
		if (last != null)
		{
			// Tiles per tick between consecutive samples (128 local units = one tile).
			int elapsed = Math.max(1, tick - last[3]);
			boatSpeedTiles = Math.hypot(sample[0] - last[0], sample[1] - last[1]) / 128.0 / elapsed;
		}
		boatWake.addLast(sample);
		while (boatWake.size() > WAKE_SAMPLES)
		{
			boatWake.removeFirst();
		}
		trimWakeToLength();
	}

	/**
	 * Drops the oldest samples once the trail is longer than {@link #WAKE_MAX_TILES}. Trimming
	 * by travelled DISTANCE rather than sample count keeps the wake the same physical length at
	 * any speed — a fixed sample count would stretch it to a banner at full sail.
	 */
	private void trimWakeToLength()
	{
		final double budget = WAKE_MAX_TILES * 128.0;
		java.util.List<int[]> samples = new java.util.ArrayList<>(boatWake);
		double travelled = 0;
		int[] previous = null;
		int keep = 0;
		for (int i = samples.size() - 1; i >= 0; i--)
		{
			int[] sample = samples.get(i);
			if (previous != null)
			{
				travelled += Math.hypot(sample[0] - previous[0], sample[1] - previous[1]);
				if (travelled > budget)
				{
					break;
				}
			}
			previous = sample;
			keep++;
		}
		while (boatWake.size() > keep)
		{
			boatWake.removeFirst();
		}
	}

	/**
	 * The player's boat entity, or null when ashore. byIndex(viewId) is the documented lookup;
	 * the owner-type scan covers the ticks where it returns null mid view-swap.
	 */
	net.runelite.api.WorldEntity playerBoat()
	{
		net.runelite.api.Player player = client.getLocalPlayer();
		net.runelite.api.WorldView top = client.getTopLevelWorldView();
		if (player == null || top == null || player.getWorldView() == null
			|| player.getWorldView().isTopLevel())
		{
			return null;
		}
		net.runelite.api.WorldEntity boat = top.worldEntities().byIndex(player.getWorldView().getId());
		if (boat != null)
		{
			return boat;
		}
		for (net.runelite.api.WorldEntity entity : top.worldEntities())
		{
			if (entity != null
				&& entity.getOwnerType() == net.runelite.api.WorldEntity.OWNER_TYPE_SELF_PLAYER)
			{
				return entity;
			}
		}
		return null;
	}

	java.util.List<int[]> collisionCells()
	{
		return collisionCells;
	}

	static final int LIVE_BLOCK_MASK = net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_OBJECT
		| net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FLOOR
		| net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION
		| net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL;

	/**
	 * The continuous version of {@link #dumpLiveCollision()}: per tick, classifies every tile
	 * within {@link #COLLISION_VIEW_RADIUS} of the player for the scene overlay — blocked
	 * verdicts from BOTH maps, static wall edges, and live-vs-static edge disagreements. The
	 * same pure-wall-edge comparison rule as the dump (canStep also fails into blocked tiles;
	 * comparing those against live per-direction flags fabricates mismatches). CLIENT THREAD.
	 */
	private void refreshCollisionCells()
	{
		if (!showCollision)
		{
			if (!collisionCells.isEmpty())
			{
				collisionCells = java.util.List.of();
			}
			return;
		}
		net.runelite.api.Player player = client.getLocalPlayer();
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		gps.pathfinder.CollisionMap staticMap = gpsCollisionMap();
		if (player == null || view == null || view.getCollisionMaps() == null || staticMap == null)
		{
			collisionCells = java.util.List.of();
			return;
		}
		// On a boat the player lives in the boat's sub-WorldView: their own LocalPoint indexes
		// the DECK scene, not the sea. Anchor on the boat WorldEntity's top-level position then
		// (the port-tasks pattern), so the view keeps painting the water around the hull.
		net.runelite.api.coords.LocalPoint local = player.getLocalLocation();
		net.runelite.api.WorldView playerView = player.getWorldView();
		if (playerView != null && !playerView.isTopLevel())
		{
			net.runelite.api.WorldEntity boat = view.worldEntities().byIndex(playerView.getId());
			local = boat != null ? boat.getLocalLocation() : null;
		}
		if (local == null)
		{
			collisionCells = java.util.List.of();
			return;
		}
		int plane = view.getPlane();
		int[][] flags = view.getCollisionMaps()[plane].getFlags();
		// Render settings: bit 1 on plane 0 marks water ("nomove" floor) — the same convention
		// the collision dumper bakes. Upper planes reuse the bit for roof walls, so plane 0 only.
		byte[][][] settings = view.getTileSettings();
		short[][][] overlays = view.getScene() != null ? view.getScene().getOverlayIds() : null;

		int[][] dirs = {
			{0, 1, net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_NORTH},
			{1, 0, net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_EAST},
			{0, -1, net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_SOUTH},
			{-1, 0, net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_WEST},
		};
		// PER-TILE world resolution, not base+offset: the sea scene while sailing is INSTANCED
		// (chunks remap individually to template coords), so an affine base breaks the moment
		// the boat leaves static coastline — the first symptom was "only blue near the port".
		// Cells carry SCENE coords for drawing; world coords are only used for map lookups.
		boolean sailing = playerView != null && !playerView.isTopLevel();
		// "Open flags" alone are NOT the sea: enclosed voids (the inside of thick walls, sealed
		// structure interiors) also carry zero flags. Navigable ocean = open tiles CONNECTED to
		// the boat, so flood within the window from the open ring around the hull (the hull's
		// own tiles are live-blocked) and only let reachable tiles claim the water color.
		final int windowSize = 2 * COLLISION_VIEW_RADIUS + 1;
		boolean[][] openLocal = new boolean[windowSize][windowSize];
		for (int dy = -COLLISION_VIEW_RADIUS; dy <= COLLISION_VIEW_RADIUS; dy++)
		{
			for (int dx = -COLLISION_VIEW_RADIUS; dx <= COLLISION_VIEW_RADIUS; dx++)
			{
				int sx = local.getSceneX() + dx;
				int sy = local.getSceneY() + dy;
				openLocal[dx + COLLISION_VIEW_RADIUS][dy + COLLISION_VIEW_RADIUS] =
					sx >= 0 && sy >= 0 && sx < flags.length && sy < flags[sx].length
						&& (flags[sx][sy] & LIVE_BLOCK_MASK) == 0;
			}
		}
		boolean[][] reachable = new boolean[windowSize][windowSize];
		java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
		for (int dy = -3; dy <= 3; dy++)
		{
			for (int dx = -3; dx <= 3; dx++)
			{
				int wx = dx + COLLISION_VIEW_RADIUS;
				int wy = dy + COLLISION_VIEW_RADIUS;
				if (openLocal[wx][wy] && !reachable[wx][wy])
				{
					reachable[wx][wy] = true;
					queue.add(new int[]{wx, wy});
				}
			}
		}
		while (!queue.isEmpty())
		{
			int[] at = queue.poll();
			for (int dy = -1; dy <= 1; dy++)
			{
				for (int dx = -1; dx <= 1; dx++)
				{
					int wx = at[0] + dx;
					int wy = at[1] + dy;
					if (wx >= 0 && wy >= 0 && wx < windowSize && wy < windowSize
						&& openLocal[wx][wy] && !reachable[wx][wy])
					{
						reachable[wx][wy] = true;
						queue.add(new int[]{wx, wy});
					}
				}
			}
		}
		java.util.List<int[]> cells = new java.util.ArrayList<>();
		for (int dy = -COLLISION_VIEW_RADIUS; dy <= COLLISION_VIEW_RADIUS; dy++)
		{
			for (int dx = -COLLISION_VIEW_RADIUS; dx <= COLLISION_VIEW_RADIUS; dx++)
			{
				int sx = local.getSceneX() + dx;
				int sy = local.getSceneY() + dy;
				if (sx < 0 || sy < 0 || sx >= flags.length || sy >= flags[sx].length)
				{
					continue;
				}
				int world = WorldPointUtil.fromLocalInstance(client,
					net.runelite.api.coords.LocalPoint.fromScene(sx, sy));
				boolean liveBlocked = (flags[sx][sy] & LIVE_BLOCK_MASK) != 0;
				// Generated sea chunks have no template: no static verdict exists there, but the
				// LIVE classification still does — render water/blockers from the client alone
				// instead of leaving holes around the boat (static comparison needs a template).
				boolean staticKnown = world != WorldPointUtil.UNDEFINED;
				boolean staticBlocked = staticKnown
					&& staticMap.isBlocked(WorldPointUtil.unpackWorldX(world),
						WorldPointUtil.unpackWorldY(world), WorldPointUtil.unpackWorldPlane(world));
				int tileState;
				if (staticKnown)
				{
					tileState = liveBlocked
						? (staticBlocked ? COLLISION_BOTH_BLOCKED : COLLISION_LIVE_ONLY)
						: (staticBlocked ? COLLISION_STATIC_ONLY : 0);
				}
				else
				{
					// Generated sea: open tiles ARE the navigable water (flags 0, no settings
					// bits — boats sail them), blocked tiles are sailing obstacles. Claim water
					// only while on a boat AND connected to it — enclosed voids inside wall
					// volumes are also flag-free but no boat can ever reach them.
					boolean connected = reachable[dx + COLLISION_VIEW_RADIUS][dy + COLLISION_VIEW_RADIUS];
					tileState = liveBlocked ? COLLISION_BOTH_BLOCKED
						: (sailing && connected ? COLLISION_WATER : 0);
				}
				if (liveBlocked && plane == 0 && settings != null
					&& (settings[0][sx][sy] & 1) != 0)
				{
					tileState = COLLISION_WATER;
				}
				int staticEdges = 0;
				int mismatchEdges = 0;
				if (staticKnown)
				{
					int wx = WorldPointUtil.unpackWorldX(world);
					int wy = WorldPointUtil.unpackWorldY(world);
					int wp = WorldPointUtil.unpackWorldPlane(world);
					// RAW wall flags (not canStep, which also fails into blocked tiles): drawn
					// for every templated tile, so walls render on and around blocked ground
					// too. Interior edges of fully-blocked regions are suppressed — the result
					// is wall lines plus crisp outlines of blocked volumes, not grid soup.
					boolean[] closed = {
						!staticMap.n(wx, wy, wp), !staticMap.e(wx, wy, wp),
						!staticMap.s(wx, wy, wp), !staticMap.w(wx, wy, wp),
					};
					for (int d = 0; d < 4; d++)
					{
						int nsx = sx + dirs[d][0];
						int nsy = sy + dirs[d][1];
						if (nsx < 0 || nsy < 0 || nsx >= flags.length || nsy >= flags[nsx].length)
						{
							continue;
						}
						int neighbourWorld = WorldPointUtil.fromLocalInstance(client,
							net.runelite.api.coords.LocalPoint.fromScene(nsx, nsy));
						// At instance chunk seams adjacent scene tiles can map to distant
						// template coords — edge comparisons would misread them as walls. Skip.
						if (neighbourWorld == WorldPointUtil.UNDEFINED
							|| Math.abs(WorldPointUtil.unpackWorldX(neighbourWorld) - wx) > 1
							|| Math.abs(WorldPointUtil.unpackWorldY(neighbourWorld) - wy) > 1)
						{
							continue;
						}
						boolean neighbourLive = (flags[nsx][nsy] & LIVE_BLOCK_MASK) != 0;
						boolean neighbourStatic = staticMap.isBlocked(
							WorldPointUtil.unpackWorldX(neighbourWorld),
							WorldPointUtil.unpackWorldY(neighbourWorld),
							WorldPointUtil.unpackWorldPlane(neighbourWorld));
						if (closed[d] && !(staticBlocked && neighbourStatic))
						{
							staticEdges |= 1 << d;
						}
						if (!liveBlocked && !staticBlocked && !neighbourLive && !neighbourStatic)
						{
							boolean liveStop = (flags[sx][sy] & dirs[d][2]) != 0;
							boolean staticStop = !staticMap.canStep(world, neighbourWorld);
							if (liveStop != staticStop)
							{
								mismatchEdges |= 1 << d;
							}
						}
					}
				}
				if (tileState != 0 || staticEdges != 0 || mismatchEdges != 0)
				{
					// Overlay id rides along for water tiles: sailing shallows (hull damage)
					// use distinct ground overlays, so the view tints water per overlay and a
					// dump histograms them — the field method for pinning the damaging ids.
					int overlayId = overlays != null && plane < overlays.length
						? overlays[plane][sx][sy] : 0;
					cells.add(new int[]{sx, sy, tileState, staticEdges, mismatchEdges, overlayId});
				}
			}
		}
		collisionCells = cells;
	}

	/**
	 * Ground truth vs shipped map: dumps the client's RUNTIME collision flags (which include
	 * every dynamic/invisible state the cache can't express) around the player, side by side
	 * with the GPS static map's verdicts. For "the data says blocked but I just walked through
	 * it" reports — stand on the disputed line and dump. CLIENT THREAD.
	 */
	String dumpLiveCollision()
	{
		net.runelite.api.Player player = client.getLocalPlayer();
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		if (player == null || view == null || view.getCollisionMaps() == null)
		{
			return "not logged in";
		}
		// Same boat anchoring as the live view: dump the sea around the hull, not the deck.
		net.runelite.api.coords.LocalPoint local = player.getLocalLocation();
		net.runelite.api.WorldView playerView = player.getWorldView();
		if (playerView != null && !playerView.isTopLevel())
		{
			net.runelite.api.WorldEntity boat = view.worldEntities().byIndex(playerView.getId());
			if (boat == null || boat.getLocalLocation() == null)
			{
				return "on a boat, but its world entity is not resolvable";
			}
			local = boat.getLocalLocation();
		}
		int plane = view.getPlane();
		int[][] flags = view.getCollisionMaps()[plane].getFlags();
		int playerWorld = WorldPointUtil.fromLocalInstance(client, local);
		int baseX = WorldPointUtil.unpackWorldX(playerWorld) - local.getSceneX();
		int baseY = WorldPointUtil.unpackWorldY(playerWorld) - local.getSceneY();
		gps.pathfinder.CollisionMap staticMap = gpsCollisionMap();

		final int radius = 15;
		StringBuilder out = new StringBuilder();
		out.append("# live vs static collision, player ")
			.append(tileText(playerWorld)).append(" plane ").append(plane).append('\n');
		out.append("# tiles: '.' both open, '#' both blocked, '!' STATIC BLOCKED but live open,")
			.append(" '?' live blocked but static open, ' ' outside scene\n");
		java.util.List<String> edgeMismatches = new java.util.ArrayList<>();
		for (int dy = radius; dy >= -radius; dy--)
		{
			StringBuilder row = new StringBuilder();
			for (int dx = -radius; dx <= radius; dx++)
			{
				int sx = local.getSceneX() + dx;
				int sy = local.getSceneY() + dy;
				if (sx < 0 || sy < 0 || sx >= flags.length || sy >= flags[sx].length)
				{
					row.append(' ');
					continue;
				}
				int flag = flags[sx][sy];
				boolean liveBlocked = (flag & (net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_OBJECT
					| net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FLOOR
					| net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION
					| net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL)) != 0;
				boolean staticBlocked = staticMap != null
					&& staticMap.isBlocked(baseX + sx, baseY + sy, plane);
				row.append(liveBlocked ? (staticBlocked ? '#' : '?') : (staticBlocked ? '!' : '.'));
				// Directional (wall) flags vs the static map's edge verdicts. Only PURE wall
				// edges are comparable: canStep() also fails into a blocked tile, while the
				// live per-direction flags don't — comparing those produced 346 phantom
				// "mismatches" on the first Kebos dump.
				if (staticMap != null && !liveBlocked && !staticBlocked)
				{
					int world = WorldPointUtil.packWorldPoint(baseX + sx, baseY + sy, plane);
					int[][] dirs = {
						{0, 1, net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_NORTH},
						{1, 0, net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_EAST},
						{0, -1, net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_SOUTH},
						{-1, 0, net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_WEST},
					};
					for (int[] dir : dirs)
					{
						int nsx = sx + dir[0];
						int nsy = sy + dir[1];
						if (nsx < 0 || nsy < 0 || nsx >= flags.length || nsy >= flags[nsx].length)
						{
							continue;
						}
						boolean neighbourLiveBlocked = (flags[nsx][nsy]
							& (net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_OBJECT
							| net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FLOOR
							| net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION
							| net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL)) != 0;
						boolean neighbourStaticBlocked = staticMap.isBlocked(
							baseX + nsx, baseY + nsy, plane);
						if (neighbourLiveBlocked || neighbourStaticBlocked)
						{
							continue; // tile-level difference, reported by the grid already
						}
						boolean liveStop = (flag & dir[2]) != 0;
						boolean staticStop = !staticMap.canStep(world,
							WorldPointUtil.packWorldPoint(baseX + nsx, baseY + nsy, plane));
						if (liveStop != staticStop)
						{
							edgeMismatches.add(tileText(world) + " dir(" + dir[0] + "," + dir[1] + ")"
								+ " live=" + (liveStop ? "wall" : "open")
								+ " static=" + (staticStop ? "wall" : "open"));
						}
					}
				}
			}
			out.append(row).append('\n');
		}
		out.append("# edge mismatches (").append(edgeMismatches.size()).append("):\n");
		for (String mismatch : edgeMismatches)
		{
			out.append(mismatch).append('\n');
		}
		// Overlay histogram of the window's OPEN tiles: sail onto the damaging shallows and
		// dump — the id under the boat is the shallow-water overlay to blacklist in routing.
		short[][][] overlays = view.getScene() != null ? view.getScene().getOverlayIds() : null;
		if (overlays != null)
		{
			java.util.Map<Integer, Integer> overlayCounts = new java.util.TreeMap<>();
			for (int dy = -radius; dy <= radius; dy++)
			{
				for (int dx = -radius; dx <= radius; dx++)
				{
					int sx = local.getSceneX() + dx;
					int sy = local.getSceneY() + dy;
					if (sx >= 0 && sy >= 0 && sx < flags.length && sy < flags[sx].length
						&& (flags[sx][sy] & LIVE_BLOCK_MASK) == 0)
					{
						overlayCounts.merge((int) overlays[plane][sx][sy], 1, Integer::sum);
					}
				}
			}
			out.append("# open-tile overlay histogram (id=count): ");
			overlayCounts.forEach((id, count) -> out.append(id).append('=').append(count).append(' '));
			out.append('\n');
			int centerOverlay = overlays[plane][local.getSceneX()][local.getSceneY()];
			out.append("# overlay under the boat/player: ").append(centerOverlay).append('\n');
		}
		try
		{
			java.io.File file = new java.io.File(capturesFile.getParentFile(),
				"collision-live-" + System.currentTimeMillis() + ".txt");
			try (java.io.FileWriter writer = new java.io.FileWriter(file))
			{
				writer.write(out.toString());
			}
			log.info("[audit] live collision dump:\n{}", out);
			return "collision dump written: " + file.getName();
		}
		catch (Exception e)
		{
			log.warn("[audit] collision dump failed", e);
			return "dump failed: " + e.getMessage();
		}
	}

	/**
	 * Panel row selection: loads the entry into the builder for manual authoring — the menu
	 * column from the entry, and the object's tile as a STARTING origin (correct it to the tile
	 * you stand on via shift right-click before saving). Destination and typed fields are left
	 * alone.
	 */
	String loadIntoBuilder(Row row)
	{
		builderMenu = row.action + " " + row.name + " " + row.id;
		builderOrigin = row.packedTile;
		return "Loaded into builder — correct origin to your stand tile, set dest, then Save";
	}

	private static int menuObjectId(String menu)
	{
		String[] parts = menu.trim().split(" ");
		try
		{
			return parts.length > 0 ? Integer.parseInt(parts[parts.length - 1]) : -1;
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	private static String tileText(int packedTile)
	{
		return WorldPointUtil.unpackWorldX(packedTile) + " " + WorldPointUtil.unpackWorldY(packedTile)
			+ " " + WorldPointUtil.unpackWorldPlane(packedTile);
	}

	/**
	 * Requirement harvesting: while a capture is armed, a "You need…" / "You must…" game message
	 * means the traversal was refused — the one moment the game states the requirement outright.
	 * The armed capture transfers into the builder (origin + object pre-filled), the message is
	 * parsed into Skills ("72 Agility") / Quests where possible, and the raw text goes to the log.
	 */
	@Subscribe
	public void onChatMessage(net.runelite.api.events.ChatMessage event)
	{
		if (pending == null
			|| (event.getType() != net.runelite.api.ChatMessageType.GAMEMESSAGE
			&& event.getType() != net.runelite.api.ChatMessageType.MESBOX))
		{
			return;
		}
		String message = net.runelite.client.util.Text.removeTags(event.getMessage());
		String lower = message.toLowerCase(Locale.ROOT);
		if (!lower.contains("you need") && !lower.contains("you must") && !lower.contains("level of"))
		{
			return;
		}
		PendingCapture refused = pending;
		pending = null;
		log.info("[audit] requirement refusal on {}: \"{}\"", refused.finding.describe(), message);
		builderOrigin = refused.originTile != WorldPointUtil.UNDEFINED
			? refused.originTile
			: WorldPointUtil.fromLocalInstance(client, client.getLocalPlayer());
		builderMenu = refused.finding.action + " " + refused.finding.name + " "
			+ refused.finding.object.getId();
		final String skill = parseSkillRequirement(message);
		final String quest = parseQuestRequirement(message);
		javax.swing.SwingUtilities.invokeLater(() -> panel.suggestRequirements(skill, quest, message));
	}

	/** "You need an Agility level of 72 …" → "72 Agility" (the transports.tsv Skills format). */
	static String parseSkillRequirement(String message)
	{
		java.util.regex.Matcher matcher = java.util.regex.Pattern
			.compile("(?i)([a-z]+) level of (\\d+)").matcher(message);
		if (!matcher.find())
		{
			return null;
		}
		String skill = matcher.group(1);
		return matcher.group(2) + " " + Character.toUpperCase(skill.charAt(0))
			+ skill.substring(1).toLowerCase(Locale.ROOT);
	}

	/** "You must have completed (the) X (quest) to …" → "X" (the transports.tsv Quests format). */
	static String parseQuestRequirement(String message)
	{
		java.util.regex.Matcher matcher = java.util.regex.Pattern
			.compile("(?i)(?:completed|finished) (?:the )?(.+?)(?: quest)?(?: to | before |\\.|$)")
			.matcher(message);
		return matcher.find() ? matcher.group(1).trim() : null;
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
			recomputeEdgePairs();
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
		Set<Long> liveKeys = new HashSet<>();
		for (Finding finding : findings.values())
		{
			liveKeys.add(((long) finding.packedTemplateTile << 20) | finding.object.getId());
			rows.add(new Row(finding.name, finding.action, finding.object.getId(),
				finding.packedTemplateTile, stateOf(finding),
				WorldPointUtil.distanceBetween(playerTile, finding.packedTemplateTile), true,
				finding.dossier()));
		}
		// Everything recorded across sessions whose area isn't loaded right now — the backlog.
		for (RecordedEntry entry : recorded)
		{
			long key = ((long) entry.packedTile << 20) | entry.id;
			if (liveKeys.contains(key) || ignoredKeys.contains(key))
			{
				continue;
			}
			rows.add(new Row(entry.name, entry.action, entry.id, entry.packedTile,
				stateOfRecorded(entry),
				WorldPointUtil.distanceBetween2D(playerTile, entry.packedTile), false,
				dossierText(entry.name, entry.id, entry.packedTile, entry.action,
					entry.actions, entry.door, -1, 1, 1)));
		}
		for (MetaEdges.Entry entry : metaEdges)
		{
			int anchor = entry.origin != WorldPointUtil.UNDEFINED ? entry.origin : entry.destination;
			int distance = anchor != WorldPointUtil.UNDEFINED
				? WorldPointUtil.distanceBetween2D(playerTile, anchor) : Integer.MAX_VALUE;
			rows.add(new Row(entry.menu.isEmpty() ? entry.file : entry.menu, entry.tags, 0,
				anchor, FindingState.CONFIRM, distance, false,
				"Meta-tagged row (" + entry.file + "): " + entry.describe()
					+ "\nConfirm by traversing/using it with the audit running — the capture "
					+ "measures the real values; then remove the tags from the row."));
		}
		if (showKnown)
		{
			String filter = listFilter;
			java.util.List<Row> known = new java.util.ArrayList<>();
			for (KnownEntry entry : knownEntries)
			{
				int anchor = entry.origin != WorldPointUtil.UNDEFINED ? entry.origin : entry.destination;
				if (anchor == WorldPointUtil.UNDEFINED)
				{
					continue;
				}
				int distance = WorldPointUtil.distanceBetween2D(playerTile, anchor);
				if (filter.isEmpty())
				{
					if (distance > 60)
					{
						continue; // browsing: only the local area is useful in-world
					}
				}
				else if (!entry.label.toLowerCase(java.util.Locale.ROOT).contains(filter))
				{
					continue; // searching: match anywhere in the world, by name
				}
				known.add(new Row(entry.label, "curated", 0, anchor, FindingState.KNOWN,
					distance, false,
					"Curated row: " + entry.label + "\norigin " + tileText(entry.origin)
						+ " -> dest " + tileText(entry.destination)));
			}
			known.sort(java.util.Comparator.comparingInt(row -> row.distance));
			rows.addAll(known.subList(0, Math.min(filter.isEmpty() ? 25 : 100, known.size())));
		}
		rows.sort(java.util.Comparator
			.comparingInt((Row row) -> row.live ? 0 : 1)
			.thenComparingInt(row -> statePriority(row.state))
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
		final String origin = builderOrigin == WorldPointUtil.UNDEFINED ? null : tileText(builderOrigin);
		final String dest = builderDest == WorldPointUtil.UNDEFINED ? null : tileText(builderDest);
		final String menu = builderMenu == null || builderMenu.isEmpty() ? null : builderMenu;
		javax.swing.SwingUtilities.invokeLater(() -> panel.update(rows, line, color, origin, dest, menu));
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
			case CONFIRM:
				return 3; // machine-derived values awaiting a field check
			case CAPTURED_ONE_WAY:
			case DATA_ONE_WAY:
				return 4; // actionable: the return trip is still missing
			case CAPTURED_SESSION:
				return 5;
			case CAPTURED_PRIOR:
				return 6;
			case KNOWN:
				return 8; // debugging browser rows sit at the bottom
			default:
				return 7; // RESOLVED — prunable tail
		}
	}

	/** State of a collection-file entry whose object isn't in the loaded scene. */
	private FindingState stateOfRecorded(RecordedEntry entry)
	{
		if (entry.coverageResolved)
		{
			return FindingState.RESOLVED;
		}
		if (entry.door)
		{
			return FindingState.DOOR;
		}
		long key = ((long) entry.packedTile << 20) | entry.id;
		return capturedState(entry.id, entry.packedTile, capturedThisSession.contains(key));
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
		// A multiloc's controlling varbit is a strong quest-gating hint (quest doors/caves swap
		// their impostor on the quest's progress varbit) — surfaced in the dossier.
		int gatingVarbit = -1;
		try
		{
			gatingVarbit = client.getObjectDefinition(object.getId()).getVarbitId();
		}
		catch (Exception ignored)
		{
			// definition lookup already succeeded once; defensive only
		}

		// NB: the object's OWN plane, not the render plane — WorldPointUtil's Client+LocalPoint
		// overload stamps worldView.getPlane(), which mis-tiled upstairs objects seen from the
		// ground floor (e.g. the top half of a ladder pair logged as plane 0).
		net.runelite.api.coords.WorldPoint worldPoint = net.runelite.api.coords.WorldPoint
			.fromLocalInstance(client, object.getLocalLocation(), object.getPlane());
		int templateTile = WorldPointUtil.packWorldPoint(worldPoint);
		boolean door = wall && (action.equalsIgnoreCase("Open") || action.equalsIgnoreCase("Close"));
		boolean oneWayData = false;
		if (door)
		{
			if (doorCovered(templateTile))
			{
				return;
			}
		}
		else
		{
			int coverage = transportCoverage(templateTile);
			if (coverage == 2)
			{
				return;
			}
			oneWayData = coverage == 1;
		}

		String[] actions = new String[0];
		if (composition.getActions() != null)
		{
			actions = java.util.Arrays.stream(composition.getActions())
				.filter(java.util.Objects::nonNull).toArray(String[]::new);
		}
		long key = ((long) templateTile << 20) | object.getId();
		if (ignoredKeys.contains(key))
		{
			return; // operator declared it a false positive
		}
		Finding finding = new Finding(object, templateTile, composition.getName(), action, actions, door, gatingVarbit, oneWayData);
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
					int id = Integer.parseInt(fields[2]);
					logged.add(((long) packed << 20) | id);
					boolean door = "door".equals(fields[1]);
					recorded.add(new RecordedEntry(door, id, fields[3], fields[4],
						fields[5].isEmpty() ? new String[0] : fields[5].split("\\|"), packed,
						door ? doorCovered(packed) : transportCoverage(packed) == 2));
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
		if (finding.oneWayData)
		{
			return; // half-mapped, visible live; capturing the reverse resolves it
		}
		recorded.add(new RecordedEntry(finding.door, finding.object.getId(), finding.name,
			finding.action, finding.actions, finding.packedTemplateTile, false));
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

	/** 2 = rows leave AND arrive nearby (fully mapped), 1 = one direction only, 0 = nothing. */
	private int transportCoverage(int packedTile)
	{
		boolean out = near(transportOriginTiles, packedTile, COVERAGE_RADIUS);
		boolean in = near(transportDestTiles, packedTile, COVERAGE_RADIUS);
		return out && in ? 2 : (out || in ? 1 : 0);
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
