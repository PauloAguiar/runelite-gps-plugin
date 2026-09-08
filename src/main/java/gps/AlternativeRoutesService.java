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
	/** Closest-approach routes kept for a provably unreachable target (each is a full-world flood). */
	static final int UNREACHABLE_ESCAPE_ROUTES = 3;
	private static final long CLIENT_THREAD_TIMEOUT_SECONDS = 10;

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
	 * The specific missing-unlock reason per unavailable catalog method ("Requires 60 Mining"),
	 * from the same client-thread refresh that produced the availability statuses.
	 */
	public Map<TeleportMethod, String> getAvailabilityDetails()
	{
		return planningConfig.getMethodAvailabilityDetail();
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
				// Without a terminal update the plugin keeps altGenerationInFlight forever and
				// the panel sits on "Finding the best route". emit() itself drops stale gens.
				emit(gen, listener, List.of(), List.of(), Map.of(), true);
			}
		});
	}

	public void cancel()
	{
		generation.incrementAndGet();
	}

	/** What the panel shows beside the catalog: the catalog and who can't use what. */
	public interface CatalogListener
	{
		void onCatalog(List<TeleportMethod> catalog, Map<TeleportMethod, MethodAvailability> unavailable);
	}

	/**
	 * Re-snapshots the game state and re-classifies the method catalog WITHOUT generating routes
	 * (issue #5: the "usable/total" count and the per-method reasons were a per-generation
	 * snapshot — opening the bank, equipping runes or gaining a level changed nothing until the
	 * next route computation). Serialized on the generation executor so it never overlaps a
	 * generation; the listener runs there too.
	 */
	public void refreshCatalog(AlternativeRoutesMode mode, CatalogListener listener)
	{
		executor.submit(() ->
		{
			try
			{
				if (!refreshOnClientThread(Collections.emptySet(), null, mode))
				{
					return;
				}
				List<TeleportMethod> catalog = new ArrayList<>(planningConfig.getMethodCatalog());
				listener.onCatalog(catalog, notUsable(catalog));
			}
			catch (Exception e)
			{
				log.warn("Catalog refresh failed", e);
			}
		});
	}

	/**
	 * The catalog is the full method universe in every mode; this maps each entry the player can't
	 * use straight from the inventory to WHY (missing item/level/quest, in the bank, not unlocked),
	 * mode-independently — the panel decides usability per mode (a banked item is usable in the
	 * "Inventory + bank" mode, whose route walks to a bank) and filters by these reasons.
	 */
	private Map<TeleportMethod, MethodAvailability> notUsable(List<TeleportMethod> catalog)
	{
		final Map<TeleportMethod, MethodAvailability> statuses = planningConfig.getMethodAvailability();
		final Map<TeleportMethod, MethodAvailability> notUsable = new HashMap<>();
		for (TeleportMethod method : catalog)
		{
			MethodAvailability status = statuses.getOrDefault(method, MethodAvailability.AVAILABLE);
			if (status != MethodAvailability.AVAILABLE)
			{
				notUsable.put(method, status);
			}
		}
		return Collections.unmodifiableMap(notUsable);
	}

	public void shutdown()
	{
		executor.shutdownNow();
		seedExecutor.shutdownNow();
		cachedField = null;
		cachedFieldKey = null;
	}

	private void computeRoutes(int gen, int start, Set<Integer> targets,
		Set<TeleportMethod> userExclusions, AlternativeRoutesMode mode, int maxRoutes, int costMultiple,
		boolean roundTrip, ResultListener listener)
	{
		// The generation as phases over one context (plan step L3): prepare the query (resume or
		// refresh, sea legs), build the field and its verdict, start the concurrent walk search,
		// run the exclusion chain, fill the page (seeds, tail diversity), append the baselines,
		// finish (round trips, timing, resume state, the unreachable cause, the terminal update).
		Generation g = prepare(gen, start, targets, userExclusions, mode, maxRoutes, costMultiple, roundTrip, listener);
		if (g == null)
		{
			return;
		}
		buildField(g);
		startWalkSearch(g);
		if (!runChain(g))
		{
			return;
		}
		fillPage(g);
		appendBaselines(g);
		finish(g);
	}

	/**
	 * Resolves the query into a generation context: a widened re-request resumes the previous
	 * chain state, anything else refreshes the planning snapshot on the client thread and builds
	 * the catalog; both synthesize the sea legs. Null when the generation ended here (the refresh
	 * timed out, or no target survived filtering); the terminal update has then been emitted.
	 */
	private Generation prepare(int gen, int start, Set<Integer> targets,
		Set<TeleportMethod> userExclusions, AlternativeRoutesMode mode, int maxRoutes, int costMultiple,
		boolean roundTrip, ResultListener listener)
	{
		final int limit = Math.max(1, Math.min(maxRoutes, MAX_ROUTES_CAP));
		final Set<Integer> rawTargets = new HashSet<>(targets);
		final Set<Integer> ends = new HashSet<>(targets);
		final Map<String, Integer> chainTailCounts;
		final GenTimer timer = new GenTimer();
		final long wallStart = System.nanoTime();

		// A widened re-request for the SAME query ("+ more routes": limit/multiple grew, nothing else
		// changed) CONTINUES the previous generation instead of starting over: the exclusion chain's
		// state is resumable, and widening only ever appends costlier routes. The already-found routes
		// go out in the very first update, so the panel never blanks while the extra ones compute.
		final ResumeState prior = resumeState;
		final boolean resumed = !roundTrip && prior != null
			&& prior.start == start && prior.rawTargets.equals(rawTargets)
			&& prior.mode == mode && prior.userExclusions.equals(userExclusions)
			&& limit >= prior.limit && costMultiple >= prior.costMultiple
			&& (limit > prior.limit || costMultiple > prior.costMultiple);

		final Set<TeleportMethod> excluded;
		final List<TeleportMethod> catalog;
		final Map<TeleportMethod, MethodAvailability> unavailable;
		final List<Transport> seedCandidates;
		final List<RouteOption> routes;
		final Set<String> seenSignatures;
		// Remaining distance to the target of the first route's endpoint; -1 until known. For an
		// unreachable exact target (e.g. an NPC tile) every route ends at the closest reachable area,
		// so later routes are only accepted while they get equally close (small tolerance).
		int bestRemaining;

		// Water targets: synthesize the final sea legs (board at a nearby mooring, sail
		// straight out) so a pin on the ocean is a real destination. Set for EVERY branch —
		// the resumed path reuses the prior snapshot and previously lost the legs entirely,
		// leaving water pins to detour via whatever static landing the search could reach.
		List<Transport> seaLegs = new ArrayList<>();
		// Wet = sailable terrain AND collision-blocked: stilt-deck tiles (the Pandemonium
		// dock) sit on water-family terrain but are walkable pier cells — functionally land.
		// Sea legs to those said "sail to the destination" where mooring + walking is the
		// real route (and the only physically sensible one).
		Set<Integer> wetTargets = new HashSet<>();
		for (int target : ends)
		{
			if (planningConfig.getMap().isBlocked(WorldPointUtil.unpackWorldX(target),
				WorldPointUtil.unpackWorldY(target), WorldPointUtil.unpackWorldPlane(target)))
			{
				wetTargets.add(target);
			}
		}
		// Synthesis must run against the CURRENT snapshot: the gated must-include berths
		// and the BOARDED varbit both come from planningConfig state that the client-thread
		// refresh rewrites, and synthesizing before it used the PREVIOUS generation's
		// toggles — flipping Summon Boat off then refreshing routed a Khazard water pin
		// toward Brimhaven as its "closest point" until a second refresh (capture 211433).
		// Resumed generations keep the prior snapshot ON PURPOSE (no refresh), so each
		// branch synthesizes at its own right moment.
		Runnable synthesizeSeaLegs = () ->
		{
			for (int target : wetTargets)
			{
				seaLegs.addAll(SailingSea.seaLegTransports(target, 6,
					planningConfig.gatedBoatMoorings()));
			}
			// Player aboard: the start tile is sealed ocean with no walk edges — without legs
			// FROM it (disembark at nearby ports, or sail straight to a water pin), every search
			// dies on the start node and the whole generation reports unreachable. Gated on the
			// BOARDED varbit, not tile wateriness: stilt decks (the Pandemonium) are sailable
			// tiles a player stands on afoot, and offering "sail from here" there was wrong.
			if (planningConfig.isOnSailingBoat())
			{
				seaLegs.addAll(SailingSea.aboardLegTransports(start, wetTargets));
			}
			planningConfig.setExtraTransports(seaLegs);
		};

		if (resumed)
		{
			synthesizeSeaLegs.run();
			// No client-thread refresh: the cached routes were computed against the previous snapshot,
			// and mixing a fresh one into a continued chain would make the page inconsistent. A real
			// re-read happens on any non-widening regeneration (new target, Find routes, recompute).
			ends.clear();
			ends.addAll(prior.filteredEnds);
			excluded = new HashSet<>(prior.excluded);
			catalog = prior.catalog;
			unavailable = prior.unavailable;
			seedCandidates = prior.seedCandidates;
			routes = new ArrayList<>(prior.routes);
			seenSignatures = new HashSet<>(prior.seenSignatures);
			chainTailCounts = new java.util.HashMap<>(prior.chainTailCounts);
			bestRemaining = prior.bestRemaining;
			// First update: the routes the user is already looking at, unchanged.
			emit(gen, listener, new ArrayList<>(routes), catalog, unavailable, false);
			log.debug("[alt-routes] resuming: {} route(s), limit {} -> {}, multiple {} -> {}",
				routes.size(), prior.limit, limit, prior.costMultiple, costMultiple);
		}
		else
		{
			excluded = new HashSet<>(userExclusions);
			// Single client-thread pass per generation, with NO exclusions: snapshots the game state and
			// builds the full availability (the complete method catalog, and the base lists that per-search
			// availability is rebuilt from off-thread), and drops targets the avoid-wilderness setting forbids.
			long clientStart = System.nanoTime();
			boolean refreshed = refreshOnClientThread(Collections.emptySet(), ends, mode);
			timer.clientNanos += System.nanoTime() - clientStart;
			if (!refreshed)
			{
				emit(gen, listener, List.of(), List.of(), Map.of(), true);
				return null;
			}
			// Now the snapshot is current — the sea legs see THIS generation's toggles.
			synthesizeSeaLegs.run();
			// The hop filter's baseline is captured HERE, before the chain's own exclusion
			// rebuilds: the chain removes the direct teleport right before its hop variants
			// are generated (that removal is WHY they appear), so a live-availability check
			// found nothing (capture 20260823-215617, on the first cut of this filter).
			hopBaselineTeleports = teleportHopBaseline(planningConfig, userExclusions);
			catalog = new ArrayList<>(planningConfig.getMethodCatalog());
			unavailable = notUsable(catalog);
			if (ends.isEmpty())
			{
				resumeState = null;
				emit(gen, listener, List.of(), catalog, unavailable, true);
				return null;
			}
			routes = new ArrayList<>();
			seenSignatures = new HashSet<>();
			chainTailCounts = new java.util.HashMap<>();
			bestRemaining = -1;
			// Show the catalog right away while the routes are still computing.
			emit(gen, listener, List.of(), catalog, unavailable, false);

			log.debug("[alt-routes] searching: start={}, target={}, mode={}, usableTeleports={}, catalog={}",
				WorldPointUtil.unpackWorldPoint(start),
				WorldPointUtil.unpackWorldPoint(ends.iterator().next()),
				mode, planningConfig.getUsableTeleports(false).length, catalog.size());

			// Snapshot the global-teleport candidates now, while availability reflects no exclusions; used
			// to seed extra routes if the exclusion loop dries up before the limit.
			seedCandidates = new ArrayList<>(Arrays.asList(
				planningConfig.getUsableTeleports(mode == AlternativeRoutesMode.OWNED_WITH_BANK)));
			// Abandonment forbidden: teleport-FIRST seeds would cast from the helm — the
			// search itself blocks that (teleportsBlockedAt), so the seeds are dead weight.
			if (planningConfig.teleportsBlockedAt(start))
			{
				seedCandidates.clear();
			}
		// Aboard: EVERY disembark port gets its own seeded search — nearest first. With
			// abandonment forbidden this is the whole diversity engine (teleport-first seeds
			// are barred, and one port seed + chains produced exactly one route in the
			// field); with abandonment allowed it still guarantees the "park the boat
			// properly" options ride alongside the teleport routes.
			if (planningConfig.isOnSailingBoat())
			{
				List<Transport> ports = new ArrayList<>();
				for (Transport leg : seaLegs)
				{
					if (leg.getOrigin() == start && leg.getDisplayInfo() != null
						&& leg.getDisplayInfo().startsWith("Disembark"))
					{
						ports.add(leg);
					}
				}
				ports.sort(Comparator.comparingInt(Transport::getDuration));
				for (int i = 0; i < ports.size(); i++)
				{
					seedCandidates.add(Math.min(i, seedCandidates.size()), ports.get(i));
				}
			}
		}

		final Generation g = new Generation(gen, start, ends, userExclusions, mode, limit, costMultiple, roundTrip,
			listener, timer, catalog, unavailable, seedCandidates, routes, seenSignatures);
		g.excluded = excluded;
		g.chainTailCounts = chainTailCounts;
		g.bestRemaining = bestRemaining;
		g.seaLegs = seaLegs;
		g.rawTargets = rawTargets;
		g.resumed = resumed;
		g.wallStart = wallStart;
		return g;
	}

	/** Builds (or reuses) the generation's distance field and its unreachable verdict. */
	private void buildField(Generation g)
	{
		final int start = g.start;
		final Set<Integer> ends = g.ends;
		final Set<TeleportMethod> userExclusions = g.userExclusions;
		final int costMultiple = g.costMultiple;
		final GenTimer timer = g.timer;
		final List<Transport> seaLegs = g.seaLegs;
		final Set<TeleportMethod> excluded = g.excluded;
		// Per-generation preprocessing: one multi-source reverse flood from the target set builds a
		// walking+transport distance field — the near-exact A* heuristic every search of this
		// generation shares (chain, walk, seeds). Compact target sets only; a map-wide nearest-X
		// set would flood everything for searches that are already cheap.
		// The field's reverse-transport index must see the sea legs set above: availability
		// only absorbs extras on REBUILD, and the first in-loop rebuild happens after the field
		// is built. Water pins therefore got an EMPTY field — production diagnostics showed 0
		// guided / 19 blind searches and a 30s wall for a pin the direct probe (which rebuilt
		// first) served a healthy field for.
		// Over the USER exclusions only: a resumed generation's chain set already holds the
		// previous chain's exclusions, and a field flooded over that smaller availability is not
		// a lower bound for the seed and tail searches, which run with the user exclusions alone
		// (a reverse flood over fewer transports can only overestimate).
		planningConfig.rebuildAvailabilityWithExclusions(userExclusions);
		boolean availabilityCurrent = excluded.equals(userExclusions);
		long fieldStart = System.nanoTime();
		// The horizon matches the searches' sanity ceiling (2x the display band), so the fill region
		// past the band edge still gets exact heuristic guidance. Same inputs as the last
		// generation: the previous field is reused instead of flooded again (plan step N4).
		final FieldKey fieldKey = new FieldKey(planningConfig.getUsableFingerprint(), userExclusions,
			extrasFingerprint(seaLegs), ends, costMultiple);
		final boolean fieldReused = cachedField != null
			&& fieldKey.sameInputs(cachedFieldKey, cachedField.horizon() == Integer.MAX_VALUE);
		final DistanceField field;
		if (fieldReused)
		{
			field = cachedField;
		}
		else
		{
			field = DistanceField.buildIfCompact(planningConfig, ends, 2 * costMultiple);
			cachedField = field;
			cachedFieldKey = field != null ? fieldKey : null;
		}
		lastFieldReused = fieldReused;
		g.field = field;
		timer.fieldNanos = System.nanoTime() - fieldStart;
		// A complete reverse field that never reached the start, with no usable teleport landing
		// inside its flooded pocket, proves the target unreachable for EVERY search of this
		// generation (exclusions only shrink availability) - exactly the condition under which
		// SearchHeuristic already discards the field. Capture 20260830-172137 then ran seven
		// full-world floods (~2M nodes, ~400 ms each) to rediscover that verdict seven times.
		// The chain then keeps a SHORT escape menu (UNREACHABLE_ESCAPE_ROUTES distinct closest-
		// approach routes - a sealed-cell capture wants several ways out, see HybridPageFillTest)
		// and skips the walk, seed and tail passes entirely. In every mode: the reverse flood
		// runs over BOTH bank states (reverse transport index, teleport landings, blocked-landing
		// patch), so a route that needs a banked item is part of the proof (plan step N7; the
		// former "+ Bank" exclusion predated that and left bank mode with a one-route page).
		final boolean targetProvablyUnreachable = field != null
			&& field.horizon() == Integer.MAX_VALUE
			&& field.distance(start) == DistanceField.UNREACHED
			&& SearchHeuristic.buildWithField(planningConfig, field) != null
			&& SearchHeuristic.buildWithField(planningConfig, field, start) == null;
		g.availabilityCurrent = availabilityCurrent;
		g.targetProvablyUnreachable = targetProvablyUnreachable;
	}

	/** Starts the concurrent walk-only search (none for single-route pages or a sealed target). */
	private void startWalkSearch(Generation g)
	{
		final boolean resumed = g.resumed;
		final List<RouteOption> routes = g.routes;
		final int costMultiple = g.costMultiple;
		final int limit = g.limit;
		final boolean targetProvablyUnreachable = g.targetProvablyUnreachable;
		// Walk-only search, run concurrently on the seed pool (its own config copy — the chain
		// mutates planningConfig per iteration): its cost is a rigorous expansion cap for every
		// search that starts after it finishes (routes costlier than walking are never shown, so a
		// node above walk cost is provably useless — this keeps a dead-end seed teleport from
		// flooding the map), and its path is the last-resort route appended when the chain doesn't
		// derive it. Polled non-blockingly so route 1's latency is unchanged. Skipped for
		// single-route generations (limit 1): one search, a cap can't pay for itself.
		// Live ceiling for the walk search: unbounded until the chain finds its (cheapest) route, then
		// dropped to that route's cost band so the walk stops flooding the map when walking is
		// uncompetitive — which is most queries, since a teleport route usually wins.
		final AtomicInteger walkCeiling = new AtomicInteger(Integer.MAX_VALUE);
		if (resumed && !routes.isEmpty() && costMultiple > 0)
		{
			// The best route is already known, so the walk search starts pre-capped at the (widened)
			// sanity ceiling instead of waiting for the chain's in-loop drop (which only fires on the
			// FIRST route — already found on a resume).
			walkCeiling.set((int) Math.min(Integer.MAX_VALUE,
				(long) routes.get(0).getTotalCost() * 2 * costMultiple));
		}
		final Future<WalkResult> walkFuture = limit > 1 && !targetProvablyUnreachable
			? seedExecutor.submit(() -> runWalkSearch(g, walkCeiling))
			: null;
		g.walkCeiling = walkCeiling;
		g.walkFuture = walkFuture;
	}

	/**
	 * The exclusion chain: each accepted route excludes its primary method for the next search,
	 * so the page walks down the distinct ways in. False when a newer generation superseded this
	 * one mid-chain (nothing more is emitted for it).
	 */
	private boolean runChain(Generation g)
	{
		final int gen = g.gen;
		final int start = g.start;
		final Set<Integer> ends = g.ends;
		final int limit = g.limit;
		final int costMultiple = g.costMultiple;
		final boolean roundTrip = g.roundTrip;
		final ResultListener listener = g.listener;
		final List<TeleportMethod> catalog = g.catalog;
		final Map<TeleportMethod, MethodAvailability> unavailable = g.unavailable;
		final List<RouteOption> routes = g.routes;
		final Set<String> seenSignatures = g.seenSignatures;
		final GenTimer timer = g.timer;
		final Set<TeleportMethod> excluded = g.excluded;
		final Map<String, Integer> chainTailCounts = g.chainTailCounts;
		final DistanceField field = g.field;
		final boolean targetProvablyUnreachable = g.targetProvablyUnreachable;
		final Future<WalkResult> walkFuture = g.walkFuture;
		final AtomicInteger walkCeiling = g.walkCeiling;
		boolean availabilityCurrent = g.availabilityCurrent;
		int bestRemaining = g.bestRemaining;
		// Whether the cost cap (best * costMultiple, below the walk ceiling) held a route back — a
		// search couldn't reach within the band. If so, "show more" (a higher multiple) can surface
		// it. Set only for cost-cap truncations, not method exhaustion or the walk ceiling.
		boolean cappedByCost = false;
		// Set when the chain stops on its own terms (methods exhausted, a duplicate signature, a
		// walk-only route, or the target drifting out of reach). If it stays false the loop simply ran
		// out of route-count budget — the count was binding, so a higher limit can surface more.
		boolean chainExhausted = false;

		for (int i = routes.size(); i < limit; i++)
		{
			if (gen != generation.get())
			{
				return false;
			}
			if (targetProvablyUnreachable && routes.size() >= UNREACHABLE_ESCAPE_ROUTES)
			{
				// The escape menu is full; every further search would flood the same world to
				// the same verdict.
				chainExhausted = true;
				break;
			}
			// Rebuild availability for the current exclusion set — pure computation over the base lists
			// captured by the client-thread pass above, so no client-thread round-trip per search.
			long rebuildStart = System.nanoTime();
			// The pre-field rebuild covered the first iteration's exclusion set; later
			// iterations rebuild because the chain grows the set between them.
			if (!availabilityCurrent)
			{
				planningConfig.rebuildAvailabilityWithExclusions(excluded);
			}
			availabilityCurrent = false;
			timer.rebuildNanos += System.nanoTime() - rebuildStart;

			long searchStart = System.nanoTime();
			int walkCap = capOf(walkFuture);
			// Searches run under the hard sanity ceiling (twice the display band): the band itself is
			// no longer a hard cut — past it the page keeps FILLING while each next route stays within
			// a modest gap of the acceptance region (see the accept check below), so the first page
			// never stops mid-cluster (e.g. 105 shown, 107 hidden) the way a bare best*multiple cut did.
			int chainCap = RouteAcceptance.cappedByBestCost(walkCap, routes, 2 * costMultiple);
			// The cost ceiling is the binding one (tighter than the walk ceiling) — a search failing now
			// was held back by cost, not by walking being cheaper or methods running out.
			boolean costLimited = chainCap < walkCap;
			// Heuristic rebuilt per iteration: the exclusion set changes the usable teleports and
			// with them the field floor — excluding the good teleports raises it, so the heuristic
			// gets stronger exactly when the searches get expensive. Null field (map-wide target
			// sets) means uninformed, which those cheap searches don't need anyway.
			SearchHeuristic heuristic = SearchHeuristic.buildWithField(planningConfig, field, start);
			Pathfinder pathfinder = new Pathfinder(planningConfig, start, ends, chainCap, heuristic);
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
			// Closeness (RouteAcceptance.tooFar): with the best route reaching, an unreached result
			// is a cap truncation a few tiles short, not a route (a phantom "unreachable" quetzal
			// route at cost 28 under a 27 band was shown once); with an unreachable target, routes
			// are accepted while they end about as close as the best approach. Either way the
			// route is beyond the band, and "more routes" reveals it by widening.
			int remaining = reached ? 0 : remainingDistance(path, ends);
			if (RouteAcceptance.tooFar(reached, remaining, bestRemaining))
			{
				log.debug("[alt-routes] search #{} ends {} tiles from target (best {}); stopping with {} route(s)",
					i, remaining, bestRemaining, routes.size());
				cappedByCost |= costLimited;
				chainExhausted = true;
				break;
			}
			if (bestRemaining < 0)
			{
				bestRemaining = remaining;
			}

			// Hybrid band edge: routes inside the display band are always accepted; past it the page
			// keeps filling while the next cost stays within a modest gap of max(band, costliest
			// accepted) — so a cost cluster straddling the band edge is shown whole, but a genuine
			// cliff (the next route being far pricier than everything shown) still ends the page.
			// A minimum page overrides the cliff: one super-cheap route (a direct minigame teleport)
			// otherwise produced a single-entry page with no alternatives at all (user capture).
			final int totalCost = result.getTotalCost();
			if (RouteAcceptance.beyondBand(totalCost, routes, costMultiple, limit))
			{
				// A route exists beyond what this page shows: "+" (a wider band) can reveal it.
				cappedByCost = true;
				chainExhausted = true;
				break;
			}

			MethodScan scan = scanMethods(planningConfig, path);
			List<TeleportMethod> methods = scan.methods;

			// A kept route nested inside this one (detour + same ending): skip it WITHOUT burning
			// its signature, and keep the chain moving by excluding its primary like any accepted
			// route — the next iteration can still find genuinely different endings.
			if (RouteAcceptance.nestsAKeptRoute(methods, !scan.bankGated.isEmpty(), routes))
			{
				excluded.add(methods.get(0));
				continue;
			}

			// A whistle-hop variant of a direct teleport (see hasRedundantTeleportHop): skip it
			// WITHOUT burning its signature or a route slot (the loop is iteration-bounded, and
			// one filtered variant per whistle site would eat the whole budget - the probe run
			// came back with 2 routes of 10). Excluding its primary keeps the chain moving to
			// genuinely different methods; the growing exclusion set bounds the extra turns.
			if (RouteAcceptance.hasRedundantTeleportHop(hopBaselineTeleports, methods))
			{
				excluded.add(methods.get(0));
				i--;
				continue;
			}
			// Prefix twin of a saturated tail: same treatment as a hop variant. Counted over
			// CHAIN acceptances only (resumable), so a widened resume replays the exact
			// saturation sequence of a from-scratch run.
			if (methods.size() >= 2
				&& chainTailCounts.getOrDefault(tailSignature(methods), 0) >= TAIL_DOMINANCE)
			{
				excluded.add(methods.get(0));
				i--;
				continue;
			}

			// Distinct method-signature gate: if this route uses the same ordered methods as a previous
			// one, excluding more would only reshuffle, so stop.
			if (!seenSignatures.add(signature(methods)))
			{
				chainExhausted = true;
				break;
			}
			routes.add(new RouteOption(withoutIdleBankFlip(path, scan), methods, scan.methodEdges, scan.methodDurations,
				totalCost, scan.rawCost, reached, scan.bankGated, scan.bankGatedTransports, scan.walkBefore, scan.trailingWalk));
			if (methods.size() >= 2)
			{
				chainTailCounts.merge(tailSignature(methods), 1, Integer::sum);
			}
			// The chain's first route is the cheapest; drop the concurrent walk search's ceiling to the
			// search sanity ceiling (twice the display band). A walk costlier than that can never be
			// shown, so a walk to an unreachable/far target stops instead of flooding the map. Only when
			// route 0 actually reached — an unreachable-target run keeps the walk uncapped so it can be
			// the last resort.
			if (routes.size() == 1 && reached && costMultiple > 0)
			{
				walkCeiling.set((int) Math.min(Integer.MAX_VALUE, (long) totalCost * 2 * costMultiple));
			}
			// Stream the route we just found so the panel shows it immediately. Round-trip mode
			// streams only the merged results — one-way costs would reorder once returns are added.
			if (!roundTrip)
			{
				emit(gen, listener, new ArrayList<>(routes), catalog, unavailable, false);
			}

			// Unreached target: every extra route is another escape toward the same closest area,
			// and with the heuristic degenerate (sealed pocket) each iteration is an uninformed
			// sweep. Time-boxed rather than counted: the page holds whatever the search budget
			// affords, and "+" resumes with a fresh budget.
			if (!reached && searchBudgetExhausted(timer))
			{
				cappedByCost = true;
				chainExhausted = true;
				break;
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
		g.bestRemaining = bestRemaining;
		g.availabilityCurrent = availabilityCurrent;
		g.cappedByCost = cappedByCost;
		g.chainExhausted = chainExhausted;
		return true;
	}

	/** The seed and tail-diversity passes, and whether a wider request could show more. */
	private void fillPage(Generation g)
	{
		final int gen = g.gen;
		final List<RouteOption> routes = g.routes;
		final int costMultiple = g.costMultiple;
		final boolean targetProvablyUnreachable = g.targetProvablyUnreachable;
		final Future<WalkResult> walkFuture = g.walkFuture;
		final boolean cappedByCost = g.cappedByCost;
		final boolean chainExhausted = g.chainExhausted;
		// Stop at the pure-walk option: once walking there is on the list, anything more expensive than
		// just walking isn't worth showing. Seeding only ever surfaces teleports that lost to walking on
		// cost (i.e. routes MORE expensive than walk-only), so skip it entirely when a walk-only route was
		// already found. A FULL list does not skip seeding: a small floor of attempts always runs, and a
		// cheaper seed evicts the costliest route — the safety net for anything the chain missed must not
		// be silenced by a low route limit (the exact failure a user capture showed at limit 10).
		boolean hasWalkOnly = routes.stream().anyMatch(RouteOption::isWalkOnly);
		if (!hasWalkOnly && !routes.isEmpty() && !targetProvablyUnreachable)
		{
			seedTeleportRoutes(g, RouteAcceptance.cappedByBestCost(capOf(walkFuture), routes, 2 * costMultiple));
		}
		// Tail-diversity pass: when the page is dominated by one shared method TAIL, surface a
		// variant with a different middle. Runs after seeds so eviction sees the full page.
		if (gen == generation.get() && !targetProvablyUnreachable)
		{
			diversifySharedTails(g, RouteAcceptance.cappedByBestCost(capOf(walkFuture), routes, 2 * costMultiple));
		}

		// More routes are worth polling for when the count budget was the binding limit (the chain kept
		// finding distinct routes and simply ran out of slots), or the cost cap held routes back — the
		// latter only while it sat below the walk ceiling, since once it reaches walking there's nothing
		// cheaper-than-walk left to reveal.
		int moreWalkCap = capOf(walkFuture);
		boolean costHeldBack = cappedByCost
			&& RouteAcceptance.cappedByBestCost(moreWalkCap, routes, costMultiple) < moreWalkCap;
		lastGenerationMoreLikely = !chainExhausted || costHeldBack;
	}

	/** The walk-only and keep-sailing baselines, the final ordering, and the cut at walking. */
	private void appendBaselines(Generation g)
	{
		final List<RouteOption> routes = g.routes;
		final int limit = g.limit;
		final Set<String> seenSignatures = g.seenSignatures;
		final int bestRemaining = g.bestRemaining;
		final Future<WalkResult> walkFuture = g.walkFuture;
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
			// When the baseline itself is the keep-sailing route (aboard), an equal-cost
			// pure-sail route IS that baseline - culling it here and letting the signature
			// dedupe block the re-add below would empty the page (close water pins: the
			// direct sail ties its own ceiling).
			final boolean sailWalk = walk.route.isPureSail();
			routes.removeIf(r -> !r.isWalkOnly() && r.getTotalCost() >= walkCost
				&& !(sailWalk && r.isPureSail() && r.getTotalCost() == walkCost));
		}
		// Always surface walking as the baseline option (when the target is reachable on foot and the
		// chain didn't already derive it), even at the route limit — the player should always see
		// "…or just walk N tiles" as a complete-picture fallback, whatever teleports were found.
		if (walk != null && walk.cap != Integer.MAX_VALUE
			&& (bestRemaining < 0 || walk.remaining <= bestRemaining + RouteAcceptance.CLOSEST_DISTANCE_TOLERANCE)
			&& seenSignatures.add(signature(walk.route.getMethods())))
		{
			routes.add(walk.route);
			// Keep it within the limit by dropping the costliest teleport route it displaces. Never
			// the baseline itself: at the helm it is a pure-sail route (not walk-only), and with a
			// full page of port-first routes it was the costliest unprotected entry, so the page
			// evicted the very route this block exists to surface (plan step N10).
			while (routes.size() > limit)
			{
				int drop = RouteAcceptance.evictionIndex(routes, -1, r -> r != walk.route && !r.isWalkOnly());
				if (drop < 0)
				{
					break;
				}
				routes.remove(drop);
			}
		}

		// The keep-sailing baseline joins the page when aboard and no pure-sail route survived
		// the band: signature-deduped, evicting the costliest teleport chain when full - the
		// walk baseline's exact shape. The plugin ranks it first at the helm.
		RouteOption sailBaseline = g.bestSail.get();
		if (sailBaseline != null && routes.stream().noneMatch(RouteOption::isPureSail)
			&& seenSignatures.add(signature(sailBaseline.getMethods())))
		{
			routes.add(sailBaseline);
			while (routes.size() > limit)
			{
				int drop = RouteAcceptance.evictionIndex(routes, -1, r -> !r.isWalkOnly() && !r.isPureSail());
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
	}

	/** Round trips, the timing summary, the resume state, the unreachable cause, the terminal update. */
	private void finish(Generation g)
	{
		final int gen = g.gen;
		final int start = g.start;
		final Set<Integer> ends = g.ends;
		final Set<TeleportMethod> userExclusions = g.userExclusions;
		final AlternativeRoutesMode mode = g.mode;
		final int limit = g.limit;
		final int costMultiple = g.costMultiple;
		final boolean roundTrip = g.roundTrip;
		final ResultListener listener = g.listener;
		final List<TeleportMethod> catalog = g.catalog;
		final Map<TeleportMethod, MethodAvailability> unavailable = g.unavailable;
		final List<Transport> seedCandidates = g.seedCandidates;
		final List<RouteOption> routes = g.routes;
		final GenTimer timer = g.timer;
		final long wallStart = g.wallStart;
		final Set<Integer> rawTargets = g.rawTargets;
		final Set<TeleportMethod> excluded = g.excluded;
		final Map<String, Integer> chainTailCounts = g.chainTailCounts;
		final int bestRemaining = g.bestRemaining;
		// Round-trip mode: give every one-way route its return leg and re-rank by combined cost.
		if (roundTrip && !routes.isEmpty() && gen == generation.get())
		{
			List<RouteOption> merged = buildRoundTrips(g, routes);
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
		// Capture the chain state so a widened re-request ("+ more routes") can continue this
		// generation instead of starting over. Round trips merge return legs into the route list,
		// which the one-way chain state can't be rebuilt from — they always regenerate.
		if (gen == generation.get() && !roundTrip)
		{
			// Carry ONLY the shown routes' signatures forward — not the full seenSignatures, which
			// also holds combos the seed pass tried but couldn't fit (cost/slot). A resume that
			// inherited those burned signatures would reject them again when the wider limit COULD
			// show them: a panel-hidden limit-1 generation would poison the full limit-10 resume,
			// silently dropping cheap routes (user capture: Quest-Helper target lost the 164 route).
			Set<String> shownSignatures = new HashSet<>();
			for (RouteOption route : routes)
			{
				shownSignatures.add(signature(route.getMethods()));
			}
			resumeState = new ResumeState(start, rawTargets, Set.copyOf(ends), Set.copyOf(userExclusions),
				mode, limit, costMultiple, Set.copyOf(excluded), shownSignatures,
				List.copyOf(routes), bestRemaining, catalog, unavailable, seedCandidates,
				java.util.Map.copyOf(chainTailCounts));
		}
		if (gen == generation.get())
		{
			lastUnreachableCause = unreachableCause(routes, start, ends, mode);
		}
		// A superseded run leaves the previous state alone: the newer generation overwrites it when it
		// completes, and the eligibility check (start/targets/mode/exclusions) guards staleness anyway.
		emit(gen, listener, new ArrayList<>(routes), catalog, unavailable, true);
	}

	/**
	 * A completed generation's chain state, kept so "+ more routes" (same query, wider
	 * limit/multiple) resumes instead of recomputing: the found routes are emitted immediately and
	 * the exclusion chain continues where it stopped. Only ever read and written on the generation
	 * executor thread.
	 */
	private static final class ResumeState
	{
		final int start;
		final Set<Integer> rawTargets;
		final Set<Integer> filteredEnds;
		final Set<TeleportMethod> userExclusions;
		final AlternativeRoutesMode mode;
		final int limit;
		final int costMultiple;
		final Set<TeleportMethod> excluded;
		final Set<String> seenSignatures;
		final List<RouteOption> routes;
		final int bestRemaining;
		final List<TeleportMethod> catalog;
		final Map<TeleportMethod, MethodAvailability> unavailable;
		final List<Transport> seedCandidates;
		// Chain-only tail counts, so a widened resume saturates tails in the exact sequence the
		// from-scratch run would (seed routes must not shift the counts between the two paths).
		final Map<String, Integer> chainTailCounts;

		ResumeState(int start, Set<Integer> rawTargets, Set<Integer> filteredEnds,
			Set<TeleportMethod> userExclusions, AlternativeRoutesMode mode, int limit, int costMultiple,
			Set<TeleportMethod> excluded, Set<String> seenSignatures, List<RouteOption> routes,
			int bestRemaining, List<TeleportMethod> catalog,
			Map<TeleportMethod, MethodAvailability> unavailable, List<Transport> seedCandidates,
			Map<String, Integer> chainTailCounts)
		{
			this.chainTailCounts = chainTailCounts;
			this.start = start;
			this.rawTargets = rawTargets;
			this.filteredEnds = filteredEnds;
			this.userExclusions = userExclusions;
			this.mode = mode;
			this.limit = limit;
			this.costMultiple = costMultiple;
			this.excluded = excluded;
			this.seenSignatures = seenSignatures;
			this.routes = routes;
			this.bestRemaining = bestRemaining;
			this.catalog = catalog;
			this.unavailable = unavailable;
			this.seedCandidates = seedCandidates;
		}
	}

	private ResumeState resumeState;

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


	// Whether the last generation's cost cap held routes back — a higher cost multiple ("show more")
	// could surface them. False when walking is the binding ceiling (nothing cheaper-than-walk left).
	private volatile boolean lastGenerationMoreLikely = false;

	/**
	 * The last built distance field and the inputs it was built from (plan step N4). A field is
	 * immutable once built and a pure function of the target set, the usable transports (the
	 * refresh fingerprint), the user exclusions, the synthesized sea legs and the flood horizon.
	 * A player walking toward a pinned target regenerates every few tiles with all of those
	 * unchanged, and used to flood the identical field each time (~200 ms per generation against
	 * ~1 ms per guided search). Generation-thread only.
	 */
	private DistanceField cachedField;
	private FieldKey cachedFieldKey;
	private volatile boolean lastFieldReused;

	/** Whether the last generation reused the previous generation's distance field. */
	boolean lastFieldReused()
	{
		return lastFieldReused;
	}

	private static final class FieldKey
	{
		private final long usableFingerprint;
		private final Set<TeleportMethod> userExclusions;
		private final long extrasFingerprint;
		private final Set<Integer> ends;
		private final int costMultiple;

		FieldKey(long usableFingerprint, Set<TeleportMethod> userExclusions, long extrasFingerprint,
			Set<Integer> ends, int costMultiple)
		{
			this.usableFingerprint = usableFingerprint;
			this.userExclusions = new HashSet<>(userExclusions);
			this.extrasFingerprint = extrasFingerprint;
			this.ends = new HashSet<>(ends);
			this.costMultiple = costMultiple;
		}

		/** Same inputs; a complete field (horizon at MAX) serves any cost multiple. */
		boolean sameInputs(FieldKey cached, boolean cachedComplete)
		{
			return cached != null
				&& usableFingerprint == cached.usableFingerprint
				&& extrasFingerprint == cached.extrasFingerprint
				&& (costMultiple == cached.costMultiple || cachedComplete)
				&& userExclusions.equals(cached.userExclusions)
				&& ends.equals(cached.ends);
		}
	}

	/** Commutative content fingerprint of the synthesized sea legs (fresh objects every generation). */
	private static long extrasFingerprint(List<Transport> extras)
	{
		long fingerprint = 0;
		for (Transport extra : extras)
		{
			long key = ((long) extra.getOrigin() << 32) ^ (extra.getDestination() & 0xffffffffL);
			fingerprint += RoutingItemDependencies.mix64(key * 31 + extra.getDuration());
		}
		return fingerprint;
	}

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
	 * One generation's shared state (plan step L2): the query, the page under construction and
	 * the per-generation search inputs, handed to every pass instead of the ten-to-eighteen
	 * parameter signatures that used to carry them, plus the one {@link #emit} that cannot forget
	 * the staleness check. Mutable fields are written by the generation thread between passes.
	 */
	private final class Generation
	{
		final int gen;
		final int start;
		final Set<Integer> ends;
		final Set<TeleportMethod> userExclusions;
		final AlternativeRoutesMode mode;
		final int limit;
		final int costMultiple;
		final boolean roundTrip;
		final ResultListener listener;
		final GenTimer timer;
		final List<TeleportMethod> catalog;
		final Map<TeleportMethod, MethodAvailability> unavailable;
		final List<Transport> seedCandidates;
		final List<RouteOption> routes;
		final Set<String> seenSignatures;
		// State written by one phase and read by the next (generation thread only).
		/** The chain's exclusion set: the user's, grown by one primary per accepted route. */
		Set<TeleportMethod> excluded;
		/** Chain acceptances per shared tail, for the saturation filter (resumable). */
		Map<String, Integer> chainTailCounts;
		/** The synthesized sea legs of this generation (water pins, the helm). */
		List<Transport> seaLegs;
		/** The caller's targets before wilderness filtering (the resume key). */
		Set<Integer> rawTargets;
		/** Whether this generation continues a previous one ("+ more routes"). */
		boolean resumed;
		long wallStart;
		/** Whether the availability already matches {@link #excluded} for the chain's first search. */
		boolean availabilityCurrent;
		/** A complete field that never reached the start: every search would find nothing. */
		boolean targetProvablyUnreachable;
		AtomicInteger walkCeiling;
		Future<WalkResult> walkFuture;
		/** The cost cap held a route back (a wider band can reveal it). */
		boolean cappedByCost;
		/** The chain stopped on its own terms rather than on the route-count budget. */
		boolean chainExhausted;
		/** The generation's distance field, once built (null for an empty target set). */
		DistanceField field;
		/** Remaining distance of the first route's endpoint; -1 until the chain knows it. */
		int bestRemaining = -1;
		/** The cheapest pure-sail continuation seen at the helm (the keep-sailing baseline). */
		final java.util.concurrent.atomic.AtomicReference<RouteOption> bestSail =
			new java.util.concurrent.atomic.AtomicReference<>();

		Generation(int gen, int start, Set<Integer> ends, Set<TeleportMethod> userExclusions,
			AlternativeRoutesMode mode, int limit, int costMultiple, boolean roundTrip, ResultListener listener,
			GenTimer timer, List<TeleportMethod> catalog, Map<TeleportMethod, MethodAvailability> unavailable,
			List<Transport> seedCandidates, List<RouteOption> routes, Set<String> seenSignatures)
		{
			this.gen = gen;
			this.start = start;
			this.ends = ends;
			this.userExclusions = userExclusions;
			this.mode = mode;
			this.limit = limit;
			this.costMultiple = costMultiple;
			this.roundTrip = roundTrip;
			this.listener = listener;
			this.timer = timer;
			this.catalog = catalog;
			this.unavailable = unavailable;
			this.seedCandidates = seedCandidates;
			this.routes = routes;
			this.seenSignatures = seenSignatures;
		}

		/** Whether a newer generation has superseded this one. */
		boolean stale()
		{
			return gen != generation.get();
		}

		/**
		 * The listener a one-way pass streams to: none in round-trip mode, which streams only
		 * merged results (one-way costs would reorder once returns are added).
		 */
		ResultListener oneWayListener()
		{
			return roundTrip ? null : listener;
		}
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

	/** Why the last generation's routes all stop short of the target (plan step N12). */
	public enum UnreachableCause
	{
		/** The page has a route that reaches the target (or there is no page). */
		NONE,
		/** Reachable with everything the game offers, not with this mode's items and unlocks. */
		MISSING_UNLOCKS,
		/** No known route in any mode: a sealed tile, or a gap in the map data. */
		NO_KNOWN_ROUTE
	}

	private volatile UnreachableCause lastUnreachableCause = UnreachableCause.NONE;

	public UnreachableCause lastUnreachableCause()
	{
		return lastUnreachableCause;
	}

	/**
	 * Tells "not with what you have" from "no known route" for a page whose routes all stop
	 * short: an all-everything planning copy is refreshed on the client thread and its distance
	 * field flooded from the targets; the target is reachable with everything when that field
	 * reaches the start or any teleport landing. Runs only for an unreached page in an owned
	 * mode (the all mode already includes everything), so the probe costs nothing on a normal
	 * generation.
	 */
	private UnreachableCause unreachableCause(List<RouteOption> routes, int start, Set<Integer> ends,
		AlternativeRoutesMode mode)
	{
		if (routes.isEmpty() || ends.isEmpty())
		{
			return UnreachableCause.NONE;
		}
		for (RouteOption route : routes)
		{
			if (route.isReached())
			{
				return UnreachableCause.NONE;
			}
		}
		if (mode == AlternativeRoutesMode.ALL_EVERYTHING)
		{
			return UnreachableCause.NO_KNOWN_ROUTE;
		}
		PathfinderConfig probe = planningConfig.copyForPlanning();
		if (!refreshOnClientThread(probe, Collections.emptySet(), null, AlternativeRoutesMode.ALL_EVERYTHING))
		{
			return UnreachableCause.NO_KNOWN_ROUTE;
		}
		probe.rebuildAvailabilityWithExclusions(Collections.emptySet());
		DistanceField everything = DistanceField.buildIfCompact(probe, ends, 0);
		boolean reachableWithEverything = everything != null
			&& SearchHeuristic.buildWithField(probe, everything, start) != null;
		return reachableWithEverything ? UnreachableCause.MISSING_UNLOCKS : UnreachableCause.NO_KNOWN_ROUTE;
	}

	/**
	 * Seeds additional routes when the exclusion loop ended early: for each candidate global teleport
	 * (best estimated arrival first), run one search with every OTHER global teleport excluded, so the
	 * result is "the best route if you use this teleport". Routes with an already-seen method
	 * signature, walk-only results, or endpoints meaningfully further than the best route are skipped.
	 */
	private void seedTeleportRoutes(Generation g, int costCap)
	{
		final int gen = g.gen;
		final int start = g.start;
		final Set<Integer> ends = g.ends;
		final Set<TeleportMethod> userExclusions = g.userExclusions;
		final AlternativeRoutesMode mode = g.mode;
		final int limit = g.limit;
		final int costMultiple = g.costMultiple;
		final List<Transport> seedCandidates = g.seedCandidates;
		final List<RouteOption> routes = g.routes;
		final Set<String> seenSignatures = g.seenSignatures;
		final List<TeleportMethod> catalog = g.catalog;
		final Map<TeleportMethod, MethodAvailability> unavailable = g.unavailable;
		final ResultListener listener = g.oneWayListener();
		final int bestRemaining = g.bestRemaining;
		final DistanceField field = g.field;
		final GenTimer timer = g.timer;
		final java.util.concurrent.atomic.AtomicReference<RouteOption> bestSail = g.bestSail;
		// Every global teleport is excluded from each seed search except the seed itself, so the
		// exclusion universe must span ALL candidates — including ones that don't get an attempt.
		final Set<TeleportMethod> allSeedMethods = new HashSet<>();
		for (Transport transport : seedCandidates)
		{
			allSeedMethods.add(transport.method());
		}

		// A floor of attempts even when the chain filled every slot: the best-ranked seeds are the
		// safety net against a missed-cheap-route bug, and cost nearly nothing when they lose.
		final int maxAttempts = Math.max(6, (limit - routes.size()) * 3);
		final List<Transport> attempts = rankSeedCandidates(seedCandidates, ends, userExclusions, maxAttempts);
		String nearestPortInfo = null;
		// Aboard, the closest-port disembark is ALWAYS attempted: cheap teleports outrank
		// every port seed and the ranking silently dropped them all (field capture 225140 —
		// ten teleport routes, zero "park the boat" options). One guaranteed attempt keeps
		// the promise that docking properly is always on the card.
		if (planningConfig.isOnSailingBoat())
		{
			Transport nearestPort = null;
			for (Transport candidate : seedCandidates)
			{
				if (candidate.getDisplayInfo() != null
					&& candidate.getDisplayInfo().startsWith("Disembark")
					&& (nearestPort == null || candidate.getDuration() < nearestPort.getDuration()))
				{
					nearestPort = candidate;
				}
			}
			if (nearestPort != null && !attempts.contains(nearestPort))
			{
				if (attempts.size() >= maxAttempts && !attempts.isEmpty())
				{
					attempts.remove(attempts.size() - 1);
				}
				attempts.add(nearestPort);
			}
			nearestPortInfo = nearestPort != null ? nearestPort.getDisplayInfo() : null;
		}
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
		final String nearestPortRetained = nearestPortInfo;
		final CompletionService<SeedResult> completion = new ExecutorCompletionService<>(seedExecutor);
		final List<Future<SeedResult>> futures = new ArrayList<>(attempts.size());
		for (Transport seed : attempts)
		{
			futures.add(completion.submit(() ->
				runSeedSearch(g, stop, allSeedMethods, seed,
					nearestPortRetained != null && nearestPortRetained.equals(seed.getDisplayInfo()),
					costCap, configPool)));
		}

		try
		{
			// Results are collected first and accepted in a DETERMINISTIC order (cost, then
			// signature), not completion order: the page-fill ceiling ratchets as routes are
			// accepted, so completion order (JIT warmth, load) decided which seeds made the page
			// and the same query produced different pages run to run (plan step N10; the seeds
			// take milliseconds each, so nothing is lost by waiting for all of them).
			List<SeedResult> results = new ArrayList<>(futures.size());
			for (int i = 0; i < futures.size() && gen == generation.get(); i++)
			{
				try
				{
					SeedResult seedResult = completion.take().get();
					if (seedResult != null)
					{
						results.add(seedResult);
					}
				}
				catch (ExecutionException e)
				{
					log.warn("Seed search failed", e);
				}
			}
			results.sort(Comparator.comparingInt((SeedResult r) -> r.totalCost)
				.thenComparing(r -> signature(r.scan.methods)));
			for (SeedResult seedResult : results)
			{
				if (gen != generation.get())
				{
					break;
				}
				// Same hybrid acceptance as the chain: a seed beyond the page-fill ceiling isn't shown
				// (skip, not stop — seeds complete in parallel, a later one can be cheaper). Checked
				// BEFORE the signature is consumed: a cost-rejected seed must stay eligible for a
				// widened re-run ("+"), which resumes with this generation's seenSignatures.
				// The nearest-port disembark is the walk fallback's sea twin: docking properly
				// is ALWAYS on the card when aboard, however cheap the teleports are. It is
				// exempt from the cost band and, below, evicts the costliest teleport route
				// even when every shown route is cheaper (finding 4: the seed always ran but
				// its route died here, and the service test caught the gap the field saw).
				boolean portPromise = nearestPortRetained != null
					&& !seedResult.scan.methods.isEmpty()
					&& nearestPortRetained.equals(seedResult.scan.methods.get(0).getDisplayInfo())
					&& !RouteAcceptance.hasPortFirstRoute(routes);
				// At the helm the cheapest PURE-SAIL continuation is a protected baseline (the
				// walk route's sibling): the cost band otherwise culls every keep-sailing option
				// while cheap disembark-teleport chains fill the page (capture 20260829-204334),
				// leaving a sailor mid-task with no sea route at all.
				if (planningConfig.isOnSailingBoat() && seedResult.reached
					&& isPureSail(seedResult.scan.methods))
				{
					RouteOption prior = bestSail.get();
					if (prior == null || seedResult.totalCost < prior.getTotalCost())
					{
						bestSail.set(new RouteOption(withoutIdleBankFlip(seedResult.path, seedResult.scan),
							seedResult.scan.methods, seedResult.scan.methodEdges,
							seedResult.scan.methodDurations, seedResult.totalCost, seedResult.scan.rawCost,
							true, seedResult.scan.bankGated, seedResult.scan.walkBefore,
							seedResult.scan.trailingWalk));
					}
				}
				if (!portPromise && RouteAcceptance.beyondBand(seedResult.totalCost, routes, costMultiple))
				{
					continue;
				}
				// The port promise IS a detour with the same ending (Disembark + the teleport
				// the bare route uses) — the nesting filter would always drop it.
				if (!portPromise && RouteAcceptance.nestsAKeptRoute(seedResult.scan.methods,
					!seedResult.scan.bankGated.isEmpty(), routes))
				{
					continue;
				}
				if (RouteAcceptance.hasRedundantTeleportHop(hopBaselineTeleports, seedResult.scan.methods))
				{
					continue;
				}
				if (!seenSignatures.add(signature(seedResult.scan.methods)))
				{
					continue;
				}
				// A full list doesn't end the pass: a strictly cheaper seed evicts the costliest
				// teleport route — the seeds are the safety net for anything the chain missed.
				if (routes.size() >= limit)
				{
					// The walk baseline is evict-proof only when there is a LIST to anchor: in
					// overlay-only mode (panel closed, limit 1) the single route must be the best
					// route, or a slow walk permanently shadows a cheap sea leg (field report:
					// 7-minute walk shown while a 131-cost sail existed). The port promise may
					// evict any cost; every other seed only a strictly costlier route.
					int evict = RouteAcceptance.evictionIndex(routes, portPromise ? -1 : seedResult.totalCost,
						r -> limit == 1 || !r.isWalkOnly());
					if (evict < 0)
					{
						continue;
					}
					routes.remove(evict);
				}
				routes.add(new RouteOption(withoutIdleBankFlip(seedResult.path, seedResult.scan),
					seedResult.scan.methods, seedResult.scan.methodEdges,
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
			if (userExclusions.contains(transport.method()))
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

	/** A tail shared by this many kept routes triggers the diversity pass. */

	private static final int TAIL_DOMINANCE = 3;
	/** At most this many extra searches per generation, one per shared-tail method. */
	private static final int TAIL_DIVERSITY_SEARCHES = 2;

	/** The signature of everything after the primary, or null when there is no tail. */
	private static String tailSignature(List<TeleportMethod> methods)
	{
		return methods.size() < 2 ? null : signature(methods.subList(1, methods.size()));
	}

	/**
	 * Tail-diversity pass (captures 20260823-230534, 20260824-183101): the exclusion chain only
	 * varies each route's PRIMARY method, and exclusions accumulate — so when every cheap route
	 * shares a tail (bank teleport + "Salve graveyard tablet + fairy ring", with the staff banked),
	 * the page fills with first-leg variants, and a route that shares a kept PRIMARY but differs in
	 * the middle (Ardougne cloak to the monastery ring) can never be generated: its primary is
	 * already spent. When one tail dominates the page, this pass runs a couple of fresh searches
	 * with ONLY the user's exclusions plus one shared-tail method — primaries deliberately come
	 * back — and feeds any distinct result through the exact acceptance the seeds use, evicting
	 * the costliest member of the dominant family when the page is full.
	 */
	private void diversifySharedTails(Generation g, int costCap)
	{
		final int gen = g.gen;
		final int start = g.start;
		final Set<Integer> ends = g.ends;
		final Set<TeleportMethod> userExclusions = g.userExclusions;
		final int limit = g.limit;
		final int costMultiple = g.costMultiple;
		final List<RouteOption> routes = g.routes;
		final Set<String> seenSignatures = g.seenSignatures;
		final List<TeleportMethod> catalog = g.catalog;
		final Map<TeleportMethod, MethodAvailability> unavailable = g.unavailable;
		final ResultListener listener = g.oneWayListener();
		final int bestRemaining = g.bestRemaining;
		final DistanceField field = g.field;
		final GenTimer timer = g.timer;
		// The dominant tail among the kept routes.
		Map<String, List<RouteOption>> byTail = new LinkedHashMap<>();
		for (RouteOption route : routes)
		{
			String tail = tailSignature(route.getMethods());
			if (tail != null)
			{
				byTail.computeIfAbsent(tail, k -> new ArrayList<>()).add(route);
			}
		}
		String dominantTail = null;
		List<RouteOption> family = null;
		for (Map.Entry<String, List<RouteOption>> entry : byTail.entrySet())
		{
			if (family == null || entry.getValue().size() > family.size())
			{
				dominantTail = entry.getKey();
				family = entry.getValue();
			}
		}
		if (family == null || family.size() < TAIL_DOMINANCE)
		{
			return;
		}

		List<TeleportMethod> sharedTail = family.get(0).getMethods();
		sharedTail = sharedTail.subList(1, sharedTail.size());
		int searches = 0;
		for (TeleportMethod shared : sharedTail)
		{
			if (searches >= TAIL_DIVERSITY_SEARCHES || gen != generation.get())
			{
				break;
			}
			if (userExclusions.contains(shared))
			{
				continue;
			}
			searches++;
			PathfinderConfig config = planningConfig.copyForParallelSearch();
			Set<TeleportMethod> exclusions = new HashSet<>(userExclusions);
			exclusions.add(shared);
			long rebuildStart = System.nanoTime();
			config.rebuildAvailabilityWithExclusions(exclusions);
			long searchStart = System.nanoTime();
			SearchHeuristic heuristic = SearchHeuristic.buildWithField(config, field, start);
			Pathfinder pathfinder = new Pathfinder(config, start, ends, costCap, heuristic);
			pathfinder.run();
			long searchEnd = System.nanoTime();
			synchronized (timer)
			{
				timer.rebuildNanos += searchStart - rebuildStart;
				timer.searchNanos += searchEnd - searchStart;
				timer.searches++;
			}
			record(timer, "tail:" + shared.label(), searchEnd - searchStart, pathfinder, costCap);

			PathfinderResult result = pathfinder.getResult();
			List<PathStep> path = (result != null) ? result.getPathSteps() : List.of();
			if (result == null || path.isEmpty())
			{
				continue;
			}
			boolean reached = result.isReached();
			int remaining = reached ? 0 : remainingDistance(path, ends);
			if (RouteAcceptance.tooFar(reached, remaining, bestRemaining))
			{
				continue;
			}
			final int totalCost = result.getTotalCost();
			if (RouteAcceptance.beyondBand(totalCost, routes, costMultiple))
			{
				continue;
			}
			MethodScan scan = scanMethods(config, path);
			if (scan.methods.isEmpty()
				|| RouteAcceptance.nestsAKeptRoute(scan.methods, !scan.bankGated.isEmpty(), routes)
				|| RouteAcceptance.hasRedundantTeleportHop(hopBaselineTeleports, scan.methods)
				|| !seenSignatures.add(signature(scan.methods)))
			{
				continue;
			}
			if (routes.size() >= limit)
			{
				// The whole point: the slot comes out of the over-represented family.
				final String familyTail = dominantTail;
				int evict = RouteAcceptance.evictionIndex(routes, -1,
					kept -> !kept.isWalkOnly() && familyTail.equals(tailSignature(kept.getMethods())));
				if (evict < 0)
				{
					continue;
				}
				routes.remove(evict);
			}
			routes.add(new RouteOption(withoutIdleBankFlip(path, scan), scan.methods, scan.methodEdges,
				scan.methodDurations, totalCost, scan.rawCost, reached, scan.bankGated,
				scan.bankGatedTransports, scan.walkBefore, scan.trailingWalk));
			emit(gen, listener, new ArrayList<>(routes), catalog, unavailable, false);
		}
	}

	private SeedResult runSeedSearch(Generation g, AtomicBoolean stop, Set<TeleportMethod> allSeedMethods,
		Transport seed, boolean portPromiseSeed, int costCap, Queue<PathfinderConfig> configPool)
	{
		final int gen = g.gen;
		final int start = g.start;
		final Set<Integer> ends = g.ends;
		final Set<TeleportMethod> userExclusions = g.userExclusions;
		final int bestRemaining = g.bestRemaining;
		final DistanceField field = g.field;
		final GenTimer timer = g.timer;
		if (gen != generation.get() || stop.get())
		{
			return null;
		}
		// Unreached-target seeds are uninformed sweeps too — same budget as the chain, checked as
		// each queued seed comes up so a long tail of pending seeds drains cheaply.
		if (bestRemaining > 0 && searchBudgetExhausted(timer))
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
			seedExclusions.remove(seed.method());
			seedExclusions.addAll(userExclusions);
			// The port-promise seed forces its port by excluding only the OTHER sailing
			// seeds; teleports stay usable but position-gated off the water start, so the
			// route is 'Disembark at the nearest port, then the best of everything'.
			config.portPromiseSearch = portPromiseSeed;
			Set<TeleportMethod> effectiveExclusions = seedExclusions;
			if (portPromiseSeed)
			{
				effectiveExclusions = new HashSet<>();
				for (TeleportMethod method : seedExclusions)
				{
					if (!method.getType().isTeleport())
					{
						effectiveExclusions.add(method);
					}
				}
			}
			long rebuildStart = System.nanoTime();
			config.rebuildAvailabilityWithExclusions(effectiveExclusions);
			long searchStart = System.nanoTime();
			// Seed searches exclude every other global teleport, so the floor comes from the seed
			// itself and the search's own optimal route uses it — strongly directed by
			// construction, and the walk-cost cap bounds any residue.
			SearchHeuristic heuristic = SearchHeuristic.buildWithField(config, field, start);
			Pathfinder pathfinder = new Pathfinder(config, start, ends, costCap, heuristic);
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
			int remaining = reached ? 0 : remainingDistance(path, ends);
			if (RouteAcceptance.tooFar(reached, remaining, bestRemaining))
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
			config.portPromiseSearch = false;
			configPool.offer(config);
		}
	}

	/**
	 * The walk-only search: every method in the catalog excluded (plain connectors — doors, stairs,
	 * shortcuts — remain, walking uses those). Its reached cost is the universal search cap, and its
	 * path is the last-resort route. Runs on the seed pool concurrently with the chain's first
	 * searches, on its own config copy.
	 */
	private WalkResult runWalkSearch(Generation g, AtomicInteger walkCeiling)
	{
		final int gen = g.gen;
		final int start = g.start;
		final Set<Integer> ends = g.ends;
		final Set<TeleportMethod> userExclusions = g.userExclusions;
		final List<TeleportMethod> catalog = g.catalog;
		final DistanceField field = g.field;
		final GenTimer timer = g.timer;
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
		SearchHeuristic heuristic = SearchHeuristic.buildWithField(config, field, start);
		Pathfinder pathfinder = new Pathfinder(config, start, ends, Integer.MAX_VALUE, heuristic);
		pathfinder.setDynamicCostCap(walkCeiling::get);
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
			// Aboard, the catalog-wide exclusion leaves the SAILING legs untouched (sea legs are
			// not catalog methods), so this search naturally yields the KEEP-SAILING route: sail
			// to the port nearest the target and walk ashore. That is the baseline a sailor
			// wants surfaced (capture 20260829-204334: every page slot was a disembark-teleport
			// chain and the band culled all keep-sailing options), not a defect - return it,
			// and its cost still serves as the universal ceiling. Any NON-sailing method here
			// remains a defect and must not become the walk cap or route.
			if (!(planningConfig.isOnSailingBoat() && isPureSail(scan.methods)))
			{
				return null;
			}
		}
		boolean reached = result.isReached();
		RouteOption route = new RouteOption(withoutIdleBankFlip(path, scan), scan.methods, scan.methodEdges, scan.methodDurations,
			result.getTotalCost(), scan.rawCost, reached, scan.bankGated, scan.bankGatedTransports, scan.walkBefore, scan.trailingWalk);
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
	private List<RouteOption> buildRoundTrips(Generation g, List<RouteOption> oneWays)
	{
		final int gen = g.gen;
		final int start = g.start;
		final Set<TeleportMethod> userExclusions = g.userExclusions;
		final List<TeleportMethod> catalog = g.catalog;
		final Map<TeleportMethod, MethodAvailability> unavailable = g.unavailable;
		final ResultListener listener = g.listener;
		final GenTimer timer = g.timer;
		final Set<Integer> home = Set.of(start);
		long fieldStart = System.nanoTime();
		// Return legs run uncapped, so keep the full flood here (a bounded one would stay correct
		// but could slow the uncapped searches); round-trip mode is the rare path.
		final DistanceField returnField = DistanceField.buildIfCompact(planningConfig, home, 0);
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
			Pathfinder back = new Pathfinder(planningConfig, endpoint, home, Integer.MAX_VALUE, heuristic);
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
			// The return leg's cumulative costs continue from the outbound total, so the loop's
			// steps carry one monotone cost line (the overlay's ETA table reads it).
			for (PathStep step : returnPath.subList(Math.min(1, returnPath.size()), returnPath.size()))
			{
				fullPath.add(step.getCost() == PathStep.UNKNOWN_COST ? step
					: new PathStep(step.getPackedPosition(), step.isBankVisited(), step.getCost() + oneWay.getTotalCost()));
			}
			MethodScan scan = scanMethods(planningConfig, fullPath);
			if (!signatures.add(signature(scan.methods))
				|| RouteAcceptance.hasRedundantTeleportHop(hopBaselineTeleports, scan.methods))
			{
				continue;
			}
			// The turnaround is the outbound path's last tile (the destination); the return leg's
			// duplicated first step was dropped, so outbound indexes are unshifted in fullPath.
			merged.add(new RouteOption(withoutIdleBankFlip(fullPath, scan), scan.methods, scan.methodEdges, scan.methodDurations,
				oneWay.getTotalCost() + result.getTotalCost(), scan.rawCost, oneWay.isReached(),
				scan.bankGated, scan.bankGatedTransports, scan.walkBefore, scan.trailingWalk, outPath.size() - 1));
			merged.sort(Comparator.comparingInt(RouteOption::getTotalCost));
			emit(gen, listener, new ArrayList<>(merged), catalog, unavailable, false);
		}
		return merged;
	}

	/**
	 * Search-CPU budget for UNREACHED targets (sealed cells, through-the-bars NPC tiles): with
	 * the heuristic degenerate, every chain route and seed costs an uninformed sweep — the page
	 * fills with whatever the budget affords (fast machines get more escapes, slow ones fewer)
	 * instead of enumerating the whole teleport catalog. "+" resumes with a fresh budget.
	 */
	private static final long UNREACHED_SEARCH_BUDGET_NANOS = 4_000_000_000L;
	/** Cumulative search CPU spent this generation vs the unreached-target budget. */
	private static boolean searchBudgetExhausted(GenTimer timer)
	{
		synchronized (timer)
		{
			return timer.searchNanos >= UNREACHED_SEARCH_BUDGET_NANOS;
		}
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
		return refreshOnClientThread(planningConfig, excluded, endsToFilter, mode);
	}

	private boolean refreshOnClientThread(PathfinderConfig target, Set<TeleportMethod> excluded,
		Set<Integer> endsToFilter, AlternativeRoutesMode mode)
	{
		final Set<TeleportMethod> excludedSnapshot = new HashSet<>(excluded);
		final CountDownLatch latch = new CountDownLatch(1);
		clientThread.invokeLater(() ->
		{
			try
			{
				target.setPlanningMode(mode == AlternativeRoutesMode.ALL_EVERYTHING);
				target.setBypassItemPossession(!mode.isOwned());
				target.setConsiderBank(mode == AlternativeRoutesMode.OWNED_WITH_BANK);
				target.setExcludedMethods(excludedSnapshot);
				target.refresh();
				if (endsToFilter != null)
				{
					target.filterLocations(endsToFilter, true);
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
		// Plain connectors (jungle bushes, a dig, a locked door) that ALSO only became usable
		// after the bank: not methods, so never on the card - but the withdraw step must name
		// their item or the route silently assumes a bank stop (issue #21: the machete).
		List<Transport> bankGatedTransports = new ArrayList<>();
		if (path == null)
		{
			return new MethodScan(methods, methodEdges, methodDurations, bankGated, bankGatedTransports, 0, new ArrayList<>(), 0);
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
				TeleportMethod method = chosen.method();
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
			if (chosen == null && edgeTransport != null && bankVisited
				&& edgeTransport.getItemRequirements() != null
				&& !availableWithoutBank(config, from.getPackedPosition(), edgeTransport))
			{
				bankGatedTransports.add(edgeTransport);
			}
			int edgeCost = edgeTransport != null
				? CostUnits.fromTicks(edgeTransport.getDuration())
				: WorldPointUtil.distanceBetween(from.getPackedPosition(), to.getPackedPosition());
			rawCost += edgeCost;
			if (chosen == null)
			{
				legSteps += edgeCost;
			}
		}
		return new MethodScan(methods, methodEdges, methodDurations, bankGated, bankGatedTransports, rawCost, walkBefore, legSteps);
	}

	/**
	 * A bank flip that unlocked nothing is not a bank stop (issues #20 / #7). The search may
	 * enter the banked state on a bank tile the path merely crosses; when no method and no
	 * connector after it needed a banked item, the flip is noise — and downstream a banked step
	 * READS as a withdrawal (the overlay's pickup hint and bank glyph, the capture JSON). The
	 * flags are cleared in place of the steps; no step is dropped, so the scan's edge indexes
	 * still line up with the path.
	 */
	static List<PathStep> withoutIdleBankFlip(List<PathStep> path, MethodScan scan)
	{
		if (path == null || !scan.bankGated.isEmpty() || !scan.bankGatedTransports.isEmpty())
		{
			return path;
		}
		boolean flipped = false;
		for (PathStep step : path)
		{
			if (step.isBankVisited())
			{
				flipped = true;
				break;
			}
		}
		if (!flipped)
		{
			return path;
		}
		List<PathStep> plain = new ArrayList<>(path.size());
		for (PathStep step : path)
		{
			plain.add(step.isBankVisited() ? new PathStep(step.getPackedPosition(), false, step.getCost()) : step);
		}
		return plain;
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
		private final List<Transport> bankGatedTransports;
		// Path cost without any configured weights, in CostUnits (run-tiles, 0.3s each): walk
		// distance plus time-normalized transport travel times. This is the route's ETA.
		private final int rawCost;
		// Tiles walked before each method (parallel to methods) and after the last one.
		private final List<Integer> walkBefore;
		private final int trailingWalk;

		MethodScan(List<TeleportMethod> methods, List<Integer> methodEdges, List<Integer> methodDurations,
			Set<TeleportMethod> bankGated, List<Transport> bankGatedTransports, int rawCost,
			List<Integer> walkBefore, int trailingWalk)
		{
			this.methods = methods;
			this.methodEdges = methodEdges;
			this.methodDurations = methodDurations;
			this.bankGated = bankGated;
			this.bankGatedTransports = bankGatedTransports;
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
				&& (!methodsOnly || TeleportMethod.isMethodType(transport.getType())
					// Sailing is not a CATALOG method, but its legs must appear in the route
					// methods: the cards, and the sea-track edge detection in the overlay.
					|| transport.getType() == gps.transport.TransportType.SAILING)
				&& searchEdgeCost(config, transport) < bestCost)
			{
				best = transport;
				bestCost = searchEdgeCost(config, transport);
			}
		}
		for (Transport transport : config.getUsableTeleports(bankVisited))
		{
			if (transport.getDestination() == destination
				&& (!methodsOnly || TeleportMethod.isMethodType(transport.getType())
					// Sailing is not a CATALOG method, but its legs must appear in the route
					// methods: the cards, and the sea-track edge detection in the overlay.
					|| transport.getType() == gps.transport.TransportType.SAILING)
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

	/**
	 * Whether the candidate's method sequence ENDS WITH a kept route's entire (non-empty)
	 * method sequence — the kept route is nested inside it: the candidate detours and then runs
	 * the kept route anyway ("Ring of dueling + glory + carts" when "glory + carts" is already
	 * shown; a field capture had SEVEN of these). Strictly costlier, zero new information.
	 * Seeds produce them because a seed search only excludes fellow NEAR-TARGET teleports, so a
	 * far teleport chain stays available inside every seed's search. Bank-fetching candidates
	 * are spared when the kept route isn't via-bank: withdrawing the item IS their point.
	 */
	/**
	 * Two shared-destination networks reached the same tile as one (capture 20260823-213943): the
	 * quetzal whistle lands at any built site, so "Whistle to Quetzacalli, quetzal to Civitas,
	 * charter on" is "Whistle to Civitas, charter on" with extra steps — six of ten routes were
	 * such variants, one per whistle destination the exclusion chain tried. A teleport followed
	 * IMMEDIATELY by a flight of the network that shares its destinations
	 * ({@link gps.transport.TransportType#sharesDestinationsWith}), landing where a usable
	 * teleport of the same kind could already go, adds nothing over the direct form (which has
	 * its own signature and its own slot). Bankless usability is checked: if the direct teleport
	 * only works from the bank, the hop variant is a genuinely different (bankless) route.
	 */
	/** Whether every method is a sailing leg (see RouteOption#isPureSail). */
	static boolean isPureSail(List<TeleportMethod> methods)
	{
		if (methods.isEmpty())
		{
			return false;
		}
		for (TeleportMethod method : methods)
		{
			if (method.getType() != gps.transport.TransportType.SAILING)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * The teleports the hop filter treats as "already available": bankless-usable, of a kind
	 * paired with a flight network (sharesDestinationsWith), minus the USER's exclusions — an
	 * excluded direct teleport is genuinely unavailable, so its hop variants are then legitimate
	 * alternatives. Captured once per generation (see computeRoutes) because the chain's own
	 * exclusion rebuilds must not blind the filter to the direct teleport they just removed.
	 */
	static List<Transport> teleportHopBaseline(PathfinderConfig config, Set<TeleportMethod> userExclusions)
	{
		List<Transport> baseline = new ArrayList<>();
		for (Transport teleport : config.getUsableTeleports(false))
		{
			if (teleport.getType() != null && teleport.getType().sharesDestinationsWith() != null
				&& !userExclusions.contains(teleport.method()))
			{
				baseline.add(teleport);
			}
		}
		return baseline;
	}

	/** The current generation's hop-filter baseline; set per generation, read by chain/seed/merge filters. */
	private volatile List<Transport> hopBaselineTeleports = List.of();

	private static String signature(List<TeleportMethod> methods)
	{
		if (methods.isEmpty())
		{
			return "<walk-only>";
		}
		StringBuilder sb = new StringBuilder();
		// Parking-variant collapse (capture 233931): when a LATER method re-embarks under
		// the summon assumption, the boat gets summoned away from wherever the route just
		// disembarked — the parking port has zero lasting effect, and five routes differing
		// only in it crowded out real alternatives. The leading disembark becomes a
		// wildcard so one such route represents the family. Abandon-off overland
		// continuations (finding 5) have no later embark: their ports stay distinct.
		boolean laterEmbark = false;
		for (int i = 1; i < methods.size(); i++)
		{
			TeleportMethod method = methods.get(i);
			laterEmbark |= gps.transport.TransportType.SAILING.equals(method.getType())
				&& method.getDisplayInfo() != null
				&& method.getDisplayInfo().startsWith("Embark at ");
		}
		TeleportMethod first = methods.get(0);
		boolean parkingFirst = gps.transport.TransportType.SAILING.equals(first.getType())
			&& first.getDisplayInfo() != null
			&& first.getDisplayInfo().startsWith("Disembark at ");
		for (int i = 0; i < methods.size(); i++)
		{
			sb.append(i == 0 && parkingFirst && laterEmbark
				? "Disembark at *" : methods.get(i).toString()).append('|');
		}
		return sb.toString();
	}
}
