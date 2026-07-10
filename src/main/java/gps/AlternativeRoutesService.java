package gps;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Queue;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import gps.pathfinder.CostUnits;
import gps.pathfinder.DistanceField;
import gps.pathfinder.PathStep;
import gps.pathfinder.Pathfinder;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.PathfinderResult;
import gps.pathfinder.ReferenceDijkstra;
import gps.pathfinder.SearchHeuristic;
import gps.pathfinder.TransportAvailability;
import gps.transport.Transport;

/**
 * Generates up to {@link #MAX_ROUTES} alternative shortest paths to a target, each using a different
 * set of teleport/transport methods.
 * <p>
 * Strategy: run the planning-mode pathfinder, record the methods the best path used, exclude that
 * path's primary method, and search again. Repeating this yields successively-different routes in
 * increasing cost order. The user's own exclusions (methods they switched off in the panel) are
 * applied on top of every search.
 * <p>
 * Searches run on a dedicated background thread. Each search needs the planning config's transport
 * availability rebuilt for the current exclusion set, and {@link PathfinderConfig#refresh()} must run
 * on the client thread (it reads live game state), so the worker bounces each refresh onto the client
 * thread and waits for it before running the search off-thread.
 */
@Slf4j
public class AlternativeRoutesService
{
	public static final int MAX_ROUTES = 10;
	// Absolute safety backstop on how many routes one generation may enumerate. "Poll more" grows the
	// live limit toward this; it exists only so a runaway query can't enumerate without bound, not as a
	// user-facing ceiling — in practice the walk-cost cap ends enumeration long before this.
	public static final int MAX_ROUTES_CAP = 250;
	private static final long CLIENT_THREAD_TIMEOUT_SECONDS = 10;
	/**
	 * When the exact target is unreachable, routes are accepted while their endpoint stays within this
	 * many tiles of the best route's endpoint (they all converge on the closest reachable area).
	 */
	private static final int CLOSEST_DISTANCE_TOLERANCE = 10;


	/**
	 * Receives progressive updates for one generation: the catalog as soon as it's known, then the
	 * routes-so-far after each one is found, and a final call with {@code done == true}. Invoked on
	 * the worker thread; the caller marshals to the Swing EDT. Stale generations stop emitting.
	 * {@code unavailable} maps each catalog method the player cannot use in the current mode to the
	 * reason why (missing item, in the bank, missing level/quest, not unlocked), so the panel can mark
	 * and explain them; it is populated in every mode.
	 */
	public interface ResultListener
	{
		void onUpdate(List<RouteOption> routes, List<TeleportMethod> catalog,
			Map<TeleportMethod, MethodAvailability> unavailable, boolean done);
	}

	/**
	 * Worker threads for the parallel seed searches (the exclusion loop itself is inherently
	 * sequential). Small pool: searches are CPU-bound.
	 */
	// Fixed pool size: the plugin hub disallows Runtime::availableProcessors. Four workers is the
	// measured sweet spot on the benchmark; on smaller machines surplus workers just idle.
	private static final int SEED_POOL_SIZE = 4;

	private final ClientThread clientThread;
	private final PathfinderConfig planningConfig;
	private final ExecutorService executor;
	private final ExecutorService seedExecutor;
	// Bumped on every generate()/cancel() so a stale in-flight generation discards its result.
	private final AtomicInteger generation = new AtomicInteger();

	public AlternativeRoutesService(ClientThread clientThread, PathfinderConfig planningConfig)
	{
		this.clientThread = clientThread;
		this.planningConfig = planningConfig;
		this.executor = Executors.newSingleThreadExecutor(
			new ThreadFactoryBuilder().setNameFormat("shortest-path-alts-%d").setDaemon(true).build());
		this.seedExecutor = Executors.newFixedThreadPool(SEED_POOL_SIZE,
			new ThreadFactoryBuilder().setNameFormat("shortest-path-alts-seed-%d").setDaemon(true).build());
	}

	/**
	 * Asynchronously computes the alternative routes, streaming progressive updates to {@code listener}
	 * (catalog first, then each route as it's found, then a final done update). Supersedes any in-flight
	 * generation.
	 */
	public void generate(int start, Set<Integer> targets, Set<TeleportMethod> userExclusions,
		AlternativeRoutesMode mode, int maxRoutes, ResultListener listener)
	{
		generate(start, targets, userExclusions, mode, maxRoutes, 0, false, listener);
	}

	/**
	 * Round-trip variant: every produced route goes out to a target AND back to the start, ranked
	 * by the combined cost — the best round-trip destination is not necessarily the nearest one-way
	 * one (a marginally farther bank with a cheap way home, or bank-unlocked teleports for the
	 * return, can win).
	 * <p>
	 * {@code costMultiple} caps each search at {@code best route cost * costMultiple} (in addition
	 * to the walk-cost ceiling), so only routes up to that many times the cheapest are computed —
	 * a cheap global teleport otherwise makes the A* heuristic flat and a search for a far-worse
	 * alternative floods the map. 0 disables the cap (used by tests). The "show more" action raises
	 * the multiple and regenerates; {@link #wasMoreLikely()} reports whether raising it further
	 * could surface routes the cap held back.
	 */
	public void generate(int start, Set<Integer> targets, Set<TeleportMethod> userExclusions,
		AlternativeRoutesMode mode, int maxRoutes, int costMultiple, boolean roundTrip, ResultListener listener)
	{
		final int gen = generation.incrementAndGet();
		final Set<Integer> targetsCopy = new HashSet<>(targets);
		final Set<TeleportMethod> userExclusionsCopy = new HashSet<>(userExclusions);
		executor.submit(() ->
		{
			try
			{
				computeRoutes(gen, start, targetsCopy, userExclusionsCopy, mode, maxRoutes, costMultiple,
					roundTrip, listener);
			}
			catch (Exception e)
			{
				log.warn("Alternative route generation failed", e);
			}
		});
	}

	public void cancel()
	{
		generation.incrementAndGet();
	}

	public void shutdown()
	{
		executor.shutdownNow();
		seedExecutor.shutdownNow();
	}

