package gps;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Microbenchmarks for the water-pin hot paths: the wet-endpoint flood a water target runs per
 * generation, and the sea-track builder the overlay schedules per sailing leg. Dev-only (jmh
 * source set, -Pjmh) — never ships, never counts toward the hub review surface.
 *
 * Each case CYCLES through several inputs so the production single-entry / LRU caches miss on
 * every call — these measure the compute, not the cache.
 *
 * Run:
 *   ./gradlew -Pjmh jmh --args='SailingSea -f 1 -wi 3 -i 5'
 * Allocation profile (B/op — the metric that caught the boxed-queue churn):
 *   ./gradlew -Pjmh jmh --args='SailingSea -f 1 -wi 3 -i 5 -prof gc'
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(1)
public class SailingSeaBenchmark
{
	/** Coastal pins: endpoints settle fast, small flood. */
	private static final int[][] COASTAL = {{2692, 3142}, {3051, 2645}, {2929, 3138}};
	/** Mid-ocean pins: the flood must reach 12 coastal endpoints — the worst case. */
	private static final int[][] MID_OCEAN = {{2894, 2637}, {2404, 2580}, {3200, 2700}};

	private final List<long[]> legs = new ArrayList<>();
	private int coastalAt;
	private int oceanAt;
	private int legAt;

	@Setup
	public void setup()
	{
		// Warm the resource load out of the measurement.
		SailingSea.isSailable(WorldPointUtil.packWorldPoint(2894, 2637, 0));
		// 16+ distinct static-row legs (> the 8-entry LRU) so every seaPath call computes.
		try (Scanner scanner = new Scanner(
			SailingSea.class.getResourceAsStream("/transports/sailing.tsv"), "UTF-8"))
		{
			while (scanner.hasNextLine() && legs.size() < 16)
			{
				String line = scanner.nextLine();
				if (line.startsWith("#") || line.isBlank())
				{
					continue;
				}
				String[] fields = line.split("\t");
				String[] origin = fields[0].split(" ");
				String[] destination = fields[1].split(" ");
				legs.add(new long[]{
					WorldPointUtil.packWorldPoint(
						Integer.parseInt(origin[0]), Integer.parseInt(origin[1]), 0),
					WorldPointUtil.packWorldPoint(
						Integer.parseInt(destination[0]), Integer.parseInt(destination[1]), 0)});
			}
		}
	}

	@Benchmark
	public int[] wetFloodCoastal()
	{
		int[] pin = COASTAL[coastalAt++ % COASTAL.length];
		return SailingSea.seaDistances(WorldPointUtil.packWorldPoint(pin[0], pin[1], 0));
	}

	@Benchmark
	public int[] wetFloodMidOcean()
	{
		int[] pin = MID_OCEAN[oceanAt++ % MID_OCEAN.length];
		return SailingSea.seaDistances(WorldPointUtil.packWorldPoint(pin[0], pin[1], 0));
	}

	@Benchmark
	public int[] seaTrackLeg()
	{
		long[] leg = legs.get(legAt++ % legs.size());
		return SailingSea.seaPathBlocking((int) leg[0], (int) leg[1]);
	}
}
