package gps;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import gps.transport.Transport;
import gps.transport.TransportType;

/**
 * The GPS search index: named places and curated amenities (bank, altar, water source,
 * furnace, ...) a player can navigate to by name or as "nearest X". The bulk is a bundled
 * resource dumped from the cache (destinations.tsv); fairy rings and spirit trees are added
 * from the live transport data because their world objects carry no name in the cache.
 */
@Slf4j
public final class Destinations
{
	private static final String RESOURCE_PATH = "/destinations.tsv";
	// Hand-curated additions (agility courses, skilling spots) kept OUT of the machine-dumped
	// resource above, so regenerating the dump never erases them.
	private static final String CURATED_PATH = "/destinations-curated.tsv";
	// Locations imported from the OSRS wiki's infobox map pins (see WikiLocationDumperTest):
	// searchable places that have no in-game world-map label, deduplicated against the above.
	private static final String WIKI_PATH = "/destinations-wiki.tsv";

	/**
	 * One searchable destination: a category, a display name, a representative packed world tile,
	 * and every target tile it stands for (one for a place; all access tiles for a named amenity
	 * such as "Falador Bank", which routes to the nearest of its booths).
	 */
	public static final class Entry
	{
		public final String category;
		public final String name;
		public final int packedPosition;
		public final Set<Integer> tiles;
		/** The "Nearest X" option a query row was synthesized from; null for ordinary entries. */
		public final NearestOption nearest;

		Entry(String category, String name, int packedPosition)
		{
			this(category, name, packedPosition, null, null);
		}

		Entry(String category, String name, int packedPosition, Set<Integer> tiles, NearestOption nearest)
		{
			this.category = category;
			this.name = name;
			this.packedPosition = packedPosition;
			this.tiles = tiles != null ? tiles
				: (packedPosition == WorldPointUtil.UNDEFINED ? Set.of() : Set.of(packedPosition));
			this.nearest = nearest;
		}
	}

	/** Categories the name search lists as-is (one tile each). */
	private static final Set<String> NAMED_CATEGORIES = Set.of(
		"place", "landmark", "dungeon", "minigame", "training", "fairy_ring", "spirit_tree");
	/** Amenity categories the name search groups by site name (plan step N11). */
	private static final Set<String> AMENITY_CATEGORIES = Set.of(
		"bank", "altar", "water", "furnace", "anvil", "range", "spinning_wheel", "pottery");

	/** A "nearest X" amenity category offered as a quick option: its id and its display label. */
	public static final class NearestOption
	{
		public final String id;
		public final String label;

		NearestOption(String id, String label)
		{
			this.id = id;
			this.label = label;
		}
	}

	/**
	 * The amenity categories offered as "nearest X" options, in display order. Picking one routes
	 * to ALL tiles of that category at once — the alternative-routes engine then ranks the shortest
	 * paths (using available teleports), which may lead to different sites.
	 */
	public static final List<NearestOption> NEAREST_OPTIONS = List.of(
		new NearestOption("bank", "Bank"),
		new NearestOption("bank_round_trip", "Bank (and back)"),
		new NearestOption("altar", "Altar"),
		new NearestOption("water", "Water source"),
		new NearestOption("furnace", "Furnace"),
		new NearestOption("anvil", "Anvil"),
		new NearestOption("range", "Cooking range"),
		new NearestOption("spinning_wheel", "Spinning wheel"),
		new NearestOption("pottery", "Potter's wheel"),
		new NearestOption("fairy_ring", "Fairy ring"),
		new NearestOption("spirit_tree", "Spirit tree"));

	/** The named places (cities/towns/landmarks) from the bundled resource. */
	public static List<Entry> places()
	{
		List<Entry> places = new ArrayList<>();
		for (Entry entry : resourceEntries())
		{
			if ("place".equals(entry.category))
			{
				places.add(entry);
			}
		}
		return places;
	}

	/**
	 * The entries offered by the name search: named places, landmarks, dungeons, minigames and
	 * training spots as they are, fairy rings and spirit trees by their code, and every amenity
	 * site ONCE under its name with all of its access tiles ("Falador Bank" is one entry whose
	 * route ends at whichever booth is nearest). Before plan step N11 amenities were left out and
	 * "Falador bank" found nothing.
	 */
	public static List<Entry> searchable(PrimitiveIntHashMap<Transport[]> transports)
	{
		List<Entry> out = new ArrayList<>();
		Map<String, Entry> amenityFirst = new LinkedHashMap<>();
		Map<String, List<Integer>> amenityTiles = new HashMap<>();
		for (Entry entry : all(transports))
		{
			if (NAMED_CATEGORIES.contains(entry.category))
			{
				out.add(entry);
			}
			else if (AMENITY_CATEGORIES.contains(entry.category))
			{
				String key = entry.category + "\t" + entry.name;
				amenityFirst.putIfAbsent(key, entry);
				amenityTiles.computeIfAbsent(key, k -> new ArrayList<>()).add(entry.packedPosition);
			}
		}
		for (Map.Entry<String, Entry> grouped : amenityFirst.entrySet())
		{
			Entry first = grouped.getValue();
			out.add(new Entry(first.category, first.name, first.packedPosition,
				Set.copyOf(amenityTiles.get(grouped.getKey())), null));
		}
		return out;
	}

