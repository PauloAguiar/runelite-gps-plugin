package gps;

import gps.transport.Transport;
import gps.transport.TransportType;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Scanner;
import java.util.zip.InflaterInputStream;

/**
 * The sailable ocean and the mooring endpoints, shipped as resources by the tooling repo's
 * WaterMapResourceTest. Lets routing treat a sea tile as a destination: when a target is
 * sailable water, {@link #seaLegTransports} synthesizes the final leg — board at a nearby
 * mooring's land tile, sail straight out to the target. Durations use the same PROVISIONAL
 * speed model as the generated port-to-port rows until the calibration pass.
 */
@lombok.extern.slf4j.Slf4j
public final class SailingSea
{
	private static final double TILES_PER_TICK = 2.0;
	private static final int OVERHEAD_TICKS = 20;
	/** Wet-endpoint flood early stop: only the closest few moorings can appear in sea legs. */
	private static final int SETTLE_ENDPOINTS = 12;

	private static volatile SailingSea instance;

	private final int minX;
	private final int minY;
	private final int width;
	private final int height;
	private final byte[] bits;
	/** Rows of {landX, landY, waterX, waterY}. */
	private final List<int[]> moorings;

	private SailingSea(int minX, int minY, int width, int height, byte[] bits, List<int[]> moorings)
	{
		this.minX = minX;
		this.minY = minY;
		this.width = width;
		this.height = height;
		this.bits = bits;
		this.moorings = moorings;
	}

	private static SailingSea get()
	{
		SailingSea loaded = instance;
		if (loaded == null)
		{
			synchronized (SailingSea.class)
			{
				loaded = instance;
				if (loaded == null)
				{
					loaded = load();
					instance = loaded;
				}
			}
		}
		return loaded;
	}

	private static SailingSea load()
	{
		try (InputStream in = SailingSea.class.getResourceAsStream("/sailing-sea.bin"))
		{
			DataInputStream header = new DataInputStream(in);
			int minX = header.readInt();
			int minY = header.readInt();
			int width = header.readInt();
			int height = header.readInt();
			byte[] bits = new InflaterInputStream(in).readAllBytes();

			List<int[]> moorings = new ArrayList<>();
			try (InputStream tsv = SailingSea.class.getResourceAsStream("/sailing-moorings.tsv");
				Scanner scanner = new Scanner(tsv, "UTF-8"))
			{
				while (scanner.hasNextLine())
				{
					String[] fields = scanner.nextLine().split("\t");
					if (fields.length < 5 || fields[0].startsWith("#") || "type".equals(fields[0]))
					{
						continue;
					}
					moorings.add(new int[]{Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
						Integer.parseInt(fields[3]), Integer.parseInt(fields[4])});
				}
			}
			return new SailingSea(minX, minY, width, height, bits, moorings);
		}
		catch (IOException | RuntimeException e)
		{
			// Missing/corrupt resources must not break routing; sailing sea targets just
			// won't resolve.
			return new SailingSea(0, 0, 0, 0, new byte[0], List.of());
		}
	}

	/** Whether this tile is on the sailable ocean (plane 0 only). */
	public static boolean isSailable(int packed)
	{
		if (WorldPointUtil.unpackWorldPlane(packed) != 0)
		{
			return false;
		}
		SailingSea sea = get();
		int x = WorldPointUtil.unpackWorldX(packed) - sea.minX;
		int y = WorldPointUtil.unpackWorldY(packed) - sea.minY;
		if (x < 0 || y < 0 || x >= sea.width || y >= sea.height)
		{
			return false;
		}
		long index = (long) y * sea.width + x;
		int byteIndex = (int) (index >> 3);
		return byteIndex < sea.bits.length && (sea.bits[byteIndex] & 1 << (index & 7)) != 0;
	}