	private void computeRoutes(int gen, int start, Set<Integer> targets,
		Set<TeleportMethod> userExclusions, AlternativeRoutesMode mode, int maxRoutes, int costMultiple,
		boolean roundTrip, ResultListener listener)
	{
		final int limit = Math.max(1, Math.min(maxRoutes, MAX_ROUTES_CAP));
		final Set<Integer> ends = new HashSet<>(targets);
		final Set<TeleportMethod> excluded = new HashSet<>(userExclusions);
		final GenTimer timer = new GenTimer();
		final long wallStart = System.nanoTime();

		// Single client-thread pass per generation, with NO exclusions: snapshots the game state and
		// builds the full availability (the complete method catalog, and the base lists that per-search
		// availability is rebuilt from off-thread), and drops targets the avoid-wilderness setting forbids.
		long clientStart = System.nanoTime();
		boolean refreshed = refreshOnClientThread(Collections.emptySet(), ends, mode);
		timer.clientNanos += System.nanoTime() - clientStart;
		if (!refreshed)
		{
			emit(gen, listener, List.of(), List.of(), Map.of(), true);
			return;
		}
		final List<TeleportMethod> catalog = new ArrayList<>(planningConfig.getMethodCatalog());
		// The catalog is the full method universe in every mode; this maps each entry the player can't
		// use straight from the inventory to WHY (missing item/level/quest, in the bank, not unlocked),
		// mode-independently — the panel decides usability per mode (a banked item is usable in the
		// "Inventory + bank" mode, whose route walks to a bank) and filters by these reasons.
		final Map<TeleportMethod, MethodAvailability> statuses = planningConfig.getMethodAvailability();
		final Map<TeleportMethod, MethodAvailability> notUsable = new HashMap<>();
		for (TeleportMethod method : catalog)
		{
			MethodAvailability status = statuses.getOrDefault(method, MethodAvailability.AVAILABLE);
			if (status == MethodAvailability.AVAILABLE)
			{
				continue;
			}
			notUsable.put(method, status);
		}
		final Map<TeleportMethod, MethodAvailability> unavailable = Collections.unmodifiableMap(notUsable);
		if (ends.isEmpty())
		{
			emit(gen, listener, List.of(), catalog, unavailable, true);
			return;
		}
		// Show the catalog right away while the routes are still computing.
		emit(gen, listener, List.of(), catalog, unavailable, false);

		log.debug("[alt-routes] searching: start={}, target={}, mode={}, usableTeleports={}, catalog={}",
			WorldPointUtil.unpackWorldPoint(start),
			WorldPointUtil.unpackWorldPoint(ends.iterator().next()),
			mode, planningConfig.getUsableTeleports(false).length, catalog.size());

		// Snapshot the global-teleport candidates now, while availability reflects no exclusions; used
		// to seed extra routes if the exclusion loop dries up before the limit.
		final List<Transport> seedCandidates = new ArrayList<>(Arrays.asList(
			planningConfig.getUsableTeleports(mode == AlternativeRoutesMode.OWNED_WITH_BANK)));

		// Per-generation preprocessing: one multi-source reverse flood from the target set builds a
		// walking+transport distance field — the near-exact A* heuristic every search of this
		// generation shares (chain, walk, seeds). Compact target sets only; a map-wide nearest-X
		// set would flood everything for searches that are already cheap.
		long fieldStart = System.nanoTime();
		final DistanceField field = DistanceField.buildIfCompact(planningConfig, ends);
		timer.fieldNanos = System.nanoTime() - fieldStart;

		// Walk-only search, run concurrently on the seed pool (its own config copy — the chain
		// mutates planningConfig per iteration): its cost is a rigorous expansion cap for every
		// search that starts after it finishes (routes costlier than walking are never shown, so a
		// node above walk cost is provably useless — this keeps a dead-end seed teleport from
		// flooding the map), and its path is the last-resort route appended when the chain doesn't
		// derive it. Polled non-blockingly so route 1's latency is unchanged. Skipped for
		// primary-only generations (limit 1, panel hidden): one search, a cap can't pay for itself.
		final Future<WalkResult> walkFuture = limit > 1
			? seedExecutor.submit(() -> runWalkSearch(gen, start, ends, userExclusions, catalog, field, timer))
			: null;

		final List<RouteOption> routes = new ArrayList<>();
		final Set<String> seenSignatures = new HashSet<>();
		// Remaining distance to the target of the first route's endpoint; -1 until known. For an
		// unreachable exact target (e.g. an NPC tile) every route ends at the closest reachable area,
		// so later routes are only accepted while they get equally close (small tolerance).
		int bestRemaining = -1;
		// Whether the cost cap (best * costMultiple, below the walk ceiling) held a route back — a
		// search couldn't reach within the band. If so, "show more" (a higher multiple) can surface
		// it. Set only for cost-cap truncations, not method exhaustion or the walk ceiling.
		boolean cappedByCost = false;
		// Set when the chain stops on its own terms (methods exhausted, a duplicate signature, a
		// walk-only route, or the target drifting out of reach). If it stays false the loop simply ran
		// out of route-count budget — the count was binding, so a higher limit can surface more.
		boolean chainExhausted = false;

		for (int i = 0; i < limit; i++)
		{
			if (gen != generation.get())
			{
				return;
			}
			// Rebuild availability for the current exclusion set — pure computation over the base lists
			// captured by the client-thread pass above, so no client-thread round-trip per search.
			long rebuildStart = System.nanoTime();
			planningConfig.rebuildAvailabilityWithExclusions(excluded);
			timer.rebuildNanos += System.nanoTime() - rebuildStart;

			long searchStart = System.nanoTime();
			int walkCap = capOf(walkFuture);
			int chainCap = cappedByBestCost(walkCap, routes, costMultiple);
			// The cost cap is the binding one (tighter than the walk ceiling) — a search failing now
			// was held back by the cost band, not by walking being cheaper or methods running out.
			boolean costLimited = chainCap < walkCap;
			// Heuristic rebuilt per iteration: the exclusion set changes the usable teleports and
			// with them the field floor — excluding the good teleports raises it, so the heuristic
			// gets stronger exactly when the searches get expensive. Null field (map-wide target
			// sets) means uninformed, which those cheap searches don't need anyway.
			SearchHeuristic heuristic = SearchHeuristic.buildWithField(planningConfig, field);
			Pathfinder pathfinder = new Pathfinder(planningConfig, start, ends, null, chainCap, heuristic);
			pathfinder.run();
			long searchNanos = System.nanoTime() - searchStart;
			timer.searchNanos += searchNanos;
			timer.searches++;
			record(timer, "chain#" + i, searchNanos, pathfinder, chainCap);
			PathfinderResult result = pathfinder.getResult();
			List<PathStep> path = (result != null) ? result.getPathSteps() : List.of();
			if (result == null || path.isEmpty())
			{
				log.debug("[alt-routes] search #{} produced no path: result={}, reason={}",
					i, result == null ? "null" : "empty",
					result == null ? "n/a" : result.getTerminationReason());
				cappedByCost |= costLimited;
				chainExhausted = true;
				break;
			}

			boolean reached = result.isReached();
			// The best route reached the target, so an unreached result is NOT "the closest
			// reachable area" — it is a search truncated by the cost cap a few tiles short of the
			// goal. Accepting it through the closeness tolerance below showed a phantom
			// "unreachable" route (user capture: the quetzal whistle at cost 28 under a 27 band,
			// rendered unreachable while perfectly reachable). Stop instead: the truncation is
			// exactly what "more routes" reveals by widening the band.
			if (!reached && bestRemaining == 0)
			{
				cappedByCost |= costLimited;
				chainExhausted = true;
				break;
			}
			// Unreachable exact targets (e.g. NPC tiles) still have meaningful alternatives: different
			// methods all ending at the closest reachable area. Keep enumerating while routes get
			// equally close; stop once exclusions make the search end up meaningfully further away.
			int remaining = reached ? 0 : remainingDistance(path, ends);
			if (bestRemaining < 0)
			{
				bestRemaining = remaining;
			}
			else if (remaining > bestRemaining + CLOSEST_DISTANCE_TOLERANCE)
			{
				log.debug("[alt-routes] search #{} ends {} tiles from target (best {}); stopping with {} route(s)",
					i, remaining, bestRemaining, routes.size());
				// The search couldn't reach within the cost band — that route is beyond the cap.
				cappedByCost |= costLimited;
				chainExhausted = true;
				break;
			}

			MethodScan scan = scanMethods(planningConfig, path);
			List<TeleportMethod> methods = scan.methods;

			// Distinct method-signature gate: if this route uses the same ordered methods as a previous
			// one, excluding more would only reshuffle, so stop.
			if (!seenSignatures.add(signature(methods)))
			{
				chainExhausted = true;
				break;
			}
			routes.add(new RouteOption(path, methods, scan.methodEdges, scan.methodDurations,
				result.getTotalCost(), scan.rawCost, reached, scan.bankGated, scan.walkBefore, scan.trailingWalk));
			// Stream the route we just found so the panel shows it immediately. Round-trip mode
			// streams only the merged results — one-way costs would reorder once returns are added.
			if (!roundTrip)
			{
				emit(gen, listener, new ArrayList<>(routes), catalog, unavailable, false);
			}

			TeleportMethod primary = methods.isEmpty() ? null : methods.get(0);
			if (primary == null)
			{
				// Walk-only route: the exclusion strategy has nothing left to remove. Seeding below can
				// still surface teleport routes that lost to walking on cost.
				chainExhausted = true;
				break;
			}
			excluded.add(primary);
		}

		// Stop at the pure-walk option: once walking there is on the list, anything more expensive than
		// just walking isn't worth showing. Seeding only ever surfaces teleports that lost to walking on
		// cost (i.e. routes MORE expensive than walk-only), so skip it entirely when a walk-only route was
		// already found. A FULL list does not skip seeding: a small floor of attempts always runs, and a
		// cheaper seed evicts the costliest route — the safety net for anything the chain missed must not
		// be silenced by a low route limit (the exact failure a user capture showed at limit 10).
		boolean hasWalkOnly = routes.stream().anyMatch(RouteOption::isWalkOnly);
		if (!hasWalkOnly && !routes.isEmpty())
		{
			seedTeleportRoutes(gen, start, ends, userExclusions, mode, limit,
				seedCandidates, routes, seenSignatures, catalog, unavailable, roundTrip ? null : listener,
				bestRemaining, cappedByBestCost(capOf(walkFuture), routes, costMultiple), field, timer);
		}
		// More routes are worth polling for when the count budget was the binding limit (the chain kept
		// finding distinct routes and simply ran out of slots), or the cost cap held routes back — the
		// latter only while it sat below the walk ceiling, since once it reaches walking there's nothing
		// cheaper-than-walk left to reveal.
		int moreWalkCap = capOf(walkFuture);
		boolean costHeldBack = cappedByCost
			&& cappedByBestCost(moreWalkCap, routes, costMultiple) < moreWalkCap;
		lastGenerationMoreLikely = !chainExhausted || costHeldBack;

		// The walk-only route from the concurrent search is the last resort: append it when the
		// chain didn't derive it (signature dedup skips it when it did), under the same closeness
		// guard as every other route. Blocking here is fine — the generation is finishing anyway.
		WalkResult walk = walkResult(walkFuture);
		// Ties lose to walking: a method route costing the same as (or more than) plain walking
		// isn't worth a slot. The searches race the concurrent walk search, and now that they're
		// heuristic-directed they can finish before its cap exists — under f-ordering an
		// equal-cost method route can then win the tie the FIFO search implicitly gave to walking.
		if (walk != null && walk.cap != Integer.MAX_VALUE)
		{
			final int walkCost = walk.cap;
			routes.removeIf(r -> !r.isWalkOnly() && r.getTotalCost() >= walkCost);
		}
		// Always surface walking as the baseline option (when the target is reachable on foot and the
		// chain didn't already derive it), even at the route limit — the player should always see
		// "…or just walk N tiles" as a complete-picture fallback, whatever teleports were found.
		if (walk != null && walk.cap != Integer.MAX_VALUE
			&& (bestRemaining < 0 || walk.remaining <= bestRemaining + CLOSEST_DISTANCE_TOLERANCE)
			&& seenSignatures.add(signature(walk.route.getMethods())))
		{
			routes.add(walk.route);
			// Keep it within the limit by dropping the costliest teleport route it displaces.
			while (routes.size() > limit)
			{
				int drop = -1;
				int maxCost = -1;
				for (int r = 0; r < routes.size(); r++)
				{
					if (!routes.get(r).isWalkOnly() && routes.get(r).getTotalCost() > maxCost)
					{
						maxCost = routes.get(r).getTotalCost();
						drop = r;
					}
				}
				if (drop < 0)
				{
					break;
				}
				routes.remove(drop);
			}
		}

		routes.sort(Comparator.comparingInt(RouteOption::getTotalCost));
		// Drop anything after the pure-walk option (belt-and-braces alongside the skipped seeding above).
		for (int i = 0; i < routes.size(); i++)
		{
			if (routes.get(i).isWalkOnly())
			{
				routes.subList(i + 1, routes.size()).clear();
				break;
			}
		}

		// Round-trip mode: give every one-way route its return leg and re-rank by combined cost.
		if (roundTrip && !routes.isEmpty() && gen == generation.get())
		{
			List<RouteOption> merged = buildRoundTrips(gen, start, userExclusions, routes,
				catalog, unavailable, listener, timer);
			routes.clear();
			routes.addAll(merged);
		}

		synchronized (timer)
		{
			// Retained for the GPS debug snapshot:
			// [wallMs, clientMs, rebuildMs, searchCpuMs, searches, fieldMs].
			lastTimingSummary = new long[]{
				(System.nanoTime() - wallStart) / 1_000_000,
				timer.clientNanos / 1_000_000,
				timer.rebuildNanos / 1_000_000,
				timer.searchNanos / 1_000_000,
				timer.searches,
				timer.fieldNanos / 1_000_000};
			List<SearchRecord> records = new ArrayList<>(timer.records);
			records.sort(Comparator.comparingLong((SearchRecord r) -> r.cpuMs).reversed());
			lastSearchRecords = Collections.unmodifiableList(records);
			log.debug("[alt-routes] generated {} route(s); timing: wall={}ms client={}ms rebuild={}ms searchCpu={}ms ({} searches)",
				routes.size(),
				lastTimingSummary[0], lastTimingSummary[1], lastTimingSummary[2],
				lastTimingSummary[3], lastTimingSummary[4]);
		}
		emit(gen, listener, new ArrayList<>(routes), catalog, unavailable, true);
	}