	/**
	 * The "nearest X" option a query asks for: a leading "nearest" followed by an option's label
	 * or id ("nearest altar", "nearest bank and back"), or a bare category word ("bank"). Null for
	 * anything else, including named searches ("falador bank").
	 */
	public static NearestOption parseNearest(String query)
	{
		String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
		String rest = q.startsWith("nearest ") ? q.substring("nearest ".length()).trim() : q;
		if (rest.isEmpty())
		{
			return null;
		}
		for (NearestOption option : NEAREST_OPTIONS)
		{
			String label = option.label.toLowerCase(Locale.ROOT);
			if (rest.equals(option.id.replace('_', ' ')) || rest.equals(label)
				|| rest.equals(label.replace("(", "").replace(")", "")))
			{
				return option;
			}
		}
		return null;
	}

	/** A category as the panel names it ("range" is "Cooking range"); title-cased otherwise. */
	public static String categoryLabel(String category)
	{
		for (NearestOption option : NEAREST_OPTIONS)
		{
			if (option.id.equals(category))
			{
				return option.label;
			}
		}
		String words = category.replace('_', ' ');
		return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
	}

	/**
	 * Every target tile of an amenity category — the target set for its "nearest X" search. The
	 * bundled rows ARE the access tiles: the dump emits, per amenity, the perimeter tiles whose
	 * shared edge with the object carries no wall (the collision map can't make that distinction
	 * at runtime — walls and object-filled neighbours clear the same edge bits — so a blind 3x3
	 * perimeter let "nearest water source" routes end one tile across a wall, OUTSIDE the
	 * building the trough stands in). Tiles occupied by other objects are harmless: never
	 * settled. Fairy rings and spirit trees come from the live transport data; their origins are
	 * the standing tiles themselves.
	 */
	public static Set<Integer> tilesForCategory(String category, PrimitiveIntHashMap<Transport[]> transports)
	{
		// The round-trip variant targets the same sites; the routing mode differs, not the tiles.
		String effective = "bank_round_trip".equals(category) ? "bank" : category;
		Set<Integer> tiles = new HashSet<>();
		boolean transportBacked = "fairy_ring".equals(effective) || "spirit_tree".equals(effective);
		for (Entry entry : transportBacked ? all(transports) : resourceEntries())
		{
			if (effective.equals(entry.category))
			{
				tiles.add(entry.packedPosition);
			}
		}
		return tiles;
	}

	/**
	 * The target set for an arbitrary single tile (a map pin, a Quest Helper NPC tile): the tile
	 * itself when it's walkable — exact semantics preserved — otherwise the tile plus the nearest
	 * ring of walkable tiles around it (radius up to {@code MAX_WALKABLE_RING}). A pin dropped on a
	 * fence, a piece of furniture or an NPC's own tile can never be settled by the search, which
	 * otherwise explores the entire map and falls back to a closest-tile path (the same pathology
	 * the amenity perimeter fixes). If no walkable tile exists within range (a pin mid-pond), the
	 * original tile is returned alone and the search keeps the old closest-tile behaviour.
	 */
	public static Set<Integer> walkableTargets(gps.pathfinder.CollisionMap map, int packed)
	{
		return walkableTargets(map, packed, null);
	}

	/**
	 * As above, but when {@code transportOrigin} is given and the blocked tile has a known
	 * transport ORIGIN within range, the origin tiles alone become the target set. A Quest
	 * Helper target on a cave's footprint would otherwise expand to whichever adjacent tile
	 * the route approaches first — including the BACK of the cave (captured at Trollheim's
	 * Troll Stronghold entrance: routed behind the mouth, then the game walked the long way
	 * around). The mapped transport's origin tile is, by definition, the interactable side.
	 */
	public static Set<Integer> walkableTargets(gps.pathfinder.CollisionMap map, int packed,
		java.util.function.IntPredicate transportOrigin)
	{
		packed = remapTemplateOnlyZones(packed);
		Set<Integer> land = walkableLandTargets(map, packed, transportOrigin);
		// A pin on SAILABLE water is a sea destination (a wreck, a fishing spot, an ocean
		// label): the water tile itself stays in the target set - aboard, the sea legs settle
		// it exactly (seaLegTransports + wet arrival) - alongside the land ring, which serves
		// the same pin on foot. Dropping the water tile disarmed the entire boat route: the
		// search only ever saw the shore (capture 20260827-220818).
		if (SailingSea.isSailable(packed) && !land.contains(packed))
		{
			Set<Integer> both = new HashSet<>(land);
			both.add(packed);
			return both;
		}
		return land;
	}

