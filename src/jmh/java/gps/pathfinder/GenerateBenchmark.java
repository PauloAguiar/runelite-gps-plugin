package gps.pathfinder;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.client.callback.ClientThread;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mockito;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import gps.AlternativeRoutesMode;
import gps.AlternativeRoutesService;
import gps.RouteOption;
import gps.TeleportMethod;

/**
 * Microbenchmark for the true end-to-end "find routes": one {@link AlternativeRoutesService}
 * generation, which builds the distance field once and runs the exclusion-chain + seed searches to
 * produce up to {@code maxRoutes} alternative routes by method. This is the actual cost of a panel
 * "find routes" click.
 * <p>
 * The generation is asynchronous (own executor + seed pool); the benchmark awaits the terminal
 * {@code done} update on a latch, so the measured wall time is the generation's. The client-thread
 * bounce is run inline via a mock. {@code maxRoutes = 1} is the primary-only path (panel hidden);
 * {@code 10} is a full page. Output in ms because a generation is field-build-dominated (~100s of ms).
 * <p>
 * Run: {@code ./gradlew jmh --args='GenerateBenchmark'}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class GenerateBenchmark
{
	// The production default from ShortestPathPlugin: cap each search at best-cost * this.
	private static final int COST_MULTIPLE = 3;

	@Param({"lumbridge-barrows", "ge-shilo", "capture"})
	public String scenario;

	@Param({"1", "10"})
	public int maxRoutes;

	private AlternativeRoutesService service;
	private int start;
	private Set<Integer> targets;

	@Setup(Level.Trial)
	public void setup()
	{
		ClientThread clientThread = Mockito.mock(ClientThread.class);
		Mockito.doAnswer(invocation ->
		{
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		service = new AlternativeRoutesService(clientThread, BenchScenarios.everythingConfig());
		start = BenchScenarios.start(scenario);
		targets = BenchScenarios.targets(scenario);
	}

	@TearDown(Level.Trial)
	public void tearDown()
	{
		service.shutdown();
	}

	@Benchmark
	public List<RouteOption> generate() throws InterruptedException
	{
		final CountDownLatch latch = new CountDownLatch(1);
		final AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(start, targets, Set.<TeleportMethod>of(), AlternativeRoutesMode.ALL_EVERYTHING,
			maxRoutes, COST_MULTIPLE, false,
			(routes, catalog, unavailable, done) ->
			{
				if (done)
				{
					out.set(routes);
					latch.countDown();
				}
			});
		latch.await(30, TimeUnit.SECONDS);
		return out.get();
	}
}