	/**
	 * The last completed generation's timing, for the GPS debug snapshot:
	 * [wallMs, clientMs, rebuildMs, searchCpuMs, searches]. Null before the first generation.
	 */
	private volatile long[] lastTimingSummary;

	long[] getLastTimingSummary()
	{
		long[] summary = lastTimingSummary;
		return summary == null ? null : summary.clone();
	}

	/**
	 * Runs the reference (oracle) search — textbook Dijkstra over the identical edge expansion —
	 * for one query, on a fresh client-thread refresh with the given exclusions applied. The
	 * benchmark's optimality audit: the best route a generation found must cost exactly this.
	 * Untimed and never in the client hot path. Returns null when the refresh fails.
	 */
	public ReferenceDijkstra.Result auditOptimality(int start, Set<Integer> targets,
		Set<TeleportMethod> userExclusions, AlternativeRoutesMode mode)
	{
		final Set<Integer> ends = new HashSet<>(targets);
		if (!refreshOnClientThread(Collections.emptySet(), ends, mode))
		{
			return null;
		}
		planningConfig.rebuildAvailabilityWithExclusions(new HashSet<>(userExclusions));
		return ReferenceDijkstra.search(planningConfig, start, ends);
	}

	// Whether the last generation's cost cap held routes back — a higher cost multiple ("show more")
	// could surface them. False when walking is the binding ceiling (nothing cheaper-than-walk left).
	private volatile boolean lastGenerationMoreLikely = false;