	private static Set<Integer> walkableLandTargets(gps.pathfinder.CollisionMap map, int packed,
		java.util.function.IntPredicate transportOrigin)
	{
		final int x = WorldPointUtil.unpackWorldX(packed);
		final int y = WorldPointUtil.unpackWorldY(packed);
		final int plane = WorldPointUtil.unpackWorldPlane(packed);
		if (map == null || !map.isBlocked(x, y, plane))
		{
			return Set.of(packed);
		}
		// Pass 1: a transport origin near the blocked tile beats plain proximity, even when it
		// sits a radius further out than the first walkable ring.
		if (transportOrigin != null)
		{
			for (int radius = 1; radius <= MAX_WALKABLE_RING; radius++)
			{
				Set<Integer> origins = new HashSet<>();
				for (int dx = -radius; dx <= radius; dx++)
				{
					for (int dy = -radius; dy <= radius; dy++)
					{
						if (Math.max(Math.abs(dx), Math.abs(dy)) != radius)
						{
							continue;
						}
						int tile = WorldPointUtil.packWorldPoint(x + dx, y + dy, plane);
						if (!map.isBlocked(x + dx, y + dy, plane) && transportOrigin.test(tile))
						{
							origins.add(tile);
						}
					}
				}
				if (!origins.isEmpty())
				{
					return origins;
				}
			}
		}
		for (int radius = 1; radius <= MAX_WALKABLE_RING; radius++)
		{
			Set<Integer> ring = new HashSet<>();
			for (int dx = -radius; dx <= radius; dx++)
			{
				for (int dy = -radius; dy <= radius; dy++)
				{
					if (Math.max(Math.abs(dx), Math.abs(dy)) != radius)
					{
						continue;
					}
					if (!map.isBlocked(x + dx, y + dy, plane))
					{
						ring.add(WorldPointUtil.packWorldPoint(x + dx, y + dy, plane));
					}
				}
			}
			if (!ring.isEmpty())
			{
				ring.add(packed);
				return ring;
			}
		}
		return Set.of(packed);
	}

	private static final int MAX_WALKABLE_RING = 5;

	/** A template-only zone: destinations inside the box snap to the anchor tile. */
	static final class Remap
	{
		final int minX;
		final int minY;
		final int maxX;
		final int maxY;
		final int plane;
		final int anchor;
		final String label;

		Remap(int minX, int minY, int maxX, int maxY, int plane, int anchor, String label)
		{
			this.minX = minX;
			this.minY = minY;
			this.maxX = maxX;
			this.maxY = maxY;
			this.plane = plane;
			this.anchor = anchor;
			this.label = label;
		}
	}

	private static List<Remap> remaps;

	/**
	 * Some map areas are pure scenery: instance templates visible from outside (Iban's Temple
	 * interior), display-only floors. A player can never stand there, so a destination inside
	 * one — a clue step, a Quest Helper tile, a curious map pin — would either error as
	 * unreachable or route to fake geometry. Those targets snap to the zone's anchor: the
	 * nearest known-good tile, usually the entrance. Data: destination-remaps.tsv.
	 */
	static int remapTemplateOnlyZones(int packed)
	{
		final int x = WorldPointUtil.unpackWorldX(packed);
		final int y = WorldPointUtil.unpackWorldY(packed);
		final int plane = WorldPointUtil.unpackWorldPlane(packed);
		for (Remap remap : loadRemaps())
		{
			if (plane == remap.plane && x >= remap.minX && x <= remap.maxX
				&& y >= remap.minY && y <= remap.maxY)
			{
				return remap.anchor;
			}
		}
		return packed;
	}

