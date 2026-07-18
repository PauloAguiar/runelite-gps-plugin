package gps;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Openable doors and gates the collision map bakes passable, indexed by tile.
 *
 * The collision extraction treats every wall object with wallOrDoor set as open, so the
 * pathfinder happily routes through doors that are currently closed in the game — with no cue
 * that the walk will stop at them. This registry (dumped from the cache by the tooling repo's
 * doorDump task into doors.tsv) lets the path overlay find the door sitting on a walk edge so
 * it can hint "Open Door" while the closed object still stands in the scene.
 *
 * For straight walls (location type 0) the orientation identifies the tile edge the door
 * occupies: 0 = west, 1 = north, 2 = east, 3 = south. A walk between two adjacent tiles is
 * gated by a door on the crossed edge of either tile. Diagonal-wall doors (type 9) block the
 * whole tile, so any edge touching their tile matches.
 */
@Slf4j
public class ClosedDoors
{
	private static final String RESOURCE_PATH = "/doors.tsv";

	private static final int ORIENTATION_WEST = 0;
	private static final int ORIENTATION_NORTH = 1;
	private static final int ORIENTATION_EAST = 2;
	private static final int ORIENTATION_SOUTH = 3;

	private static final int TYPE_WALL_STRAIGHT = 0;

	/**
	 * Game ticks a route is charged per door it walks through: ~1 tick for the door to swing open
	 * plus the stop-and-click reaction overhead. Deliberately priced so that walking a handful of
	 * tiles around a doorway beats going through it. The search charges this on the crossing edge
	 * (see CollisionMap) and the directions bill the same on the "Open X" step, so the route card
	 * ETA and the overlay agree.
	 */
	public static final int COST_TICKS = 3;

	// Edge-mask bits, matching gps.pathfinder.OrdinalDirection's ordinal order (the search tests
	// masks by direction index): W, E, S, N, SW, SE, NW, NE.
	private static final int BIT_WEST = 1;
	private static final int BIT_EAST = 1 << 1;
	private static final int BIT_SOUTH = 1 << 2;
	private static final int BIT_NORTH = 1 << 3;

	public static final class Door
	{
		public final int id;
		public final String name;
		public final int packedPosition;
		public final int type;
		public final int orientation;
		// True when the map data places this door in its OPEN state (its id has "Close").
		// Then presence of the id means passable, absence means someone closed it — the
		// inverse of the usual closed-variant test — and the recorded orientation is the
		// swung-open position, not the doorway edge.
		public final boolean placedOpen;

		Door(int id, String name, int packedPosition, int type, int orientation, boolean placedOpen)
		{
			this.id = id;
			this.name = name;
			this.packedPosition = packedPosition;
			this.type = type;
			this.orientation = orientation;
			this.placedOpen = placedOpen;
		}
	}

	/**
	 * Live scene state of a door, from whichever variant the map places there.
	 */
	public enum State
	{
		OPEN,
		CLOSED,
		UNKNOWN
	}

	public static State state(net.runelite.api.Client client, Door door)
	{
		SceneObjects.Presence presence = SceneObjects.presence(client, door.packedPosition, door.id);
		if (presence == SceneObjects.Presence.OUT_OF_SCENE)
		{
			return State.UNKNOWN;
		}
		boolean present = presence == SceneObjects.Presence.PRESENT;
		return door.placedOpen == present ? State.OPEN : State.CLOSED;
	}

	private static volatile Map<Integer, List<Door>> doorsByTile;
	private static volatile Map<Integer, Integer> edgeMasks;

	private ClosedDoors()
	{
	}

	/**
	 * Packed tile -> bitmask of walk directions (bit i = {@code OrdinalDirection} ordinal i) that
	 * cross a door when leaving that tile. Both sides of every doorway carry a bit, so the search
	 * only ever looks up the tile it is expanding. Diagonal bits follow {@link #doorBetween}'s
	 * semantics: a diagonal step is gated when any of its component cardinal boundaries is.
	 * Doors placed open in the map data are skipped here exactly as in {@link #doorAt}.
	 */
	public static Map<Integer, Integer> edgeMasks()
	{
		get();
		return edgeMasks;
	}