	/** Whether raising the cost multiple and regenerating could surface more routes. */
	public boolean wasMoreLikely()
	{
		return lastGenerationMoreLikely;
	}

	/**
	 * Per-generation timing breakdown: time blocked on the client thread, time rebuilding
	 * availability off-thread, and time in the searches. Seed searches run in parallel, so their
	 * rebuild/search nanos are CPU-summed across workers (can exceed wall time); accumulation from
	 * worker threads synchronizes on this object.
	 */
	private static final class GenTimer
	{
		private long clientNanos;
		private long rebuildNanos;
		private long searchNanos;
		private long fieldNanos;
		private int searches;
		private final List<SearchRecord> records = new ArrayList<>();
	}

	/**
	 * One search's profile within a generation — which search ran, what it found, and how much it
	 * explored — for the benchmark report and for pinpointing slow searches (the aggregate GenTimer
	 * numbers can't tell a few expensive searches from many cheap ones).
	 */
	static final class SearchRecord
	{
		final String label;
		final long cpuMs;
		/** Total cost of the found path, -1 when the search produced none. */
		final int resultCost;
		final boolean reached;
		final String termination;
		final int nodesChecked;
		final int transportsChecked;
		final boolean capped;
		final boolean astar;

		SearchRecord(String label, long cpuMs, int resultCost, boolean reached, String termination,
			int nodesChecked, int transportsChecked, boolean capped, boolean astar)
		{
			this.label = label;
			this.cpuMs = cpuMs;
			this.resultCost = resultCost;
			this.reached = reached;
			this.termination = termination;
			this.nodesChecked = nodesChecked;
			this.transportsChecked = transportsChecked;
			this.capped = capped;
			this.astar = astar;
		}
	}

	/** Appends one search's profile to the generation's records (seed workers call concurrently). */
	private static void record(GenTimer timer, String label, long searchNanos, Pathfinder pathfinder, int cap)
	{
		PathfinderResult result = pathfinder.getResult();
		Pathfinder.PathfinderStats stats = pathfinder.getStats();
		List<PathStep> path = result != null ? result.getPathSteps() : null;
		SearchRecord searchRecord = new SearchRecord(
			label,
			searchNanos / 1_000_000,
			(path != null && !path.isEmpty()) ? result.getTotalCost() : -1,
			result != null && result.isReached(),
			(result != null && result.getTerminationReason() != null) ? result.getTerminationReason().name() : "NONE",
			stats != null ? stats.getNodesChecked() : -1,
			stats != null ? stats.getTransportsChecked() : -1,
			cap != Integer.MAX_VALUE,
			pathfinder.isAstar());
		synchronized (timer)
		{
			timer.records.add(searchRecord);
		}
	}

	/**
	 * The last completed generation's per-search profiles, slowest first. Empty before the first
	 * generation. For the benchmark report.
	 */
	List<SearchRecord> getLastSearchRecords()
	{
		List<SearchRecord> records = lastSearchRecords;
		return records == null ? List.of() : records;
	}

	private volatile List<SearchRecord> lastSearchRecords;

	/**
	 * Seeds additional routes when the exclusion loop ended early: for each candidate global teleport
	 * (best estimated arrival first), run one search with every OTHER global teleport excluded, so the
	 * result is "the best route if you use this teleport". Routes with an already-seen method
	 * signature, walk-only results, or endpoints meaningfully further than the best route are skipped.
	 */
	private void seedTeleportRoutes(int gen, int start, Set<Integer> ends, Set<TeleportMethod> userExclusions,
		AlternativeRoutesMode mode, int limit, List<Transport> seedCandidates, List<RouteOption> routes,
		Set<String> seenSignatures, List<TeleportMethod> catalog, Map<TeleportMethod, MethodAvailability> unavailable,
		ResultListener listener, int bestRemaining, int costCap, DistanceField field, GenTimer timer)
	{
		// Every global teleport is excluded from each seed search except the seed itself, so the
		// exclusion universe must span ALL candidates — including ones that don't get an attempt.
		final Set<TeleportMethod> allSeedMethods = new HashSet<>();
		for (Transport transport : seedCandidates)
		{
			allSeedMethods.add(TeleportMethod.fromTransport(transport));
		}

		// A floor of attempts even when the chain filled every slot: the best-ranked seeds are the
		// safety net against a missed-cheap-route bug, and cost nearly nothing when they lose.
		final int maxAttempts = Math.max(6, (limit - routes.size()) * 3);
		final List<Transport> attempts = rankSeedCandidates(seedCandidates, ends, userExclusions, maxAttempts);
		if (attempts.isEmpty())
		{
			return;
		}

		// One config per concurrent worker (shares immutable data + the refreshed state); the seed
		// searches are independent, so they run in parallel on the seed pool. Results are collected
		// and accepted on this (generation) thread in completion order.
		final int workers = Math.min(SEED_POOL_SIZE, attempts.size());
		final Queue<PathfinderConfig> configPool = new ConcurrentLinkedQueue<>();
		for (int i = 0; i < workers; i++)
		{
			configPool.add(planningConfig.copyForParallelSearch());
		}
		final AtomicBoolean stop = new AtomicBoolean(false);
		final CompletionService<SeedResult> completion = new ExecutorCompletionService<>(seedExecutor);
		final List<Future<SeedResult>> futures = new ArrayList<>(attempts.size());
		for (Transport seed : attempts)
		{
			futures.add(completion.submit(() ->
				runSeedSearch(gen, stop, start, ends, userExclusions, allSeedMethods, seed, bestRemaining, costCap,
					field, configPool, timer)));
		}

		try
		{
			for (int i = 0; i < futures.size() && gen == generation.get(); i++)
			{
				SeedResult seedResult;
				try
				{
					seedResult = completion.take().get();
				}
				catch (ExecutionException e)
				{
					log.warn("Seed search failed", e);
					continue;
				}
				if (seedResult == null || !seenSignatures.add(signature(seedResult.scan.methods)))
				{
					continue;
				}
				// A full list doesn't end the pass: a strictly cheaper seed evicts the costliest
				// teleport route — the seeds are the safety net for anything the chain missed.
				if (routes.size() >= limit)
				{
					int evict = -1;
					int maxCost = seedResult.totalCost;
					for (int r = 0; r < routes.size(); r++)
					{
						if (!routes.get(r).isWalkOnly() && routes.get(r).getTotalCost() > maxCost)
						{
							maxCost = routes.get(r).getTotalCost();
							evict = r;
						}
					}
					if (evict < 0)
					{
						continue;
					}
					routes.remove(evict);
				}
				routes.add(new RouteOption(seedResult.path, seedResult.scan.methods, seedResult.scan.methodEdges,
					seedResult.scan.methodDurations, seedResult.totalCost, seedResult.scan.rawCost,
					seedResult.reached, seedResult.scan.bankGated, seedResult.scan.walkBefore,
					seedResult.scan.trailingWalk));
				emit(gen, listener, new ArrayList<>(routes), catalog, unavailable, false);
			}
		}
		catch (InterruptedException e)
		{
			// Interruption means shutdown/cancellation: just stop. (The plugin hub disallows
			// Thread::interrupt, so the flag is not restored; this generation thread is ours and
			// nothing downstream reads it.)
		}
		finally
		{
			stop.set(true);
			for (Future<SeedResult> future : futures)
			{
				future.cancel(false);
			}
		}
	}

