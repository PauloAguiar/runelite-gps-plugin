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
	public static int[] seaDistances(int targetPacked)
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
		int[] dist = new int[sea.width * sea.height];
		java.util.Arrays.fill(dist, Integer.MAX_VALUE);
		java.util.PriorityQueue<long[]> queue =
			new java.util.PriorityQueue<>(Comparator.comparingLong(a -> a[0]));
		int start = (WorldPointUtil.unpackWorldY(targetPacked) - sea.minY) * sea.width
			+ (WorldPointUtil.unpackWorldX(targetPacked) - sea.minX);
		dist[start] = 0;
		queue.add(new long[]{0, start});
		int settled = 0;
		while (!queue.isEmpty() && settled < SETTLE_ENDPOINTS)
		{
			long[] head = queue.poll();
			int index = (int) head[1];
			if (head[0] > dist[index])
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
					queue.add(new long[]{dist[next], next});
				}
			}
		}
		cachedDistances = result;
		cachedTarget = targetPacked;
		return result;
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
