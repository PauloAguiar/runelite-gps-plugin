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
	 * {@code count} nearest moorings' land tiles and sail straight out. Distance is octile —
	 * open ocean is nearly convex, and the port-to-port legs before it use the real Dijkstra
	 * matrix — and the whole leg is gated like any SAILING transport (master toggle).
	 */
	public static List<Transport> seaLegTransports(int targetPacked, int count)
	{
		if (!isSailable(targetPacked))
		{
			return List.of();
		}
		int tx = WorldPointUtil.unpackWorldX(targetPacked);
		int ty = WorldPointUtil.unpackWorldY(targetPacked);
		List<int[]> nearest = new ArrayList<>(get().moorings);
		nearest.sort(Comparator.comparingInt(m -> octile(m[2], m[3], tx, ty)));
		List<Transport> legs = new ArrayList<>();
		for (int[] mooring : nearest.subList(0, Math.min(count, nearest.size())))
		{
			int duration = OVERHEAD_TICKS + (int) Math.ceil(
				octile(mooring[2], mooring[3], tx, ty) / 100.0 / TILES_PER_TICK);
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

	/** Octile distance in centitiles (100 per cardinal step, 141 per diagonal step). */
	private static int octile(int x1, int y1, int x2, int y2)
	{
		int dx = Math.abs(x1 - x2);
		int dy = Math.abs(y1 - y2);
		return 100 * Math.max(dx, dy) + 41 * Math.min(dx, dy);
	}
}
