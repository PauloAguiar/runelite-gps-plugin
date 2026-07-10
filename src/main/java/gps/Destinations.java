package gps;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

	/** One searchable destination: a category, a display name, and a packed world tile. */
	public static final class Entry
	{
		public final String category;
		public final String name;
		public final int packedPosition;

		Entry(String category, String name, int packedPosition)
		{
			this.category = category;
			this.name = name;
			this.packedPosition = packedPosition;
		}
	}

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
	 * The entries offered by the name search: named places and dungeons (from the bundled resource)
	 * plus minigames (from the live transport data). Amenities like banks and altars are reached via
	 * "nearest X" instead, so they're left out here to keep results focused.
	 */
	public static List<Entry> searchable(PrimitiveIntHashMap<Transport[]> transports)
	{
		List<Entry> out = new ArrayList<>();
		for (Entry entry : all(transports))
		{
			if ("place".equals(entry.category) || "landmark".equals(entry.category)
				|| "dungeon".equals(entry.category) || "minigame".equals(entry.category)
				|| "training".equals(entry.category))
			{
				out.add(entry);
			}
		}
		return out;
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
		final int x = WorldPointUtil.unpackWorldX(packed);
		final int y = WorldPointUtil.unpackWorldY(packed);
		final int plane = WorldPointUtil.unpackWorldPlane(packed);
		if (map == null || !map.isBlocked(x, y, plane))
		{
			return Set.of(packed);
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
		Set<String> seenMinigames = new HashSet<>();
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

	private static List<Entry> load()
	{
		List<Entry> entries = loadResource(RESOURCE_PATH);
		entries.addAll(loadResource(CURATED_PATH));
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