	/**
	 * The synthesized final sea legs for a sailable-water target: board at each of the
	 * {@code count} closest-by-sea moorings' land tiles and sail out. Distances come from the
	 * wet-endpoint flood ({@link #seaDistances}) — a real Dijkstra over the shipped ocean, so
	 * islands and coastlines are respected, unlike the octile draft. Gated like any SAILING
	 * transport (master toggle).
	 */
	public static List<Transport> seaLegTransports(int targetPacked, int count)
	{
		if (!isSailable(targetPacked))
		{
			return List.of();
		}
		int tx = WorldPointUtil.unpackWorldX(targetPacked);
		int ty = WorldPointUtil.unpackWorldY(targetPacked);
		int[] distances = seaDistances(targetPacked);
		List<int[]> moorings = get().moorings;
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < moorings.size(); i++)
		{
			if (distances[i] != Integer.MAX_VALUE)
			{
				order.add(i);
			}
		}
		order.sort(Comparator.comparingInt(i -> distances[i]));
		List<Transport> legs = new ArrayList<>();
		for (int i : order.subList(0, Math.min(count, order.size())))
		{
			int[] mooring = moorings.get(i);
			int duration = OVERHEAD_TICKS + (int) Math.ceil(
				distances[i] / 100.0 / TILES_PER_TICK);
			legs.add(new Transport.TransportBuilder()
				.origin(WorldPointUtil.packWorldPoint(mooring[0], mooring[1], 0))
				.destination(targetPacked)
				.type(TransportType.SAILING)
				.duration(duration)
				.displayInfo("Sailing: open sea " + tx + "," + ty)
				.build());
		}
		return legs;
	}

	/**
	 * 16-bearing moves: 8 grid steps plus 8 half-wind (knight) steps at cost 224 (euclidean
	 * centitiles, sqrt5 x 100) — cuts the 8-dir staircase overestimate on off-axis legs from
	 * ~8.2% to ~2.8%, matching the offline matrix. Knight steps require both interposed tiles
	 * sailable so no move skips across an obstacle corner.
	 */
	private static final int[][] MOVES = {
		{1, 0, 100}, {-1, 0, 100}, {0, 1, 100}, {0, -1, 100},
		{1, 1, 141}, {1, -1, 141}, {-1, 1, 141}, {-1, -1, 141},
		{2, 1, 224}, {2, -1, 224}, {-2, 1, 224}, {-2, -1, 224},
		{1, 2, 224}, {1, -2, 224}, {-1, 2, 224}, {-1, -2, 224},
	};

	/** The last wet-endpoint flood, keyed by target: generations repeat the same pin. */
	private static volatile int cachedTarget = WorldPointUtil.UNDEFINED;
	private static volatile int[] cachedDistances;

	/**
	 * The wet-endpoint flood: exact sea distance in centitiles from a sailable target to every
	 * mooring's water endpoint (MAX_VALUE where no sea path exists). Dijkstra over the shipped
	 * ocean bitset, stopping early once the {@link #SETTLE_ENDPOINTS} nearest endpoints are
	 * settled — the rest cannot beat them and the sea legs only take the closest few.
	 */
	/**
	 * Array-backed min-heap of packed longs (dist << 32 | gridIndex): natural long ordering is
	 * distance-major, so Dijkstra needs no comparator and — the actual point — no allocation
	 * per node. The boxed PriorityQueue<long[]> it replaces allocated a long[2] per push;
	 * mid-ocean wet floods push millions.
	 */
	static final class LongHeap
	{
		private long[] heap = new long[1 << 12];
		private int size;

		boolean isEmpty()
		{
			return size == 0;
		}

		void push(long value)
		{
			if (size == heap.length)
			{
				heap = java.util.Arrays.copyOf(heap, size * 2);
			}
			int i = size++;
			while (i > 0)
			{
				int parent = (i - 1) >> 1;
				if (heap[parent] <= value)
				{
					break;
				}
				heap[i] = heap[parent];
				i = parent;
			}
			heap[i] = value;
		}

		long pop()
		{
			long top = heap[0];
			long last = heap[--size];
			int i = 0;
			while (true)
			{
				int child = 2 * i + 1;
				if (child >= size)
				{
					break;
				}
				if (child + 1 < size && heap[child + 1] < heap[child])
				{
					child++;
				}
				if (heap[child] >= last)
				{
					break;
				}
				heap[i] = heap[child];
				i = child;
			}
			heap[i] = last;
			return top;
		}

		void clear()
		{
			size = 0;
		}
	}

	/** Scratch distance grid reused across wet floods (24MB: never reallocate per pin). */
	private static int[] wetScratch;
	private static final LongHeap wetHeap = new LongHeap();

	public static synchronized int[] seaDistances(int targetPacked)
	{
		if (targetPacked == cachedTarget && cachedDistances != null)
		{
			return cachedDistances;
		}
		SailingSea sea = get();
		int[] result = new int[sea.moorings.size()];
		java.util.Arrays.fill(result, Integer.MAX_VALUE);
		if (!isSailable(targetPacked) || sea.width == 0)
		{
			return result;
		}
		java.util.Map<Integer, Integer> endpointIndex = new java.util.HashMap<>();
		for (int i = 0; i < sea.moorings.size(); i++)
		{
			int[] m = sea.moorings.get(i);
			endpointIndex.put((m[3] - sea.minY) * sea.width + (m[2] - sea.minX), i);
		}
		if (wetScratch == null || wetScratch.length != sea.width * sea.height)
		{
			wetScratch = new int[sea.width * sea.height];
		}
		int[] dist = wetScratch;
		java.util.Arrays.fill(dist, Integer.MAX_VALUE);
		LongHeap queue = wetHeap;
		queue.clear();
		int start = (WorldPointUtil.unpackWorldY(targetPacked) - sea.minY) * sea.width
			+ (WorldPointUtil.unpackWorldX(targetPacked) - sea.minX);
		dist[start] = 0;
		queue.push(start);
		int settled = 0;
		while (!queue.isEmpty() && settled < SETTLE_ENDPOINTS)
		{
			long head = queue.pop();
			int index = (int) (head & 0xFFFFFFFFL);
			if ((int) (head >>> 32) > dist[index])
			{
				continue;
			}
			Integer mooring = endpointIndex.get(index);
			if (mooring != null && result[mooring] == Integer.MAX_VALUE)
			{
				result[mooring] = dist[index];
				settled++;
			}
			int x = index % sea.width;
			int y = index / sea.width;
			for (int[] move : MOVES)
			{
				int dx = move[0];
				int dy = move[1];
				int nx = x + dx;
				int ny = y + dy;
				if (nx < 0 || ny < 0 || nx >= sea.width || ny >= sea.height
					|| !bit(sea, nx, ny))
				{
					continue;
				}
				if (Math.abs(dx) + Math.abs(dy) == 3
					&& (!bit(sea, x + dx / 2, y + dy / 2)
						|| !bit(sea,
							Math.abs(dx) == 2 ? x + dx / 2 : x + dx,
							Math.abs(dx) == 2 ? y + dy : y + dy / 2)))
				{
					continue;
				}
				int next = ny * sea.width + nx;
				if (dist[index] + move[2] < dist[next])
				{
					dist[next] = dist[index] + move[2];
					queue.push((long) dist[next] << 32 | next);
				}
			}
		}
		cachedDistances = result;
		cachedTarget = targetPacked;
		return result;
	}

	/** Sea tracks for the displayed route's sailing legs, keyed by (from, to). */
	private static final java.util.LinkedHashMap<Long, int[]> trackCache =
		new java.util.LinkedHashMap<Long, int[]>(16, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(java.util.Map.Entry<Long, int[]> eldest)
			{
				return size() > 8;
			}
		};

	/**
	 * The actual sea track between two sailing-leg endpoints, as decimated packed waypoints —
	 * what the overlays draw instead of a straight jump line. Runs a bounded 16-bearing
	 * Dijkstra inside the leg's bounding box (+60 tiles margin) on first request and caches;
	 * null when either endpoint has no sailable water within 6 tiles or no track fits the box
	 * (the caller falls back to the dashed jump).
	 */
	private static final java.util.Set<Long> tracksInFlight =
		java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static final java.util.concurrent.ExecutorService trackExecutor =
		java.util.concurrent.Executors.newSingleThreadExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "gps-sea-track");
			thread.setDaemon(true);
			return thread;
		});

	/**
	 * The overlay-facing track lookup: NEVER computes on the caller's thread. Absent tracks
	 * schedule a background computation and return null — the overlay draws the dashed hint
	 * for a beat and the solid track appears once cached. Long legs (rounding a continent,
	 * threading to Weiss) can take seconds on the full grid; render must not pay that.
	 */
	public static int[] seaPath(int fromPacked, int toPacked)
	{
		long key = (long) fromPacked << 32 | toPacked & 0xFFFFFFFFL;
		synchronized (trackCache)
		{
			if (trackCache.containsKey(key))
			{
				return trackCache.get(key);
			}
		}
		if (tracksInFlight.add(key))
		{
			trackExecutor.submit(() ->
			{
				// try/finally: an exception here must not wedge the key in-flight forever
				// (permanent silent dashes) — cache the null so the overlay falls back cleanly.
				try
				{
					seaPathBlocking(fromPacked, toPacked);
				}
				catch (RuntimeException e)
				{
					log.warn("sea track computation failed for {} -> {}",
						fromPacked, toPacked, e);
					synchronized (trackCache)
					{
						trackCache.put(key, null);
					}
				}
				finally
				{
					tracksInFlight.remove(key);
				}
			});
		}
		return null;
	}

	/** Synchronous computation+cache, for tests and background workers. */
	static int[] seaPathBlocking(int fromPacked, int toPacked)
	{
		long key = (long) fromPacked << 32 | toPacked & 0xFFFFFFFFL;
		synchronized (trackCache)
		{
			if (trackCache.containsKey(key))
			{
				return trackCache.get(key);
			}
		}
		int[] track = computeSeaPath(fromPacked, toPacked);
		synchronized (trackCache)
		{
			trackCache.put(key, track);
		}
		return track;
	}

	private static int[] computeSeaPath(int fromPacked, int toPacked)
	{
		SailingSea sea = get();
		if (sea.width == 0)
		{
			return null;
		}
		int start = trackEndpoint(sea, fromPacked);
		int goal = trackEndpoint(sea, toPacked);
		if (start < 0 || goal < 0 || start == goal)
		{
			return null;
		}
		// Leg bounding box + margin first (cheap, covers open-water legs); a leg that must
		// round a continent — Sunset coast to Kandarin dips far south of its box — retries on
		// the full grid. Both results cache, so the expensive case pays once per leg.
		// Staged margins: 60 covers open-water legs; 600 covers rounding a continent
		// (Sunset coast -> Kandarin dips ~500 south of its leg box). No full-grid stage —
		// a 6M-cell Dijkstra is too heavy for the render thread even once.
		int[] track = computeSeaPathInBox(sea, start, goal, 60);
		if (track == null)
		{
			track = computeSeaPathInBox(sea, start, goal, 600);
		}
		if (track == null)
		{
			// Rounding half the world (Weiss, Grimstone): full grid, seconds — but only ever
			// off-thread and only once per leg, then cached.
			track = computeSeaPathInBox(sea, start, goal, Integer.MAX_VALUE);
		}
		return track;
	}

	private static int[] computeSeaPathInBox(SailingSea sea, int start, int goal, int margin)
	{
		int x0 = margin >= sea.width ? 0
			: Math.max(0, Math.min(start % sea.width, goal % sea.width) - margin);
		int y0 = margin >= sea.height ? 0
			: Math.max(0, Math.min(start / sea.width, goal / sea.width) - margin);
		int x1 = margin >= sea.width ? sea.width - 1
			: Math.min(sea.width - 1, Math.max(start % sea.width, goal % sea.width) + margin);
		int y1 = margin >= sea.height ? sea.height - 1
			: Math.min(sea.height - 1, Math.max(start / sea.width, goal / sea.width) + margin);
		int boxWidth = x1 - x0 + 1;
		int boxHeight = y1 - y0 + 1;
		int[] dist = new int[boxWidth * boxHeight];
		int[] parent = new int[boxWidth * boxHeight];
		java.util.Arrays.fill(dist, Integer.MAX_VALUE);
		java.util.Arrays.fill(parent, -1);
		int boxStart = (start / sea.width - y0) * boxWidth + (start % sea.width - x0);
		int boxGoal = (goal / sea.width - y0) * boxWidth + (goal % sea.width - x0);
		LongHeap queue = new LongHeap();
		dist[boxStart] = 0;
		queue.push(boxStart);
		while (!queue.isEmpty())
		{
			long head = queue.pop();
			int index = (int) (head & 0xFFFFFFFFL);
			if ((int) (head >>> 32) > dist[index])
			{
				continue;
			}
			if (index == boxGoal)
			{
				break;
			}
			int x = index % boxWidth;
			int y = index / boxWidth;
			for (int[] move : MOVES)
			{
				int nx = x + move[0];
				int ny = y + move[1];
				if (nx < 0 || ny < 0 || nx >= boxWidth || ny >= boxHeight
					|| !bit(sea, x0 + nx, y0 + ny))
				{
					continue;
				}
				if (Math.abs(move[0]) + Math.abs(move[1]) == 3
					&& (!bit(sea, x0 + x + move[0] / 2, y0 + y + move[1] / 2)
						|| !bit(sea,
							Math.abs(move[0]) == 2 ? x0 + x + move[0] / 2 : x0 + x + move[0],
							Math.abs(move[0]) == 2 ? y0 + y + move[1] : y0 + y + move[1] / 2)))
				{
					continue;
				}
				int next = ny * boxWidth + nx;
				if (dist[index] + move[2] < dist[next])
				{
					dist[next] = dist[index] + move[2];
					parent[next] = index;
					queue.push((long) dist[next] << 32 | next);
				}
			}
		}
		if (dist[boxGoal] == Integer.MAX_VALUE)
		{
			return null;
		}
		java.util.List<Integer> waypoints = new ArrayList<>();
		for (int at = boxGoal; at != -1; at = parent[at])
		{
			waypoints.add(WorldPointUtil.packWorldPoint(
				sea.minX + x0 + at % boxWidth, sea.minY + y0 + at / boxWidth, 0));
		}
		java.util.Collections.reverse(waypoints);
		int[] track = new int[(waypoints.size() + 2) / 3 + 1];
		int n = 0;
		for (int i = 0; i < waypoints.size(); i += 3)
		{
			track[n++] = waypoints.get(i);
		}
		track[n] = waypoints.get(waypoints.size() - 1);
		return n + 1 == track.length ? track : java.util.Arrays.copyOf(track, n + 1);
	}

	/**
	 * Grid index of the nearest sailable tile within 10 of the endpoint, or -1. Ten, not six:
	 * the mooring dumper pairs land tiles with water up to 8 tiles away (piers), and the track
	 * must reach the water from the same land tile the transport departs from.
	 */
	/**
	 * The sea tile a track starts/ends at. A mooring LAND tile maps to its PAIRED water
	 * endpoint from the shipped data — the radius spiral can snap to a disconnected inland
	 * magenta pocket instead of the harbour (Sunset coast: every track from that port flooded
	 * a puddle, returned null, and dashed the whole port). Water tiles map to themselves.
	 */
	private static int trackEndpoint(SailingSea sea, int packed)
	{
		int x = WorldPointUtil.unpackWorldX(packed);
		int y = WorldPointUtil.unpackWorldY(packed);
		for (int[] mooring : sea.moorings)
		{
			if (mooring[0] == x && mooring[1] == y)
			{
				return (mooring[3] - sea.minY) * sea.width + (mooring[2] - sea.minX);
			}
		}
		if (isSailable(packed))
		{
			return (y - sea.minY) * sea.width + (x - sea.minX);
		}
		return nearestSailable(sea, packed);
	}

	private static int nearestSailable(SailingSea sea, int packed)
	{
		int px = WorldPointUtil.unpackWorldX(packed);
		int py = WorldPointUtil.unpackWorldY(packed);
		for (int radius = 0; radius <= 10; radius++)
		{
			for (int dx = -radius; dx <= radius; dx++)
			{
				for (int dy = -radius; dy <= radius; dy++)
				{
					if (Math.max(Math.abs(dx), Math.abs(dy)) != radius)
					{
						continue;
					}
					int x = px + dx - sea.minX;
					int y = py + dy - sea.minY;
					if (x >= 0 && y >= 0 && x < sea.width && y < sea.height && bit(sea, x, y))
					{
						return y * sea.width + x;
					}
				}
			}
		}
		return -1;
	}

	/**
	 * Sea tiles covered by a sailing leg of the given duration — the inverse of the duration
	 * model every sailing edge (static rows and synthetic legs) is generated with:
	 * OVERHEAD_TICKS + tiles / TILES_PER_TICK. COUPLED to that provisional model: when the
	 * calibration campaign replaces the constants, this inverts whatever replaces them.
	 */
	public static int tilesFromDuration(int durationTicks)
	{
		return (int) Math.round(Math.max(0, durationTicks - OVERHEAD_TICKS) * TILES_PER_TICK);
	}

	/** Local-grid sailability test used by the wet-endpoint flood's move loop. */
	private static boolean bit(SailingSea sea, int x, int y)
	{
		long index = (long) y * sea.width + x;
		return (sea.bits[(int) (index >> 3)] & 1 << (index & 7)) != 0;
	}

	/** Octile distance in centitiles (100 per cardinal step, 141 per diagonal step). */
	private static int octile(int x1, int y1, int x2, int y2)
	{
		int dx = Math.abs(x1 - x2);
		int dy = Math.abs(y1 - y2);
		return 100 * Math.max(dx, dy) + 41 * Math.min(dx, dy);
	}
}
