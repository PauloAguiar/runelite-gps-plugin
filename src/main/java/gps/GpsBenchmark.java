package gps;

import com.google.gson.Gson;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import gps.transport.Transport;

/**
 * A fixed, repeatable performance benchmark for the route generator: a prepared set of
 * alternative-routes trips and "nearest X" queries, run with pinned parameters (explicit start
 * tiles, {@code All} mode so possession doesn't skew results, fixed route limit, warmup + measured
 * runs), producing a JSON report under {@code ~/.runelite/gps-debug/}. Reports from different
 * plugin versions are directly comparable: per scenario the median timing (the service's own
 * wall/rebuild/searchCpu/searches breakdown) plus a route fingerprint (costs and methods), so a
 * "speedup" that changed the answers is visible too.
 * <p>
 * Triggered by the panel's stopwatch button ({@link ShortestPathPlugin#runBenchmark()}). Runs
 * sequentially on its own daemon thread; each generation goes through the real
 * {@link AlternativeRoutesService} pipeline (exclusion chain, walk cap, parallel seeds).
 */
@Slf4j
public final class GpsBenchmark
{
	private static final int LIMIT = 10;
	private static final int WARMUP_RUNS = 1;
	private static final int MEASURED_RUNS = 3;
	private static final long RUN_TIMEOUT_SECONDS = 60;

	/** One prepared query: an alternative-routes trip or a multi-target nearest-X search. */
	public static final class Scenario
	{
		private final String name;
		private final String kind;
		private final int start;
		private final Set<Integer> targets;

		private Scenario(String name, String kind, int start, Set<Integer> targets)
		{
			this.name = name;
			this.kind = kind;
			this.start = start;
			this.targets = targets;
		}

		public static Scenario trip(String name, int start, int target)
		{
			return new Scenario(name, "alt-routes", start, Set.of(target));
		}

		public static Scenario nearest(String name, int start, Set<Integer> targets)
		{
			return new Scenario(name, "nearest", start, targets);
		}
	}

	private final AlternativeRoutesService service;
	private final List<Scenario> scenarios;
	private final File outputDir;
	private final Gson gson;
	private final Consumer<String> notify;
	private final Runnable onDone;
	// The method exclusions each scenario runs with — the player's set, which by default excludes
	// seasonal (Leagues) methods. Recorded (as a count) in the report header, since the excluded
	// set changes the method universe: two reports are only comparable when it matches.
	private final Set<TeleportMethod> exclusions;

	public GpsBenchmark(AlternativeRoutesService service, List<Scenario> scenarios, File outputDir,
		Set<TeleportMethod> exclusions, Gson gson, Consumer<String> notify, Runnable onDone)
	{
		this.service = service;
		this.scenarios = scenarios;
		this.outputDir = outputDir;
		this.exclusions = exclusions != null ? Set.copyOf(exclusions) : Set.of();
		this.gson = gson;
		this.notify = notify;
		this.onDone = onDone;
	}

	/**
	 * The standard scenario set: stable landmark tiles, chosen to exercise the distinct load
	 * shapes — mid- and long-distance trips, an underground destination, and nearest-X queries
	 * whose target sets range from dense (water sources) to the canonical bank case.
	 */
	public static GpsBenchmark standard(AlternativeRoutesService service,
		PrimitiveIntHashMap<Transport[]> transports, Set<TeleportMethod> exclusions, Gson gson,
		Consumer<String> notify, Runnable onDone)
	{
		int lumbridge = WorldPointUtil.packWorldPoint(3222, 3218, 0);
		int grandExchange = WorldPointUtil.packWorldPoint(3165, 3487, 0);
		int falador = WorldPointUtil.packWorldPoint(2965, 3380, 0);
		int varrock = WorldPointUtil.packWorldPoint(3213, 3428, 0);
		int barrows = WorldPointUtil.packWorldPoint(3566, 3291, 0);
		int shiloVillage = WorldPointUtil.packWorldPoint(2852, 2955, 0);
		int keldagrim = WorldPointUtil.packWorldPoint(2845, 10210, 0);
		List<Scenario> scenarios = List.of(
			Scenario.trip("Lumbridge -> Barrows", lumbridge, barrows),
			Scenario.trip("Grand Exchange -> Shilo Village", grandExchange, shiloVillage),
			Scenario.trip("Falador -> Keldagrim (underground)", falador, keldagrim),
			Scenario.nearest("Nearest bank from Lumbridge", lumbridge,
				Destinations.tilesForCategory("bank", transports)),
			Scenario.nearest("Nearest altar from Grand Exchange", grandExchange,
				Destinations.tilesForCategory("altar", transports)),
			Scenario.nearest("Nearest anvil from Varrock", varrock,
				Destinations.tilesForCategory("anvil", transports)));
		return new GpsBenchmark(service, scenarios,
			new File(net.runelite.client.RuneLite.RUNELITE_DIR, "gps-debug"), exclusions,
			gson, notify, onDone);
	}

