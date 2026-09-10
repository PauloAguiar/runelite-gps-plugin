package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathStep;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The arrival zone (plan step L19, out of the plugin class): every tile within the finish
 * distance of the destination in WALKING steps, a flood from the displayed path's end over the
 * collision map with the pathfinder's own movement rules, so a tile across a wall or fence is
 * not part of the zone. Standing on any of these tiles completes the journey; the debug overlay
 * renders exactly this set. Cached per (path end, finish distance): recomputed only when the
 * displayed route's end or the config changes, then read every tick and every frame.
 */
final class ArrivalZone
{
	// One immutable holder for the zone and its key: the render thread and the client thread
	// both read it, and two fields could publish one route's key beside another's zone.
	private static final class Cache
	{
		final int end;
		final int radius;
		final Set<Integer> zone;

		Cache(int end, int radius, Set<Integer> zone)
		{
			this.end = end;
			this.radius = radius;
			this.zone = zone;
		}
	}

	private final Supplier<CollisionMap> map;
	private volatile Cache cache = new Cache(WorldPointUtil.UNDEFINED, Integer.MIN_VALUE, Set.of());

	ArrivalZone(Supplier<CollisionMap> map)
	{
		this.map = map;
	}

	/**
	 * The zone around the path's end, {@code radius} walking steps wide. Empty when there is no
	 * path or the finish distance is negative (never finish).
	 */
	Set<Integer> tiles(List<PathStep> path, int radius)
	{
		if (path == null || path.isEmpty() || radius < 0)
		{
			return Set.of();
		}
		int end = path.get(path.size() - 1).getPackedPosition();
		Cache cached = cache;
		if (end != cached.end || radius != cached.radius)
		{
			cached = new Cache(end, radius, flood(map.get(), end, radius));
			cache = cached;
		}
		return cached.zone;
	}

	/**
	 * Breadth-first flood from {@code end} over walkable edges, up to {@code maxSteps} moves.
	 * Diagonal moves mirror {@link CollisionMap}'s corner rules (both cardinals of the corner
	 * must be open on both sides), so the zone matches where the player can actually walk.
	 * Without a map, or with no steps, the zone is the end tile alone.
	 */
	static Set<Integer> flood(CollisionMap map, int end, int maxSteps)
	{
		Set<Integer> zone = new HashSet<>();
		zone.add(end);
		if (map == null || maxSteps <= 0)
		{
			return zone;
		}
		final int plane = WorldPointUtil.unpackWorldPlane(end);
		List<Integer> frontier = new ArrayList<>();
		frontier.add(end);
		for (int depth = 0; depth < maxSteps && !frontier.isEmpty(); depth++)
		{
			List<Integer> next = new ArrayList<>();
			for (int tile : frontier)
			{
				final int x = WorldPointUtil.unpackWorldX(tile);
				final int y = WorldPointUtil.unpackWorldY(tile);
				final boolean n = map.n(x, y, plane);
				final boolean s = map.s(x, y, plane);
				final boolean e = map.e(x, y, plane);
				final boolean w = map.w(x, y, plane);
				grow(zone, next, x, y + 1, plane, n);
				grow(zone, next, x, y - 1, plane, s);
				grow(zone, next, x + 1, y, plane, e);
				grow(zone, next, x - 1, y, plane, w);
				grow(zone, next, x + 1, y + 1, plane, n && e && map.e(x, y + 1, plane) && map.n(x + 1, y, plane));
				grow(zone, next, x - 1, y + 1, plane, n && w && map.w(x, y + 1, plane) && map.n(x - 1, y, plane));
				grow(zone, next, x + 1, y - 1, plane, s && e && map.e(x, y - 1, plane) && map.s(x + 1, y, plane));
				grow(zone, next, x - 1, y - 1, plane, s && w && map.w(x, y - 1, plane) && map.s(x - 1, y, plane));
			}
			frontier = next;
		}
		return zone;
	}

	private static void grow(Set<Integer> zone, List<Integer> next, int x, int y, int plane, boolean open)
	{
		if (!open)
		{
			return;
		}
		int packed = WorldPointUtil.packWorldPoint(x, y, plane);
		if (zone.add(packed))
		{
			next.add(packed);
		}
	}
}