	/**
	 * Selects and orders the seed-teleport attempt list: user-excluded methods and duplicate landings
	 * (e.g. tab vs spell to the same tile) are dropped, the rest are ranked by estimated arrival cost
	 * — the teleport's cast/travel duration (in {@link CostUnits}) plus the straight-line distance
	 * from its landing to the NEAREST target (a lower bound of the remaining run, in the same
	 * currency) — and the list is capped at {@code maxAttempts}. Ranking against the whole target set
	 * matters for multi-target queries ("nearest bank" has ~150): a teleport landing beside a distant
	 * bank is an excellent seed, but measured against only the player's local bank it would rank last
	 * and be cut by the cap.
	 * <p>
	 * Candidates landing farther than the start are deliberately KEPT: when an obstacle separates the
	 * start from the target, the straight-line start distance understates the real walk, and a
	 * teleport landing "farther" can be much closer by path (it also keeps cross-plane landings,
	 * where the straight-line distance is unknowable). Ranking tries them last and the attempt cap
	 * bounds the work, so this costs nothing when closer candidates exist. Each attempt is priced by
	 * a full search afterwards — this pre-selection never decides a shown cost, only which candidates
	 * get a search.
	 */
	static List<Transport> rankSeedCandidates(List<Transport> seedCandidates, Set<Integer> targets,
		Set<TeleportMethod> userExclusions, int maxAttempts)
	{
		final List<Transport> ranked = new ArrayList<>(seedCandidates);
		// Long arithmetic: a cross-plane landing has straight-line distance Integer.MAX_VALUE, which
		// must rank last rather than overflow into ranking first.
		final int[] targetArray = new int[targets.size()];
		int t = 0;
		for (int target : targets)
		{
			targetArray[t++] = target;
		}
		ranked.sort(Comparator.comparingLong(
			candidate -> (long) CostUnits.fromTicks(candidate.getDuration())
				+ distanceToNearest(candidate.getDestination(), targetArray)));
		final Map<Integer, Transport> byDestination = new LinkedHashMap<>();
		for (Transport transport : ranked)
		{
			if (userExclusions.contains(TeleportMethod.fromTransport(transport)))
			{
				continue;
			}
			byDestination.putIfAbsent(transport.getDestination(), transport);
		}
		final List<Transport> attempts = new ArrayList<>(Math.max(0, Math.min(maxAttempts, byDestination.size())));
		for (Transport seed : byDestination.values())
		{
			if (attempts.size() >= maxAttempts)
			{
				break;
			}
			attempts.add(seed);
		}
		return attempts;
	}

	/** Straight-line distance from a landing to its nearest target (MAX_VALUE across planes). */
	private static int distanceToNearest(int packedPoint, int[] targets)
	{
		int best = Integer.MAX_VALUE;
		for (int target : targets)
		{
			best = Math.min(best, WorldPointUtil.distanceBetween(packedPoint, target));
		}
		return best;
	}

	/**
	 * One parallel seed attempt: rebuild availability on a worker-owned config with every other
	 * global teleport excluded, search, and pre-filter the result. Returns null when rejected.
	 */
	private SeedResult runSeedSearch(int gen, AtomicBoolean stop, int start, Set<Integer> ends,
		Set<TeleportMethod> userExclusions, Set<TeleportMethod> allSeedMethods, Transport seed,
		int bestRemaining, int costCap, DistanceField field, Queue<PathfinderConfig> configPool, GenTimer timer)
	{
		if (gen != generation.get() || stop.get())
		{
			return null;
		}
		PathfinderConfig config = configPool.poll();
		if (config == null)
		{
			// Should not happen (pool size == max concurrency), but never block on it.
			config = planningConfig.copyForParallelSearch();
		}
		try
		{
			// Exclude every other global teleport so the search is forced onto (at most) this one.
			Set<TeleportMethod> seedExclusions = new HashSet<>(allSeedMethods);
			seedExclusions.remove(TeleportMethod.fromTransport(seed));
			seedExclusions.addAll(userExclusions);
			long rebuildStart = System.nanoTime();
			config.rebuildAvailabilityWithExclusions(seedExclusions);
			long searchStart = System.nanoTime();
			// Seed searches exclude every other global teleport, so the floor comes from the seed
			// itself and the search's own optimal route uses it — strongly directed by
			// construction, and the walk-cost cap bounds any residue.
			SearchHeuristic heuristic = SearchHeuristic.buildWithField(config, field);
			Pathfinder pathfinder = new Pathfinder(config, start, ends, null, costCap, heuristic);
			pathfinder.run();
			long searchEnd = System.nanoTime();
			synchronized (timer)
			{
				timer.rebuildNanos += searchStart - rebuildStart;
				timer.searchNanos += searchEnd - searchStart;
				timer.searches++;
			}
			String seedLabel = seed.getDisplayInfo() != null && !seed.getDisplayInfo().isEmpty()
				? seed.getDisplayInfo()
				: WorldPointUtil.unpackWorldX(seed.getDestination()) + "," + WorldPointUtil.unpackWorldY(seed.getDestination());
			record(timer, "seed:" + seedLabel, searchEnd - searchStart, pathfinder, costCap);

			PathfinderResult result = pathfinder.getResult();
			List<PathStep> path = (result != null) ? result.getPathSteps() : List.of();
			if (result == null || path.isEmpty())
			{
				return null;
			}
			boolean reached = result.isReached();
			// The best route reached the target: an unreached seed is a cost-cap truncation a few
			// tiles short, not a route — dropping it keeps phantom "unreachable" entries out.
			if (!reached && bestRemaining == 0)
			{
				return null;
			}
			int remaining = reached ? 0 : remainingDistance(path, ends);
			if (bestRemaining >= 0 && remaining > bestRemaining + CLOSEST_DISTANCE_TOLERANCE)
			{
				return null;
			}
			MethodScan scan = scanMethods(config, path);
			if (scan.methods.isEmpty())
			{
				// Walk-only: the seed teleport didn't help.
				return null;
			}
			return new SeedResult(path, scan, result.getTotalCost(), reached);
		}
		finally
		{
			configPool.offer(config);
		}
	}

