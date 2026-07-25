package gps.pathfinder;

import gps.WorldPointUtil;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.Assume;
import org.junit.Test;

/**
 * Dev probe (-DcollisionDiff.run=true): quantifies what a collision-map change did to
 * PLAYER-REACHABLE space, not just raw edges. Loads two zips (-DcollisionDiff.oldZip /
 * -DcollisionDiff.newZip), measures load time and retained heap, then flood-fills each with
 * the real 8-direction canStep from Lumbridge plus every transport endpoint, and diffs the
 * reachable sets both ways: newly reachable tiles (possible void leaks) and newly
 * UNREACHABLE tiles (broken paths), grouped by region.
 */
public class CollisionDiffProbeTest
{
	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);

	@Test
	public void diff() throws Exception
	{
		Assume.assumeTrue(Boolean.getBoolean("collisionDiff.run"));
		Path oldZip = Paths.get(System.getProperty("collisionDiff.oldZip"));
		Path newZip = Paths.get(System.getProperty("collisionDiff.newZip"));

		List<Integer> seeds = transportSeeds();
		seeds.add(LUMBRIDGE);
		System.out.println("seeds: " + seeds.size() + " (transport endpoints + Lumbridge)");

		Result oldResult = measure("old", oldZip, seeds);
		Result newResult = measure("new", newZip, seeds);

		BitSet gained = (BitSet) newResult.reachable.clone();
		gained.andNot(oldResult.reachable);
		BitSet lost = (BitSet) oldResult.reachable.clone();
		lost.andNot(newResult.reachable);

		StringBuilder report = new StringBuilder();
		report.append(String.format("old: load %dms, retained %.1f MB, flood %dms, reachable %,d%n",
			oldResult.loadMs, oldResult.retainedMb, oldResult.floodMs, oldResult.reachable.cardinality()));
		report.append(String.format("new: load %dms, retained %.1f MB, flood %dms, reachable %,d%n",
			newResult.loadMs, newResult.retainedMb, newResult.floodMs, newResult.reachable.cardinality()));
		report.append(String.format("newly reachable: %,d   newly UNREACHABLE: %,d%n%n",
			gained.cardinality(), lost.cardinality()));
		bandTotals(report, "gained", gained);
		summarize(report, "NEWLY REACHABLE (leak candidates)", gained);
		summarize(report, "NEWLY UNREACHABLE (broken paths)", lost);
		report.append("exact newly-unreachable tiles:\n");
		for (int i = lost.nextSetBit(0); i >= 0; i = lost.nextSetBit(i + 1))
		{
			report.append("  ").append(i & 0x3FFF).append(',')
				.append((i >> 14) & 0x3FFF).append(',').append(i >>> 28).append('\n');
		}

		Files.write(Paths.get("build/collision-diff-report.txt"),
			report.toString().getBytes(StandardCharsets.UTF_8));
		System.out.println(report);
	}

	private static final class Result
	{
		long loadMs;
		double retainedMb;
		long floodMs;
		BitSet reachable;
	}

	private Result measure(String label, Path zip, List<Integer> seeds) throws Exception
	{
		Result result = new Result();
		System.gc();
		Thread.sleep(200);
		long before = used();
		long t0 = System.nanoTime();
		CollisionMap map = loadMap(zip);
		result.loadMs = (System.nanoTime() - t0) / 1_000_000;
		System.gc();
		Thread.sleep(200);
		result.retainedMb = (used() - before) / 1048576.0;

		t0 = System.nanoTime();
		result.reachable = flood(map, seeds);
		result.floodMs = (System.nanoTime() - t0) / 1_000_000;
		System.out.println(label + " done: " + result.reachable.cardinality() + " reachable");
		return result;
	}

	private static long used()
	{
		Runtime r = Runtime.getRuntime();
		return r.totalMemory() - r.freeMemory();
	}

	/** Mirrors SplitFlagMap.fromResources but reads an arbitrary zip (extents set reflectively). */
	private static CollisionMap loadMap(Path zip) throws Exception
	{
		Map<Integer, byte[]> compressed = new HashMap<>();
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = 0;
		int maxY = 0;
		try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip.toFile())))
		{
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null)
			{
				String[] n = entry.getName().split("_");
				int x = Integer.parseInt(n[0]);
				int y = Integer.parseInt(n[1]);
				minX = Math.min(minX, x);
				minY = Math.min(minY, y);
				maxX = Math.max(maxX, x);
				maxY = Math.max(maxY, y);
				compressed.put(SplitFlagMap.packPosition(x, y), gps.Util.readAllBytes(in));
			}
		}
		Constructor<?> extentCtor = SplitFlagMap.RegionExtent.class.getDeclaredConstructors()[0];
		extentCtor.setAccessible(true);
		Object extent = extentCtor.newInstance(minX, minY, maxX, maxY);
		Field field = SplitFlagMap.class.getDeclaredField("regionExtents");
		field.setAccessible(true);
		field.set(null, extent);
		return new CollisionMap(new SplitFlagMap(compressed));
	}

	private static BitSet flood(CollisionMap map, List<Integer> seeds)
	{
		BitSet visited = new BitSet(1 << 30);
		int[] queue = new int[1 << 22];
		int head = 0;
		int tail = 0;
		for (int seed : seeds)
		{
			int x = WorldPointUtil.unpackWorldX(seed);
			int y = WorldPointUtil.unpackWorldY(seed);
			int plane = WorldPointUtil.unpackWorldPlane(seed);
			if (map.isBlocked(x, y, plane))
			{
				continue;
			}
			int index = index(x, y, plane);
			if (!visited.get(index))
			{
				visited.set(index);
				queue[tail++] = seed;
			}
		}
		while (head != tail)
		{
			int at = queue[head++];
			if (head == queue.length)
			{
				head = 0;
			}
			int x = WorldPointUtil.unpackWorldX(at);
			int y = WorldPointUtil.unpackWorldY(at);
			int plane = WorldPointUtil.unpackWorldPlane(at);
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					if (dx == 0 && dy == 0)
					{
						continue;
					}
					int nx = x + dx;
					int ny = y + dy;
					if (nx < 1024 || nx > 4200 || ny < 2400 || ny > 12800)
					{
						continue;
					}
					int neighborIndex = index(nx, ny, plane);
					if (visited.get(neighborIndex))
					{
						continue;
					}
					int neighbor = WorldPointUtil.packWorldPoint(nx, ny, plane);
					if (!map.canStep(at, neighbor))
					{
						continue;
					}
					visited.set(neighborIndex);
					queue[tail++] = neighbor;
					if (tail == queue.length)
					{
						tail = 0;
					}
					if (tail == head)
					{
						throw new IllegalStateException("flood queue overflow");
					}
				}
			}
		}
		return visited;
	}

	private static int index(int x, int y, int plane)
	{
		return (plane << 28) | (y << 14) | x;
	}

	private static void bandTotals(StringBuilder report, String label, BitSet tiles)
	{
		int surface = 0;
		int under = 0;
		int instance = 0;
		for (int i = tiles.nextSetBit(0); i >= 0; i = tiles.nextSetBit(i + 1))
		{
			int y = (i >> 14) & 0x3FFF;
			if (y <= 4160)
			{
				surface++;
			}
			else if (y >= 8900)
			{
				under++;
			}
			else
			{
				instance++;
			}
		}
		report.append(label).append(" by band: surface=").append(surface)
			.append(" underground=").append(under).append(" instance-band=").append(instance)
			.append('\n');
	}

	private static void summarize(StringBuilder report, String title, BitSet tiles)
	{
		Map<Long, Integer> byRegion = new HashMap<>();
		for (int i = tiles.nextSetBit(0); i >= 0; i = tiles.nextSetBit(i + 1))
		{
			int x = i & 0x3FFF;
			int y = (i >> 14) & 0x3FFF;
			byRegion.merge(((long) (x / 64) << 20) | (y / 64), 1, Integer::sum);
		}
		List<Map.Entry<Long, Integer>> top = new ArrayList<>(byRegion.entrySet());
		top.sort((a, b) -> b.getValue() - a.getValue());
		report.append("=== ").append(title).append(": ").append(tiles.cardinality())
			.append(" tiles in ").append(byRegion.size()).append(" regions ===\n");
		for (int i = 0; i < Math.min(25, top.size()); i++)
		{
			int rx = (int) (top.get(i).getKey() >> 20);
			int ry = (int) (top.get(i).getKey() & 0xFFFFF);
			report.append(String.format("  %6d tiles  region %d_%d  world (%d,%d)%s%n",
				top.get(i).getValue(), rx, ry, rx * 64, ry * 64,
				ry * 64 >= 8900 ? "  [underground]" : (ry * 64 > 4160 ? "  [instance band]" : "")));
		}
	}

	private static List<Integer> transportSeeds() throws IOException
	{
		List<Integer> seeds = new ArrayList<>();
		File dir = new File("src/main/resources/transports");
		File[] files = dir.listFiles((d, name) -> name.endsWith(".tsv"));
		if (files == null)
		{
			return seeds;
		}
		for (File file : files)
		{
			for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))
			{
				if (line.startsWith("#") || line.isEmpty())
				{
					continue;
				}
				String[] cols = line.split("\t");
				for (int c = 0; c < Math.min(2, cols.length); c++)
				{
					String[] parts = cols[c].trim().split(" ");
					if (parts.length == 3)
					{
						try
						{
							seeds.add(WorldPointUtil.packWorldPoint(Integer.parseInt(parts[0]),
								Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
						}
						catch (NumberFormatException ignored)
						{
							// header or malformed row — not a tile
						}
					}
				}
			}
		}
		return seeds;
	}
}