	public void start()
	{
		Thread thread = new Thread(this::runAll, "gps-benchmark");
		thread.setDaemon(true);
		thread.start();
	}

	private void runAll()
	{
		long benchmarkStart = System.nanoTime();
		try
		{
			notify.accept("GPS benchmark started: " + scenarios.size() + " scenarios x ("
				+ WARMUP_RUNS + " warmup + " + MEASURED_RUNS + " measured) runs...");

			List<Map<String, Object>> scenarioReports = new ArrayList<>();
			StringBuilder table = new StringBuilder();
			for (int i = 0; i < scenarios.size(); i++)
			{
				Scenario scenario = scenarios.get(i);
				for (int w = 0; w < WARMUP_RUNS; w++)
				{
					runOnce(scenario);
				}
				List<Map<String, Object>> runs = new ArrayList<>();
				for (int m = 0; m < MEASURED_RUNS; m++)
				{
					runs.add(runOnce(scenario));
				}
				Map<String, Object> median = median(runs);

				Map<String, Object> report = new LinkedHashMap<>();
				report.put("name", scenario.name);
				report.put("kind", scenario.kind);
				report.put("start", pointJson(scenario.start));
				report.put("targetCount", scenario.targets.size());
				report.put("median", median);
				report.put("runs", runs);
				scenarioReports.add(report);

				String line = String.format(Locale.ROOT, "%-40s wall %6sms searchCpu %6sms searches %3s routes %2s",
					scenario.name, median.get("serviceWallMs"), median.get("searchCpuMs"),
					median.get("searches"), median.get("routeCount"));
				table.append(line).append('\n');
				notify.accept("GPS benchmark " + (i + 1) + "/" + scenarios.size() + ": "
					+ scenario.name + " - median wall " + median.get("serviceWallMs") + "ms");
			}

			Map<String, Object> report = new LinkedHashMap<>();
			report.put("benchmark", "gps-fixed-suite");
			report.put("capturedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
			report.put("mode", String.valueOf(AlternativeRoutesMode.ALL_EVERYTHING));
			report.put("excludedMethods", exclusions.size());
			report.put("routeLimit", LIMIT);
			report.put("warmupRuns", WARMUP_RUNS);
			report.put("measuredRuns", MEASURED_RUNS);
			report.put("javaVersion", System.getProperty("java.version"));
			report.put("scenarios", scenarioReports);

			//noinspection ResultOfMethodCallIgnored
			outputDir.mkdirs();
			File out = new File(outputDir,
				"gps-benchmark-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".json");
			try (Writer writer = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))
			{
				gson.newBuilder().setPrettyPrinting().create().toJson(report, writer);
			}

			long totalSeconds = (System.nanoTime() - benchmarkStart) / 1_000_000_000;
			log.info("GPS benchmark finished in {}s (median of {}):\n{}", totalSeconds, MEASURED_RUNS, table);
			notify.accept("GPS benchmark done in " + totalSeconds + "s - report: " + out.getAbsolutePath());
		}
		catch (InterruptedException e)
		{
			// The benchmark thread is a dedicated daemon; interruption just ends the run.
			// (Hub rule: no Thread::interrupt.)
			notify.accept("GPS benchmark interrupted.");
		}
		catch (Exception e)
		{
			log.error("GPS benchmark failed", e);
			notify.accept("GPS benchmark failed: " + e.getMessage());
		}
		finally
		{
			onDone.run();
		}
	}

	/**
	 * One generation through the real service, waited to completion; returns its metrics. A run
	 * that doesn't complete within the timeout (e.g. the user started their own route search,
	 * which cancels the benchmark's generation) is flagged rather than aborting the suite.
	 */
	private Map<String, Object> runOnce(Scenario scenario) throws InterruptedException
	{
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> finalRoutes = new AtomicReference<>(List.of());
		AtomicInteger catalogSize = new AtomicInteger(-1);
		long outerStart = System.nanoTime();
		service.generate(scenario.start, scenario.targets, exclusions, AlternativeRoutesMode.ALL_EVERYTHING, LIMIT,
			(routes, catalog, unavailable, isDone) ->
			{
				if (isDone)
				{
					finalRoutes.set(routes);
					catalogSize.set(catalog.size());
					done.countDown();
				}
			});
		boolean completed = done.await(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		long outerWallMs = (System.nanoTime() - outerStart) / 1_000_000;

		Map<String, Object> run = new LinkedHashMap<>();
		run.put("completed", completed);
		run.put("outerWallMs", outerWallMs);
		if (!completed)
		{
			return run;
		}
		// [wallMs, clientMs, rebuildMs, searchCpuMs, searches] — searches run in parallel, so
		// searchCpu can exceed wall.
		long[] timing = service.getLastTimingSummary();
		run.put("serviceWallMs", timing != null ? timing[0] : -1);
		run.put("clientMs", timing != null ? timing[1] : -1);
		run.put("rebuildMs", timing != null ? timing[2] : -1);
		run.put("searchCpuMs", timing != null ? timing[3] : -1);
		run.put("searches", timing != null ? timing[4] : -1);
		run.put("fieldMs", timing != null && timing.length > 5 ? timing[5] : -1);
		run.put("catalogSize", catalogSize.get());
		run.put("routeCount", finalRoutes.get().size());
		// Per-search profiles (slowest first): which searches the time actually went to — the
		// aggregate can't tell one expensive search from many cheap ones.
		List<Object> searchDetails = new ArrayList<>();
		for (AlternativeRoutesService.SearchRecord searchRecord : service.getLastSearchRecords())
		{
			Map<String, Object> detail = new LinkedHashMap<>();
			detail.put("label", searchRecord.label);
			detail.put("cpuMs", searchRecord.cpuMs);
			detail.put("cost", searchRecord.resultCost);
			detail.put("reached", searchRecord.reached);
			detail.put("termination", searchRecord.termination);
			detail.put("nodes", searchRecord.nodesChecked);
			detail.put("transports", searchRecord.transportsChecked);
			detail.put("capped", searchRecord.capped);
			detail.put("astar", searchRecord.astar);
			searchDetails.add(detail);
		}
		run.put("searchDetails", searchDetails);
		List<Object> routesJson = new ArrayList<>();
		for (RouteOption route : finalRoutes.get())
		{
			Map<String, Object> routeJson = new LinkedHashMap<>();
			routeJson.put("cost", route.getTotalCost());
			routeJson.put("rawCost", route.getRawCost());
			routeJson.put("reached", route.isReached());
			List<String> methods = new ArrayList<>();
			for (TeleportMethod method : route.getMethods())
			{
				methods.add(method.toString());
			}
			routeJson.put("methods", methods.isEmpty() ? List.of("Walk") : methods);
			routesJson.add(routeJson);
		}
		run.put("routes", routesJson);
		return run;
	}

	/** The completed run with the median service wall time (all-failed runs yield a stub). */
	private static Map<String, Object> median(List<Map<String, Object>> runs)
	{
		List<Map<String, Object>> completed = new ArrayList<>();
		for (Map<String, Object> run : runs)
		{
			if (Boolean.TRUE.equals(run.get("completed")))
			{
				completed.add(run);
			}
		}
		if (completed.isEmpty())
		{
			Map<String, Object> none = new LinkedHashMap<>();
			none.put("completed", false);
			return none;
		}
		completed.sort(Comparator.comparingLong(run -> (Long) run.get("serviceWallMs")));
		return completed.get(completed.size() / 2);
	}

	private static Map<String, Object> pointJson(int packed)
	{
		Map<String, Object> point = new LinkedHashMap<>();
		point.put("x", WorldPointUtil.unpackWorldX(packed));
		point.put("y", WorldPointUtil.unpackWorldY(packed));
		point.put("plane", WorldPointUtil.unpackWorldPlane(packed));
		return point;
	}
}