	/**
	 * The walk-only search: every method in the catalog excluded (plain connectors — doors, stairs,
	 * shortcuts — remain, walking uses those). Its reached cost is the universal search cap, and its
	 * path is the last-resort route. Runs on the seed pool concurrently with the chain's first
	 * searches, on its own config copy.
	 */
	private WalkResult runWalkSearch(int gen, int start, Set<Integer> ends, Set<TeleportMethod> userExclusions,
		List<TeleportMethod> catalog, DistanceField field, GenTimer timer)
	{
		if (gen != generation.get())
		{
			return null;
		}
		PathfinderConfig config = planningConfig.copyForParallelSearch();
		Set<TeleportMethod> walkExclusions = new HashSet<>(catalog);
		walkExclusions.addAll(userExclusions);
		long rebuildStart = System.nanoTime();
		config.rebuildAvailabilityWithExclusions(walkExclusions);
		long searchStart = System.nanoTime();
		// With every method excluded the floor is effectively unbounded, so h is the raw field —
		// the walk search (the biggest disc of the generation) collapses to a corridor.
		SearchHeuristic heuristic = SearchHeuristic.buildWithField(config, field);
		Pathfinder pathfinder = new Pathfinder(config, start, ends, null, Integer.MAX_VALUE, heuristic);
		pathfinder.run();
		long searchEnd = System.nanoTime();
		synchronized (timer)
		{
			timer.rebuildNanos += searchStart - rebuildStart;
			timer.searchNanos += searchEnd - searchStart;
			timer.searches++;
		}
		record(timer, "walk", searchEnd - searchStart, pathfinder, Integer.MAX_VALUE);

		PathfinderResult result = pathfinder.getResult();
		List<PathStep> path = (result != null) ? result.getPathSteps() : List.of();
		if (result == null || path.isEmpty())
		{
			return null;
		}
		MethodScan scan = scanMethods(config, path);
		if (!scan.methods.isEmpty())
		{
			// Defensive: with the whole catalog excluded no method should appear; if one does,
			// this isn't a pure walk and must not serve as the walk cap or route.
			return null;
		}
		boolean reached = result.isReached();
		RouteOption route = new RouteOption(path, scan.methods, scan.methodEdges, scan.methodDurations,
			result.getTotalCost(), scan.rawCost, reached, scan.bankGated, scan.walkBefore, scan.trailingWalk);
		// Only a walk that actually reaches the target is a valid cost ceiling; a closest-tile
		// partial walk (island target) must not constrain teleport routes that can truly get there.
		int cap = reached ? result.getTotalCost() : Integer.MAX_VALUE;
		return new WalkResult(route, cap, reached ? 0 : remainingDistance(path, ends));
	}

	/**
	 * Extends each one-way route with its return leg: one search from the route's endpoint back to
	 * the start — all sharing a single start-rooted distance field — the two paths concatenated and
	 * re-scanned so methods, directions and progress work on the whole loop, then re-ranked by
	 * combined cost. The best round-trip destination is not necessarily the nearest one-way one.
	 * In bank mode the return leg naturally gets the banked-state teleports: the leg starts ON a
	 * bank tile, so the engine flips into the banked state immediately.
	 */
	private List<RouteOption> buildRoundTrips(int gen, int start, Set<TeleportMethod> userExclusions,
		List<RouteOption> oneWays, List<TeleportMethod> catalog,
		Map<TeleportMethod, MethodAvailability> unavailable, ResultListener listener, GenTimer timer)
	{
		final Set<Integer> home = Set.of(start);
		long fieldStart = System.nanoTime();
		final DistanceField returnField = DistanceField.buildIfCompact(planningConfig, home);
		timer.fieldNanos += System.nanoTime() - fieldStart;

		// Return searches run with the base availability (user exclusions only): the chain left
		// planningConfig with its last exclusion set.
		long rebuildStart = System.nanoTime();
		planningConfig.rebuildAvailabilityWithExclusions(userExclusions);
		timer.rebuildNanos += System.nanoTime() - rebuildStart;
		final SearchHeuristic heuristic = SearchHeuristic.buildWithField(planningConfig, returnField);

		final List<RouteOption> merged = new ArrayList<>();
		final Set<String> signatures = new HashSet<>();
		for (RouteOption oneWay : oneWays)
		{
			if (gen != generation.get())
			{
				return merged;
			}
			final List<PathStep> outPath = oneWay.getPath();
			final int endpoint = outPath.get(outPath.size() - 1).getPackedPosition();
			long searchStart = System.nanoTime();
			Pathfinder back = new Pathfinder(planningConfig, endpoint, home, null, Integer.MAX_VALUE, heuristic);
			back.run();
			long searchNanos = System.nanoTime() - searchStart;
			synchronized (timer)
			{
				timer.searchNanos += searchNanos;
				timer.searches++;
			}
			record(timer, "return:" + WorldPointUtil.unpackWorldX(endpoint)
				+ "," + WorldPointUtil.unpackWorldY(endpoint), searchNanos, back, Integer.MAX_VALUE);

			PathfinderResult result = back.getResult();
			List<PathStep> returnPath = (result != null) ? result.getPathSteps() : List.of();
			if (result == null || returnPath.isEmpty() || !result.isReached())
			{
				continue;
			}
			// Concatenate, dropping the duplicated endpoint tile, and re-derive the method scan
			// over the whole loop so edges/durations/legs are consistent for the overlay.
			List<PathStep> fullPath = new ArrayList<>(outPath);
			fullPath.addAll(returnPath.subList(Math.min(1, returnPath.size()), returnPath.size()));
			MethodScan scan = scanMethods(planningConfig, fullPath);
			if (!signatures.add(signature(scan.methods)))
			{
				continue;
			}
			// The turnaround is the outbound path's last tile (the destination); the return leg's
			// duplicated first step was dropped, so outbound indexes are unshifted in fullPath.
			merged.add(new RouteOption(fullPath, scan.methods, scan.methodEdges, scan.methodDurations,
				oneWay.getTotalCost() + result.getTotalCost(), scan.rawCost, oneWay.isReached(),
				scan.bankGated, scan.walkBefore, scan.trailingWalk, outPath.size() - 1));
			merged.sort(Comparator.comparingInt(RouteOption::getTotalCost));
			emit(gen, listener, new ArrayList<>(merged), catalog, unavailable, false);
		}
		return merged;
	}