	private static synchronized List<Remap> loadRemaps()
	{
		if (remaps != null)
		{
			return remaps;
		}
		List<Remap> loaded = new ArrayList<>();
		try (InputStream in = ShortestPathPlugin.class.getResourceAsStream("/destination-remaps.tsv"))
		{
			if (in != null)
			{
				try (java.util.Scanner scanner = new java.util.Scanner(in, "UTF-8"))
				{
					while (scanner.hasNextLine())
					{
						String line = scanner.nextLine();
						if (line.startsWith("#") || line.isBlank())
						{
							continue;
						}
						String[] f = line.split("\t");
						if (f.length < 9)
						{
							continue;
						}
						loaded.add(new Remap(Integer.parseInt(f[0].trim()), Integer.parseInt(f[1].trim()),
							Integer.parseInt(f[2].trim()), Integer.parseInt(f[3].trim()),
							Integer.parseInt(f[4].trim()),
							WorldPointUtil.packWorldPoint(Integer.parseInt(f[5].trim()),
								Integer.parseInt(f[6].trim()), Integer.parseInt(f[7].trim())),
							f[8].trim()));
					}
				}
			}
		}
		catch (IOException | NumberFormatException e)
		{
			// A malformed remap file must never break routing — fall through with what parsed.
		}
		remaps = loaded;
		return remaps;
	}

	private static volatile List<Entry> resourceEntries;

	private Destinations()
	{
	}

	/** The static place + amenity entries from the bundled resource (loaded once). */
	public static List<Entry> resourceEntries()
	{
		List<Entry> snapshot = resourceEntries;
		if (snapshot == null)
		{
			synchronized (Destinations.class)
			{
				snapshot = resourceEntries;
				if (snapshot == null)
				{
					snapshot = load();
					resourceEntries = snapshot;
				}
			}
		}
		return snapshot;
	}

	/**
	 * The static entries plus destinations derived from the live transport data: one per fairy ring
	 * and spirit tree (deduplicated by origin — these objects have no cache name, so they're named by
	 * their transport display info like "Fairy ring AIQ"), and one per minigame (deduplicated by name,
	 * placed at the minigame's teleport destination and named from its display info, e.g. "Castle Wars").
	 */
	public static List<Entry> all(PrimitiveIntHashMap<Transport[]> transports)
	{
		List<Entry> entries = new ArrayList<>(resourceEntries());
		if (transports == null)
		{
			return entries;
		}
		PrimitiveIntList seen = new PrimitiveIntList();
		// Seed with the bundled minigame entries (curated rows), so a transport-derived duplicate
		// of the same minigame isn't added twice.
		Set<String> seenMinigames = new HashSet<>();
		for (Entry entry : entries)
		{
			if ("minigame".equals(entry.category))
			{
				seenMinigames.add(entry.name.toLowerCase(java.util.Locale.ROOT));
			}
		}
		for (int key : transports.keys())
		{
			Transport[] set = transports.get(key);
			if (set == null)
			{
				continue;
			}
			for (Transport transport : set)
			{
				TransportType type = transport.getType();
				if (type == TransportType.FAIRY_RING || type == TransportType.SPIRIT_TREE)
				{
					int origin = transport.getOrigin();
					if (origin == Transport.UNDEFINED_ORIGIN || seen.contains(origin))
					{
						continue;
					}
					seen.add(origin);
					String label = type == TransportType.FAIRY_RING ? "Fairy ring" : "Spirit tree";
					String info = transport.getDisplayInfo();
					String name = info != null && !info.isEmpty() ? label + " " + info : label;
					entries.add(new Entry(type == TransportType.FAIRY_RING ? "fairy_ring" : "spirit_tree",
						name, origin));
				}
				else if (type == TransportType.TELEPORTATION_MINIGAME)
				{
					int destination = transport.getDestination();
					String name = minigameName(transport.getDisplayInfo());
					if (destination == WorldPointUtil.UNDEFINED
						|| !seenMinigames.add(name.toLowerCase(java.util.Locale.ROOT)))
					{
						continue;
					}
					entries.add(new Entry("minigame", name, destination));
				}
			}
		}
		return entries;
	}

	/**
	 * A clean minigame label from a minigame-teleport's display info: drops the "Minigame Teleport"
	 * boilerplate and the numbered-variant prefix, e.g. "Castle Wars Minigame Teleport" -&gt;
	 * "Castle Wars", and "Rat Pits Minigame Teleport: 1. Ardougne" -&gt; "Rat Pits: Ardougne".
	 */
	private static String minigameName(String info)
	{
		if (info == null || info.isEmpty())
		{
			return "Minigame";
		}
		String name = info.replace("Minigame Teleport", " ").replaceAll("\\s+", " ").trim();
		name = name.replace(" :", ":").replaceAll(":\\s*\\d+\\.\\s*", ": ");
		return name.trim();
	}

	/** Minigame-only interior boxes (destination-exclusions.tsv): pins inside them are dropped. */
	private static final String EXCLUSIONS_PATH = "/destination-exclusions.tsv";