	private static Map<Integer, Integer> buildEdgeMasks(Map<Integer, List<Door>> byTile)
	{
		// Pass 1: cardinal bits, projected onto both tiles of each gated boundary.
		Map<Integer, Integer> cardinal = new HashMap<>();
		for (List<Door> doors : byTile.values())
		{
			for (Door door : doors)
			{
				if (door.placedOpen)
				{
					continue;
				}
				if (door.type == TYPE_WALL_STRAIGHT)
				{
					setEdgeBits(cardinal, door.packedPosition, door.orientation);
				}
				else
				{
					// Diagonal walls and corner pieces gate the whole tile: every boundary.
					for (int orientation = 0; orientation < 4; orientation++)
					{
						setEdgeBits(cardinal, door.packedPosition, orientation);
					}
				}
			}
		}

		// Pass 2: diagonal bits, derived from the cardinal bits of the tile and its neighbours —
		// candidates are every masked tile plus its 8 neighbours (a tile with no cardinal bits of
		// its own can still have a gated diagonal past a neighbouring door corner).
		Map<Integer, Integer> masks = new HashMap<>(cardinal);
		for (Map.Entry<Integer, Integer> entry : cardinal.entrySet())
		{
			int tile = entry.getKey();
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					int candidate = WorldPointUtil.packWorldPoint(
						WorldPointUtil.unpackWorldX(tile) + dx,
						WorldPointUtil.unpackWorldY(tile) + dy,
						WorldPointUtil.unpackWorldPlane(tile));
					int diagonals = diagonalBits(cardinal, candidate);
					if (diagonals != 0)
					{
						masks.merge(candidate, diagonals, (a, b) -> a | b);
					}
				}
			}
		}
		return masks;
	}

	/** Marks the boundary on the door's edge: one bit on its own tile, the opposite on the neighbour. */
	private static void setEdgeBits(Map<Integer, Integer> masks, int tile, int orientation)
	{
		int x = WorldPointUtil.unpackWorldX(tile);
		int y = WorldPointUtil.unpackWorldY(tile);
		int plane = WorldPointUtil.unpackWorldPlane(tile);
		int bit;
		int neighborBit;
		int nx = x;
		int ny = y;
		switch (orientation)
		{
			case ORIENTATION_WEST:
				bit = BIT_WEST;
				neighborBit = BIT_EAST;
				nx--;
				break;
			case ORIENTATION_NORTH:
				bit = BIT_NORTH;
				neighborBit = BIT_SOUTH;
				ny++;
				break;
			case ORIENTATION_EAST:
				bit = BIT_EAST;
				neighborBit = BIT_WEST;
				nx++;
				break;
			default:
				bit = BIT_SOUTH;
				neighborBit = BIT_NORTH;
				ny--;
				break;
		}
		masks.merge(tile, bit, (a, b) -> a | b);
		masks.merge(WorldPointUtil.packWorldPoint(nx, ny, plane), neighborBit, (a, b) -> a | b);
	}

	/**
	 * The four diagonal-direction bits for a tile: a diagonal crossing is gated when either of its
	 * component cardinal boundaries is — from this tile, or around the corner via a neighbour
	 * (mirroring {@link #doorBetween}'s four component checks).
	 */
	private static int diagonalBits(Map<Integer, Integer> cardinal, int tile)
	{
		int x = WorldPointUtil.unpackWorldX(tile);
		int y = WorldPointUtil.unpackWorldY(tile);
		int plane = WorldPointUtil.unpackWorldPlane(tile);
		int own = cardinal.getOrDefault(tile, 0);
		int west = cardinal.getOrDefault(WorldPointUtil.packWorldPoint(x - 1, y, plane), 0);
		int east = cardinal.getOrDefault(WorldPointUtil.packWorldPoint(x + 1, y, plane), 0);
		int south = cardinal.getOrDefault(WorldPointUtil.packWorldPoint(x, y - 1, plane), 0);
		int north = cardinal.getOrDefault(WorldPointUtil.packWorldPoint(x, y + 1, plane), 0);

		int bits = 0;
		// Bit order: SW=4, SE=5, NW=6, NE=7 (OrdinalDirection ordinals).
		if (((own | south) & BIT_WEST) != 0 || ((own | west) & BIT_SOUTH) != 0)
		{
			bits |= 1 << 4;
		}
		if (((own | south) & BIT_EAST) != 0 || ((own | east) & BIT_SOUTH) != 0)
		{
			bits |= 1 << 5;
		}
		if (((own | north) & BIT_WEST) != 0 || ((own | west) & BIT_NORTH) != 0)
		{
			bits |= 1 << 6;
		}
		if (((own | north) & BIT_EAST) != 0 || ((own | east) & BIT_NORTH) != 0)
		{
			bits |= 1 << 7;
		}
		return bits;
	}

	/**
	 * The door gating the walk between two adjacent tiles on the same plane, or null when the
	 * boundary is doorless (or the tiles aren't an adjacent same-plane pair). Diagonal steps
	 * are gated by any of their four component boundaries.
	 */
	public static Door doorBetween(int from, int to)
	{
		int plane = WorldPointUtil.unpackWorldPlane(from);
		if (plane != WorldPointUtil.unpackWorldPlane(to))
		{
			return null;
		}
		int fromX = WorldPointUtil.unpackWorldX(from);
		int fromY = WorldPointUtil.unpackWorldY(from);
		int dx = WorldPointUtil.unpackWorldX(to) - fromX;
		int dy = WorldPointUtil.unpackWorldY(to) - fromY;
		if ((dx == 0 && dy == 0) || Math.abs(dx) > 1 || Math.abs(dy) > 1)
		{
			return null;
		}

		if (dx != 0 && dy != 0)
		{
			// A diagonal step crosses a corner: it is blocked if any of the boundaries of the
			// two cardinal detours around that corner has a closed door.
			int cornerX = WorldPointUtil.packWorldPoint(fromX + dx, fromY, plane);
			int cornerY = WorldPointUtil.packWorldPoint(fromX, fromY + dy, plane);
			Door door = doorBetween(from, cornerX);
			if (door == null)
			{
				door = doorBetween(from, cornerY);
			}
			if (door == null)
			{
				door = doorBetween(cornerX, to);
			}
			if (door == null)
			{
				door = doorBetween(cornerY, to);
			}
			return door;
		}

		int facing = dx > 0 ? ORIENTATION_EAST
			: dx < 0 ? ORIENTATION_WEST
			: dy > 0 ? ORIENTATION_NORTH
			: ORIENTATION_SOUTH;
		Door door = doorAt(from, facing);
		if (door == null)
		{
			door = doorAt(to, opposite(facing));
		}
		return door;
	}

	private static Door doorAt(int packedPosition, int facing)
	{
		List<Door> doors = get().get(packedPosition);
		if (doors == null)
		{
			return null;
		}
		for (Door door : doors)
		{
			// Doors placed OPEN in the map data are open by default. Their rare closed state can't
			// be read reliably from the scene — the swung-open leaf anchors to a neighbouring tile,
			// so a presence check at the doorway misses it and wrongly reports "closed", which
			// false-blocked open doorways. They're skipped: an open door needs no hint, and never
			// blocking one is far better than blocking one that's actually open.
			if (door.placedOpen)
			{
				continue;
			}
			// Straight walls sit on one edge; anything else (diagonal walls, corner pieces) blocks
			// its whole tile, so every edge of the tile matches.
			if (door.type != TYPE_WALL_STRAIGHT || door.orientation == facing)
			{
				return door;
			}
		}
		return null;
	}

	private static int opposite(int orientation)
	{
		return (orientation + 2) % 4;
	}

	private static Map<Integer, List<Door>> get()
	{
		Map<Integer, List<Door>> snapshot = doorsByTile;
		if (snapshot == null)
		{
			synchronized (ClosedDoors.class)
			{
				snapshot = doorsByTile;
				if (snapshot == null)
				{
					snapshot = loadFromResource();
					// Masks are published before doorsByTile so edgeMasks() (which calls get()
					// first) can never observe the registry without them.
					edgeMasks = buildEdgeMasks(snapshot);
					doorsByTile = snapshot;
				}
			}
		}
		return snapshot;
	}

	private static Map<Integer, List<Door>> loadFromResource()
	{
		try (InputStream in = ShortestPathPlugin.class.getResourceAsStream(RESOURCE_PATH))
		{
			if (in == null)
			{
				log.warn("Door registry resource not found at {}; closed-door hints disabled", RESOURCE_PATH);
				return new HashMap<>();
			}
			return parse(new String(Util.readAllBytes(in), StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.error("Failed to load door registry from {}", RESOURCE_PATH, e);
			return new HashMap<>();
		}
	}

	private static Map<Integer, List<Door>> parse(String tsv)
	{
		Map<Integer, List<Door>> result = new HashMap<>();
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
			// id, name, x, y, plane, type, orientation, sizeX, sizeY, state
			String[] fields = line.split("\t");
			if (fields.length < 7)
			{
				log.warn("Skipping malformed door row: '{}'", line);
				continue;
			}
			try
			{
				int id = Integer.parseInt(fields[0]);
				String name = fields[1];
				int packed = WorldPointUtil.packWorldPoint(
					Integer.parseInt(fields[2]),
					Integer.parseInt(fields[3]),
					Integer.parseInt(fields[4]));
				int type = Integer.parseInt(fields[5]);
				int orientation = Integer.parseInt(fields[6]);
				boolean placedOpen = fields.length > 9 && "open".equals(fields[9]);
				result.computeIfAbsent(packed, k -> new ArrayList<>(1))
					.add(new Door(id, name, packed, type, orientation, placedOpen));
			}
			catch (NumberFormatException e)
			{
				log.warn("Skipping door row with non-numeric field: '{}'", line);
			}
		}
		return result;
	}
}