	/**
	 * The current search cap from the walk search, polled without blocking: MAX_VALUE (uncapped)
	 * until the walk finishes — so the chain's first searches never wait on it — then the walk cost.
	 */
	/**
	 * Tightens a search's cost cap to {@code best route cost * multiple} once a route is found, so
	 * searches for far-worse alternatives don't flood the map. Routes are added in non-decreasing
	 * cost order, so the first is the cheapest. No effect before the first route, when
	 * {@code multiple <= 0} (uncapped), or when the product exceeds {@code cap}.
	 */
	// A super-cheap best route must not strangle the cost band: with the best route at cost 9 (a
	// direct teleport), best x 3 = 27 hid every teleport+short-walk combination (a 28-cost quetzal
	// whistle route) behind "more". The band prices off max(best, this), so the default band is
	// never tighter than ~100 units (~30s of travel) and still grows with every "more" press.
	private static final int MIN_BEST_FOR_BAND = 35;

	private static int cappedByBestCost(int cap, List<RouteOption> routes, int multiple)
	{
		if (routes.isEmpty() || multiple <= 0)
		{
			return cap;
		}
		long byBest = (long) Math.max(routes.get(0).getTotalCost(), MIN_BEST_FOR_BAND) * multiple;
		return byBest < cap ? (int) byBest : cap;
	}

	private static int capOf(Future<WalkResult> walkFuture)
	{
		if (walkFuture == null || !walkFuture.isDone())
		{
			return Integer.MAX_VALUE;
		}
		try
		{
			WalkResult walk = walkFuture.get();
			return walk == null ? Integer.MAX_VALUE : walk.cap;
		}
		catch (InterruptedException e)
		{
			// Shutdown/cancellation: uncapped is always safe. (Hub rule: no Thread::interrupt.)
			return Integer.MAX_VALUE;
		}
		catch (ExecutionException e)
		{
			log.warn("Walk search failed", e);
			return Integer.MAX_VALUE;
		}
	}

	/** The finished walk search's result, waiting for it if needed (used once, at generation end). */
	private static WalkResult walkResult(Future<WalkResult> walkFuture)
	{
		if (walkFuture == null)
		{
			return null;
		}
		try
		{
			return walkFuture.get();
		}
		catch (InterruptedException e)
		{
			// Shutdown/cancellation: no walk route to append. (Hub rule: no Thread::interrupt.)
			return null;
		}
		catch (ExecutionException e)
		{
			log.warn("Walk search failed", e);
			return null;
		}
	}

	/** The walk-only search's route, its cost cap for other searches, and its closeness to the target. */
	private static final class WalkResult
	{
		private final RouteOption route;
		private final int cap;
		private final int remaining;

		WalkResult(RouteOption route, int cap, int remaining)
		{
			this.route = route;
			this.cap = cap;
			this.remaining = remaining;
		}
	}

	/**
	 * A candidate route produced by one parallel seed search, before signature dedup on the
	 * generation thread.
	 */
	private static final class SeedResult
	{
		private final List<PathStep> path;
		private final MethodScan scan;
		private final int totalCost;
		private final boolean reached;

		SeedResult(List<PathStep> path, MethodScan scan, int totalCost, boolean reached)
		{
			this.path = path;
			this.scan = scan;
			this.totalCost = totalCost;
			this.reached = reached;
		}
	}

	private static int remainingDistance(List<PathStep> path, Set<Integer> targets)
	{
		int end = path.get(path.size() - 1).getPackedPosition();
		int best = Integer.MAX_VALUE;
		for (int target : targets)
		{
			best = Math.min(best, WorldPointUtil.distanceBetween(end, target));
		}
		return best;
	}

	private void emit(int gen, ResultListener listener, List<RouteOption> routes,
		List<TeleportMethod> catalog, Map<TeleportMethod, MethodAvailability> unavailable, boolean done)
	{
		// A null listener means streaming is suppressed for this phase (round-trip mode streams
		// only merged results).
		if (listener != null && gen == generation.get())
		{
			listener.onUpdate(routes, catalog, unavailable, done);
		}
	}

