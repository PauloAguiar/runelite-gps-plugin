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
	/** Deliberate fixed approximation, not a placeholder: hulls cruise 1.5 (wooden) to 3.0
	 * (rosewood) tiles/tick per the wiki hull table, so the mid-table 2.0 keeps every ETA
	 * within ~33% without per-boat calibration machinery. */
	private static final double TILES_PER_TICK = 2.0;
	/** Board + cast off + moor + disembark, in ticks — every sailing edge's fixed cost.
	 * Shared with RouteDirections, which splits a leg into embark + sail steps. */
	static final int OVERHEAD_TICKS = 20;
	/** Wet-endpoint flood early stop: only the closest few moorings can appear in sea legs. */
	private static final int SETTLE_ENDPOINTS = 12;
	/** Wet floods keep going until this many WALK-REACHABLE ports settle (field anchors). */
	private static final int REACHABLE_QUOTA = 4;
	/** Sea-leg sets always include at least this many walk-reachable ports (field anchors). */
	private static final int REACHABLE_LEGS = 3;
	/** Moor + step off, for legs that START aboard (the full cycle is OVERHEAD_TICKS). */
	private static final int DISEMBARK_TICKS = 8;

	private static volatile SailingSea instance;

	private final int minX;
	private final int minY;
	private final int width;
	private final int height;
	private final byte[] bits;
	/** Rows of {landX, landY, waterX, waterY, walkReachable(0/1)}. */
	private final List<int[]> moorings;
	/** Port display names, parallel to {@link #moorings} (shipped, nearest-pin derived). */
	private final List<String> mooringNames;
	/** Water-endpoint grid index -> mooring index, precomputed at load: the wet flood
	 * consults it once per popped node (millions on mid-ocean floods), so no boxed keys. */
	private final PrimitiveIntHashMap<Integer> endpointIndex;
	/** Packed land tiles of every mooring — the origins of boarding legs, for the
	 * boat-location gate to tell "boards a boat here" from "already under way". */
	private final java.util.Set<Integer> mooringLandTiles;
	/** Port-to-port sea distances in centitiles (shipped matrix, MAX_VALUE = unconnected),
	 * indexed like {@link #moorings} — lets partial wet floods reach EVERY port by
	 * composing through a settled near one. Null when the resource is absent. */
	private int[][] portMatrix;

	private SailingSea(int minX, int minY, int width, int height, byte[] bits,
		List<int[]> moorings, List<String> mooringNames)
	{
		this.minX = minX;
		this.minY = minY;
		this.width = width;
		this.height = height;
		this.bits = bits;
		this.moorings = moorings;
		this.mooringNames = mooringNames;
		this.endpointIndex = new PrimitiveIntHashMap<>(Math.max(1, moorings.size() * 2));
		this.mooringLandTiles = new java.util.HashSet<>();
		for (int i = 0; i < moorings.size(); i++)
		{
			int[] mooring = moorings.get(i);
			endpointIndex.put((mooring[3] - minY) * width + (mooring[2] - minX), i);
			mooringLandTiles.add(WorldPointUtil.packWorldPoint(mooring[0], mooring[1], 0));
		}
	}

	/** Whether the tile is a mooring's boarding (land) tile — the origin class of every
	 * static sailing row and synthetic embark leg. */
	public static boolean isMooringLand(int packed)
	{
		return get().mooringLandTiles.contains(packed);
	}

	/** The shipped port-to-port matrix, re-keyed from land tiles to mooring indices; null
	 * (composition disabled, floods stand alone) when the resource is missing. */
	private static int[][] loadPortMatrix(List<int[]> moorings)
	{
		java.util.Map<Long, Integer> byLand = new java.util.HashMap<>();
		for (int i = 0; i < moorings.size(); i++)
		{
			byLand.put((long) moorings.get(i)[0] << 16 | moorings.get(i)[1], i);
		}
		try (InputStream tsv = SailingSea.class.getResourceAsStream("/sailing-sea-matrix.tsv");
			Scanner scanner = new Scanner(tsv, "UTF-8"))
		{
			int[][] matrix = new int[moorings.size()][moorings.size()];
			for (int[] row : matrix)
			{
				java.util.Arrays.fill(row, Integer.MAX_VALUE);
			}
			for (int i = 0; i < matrix.length; i++)
			{
				matrix[i][i] = 0;
			}
			while (scanner.hasNextLine())
			{
				String[] fields = scanner.nextLine().split("\t");
				if (fields.length < 5 || fields[0].startsWith("#") || "fromLandX".equals(fields[0]))
				{
					continue;
				}
				Integer from = byLand.get(Long.parseLong(fields[0]) << 16 | Long.parseLong(fields[1]));
				Integer to = byLand.get(Long.parseLong(fields[2]) << 16 | Long.parseLong(fields[3]));
				if (from != null && to != null)
				{
					matrix[from][to] = Integer.parseInt(fields[4]);
				}
			}
			return matrix;
		}
		catch (IOException | RuntimeException e)
		{
			return null;
		}
	}

	/**
	 * Sea distance from {@code packed} to EVERY mooring: the partial wet flood's exact
	 * values where it settled, and flood + port-matrix composition everywhere else — so far
	 * ports get honest (slightly conservative: the join is a settled endpoint, not the true
	 * merge point) distances instead of MAX_VALUE. This is what lets aboard starts offer a
	 * single continuous sail to ANY port rather than a disembark/re-embark chain.
	 */
	private static int[] composedPortDistances(int packed)
	{
		int[] flood = seaDistances(packed);
		int[][] matrix = get().portMatrix;
		if (matrix == null)
		{
			return flood;
		}
		int[] out = flood.clone();
		for (int far = 0; far < out.length; far++)
		{
			if (flood[far] != Integer.MAX_VALUE)
			{
				continue;
			}
			for (int near = 0; near < flood.length; near++)
			{
				if (flood[near] != Integer.MAX_VALUE && matrix[near][far] != Integer.MAX_VALUE)
				{
					out[far] = Math.min(out[far], flood[near] + matrix[near][far]);
				}
			}
		}
		return out;
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
			List<String> mooringNames = new ArrayList<>();
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
						Integer.parseInt(fields[3]), Integer.parseInt(fields[4]),
						fields.length > 5 && "true".equals(fields[5].trim()) ? 1 : 0});
					mooringNames.add(fields.length > 6 ? fields[6].trim() : "");
				}
			}
			SailingSea loaded = new SailingSea(minX, minY, width, height, bits, moorings, mooringNames);
			loaded.portMatrix = loadPortMatrix(moorings);
			return loaded;
		}
		catch (IOException | RuntimeException e)
		{
			// Missing/corrupt resources must not break routing; sailing sea targets just
			// won't resolve.
			return new SailingSea(0, 0, 0, 0, new byte[0], List.of(), List.of());
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
		return bit(sea, x, y);
	}

	/**
	 * The synthesized final sea legs for a sailable-water target: board at each of the
	 * {@code count} closest-by-sea moorings' land tiles and sail out. Distances come from the
	 * wet-endpoint flood ({@link #seaDistances}) — a real Dijkstra over the shipped ocean, so
	 * islands and coastlines are respected. Gated like any SAILING transport (master toggle).
	 */
	/**
	 * Legs for a search STARTING on the water (player aboard): sail from the start to EVERY
	 * sea-connected mooring and disembark — the start-side twin of {@link #seaLegTransports},
	 * matrix-composed so far ports get one continuous sail instead of the field's
	 * disembark/re-embark chains (findings 6-7) — plus, when a target is itself sailable
	 * water, one direct sail-to-the-pin leg (staged-box exact, matrix-composed when the pin
	 * is beyond the boxes). Without these, a search from aboard dies on its sealed start
	 * tile ("unreachable" the moment the player boards and drops a pin).
	 */
	public static List<Transport> aboardLegTransports(int startPacked,
		java.util.Set<Integer> targets)
	{
		if (!isSailable(startPacked))
		{
			return List.of();
		}
		List<Transport> legs = new ArrayList<>();
		SailingSea sea = get();
		int[] distances = composedPortDistances(startPacked);
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < distances.length; i++)
		{
			if (distances[i] != Integer.MAX_VALUE)
			{
				order.add(i);
			}
		}
		// Nearest-first so the service's port seeding keeps its ranking for free.
		order.sort(Comparator.comparingInt(i -> distances[i]));
		for (int i : order)
		{
			int[] mooring = sea.moorings.get(i);
			// Moor + step off only (~8 ticks): the player is already aboard and under way;
			// the full 20-tick cycle (board, cast off, moor, disembark) double-charged every
			// aboard hop — a berth-adjacent disembark priced at 16 seconds in the field.
			int duration = DISEMBARK_TICKS + (int) Math.ceil(
				distances[i] / 100.0 / TILES_PER_TICK);
			legs.add(new Transport.TransportBuilder()
				.origin(startPacked)
				.destination(WorldPointUtil.packWorldPoint(mooring[0], mooring[1], 0))
				.type(TransportType.SAILING)
				.duration(duration)
				.displayInfo("Disembark at " + portName(i))
				.build());
		}
		for (int target : targets)
		{
			int centitiles = seaDistanceBetween(startPacked, target);
			if (centitiles < 0)
			{
				// Beyond the staged boxes: sail via the cheapest port join — the composed
				// start-side distances plus the target's own composed distances share every
				// mooring as a meeting point.
				int[] toTarget = composedPortDistances(target);
				long best = Long.MAX_VALUE;
				for (int i = 0; i < distances.length; i++)
				{
					if (distances[i] != Integer.MAX_VALUE && toTarget[i] != Integer.MAX_VALUE)
					{
						best = Math.min(best, (long) distances[i] + toTarget[i]);
					}
				}
				if (best < Long.MAX_VALUE)
				{
					centitiles = (int) Math.min(Integer.MAX_VALUE, best);
				}
			}
			if (centitiles >= 0)
			{
				legs.add(new Transport.TransportBuilder()
					.origin(startPacked)
					.destination(target)
					.type(TransportType.SAILING)
					.duration(Math.max(1, (int) Math.ceil(centitiles / 100.0 / TILES_PER_TICK)))
					.displayInfo("Sail to the destination")
					.build());
			}
		}
		return legs;
	}

	/**
	 * Exact sea distance between two sailable tiles in centitiles, or -1 when either tile is
	 * not sailable or no path fits the staged search boxes (60 then 600 margin — no full-grid
	 * stage: this runs on the generation thread, and a leg the 600 box cannot connect is
	 * served by the mooring legs instead).
	 */
	public static synchronized int seaDistanceBetween(int fromPacked, int toPacked)
	{
		SailingSea sea = get();
		if (sea.width == 0 || !isSailable(fromPacked) || !isSailable(toPacked))
		{
			return -1;
		}
		int start = (WorldPointUtil.unpackWorldY(fromPacked) - sea.minY) * sea.width
			+ (WorldPointUtil.unpackWorldX(fromPacked) - sea.minX);
		int goal = (WorldPointUtil.unpackWorldY(toPacked) - sea.minY) * sea.width
			+ (WorldPointUtil.unpackWorldX(toPacked) - sea.minX);
		if (start == goal)
		{
			return 0;
		}
		int cost = boxDistance(sea, start, goal, 60);
		return cost >= 0 ? cost : boxDistance(sea, start, goal, 600);
	}

	/** Box-bounded Dijkstra cost from start to goal grid index, or -1 when unconnected. */
	private static int boxDistance(SailingSea sea, int start, int goal, int margin)
	{
		int x0 = Math.max(0, Math.min(start % sea.width, goal % sea.width) - margin);
		int y0 = Math.max(0, Math.min(start / sea.width, goal / sea.width) - margin);
		int x1 = Math.min(sea.width - 1, Math.max(start % sea.width, goal % sea.width) + margin);
		int y1 = Math.min(sea.height - 1, Math.max(start / sea.width, goal / sea.width) + margin);
		int boxWidth = x1 - x0 + 1;
		int boxHeight = y1 - y0 + 1;
		int[] dist = new int[boxWidth * boxHeight];
		java.util.Arrays.fill(dist, Integer.MAX_VALUE);
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
				return dist[index];
			}
			int x = index % boxWidth;
			int y = index / boxWidth;
			for (int[] move : MOVES)
			{
				int nx = x + move[0];
				int ny = y + move[1];
				if (nx < 0 || ny < 0 || nx >= boxWidth || ny >= boxHeight
					|| !bit(sea, x0 + nx, y0 + ny)
					|| obstacleAtGrid(sea, x0 + nx, y0 + ny)
					|| knightBlocked(sea, x0 + x, y0 + y, move[0], move[1]))
				{
					continue;
				}
				int next = ny * boxWidth + nx;
				if (dist[index] + move[2] < dist[next])
				{
					dist[next] = dist[index] + move[2];
					queue.push((long) dist[next] << 32 | next);
				}
			}
		}
		return -1;
	}

	public static List<Transport> seaLegTransports(int targetPacked, int count)
	{
		return seaLegTransports(targetPacked, count, java.util.Set.of());
	}

	/**
	 * @param mustIncludeLands packed mooring land tiles that get an embark leg even when
	 *                         outside the nearest-{@code count} — with Summon Boat not
	 *                         assumed, the boat's actual berth is the ONLY legal embark, and
	 *                         nearest-selection would happily gate every offered leg away.
	 */
	public static List<Transport> seaLegTransports(int targetPacked, int count,
		java.util.Set<Integer> mustIncludeLands)
	{
		if (!isSailable(targetPacked))
		{
			return List.of();
		}
		int tx = WorldPointUtil.unpackWorldX(targetPacked);
		int ty = WorldPointUtil.unpackWorldY(targetPacked);
		int[] distances = composedPortDistances(targetPacked);
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
		// Nearest-by-sea plus a guarantee of REACHABLE_LEGS walk-reachable ports. Near new
		// islands every nearby mooring is an unreachable unlock — legs only from those give
		// the heuristic field nothing on the mainland, and every search of the generation
		// runs blind (the 30s water-pin captures).
		List<Integer> chosen = new ArrayList<>();
		int reachableChosen = 0;
		for (int i : order)
		{
			boolean reachable = reachable(moorings.get(i));
			if (chosen.size() < count)
			{
				chosen.add(i);
				if (reachable)
				{
					reachableChosen++;
				}
			}
			else if (reachableChosen < REACHABLE_LEGS && reachable)
			{
				chosen.add(i);
				reachableChosen++;
			}
			else if (!mustIncludeLands.isEmpty() && mustIncludeLands.contains(
				WorldPointUtil.packWorldPoint(moorings.get(i)[0], moorings.get(i)[1], 0)))
			{
				chosen.add(i);
			}
		}
		List<Transport> legs = new ArrayList<>();
		for (int i : chosen)
		{
			int[] mooring = moorings.get(i);
			int duration = OVERHEAD_TICKS + (int) Math.ceil(
				distances[i] / 100.0 / TILES_PER_TICK);
			legs.add(new Transport.TransportBuilder()
				.origin(WorldPointUtil.packWorldPoint(mooring[0], mooring[1], 0))
				.destination(targetPacked)
				.type(TransportType.SAILING)
				.duration(duration)
				.displayInfo("Embark at " + portName(i))
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

	/**
	 * The wet-endpoint flood: exact sea distance in centitiles from a sailable target to every
	 * mooring's water endpoint (MAX_VALUE where no sea path exists). Dijkstra over the shipped
	 * ocean bitset, stopping early once the {@link #SETTLE_ENDPOINTS} nearest endpoints AND
	 * {@link #REACHABLE_QUOTA} walk-reachable ports are settled.
	 */
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
		int settledReachable = 0;
		while (!queue.isEmpty()
			&& (settled < SETTLE_ENDPOINTS || settledReachable < REACHABLE_QUOTA))
		{
			long head = queue.pop();
			int index = (int) (head & 0xFFFFFFFFL);
			if ((int) (head >>> 32) > dist[index])
			{
				continue;
			}
			Integer mooring = sea.endpointIndex.get(index);
			if (mooring != null && result[mooring] == Integer.MAX_VALUE)
			{
				result[mooring] = dist[index];
				settled++;
				if (reachable(sea.moorings.get(mooring)))
				{
					settledReachable++;
				}
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
					|| !bit(sea, nx, ny) || obstacleAtGrid(sea, nx, ny))
				{
					continue;
				}
				if (knightBlocked(sea, x, y, dx, dy))
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
	/**
	 * Session-learned sea blockers: tiles the shipped ocean calls sailable but LIVE scene
	 * collision blocks — moored vessels, harbour clutter, true hazards. The offline map stays
	 * the approximate planner; the client corrects itself as scenes reveal the truth (the
	 * user's chosen architecture over curated harvests). Dilated by one tile for hull
	 * standoff; water within 4 of a mooring's water endpoint is never masked so berth
	 * approaches survive. Volatile copy-on-write: hot flood loops read without locks.
	 */
	private static volatile PrimitiveIntHashMap<Boolean> liveObstacles;

	/** Whether a world tile is masked by a live-learned obstacle. */
	public static boolean obstacleAt(int worldX, int worldY)
	{
		PrimitiveIntHashMap<Boolean> mask = liveObstacles;
		return mask != null
			&& mask.get(WorldPointUtil.packWorldPoint(worldX, worldY, 0)) != null;
	}

	private static boolean obstacleAtGrid(SailingSea sea, int gridX, int gridY)
	{
		PrimitiveIntHashMap<Boolean> mask = liveObstacles;
		return mask != null && mask.get(
			WorldPointUtil.packWorldPoint(sea.minX + gridX, sea.minY + gridY, 0)) != null;
	}

	/**
	 * Learns live-blocked sailable tiles (packed world points). New knowledge invalidates
	 * every cached track and wet flood — they recompute lazily against the corrected sea.
	 */
	public static synchronized void learnObstacles(java.util.List<Integer> packedTiles)
	{
		SailingSea sea = get();
		if (sea.width == 0 || packedTiles.isEmpty())
		{
			return;
		}
		PrimitiveIntHashMap<Boolean> current = liveObstacles;
		PrimitiveIntHashMap<Boolean> grown = null;
		for (int packed : packedTiles)
		{
			int px = WorldPointUtil.unpackWorldX(packed);
			int py = WorldPointUtil.unpackWorldY(packed);
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					int tile = WorldPointUtil.packWorldPoint(px + dx, py + dy, 0);
					if (!isSailable(tile) || nearMooringWater(sea, px + dx, py + dy)
						|| (current != null && current.get(tile) != null)
						|| (grown != null && grown.get(tile) != null))
					{
						continue;
					}
					if (grown == null)
					{
						grown = copyMask(current);
					}
					grown.put(tile, Boolean.TRUE);
				}
			}
		}
		if (grown != null)
		{
			liveObstacles = grown;
			synchronized (trackCache)
			{
				trackCache.clear();
			}
			cachedTarget = WorldPointUtil.UNDEFINED;
			cachedDistances = null;
		}
	}

	private static PrimitiveIntHashMap<Boolean> copyMask(PrimitiveIntHashMap<Boolean> current)
	{
		PrimitiveIntHashMap<Boolean> copy = new PrimitiveIntHashMap<>(256);
		if (current != null)
		{
			for (int key : current.keys())
			{
				copy.put(key, Boolean.TRUE);
			}
		}
		return copy;
	}

	private static boolean nearMooringWater(SailingSea sea, int x, int y)
	{
		for (int[] mooring : sea.moorings)
		{
			if (Math.max(Math.abs(mooring[2] - x), Math.abs(mooring[3] - y)) <= 4)
			{
				return true;
			}
		}
		return false;
	}

	/** Test seam: forget everything learned this session. */
	static synchronized void clearLiveObstacles()
	{
		liveObstacles = null;
		synchronized (trackCache)
		{
			trackCache.clear();
		}
		cachedTarget = WorldPointUtil.UNDEFINED;
		cachedDistances = null;
	}

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
		long key = trackKey(fromPacked, toPacked);
		int[] cached = cachedTrack(key);
		if (cached != null || trackCached(key))
		{
			return cached;
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
		long key = trackKey(fromPacked, toPacked);
		int[] cached = cachedTrack(key);
		if (cached != null || trackCached(key))
		{
			return cached;
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
					|| !bit(sea, x0 + nx, y0 + ny)
					|| obstacleAtGrid(sea, x0 + nx, y0 + ny))
				{
					continue;
				}
				if (knightBlocked(sea, x0 + x, y0 + y, move[0], move[1]))
				{
					continue;
				}
				// Distance-optimal paths GRAZE obstacles (tangents are shortest), so pure
				// Dijkstra hugs every coastline. A soft near-land penalty makes the track
				// stand off wherever open water is free while still threading channels and
				// port approaches. Track aesthetics only: durations come from the
				// un-penalised wet flood and boxDistance.
				int step = move[2] + (nearLand(sea, x0 + nx, y0 + ny) ? SHORE_PENALTY : 0);
				int next = ny * boxWidth + nx;
				if (dist[index] + step < dist[next])
				{
					dist[next] = dist[index] + step;
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
		return smoothTrack(sea, waypoints);
	}

	/** Penalty per near-land step: ~0.6 tiles equivalent — stand off shore when open water is free. */
	private static final int SHORE_PENALTY = 60;
	/** Re-densified waypoint spacing: progress tracking and off-route bands need dense points. */
	private static final int TRACK_POINT_SPACING = 4;

	/** Standoff radius: hugging means within TWO tiles of land — one tile still reads
	 * "almost touching" at sea scale (field capture 212843). */
	private static final int STANDOFF = 2;

	private static boolean nearLand(SailingSea sea, int gridX, int gridY)
	{
		for (int dx = -STANDOFF; dx <= STANDOFF; dx++)
		{
			for (int dy = -STANDOFF; dy <= STANDOFF; dy++)
			{
				int x = gridX + dx;
				int y = gridY + dy;
				if (x < 0 || y < 0 || x >= sea.width || y >= sea.height || !bit(sea, x, y))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Long straight legs from a jittery grid path: greedy line-of-sight simplification (extend
	 * each leg to the farthest waypoint the straight line can reach over sailable water), then
	 * re-densify points every {@link #TRACK_POINT_SPACING} tiles along the straight legs —
	 * the overlays and off-route bands measure against WAYPOINTS, so long bare segments would
	 * break progress tracking mid-leg.
	 */
	private static int[] smoothTrack(SailingSea sea, java.util.List<Integer> waypoints)
	{
		java.util.List<Integer> corners = new ArrayList<>();
		int trackStart = waypoints.get(0);
		int trackGoal = waypoints.get(waypoints.size() - 1);
		int at = 0;
		corners.add(trackStart);
		while (at < waypoints.size() - 1)
		{
			int reach = at + 1;
			for (int j = waypoints.size() - 1; j > at + 1; j--)
			{
				if (lineKeepsStandoff(sea, waypoints.get(at), waypoints.get(j), trackStart, trackGoal))
				{
					reach = j;
					break;
				}
			}
			corners.add(waypoints.get(reach));
			at = reach;
		}
		// Greedy never revisits: a second pass merges corners whose neighbours connect
		// directly (13 -> fewer helm changes on the capture-212843 leg), repeated to fixpoint.
		boolean merged = true;
		while (merged)
		{
			merged = false;
			for (int c = 1; c + 1 < corners.size(); c++)
			{
				if (lineKeepsStandoff(sea, corners.get(c - 1), corners.get(c + 1),
					trackStart, trackGoal))
				{
					corners.remove(c);
					merged = true;
					c--;
				}
			}
		}
		// The boat steers 16 quantized bearings: snap every leg onto them by decomposing
		// each chord into (at most) two runs along adjacent bearings — the adjacent basis
		// determinants are 1, so the decomposition is exact and integer. Points sampled
		// along an exact bearing run are perfectly collinear: the drawn line is straight
		// because the geometry is, not because rendering hides jitter (field screenshot:
		// tracks neither straight nor on sailable headings).
		corners = snapCornersToBearings(sea, corners, trackStart, trackGoal);
		java.util.List<Integer> dense = new ArrayList<>();
		for (int c = 0; c + 1 < corners.size(); c++)
		{
			densifyLeg(dense, corners.get(c), corners.get(c + 1));
		}
		dense.add(corners.get(corners.size() - 1));
		int[] track = new int[dense.size()];
		for (int i = 0; i < track.length; i++)
		{
			track[i] = dense.get(i);
		}
		return track;
	}

	/**
	 * Indices of the REAL corners of a dense track: direction measured over a two-point
	 * window on each side, so the integer stair-stepping of a densified straight chord
	 * (E, E, NE, E...) does not count as helm changes. First and last index included.
	 */
	public static java.util.List<Integer> trackCorners(int[] track)
	{
		java.util.List<Integer> corners = new ArrayList<>();
		corners.add(0);
		for (int w = 2; w + 2 < track.length; w++)
		{
			double inX = WorldPointUtil.unpackWorldX(track[w]) - WorldPointUtil.unpackWorldX(track[w - 2]);
			double inY = WorldPointUtil.unpackWorldY(track[w]) - WorldPointUtil.unpackWorldY(track[w - 2]);
			double outX = WorldPointUtil.unpackWorldX(track[w + 2]) - WorldPointUtil.unpackWorldX(track[w]);
			double outY = WorldPointUtil.unpackWorldY(track[w + 2]) - WorldPointUtil.unpackWorldY(track[w]);
			double cross = inX * outY - inY * outX;
			double dot = inX * outX + inY * outY;
			// > ~18 degrees of direction change across the window = a genuine turn.
			if (Math.abs(Math.atan2(cross, dot)) > Math.toRadians(18))
			{
				corners.add(w);
				w += 2;
			}
		}
		corners.add(track.length - 1);
		return corners;
	}

	/**
	 * The 16 boat bearings in angular order — the game's OWN movement vectors (chart-plotter's
	 * DX/DY table): a uniform 22.5-degree grid. NOT knight vectors: (2,1) is 26.6 degrees,
	 * visibly off the heading a boat actually holds; (9,4) is 24.0, within 1.5 of the grid.
	 */
	private static final int[][] BEARINGS = {
		{10, 0}, {9, 4}, {7, 7}, {4, 9}, {0, 10}, {-4, 9}, {-7, 7}, {-9, 4},
		{-10, 0}, {-9, -4}, {-7, -7}, {-4, -9}, {0, -10}, {4, -9}, {7, -7}, {9, -4},
	};

	/**
	 * Rewrites each leg between corners as at most two exact bearing runs (A -> A+a*u -> B,
	 * u and v adjacent bearings whose cone contains the displacement). Both runs must keep
	 * the standoff or the raw chord stays — port approaches and tight channels degrade
	 * gracefully instead of failing.
	 */
	/** Dogleg turn positions along the first bearing run, tried in order. */
	private static final double[] DOGLEG_FRACTIONS = {0.7, 0.5, 0.3};

	private static java.util.List<Integer> snapCornersToBearings(SailingSea sea,
		java.util.List<Integer> corners, int trackStart, int trackGoal)
	{
		java.util.List<Integer> snapped = new ArrayList<>();
		snapped.add(corners.get(0));
		for (int c = 1; c < corners.size(); c++)
		{
			int from = snapped.get(snapped.size() - 1);
			int to = corners.get(c);
			// Clearance staging: full standoff first; when hulls or headlands block every
			// on-bearing shape, retry requiring only sailable water — the OLD fallback (a
			// raw off-grid chord) threaded the same tight corridor anyway, so staying on
			// the 16 headings at reduced clearance is a strict improvement. The chord
			// remains the true last resort.
			if (!snapLeg(sea, snapped, from, to, trackStart, trackGoal, true)
				&& !snapLeg(sea, snapped, from, to, trackStart, trackGoal, false))
			{
				snapped.add(to);
			}
		}
		return snapped;
	}

	/**
	 * Appends an on-bearing rewrite of from -> to (two-leg corner, else a three-leg
	 * dogleg with the turn slid along the first run — the shape that clears a moored
	 * hull the single corner cannot). False when no shape fits at this clearance.
	 */
	private static boolean snapLeg(SailingSea sea, java.util.List<Integer> snapped,
		int from, int to, int trackStart, int trackGoal, boolean fullClearance)
	{
		int dx = WorldPointUtil.unpackWorldX(to) - WorldPointUtil.unpackWorldX(from);
		int dy = WorldPointUtil.unpackWorldY(to) - WorldPointUtil.unpackWorldY(from);
		int mid = bearingMidpoint(from, dx, dy, false);
		if (mid == from || mid == to)
		{
			// Already a single bearing run (or no cone contains it): nothing to rewrite.
			return false;
		}
		int alt = bearingMidpoint(from, dx, dy, true);
		int[] mids = alt == from || alt == to ? new int[]{mid} : new int[]{mid, alt};
		for (int full : mids)
		{
			if (lineKeepsStandoff(sea, from, full, trackStart, trackGoal, fullClearance)
				&& lineKeepsStandoff(sea, full, to, trackStart, trackGoal, fullClearance))
			{
				snapped.add(full);
				snapped.add(to);
				return true;
			}
		}
		// Doglegs: mid1 = lerp(from, full, t) stays on the first bearing; mid2 = mid1 +
		// (to - full) makes the middle leg EXACTLY the second bearing's displacement;
		// the final leg is the first bearing's remainder. Only mid1 rounds.
		for (int full : mids)
		{
			int fx = WorldPointUtil.unpackWorldX(full);
			int fy = WorldPointUtil.unpackWorldY(full);
			int sx = WorldPointUtil.unpackWorldX(from);
			int sy = WorldPointUtil.unpackWorldY(from);
			for (double t : DOGLEG_FRACTIONS)
			{
				int mid1 = WorldPointUtil.packWorldPoint(
					sx + (int) Math.round((fx - sx) * t),
					sy + (int) Math.round((fy - sy) * t), 0);
				int mid2 = WorldPointUtil.packWorldPoint(
					WorldPointUtil.unpackWorldX(mid1) + WorldPointUtil.unpackWorldX(to) - fx,
					WorldPointUtil.unpackWorldY(mid1) + WorldPointUtil.unpackWorldY(to) - fy, 0);
				if (mid1 == from || mid1 == mid2 || mid2 == to)
				{
					continue;
				}
				if (lineKeepsStandoff(sea, from, mid1, trackStart, trackGoal, fullClearance)
					&& lineKeepsStandoff(sea, mid1, mid2, trackStart, trackGoal, fullClearance)
					&& lineKeepsStandoff(sea, mid2, to, trackStart, trackGoal, fullClearance))
				{
					snapped.add(mid1);
					snapped.add(mid2);
					snapped.add(to);
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The corner of the two-bearing decomposition of (dx, dy): from + a*u (or from + b*v
	 * when {@code swapOrder}). Returns {@code from} when the displacement is already a
	 * single bearing run or the cone search fails.
	 */
	private static int bearingMidpoint(int from, int dx, int dy, boolean swapOrder)
	{
		for (int k = 0; k < BEARINGS.length; k++)
		{
			int[] u = BEARINGS[k];
			int[] v = BEARINGS[(k + 1) % BEARINGS.length];
			double det = u[0] * v[1] - u[1] * v[0];
			double a = (dx * v[1] - dy * v[0]) / det;
			double b = (dy * u[0] - dx * u[1]) / det;
			// Real-valued cone test: the true bearing vectors have det 40-ish, so the
			// decomposition is fractional; the midpoint rounds to the nearest tile (the
			// half-tile error tilts a long leg by well under a degree).
			if (a < -1e-9 || b < -1e-9)
			{
				continue;
			}
			if (a < 0.5 || b < 0.5)
			{
				return from;
			}
			int x = WorldPointUtil.unpackWorldX(from);
			int y = WorldPointUtil.unpackWorldY(from);
			return swapOrder
				? WorldPointUtil.packWorldPoint(x + (int) Math.round(b * v[0]),
					y + (int) Math.round(b * v[1]), 0)
				: WorldPointUtil.packWorldPoint(x + (int) Math.round(a * u[0]),
					y + (int) Math.round(a * u[1]), 0);
		}
		return from;
	}

	/**
	 * Densifies one leg. Exact bearing runs step their basis vector (points ON the line);
	 * fallback chords keep the rounded interpolation.
	 */
	private static void densifyLeg(java.util.List<Integer> dense, int from, int to)
	{
		int ax = WorldPointUtil.unpackWorldX(from);
		int ay = WorldPointUtil.unpackWorldY(from);
		int dx = WorldPointUtil.unpackWorldX(to) - ax;
		int dy = WorldPointUtil.unpackWorldY(to) - ay;
		// Bearing-aligned legs interpolate along the true (fractional) direction; rounding
		// keeps every point within half a tile of the ideal line, which is sub-pixel on the
		// world map — no basis-stepping special case needed with the 10-scale vectors.
		int steps = Math.max(1, Math.max(Math.abs(dx), Math.abs(dy)) / TRACK_POINT_SPACING);
		for (int s = 0; s < steps; s++)
		{
			dense.add(WorldPointUtil.packWorldPoint(
				ax + Math.round((float) dx * s / steps),
				ay + Math.round((float) dy * s / steps), 0));
		}
	}

	/**
	 * Supercover line test that PRESERVES the standoff: every cell sailable, and — except
	 * within 8 tiles of the track's endpoints (port water must be approachable) — no cell
	 * within {@link #STANDOFF} of land. Without the standoff requirement the simplification
	 * chords cut straight back around every headland, undoing what the Dijkstra penalty paid
	 * to avoid (field capture 212843: smoothed tracks still touching the shore).
	 */
	private static boolean lineKeepsStandoff(SailingSea sea, int fromPacked, int toPacked,
		int trackStart, int trackGoal)
	{
		return lineKeepsStandoff(sea, fromPacked, toPacked, trackStart, trackGoal, true);
	}

	/** {@code requireClearance} false = sailable, obstacle-free water only — the reduced
	 * bar for last-resort on-bearing shapes through corridors the raw chord would have
	 * threaded anyway. */
	private static boolean lineKeepsStandoff(SailingSea sea, int fromPacked, int toPacked,
		int trackStart, int trackGoal, boolean requireClearance)
	{
		int x0 = WorldPointUtil.unpackWorldX(fromPacked) - sea.minX;
		int y0 = WorldPointUtil.unpackWorldY(fromPacked) - sea.minY;
		int x1 = WorldPointUtil.unpackWorldX(toPacked) - sea.minX;
		int y1 = WorldPointUtil.unpackWorldY(toPacked) - sea.minY;
		int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)) * 2;
		for (int s = 0; s <= steps; s++)
		{
			int x = x0 + Math.round((float) (x1 - x0) * s / steps);
			int y = y0 + Math.round((float) (y1 - y0) * s / steps);
			if (!bit(sea, x, y) || obstacleAtGrid(sea, x, y))
			{
				return false;
			}
			int world = WorldPointUtil.packWorldPoint(sea.minX + x, sea.minY + y, 0);
			boolean nearEndpoint =
				WorldPointUtil.distanceBetween(world, trackStart) <= 8
					|| WorldPointUtil.distanceBetween(world, trackGoal) <= 8;
			if (requireClearance && !nearEndpoint && nearLand(sea, x, y))
			{
				return false;
			}
		}
		return true;
	}

	private static long trackKey(int fromPacked, int toPacked)
	{
		return (long) fromPacked << 32 | toPacked & 0xFFFFFFFFL;
	}

	private static int[] cachedTrack(long key)
	{
		synchronized (trackCache)
		{
			return trackCache.get(key);
		}
	}

	/** Distinguishes a cached null (no track computable) from a cache miss. */
	private static boolean trackCached(long key)
	{
		synchronized (trackCache)
		{
			return trackCache.containsKey(key);
		}
	}

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

	/**
	 * Grid index of the nearest sailable tile within 10 of the endpoint, or -1. Ten, not six:
	 * the mooring dumper pairs land tiles with water up to 8 tiles away (piers), and the track
	 * must reach the water from the same land tile the transport departs from.
	 */
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
	 * OVERHEAD_TICKS + tiles / TILES_PER_TICK. COUPLED to that model: if the constants ever
	 * change, this must keep inverting whatever replaces them.
	 */
	public static int tilesFromDuration(int durationTicks)
	{
		return (int) Math.round(Math.max(0, durationTicks - OVERHEAD_TICKS) * TILES_PER_TICK);
	}

	/** The shipped port display name, falling back to the boarding tile's coordinates. */
	private static String portName(int mooringIndex)
	{
		SailingSea sea = get();
		String name = mooringIndex < sea.mooringNames.size()
			? sea.mooringNames.get(mooringIndex) : "";
		if (!name.isEmpty())
		{
			return name;
		}
		int[] mooring = sea.moorings.get(mooringIndex);
		return mooring[0] + "," + mooring[1];
	}

	/** The shipped walk-reachable flag: a mainland port the walking network serves. */
	private static boolean reachable(int[] mooring)
	{
		return mooring[4] == 1;
	}

	/**
	 * Half-wind (knight) steps must not cut across obstacle corners: both interposed tiles —
	 * the half-step along the long axis, and the full short-axis neighbour beside it — must be
	 * sailable. The one subtle piece of the 16-bearing geometry, shared by both Dijkstras.
	 */
	private static boolean knightBlocked(SailingSea sea, int x, int y, int dx, int dy)
	{
		return Math.abs(dx) + Math.abs(dy) == 3
			&& (!bit(sea, x + dx / 2, y + dy / 2)
				|| !bit(sea,
					Math.abs(dx) == 2 ? x + dx / 2 : x + dx,
					Math.abs(dx) == 2 ? y + dy : y + dy / 2));
	}

	/** Local-grid sailability test used by the wet-endpoint flood's move loop. */
	private static boolean bit(SailingSea sea, int x, int y)
	{
		long index = (long) y * sea.width + x;
		return (sea.bits[(int) (index >> 3)] & 1 << (index & 7)) != 0;
	}

	}