	private static final class ExclusionZone
	{
		final int minX, minY, maxX, maxY, plane;

		ExclusionZone(int minX, int minY, int maxX, int maxY, int plane)
		{
			this.minX = minX;
			this.minY = minY;
			this.maxX = maxX;
			this.maxY = maxY;
			this.plane = plane;
		}
	}

	private static volatile List<ExclusionZone> exclusionZones;

	/**
	 * Whether a destination pin sits inside a minigame-only interior (Trouble Brewing's water
	 * sources): such amenities serve the activity, not overworld routing, so the pin is dropped
	 * from search, amenity sets and the reachability audits alike (field call 2026-08-27:
	 * "there is no point in routing to it").
	 */
	static boolean insideExcludedZone(int packed)
	{
		final int x = WorldPointUtil.unpackWorldX(packed);
		final int y = WorldPointUtil.unpackWorldY(packed);
		final int plane = WorldPointUtil.unpackWorldPlane(packed);
		for (ExclusionZone zone : loadExclusionZones())
		{
			if (plane == zone.plane && x >= zone.minX && x <= zone.maxX
				&& y >= zone.minY && y <= zone.maxY)
			{
				return true;
			}
		}
		return false;
	}

	private static synchronized List<ExclusionZone> loadExclusionZones()
	{
		if (exclusionZones != null)
		{
			return exclusionZones;
		}
		List<ExclusionZone> loaded = new ArrayList<>();
		try (InputStream in = ShortestPathPlugin.class.getResourceAsStream(EXCLUSIONS_PATH))
		{
			if (in != null)
			{
				try (java.util.Scanner scanner = new java.util.Scanner(in, "UTF-8"))
				{
					while (scanner.hasNextLine())
					{
						String line = scanner.nextLine();
						if (line.isEmpty() || line.startsWith("#"))
						{
							continue;
						}
						String[] f = line.split("	");
						if (f.length < 5)
						{
							continue;
						}
						loaded.add(new ExclusionZone(Integer.parseInt(f[0].trim()), Integer.parseInt(f[1].trim()),
							Integer.parseInt(f[2].trim()), Integer.parseInt(f[3].trim()), Integer.parseInt(f[4].trim())));
					}
				}
			}
		}
		catch (IOException e)
		{
			log.error("Failed to load destination exclusions", e);
		}
		exclusionZones = loaded;
		return loaded;
	}

	private static List<Entry> load()
	{
		List<Entry> entries = loadResource(RESOURCE_PATH);
		entries.addAll(loadResource(CURATED_PATH));
		entries.addAll(loadResource(WIKI_PATH));
		entries.removeIf(entry -> insideExcludedZone(entry.packedPosition));
		// Entries inside a template-only/minigame-arena remap zone adopt their anchor at load,
		// so search results and the reachability audits both see the real-world stand-in
		// ("Pest Control" lands on the Void outpost, not inside the lander-only arena).
		for (int i = 0; i < entries.size(); i++)
		{
			Entry entry = entries.get(i);
			int remapped = remapTemplateOnlyZones(entry.packedPosition);
			if (remapped != entry.packedPosition)
			{
				entries.set(i, new Entry(entry.category, entry.name, remapped));
			}
		}
		return entries;
	}

	private static List<Entry> loadResource(String path)
	{
		try (InputStream in = ShortestPathPlugin.class.getResourceAsStream(path))
		{
			if (in == null)
			{
				log.warn("Destinations resource not found at {}", path);
				return new ArrayList<>();
			}
			return parse(new String(Util.readAllBytes(in), StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.error("Failed to load destinations from {}", path, e);
			return new ArrayList<>();
		}
	}

	private static List<Entry> parse(String tsv)
	{
		List<Entry> entries = new ArrayList<>();
		boolean header = true;
		for (String line : tsv.split("\\R"))
		{
			if (header)
			{
				header = false;
				continue;
			}
			if (line.isEmpty())
			{
				continue;
			}
			// category, name, x, y, plane [, verified_source, verified_date] — the trailing
			// verification columns are metadata for maintenance and are ignored at load time.
			String[] fields = line.split("\t");
			if (fields.length < 5)
			{
				continue;
			}
			try
			{
				int packed = WorldPointUtil.packWorldPoint(
					Integer.parseInt(fields[2]), Integer.parseInt(fields[3]), Integer.parseInt(fields[4]));
				entries.add(new Entry(fields[0], fields[1], packed));
			}
			catch (NumberFormatException e)
			{
				log.warn("Skipping malformed destination row: '{}'", line);
			}
		}
		return entries;
	}
}