	/**
	 * Runs {@code setExcludedMethods + refresh} (and, when {@code endsToFilter} is non-null, target
	 * wilderness filtering) on the client thread and blocks the worker until it completes.
	 *
	 * @return false if the client thread did not run the task within the timeout.
	 */
	private boolean refreshOnClientThread(Set<TeleportMethod> excluded, Set<Integer> endsToFilter, AlternativeRoutesMode mode)
	{
		final Set<TeleportMethod> excludedSnapshot = new HashSet<>(excluded);
		final CountDownLatch latch = new CountDownLatch(1);
		clientThread.invokeLater(() ->
		{
			try
			{
				planningConfig.setPlanningMode(mode == AlternativeRoutesMode.ALL_EVERYTHING);
				planningConfig.setBypassItemPossession(!mode.isOwned());
				planningConfig.setConsiderBank(mode == AlternativeRoutesMode.OWNED_WITH_BANK);
				planningConfig.setExcludedMethods(excludedSnapshot);
				planningConfig.refresh();
				if (endsToFilter != null)
				{
					planningConfig.filterLocations(endsToFilter, true);
				}
			}
			finally
			{
				latch.countDown();
			}
		});
		try
		{
			if (!latch.await(CLIENT_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
			{
				log.warn("Timed out waiting for planning config refresh on client thread");
				return false;
			}
			return true;
		}
		catch (InterruptedException e)
		{
			// Shutdown/cancellation while waiting on the client thread; the generation aborts.
			// (Hub rule: no Thread::interrupt.)
			return false;
		}
	}

	/**
	 * Derives the ordered list of teleport/transport methods a path uses, by inspecting each edge for
	 * a method-type transport (anything beyond plain walking connectors) whose destination matches.
	 * Also collects which of those methods are bank-gated (only available in the post-bank state), i.e.
	 * the route walks to a bank to withdraw that method's required item first — so the panel can say
	 * which method the bank detour is for.
	 */
	private MethodScan scanMethods(PathfinderConfig config, List<PathStep> path)
	{
		List<TeleportMethod> methods = new ArrayList<>();
		List<Integer> methodEdges = new ArrayList<>();
		List<Integer> methodDurations = new ArrayList<>();
		Set<TeleportMethod> bankGated = new LinkedHashSet<>();
		if (path == null)
		{
			return new MethodScan(methods, methodEdges, methodDurations, bankGated, 0, new ArrayList<>(), 0);
		}
		int rawCost = 0;
		// Walking-leg lengths: tiles walked before each method (parallel to `methods`), and after the
		// last one. Plain connectors (doors, stairs, shortcuts) count into the leg they sit in.
		List<Integer> walkBefore = new ArrayList<>();
		int legSteps = 0;
		for (int i = 1; i < path.size(); i++)
		{
			PathStep from = path.get(i - 1);
			PathStep to = path.get(i);
			boolean bankVisited = from.isBankVisited() || to.isBankVisited();
			Transport chosen = matchMethodTransport(config, from.getPackedPosition(), to.getPackedPosition(), bankVisited);
			if (chosen != null)
			{
				TeleportMethod method = TeleportMethod.fromTransport(chosen);
				methods.add(method);
				methodEdges.add(i);
				methodDurations.add(chosen.getDuration());
				walkBefore.add(legSteps);
				legSteps = 0;
				// Bank-gated: used in the post-bank state and not available without the bank.
				if (bankVisited && !availableWithoutBank(config, from.getPackedPosition(), chosen))
				{
					bankGated.add(method);
				}
			}
			// Raw cost: what the edge costs without any configured weights, in CostUnits (run-tiles) —
			// a transport edge counts its travel time normalized to units, a walking edge its tile
			// distance (mirrors the search's own cost accumulation minus the additional/weight terms).
			// With no weights the route's rawCost IS its ETA in units, so cost order == ETA order.
			Transport edgeTransport = chosen != null
				? chosen
				: matchAnyTransport(config, from.getPackedPosition(), to.getPackedPosition(), bankVisited);
			int edgeCost = edgeTransport != null
				? CostUnits.fromTicks(edgeTransport.getDuration())
				: WorldPointUtil.distanceBetween(from.getPackedPosition(), to.getPackedPosition());
			rawCost += edgeCost;
			if (chosen == null)
			{
				legSteps += edgeCost;
			}
		}
		return new MethodScan(methods, methodEdges, methodDurations, bankGated, rawCost, walkBefore, legSteps);
	}

	private static boolean availableWithoutBank(PathfinderConfig config, int origin, Transport transport)
	{
		for (Transport candidate : config.getTransportsPacked(false)
			.getOrDefault(origin, TransportAvailability.EMPTY_TRANSPORTS))
		{
			if (candidate == transport)
			{
				return true;
			}
		}
		for (Transport candidate : config.getUsableTeleports(false))
		{
			if (candidate == transport)
			{
				return true;
			}
		}
		return false;
	}

	private static final class MethodScan
	{
		private final List<TeleportMethod> methods;
		// Path index of the edge each method sits on (parallel to methods): the index of the step the
		// method arrives at. Authoritative for the directions overlay, which cannot re-derive methods
		// against the main config (the route came from the planning config's availability).
		private final List<Integer> methodEdges;
		// Travel time of each method's transport in game ticks (parallel to methods), for ETAs.
		private final List<Integer> methodDurations;
		private final Set<TeleportMethod> bankGated;
		// Path cost without any configured weights, in CostUnits (run-tiles, 0.3s each): walk
		// distance plus time-normalized transport travel times. This is the route's ETA.
		private final int rawCost;
		// Tiles walked before each method (parallel to methods) and after the last one.
		private final List<Integer> walkBefore;
		private final int trailingWalk;

		MethodScan(List<TeleportMethod> methods, List<Integer> methodEdges, List<Integer> methodDurations,
			Set<TeleportMethod> bankGated, int rawCost, List<Integer> walkBefore, int trailingWalk)
		{
			this.methods = methods;
			this.methodEdges = methodEdges;
			this.methodDurations = methodDurations;
			this.bankGated = bankGated;
			this.rawCost = rawCost;
			this.walkBefore = walkBefore;
			this.trailingWalk = trailingWalk;
		}
	}

	private static Transport matchMethodTransport(PathfinderConfig config, int origin, int destination, boolean bankVisited)
	{
		return cheapestMatch(config, origin, destination, bankVisited, true);
	}

	/**
	 * Like {@link #matchMethodTransport} but without the method-type filter: also matches plain
	 * connectors (doors, stairs, agility shortcuts, ...) so the raw-cost scan can use the transport's
	 * travel time for any edge the search traversed via a transport.
	 */
	private static Transport matchAnyTransport(PathfinderConfig config, int origin, int destination, boolean bankVisited)
	{
		return cheapestMatch(config, origin, destination, bankVisited, false);
	}

	/**
	 * The matching transport the search would have used for this edge: among the fixed-origin
	 * transports at {@code origin} (fairy rings, boats, doors, ...) and the global teleports
	 * (castable from anywhere, matched purely on destination), several can share the destination
	 * tile — e.g. the Varrock Teleport spell and its tab. The search settles the cheapest edge, so
	 * the scan must attribute the same one: picking any other misstates the raw (ETA) cost and can
	 * even name the wrong method on the route card. {@code methodsOnly} restricts the match to
	 * method-type transports (excluding plain connectors like doors and stairs).
	 */
	private static Transport cheapestMatch(PathfinderConfig config, int origin, int destination,
		boolean bankVisited, boolean methodsOnly)
	{
		Transport best = null;
		long bestCost = Long.MAX_VALUE;
		Transport[] atOrigin = config.getTransportsPacked(bankVisited)
			.getOrDefault(origin, TransportAvailability.EMPTY_TRANSPORTS);
		for (Transport transport : atOrigin)
		{
			if (transport.getDestination() == destination
				&& (!methodsOnly || TeleportMethod.isMethodType(transport.getType()))
				&& searchEdgeCost(config, transport) < bestCost)
			{
				best = transport;
				bestCost = searchEdgeCost(config, transport);
			}
		}
		for (Transport transport : config.getUsableTeleports(bankVisited))
		{
			if (transport.getDestination() == destination
				&& (!methodsOnly || TeleportMethod.isMethodType(transport.getType()))
				&& searchEdgeCost(config, transport) < bestCost)
			{
				best = transport;
				bestCost = searchEdgeCost(config, transport);
			}
		}
		return best;
	}

	/** A transport edge's cost as the search charges it (see NodeGraph.createTransport's clamp). */
	private static int searchEdgeCost(PathfinderConfig config, Transport transport)
	{
		return Math.max(0, CostUnits.fromTicks(transport.getDuration())
			+ config.getAdditionalTransportCost(transport));
	}

	private static String signature(List<TeleportMethod> methods)
	{
		if (methods.isEmpty())
		{
			return "<walk-only>";
		}
		StringBuilder sb = new StringBuilder();
		for (TeleportMethod method : methods)
		{
			sb.append(method.toString()).append('|');
		}
		return sb.toString();
	}
}
