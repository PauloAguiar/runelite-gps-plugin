package shortestpath;

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

	public static final class Door
	{
		public final int id;
		public final String name;
		public final int packedPosition;
		public final int type;
		public final int orientation;

		Door(int id, String name, int packedPosition, int type, int orientation)
		{
			this.id = id;
			this.name = name;
			this.packedPosition = packedPosition;
			this.type = type;
			this.orientation = orientation;
		}
	}

	private static volatile Map<Integer, List<Door>> doorsByTile;

	private ClosedDoors()
	{
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
			// Straight walls sit on one edge; anything else (diagonal walls, corner pieces)
			// blocks its whole tile, so every edge of the tile matches.
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
			// id, name, x, y, plane, type, orientation, sizeX, sizeY
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
				result.computeIfAbsent(packed, k -> new ArrayList<>(1))
					.add(new Door(id, name, packed, type, orientation));
			}
			catch (NumberFormatException e)
			{
				log.warn("Skipping door row with non-numeric field: '{}'", line);
			}
		}
		return result;
	}
}
