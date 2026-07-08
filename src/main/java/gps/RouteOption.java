package gps;

import java.util.List;
import java.util.Set;
import lombok.Getter;
import gps.pathfinder.PathStep;

/**
 * One alternative route to the target, produced by {@link AlternativeRoutesService}. Each route is a
 * shortest path under the constraint that it uses a different set of teleport/transport methods than
 * the other routes in the same result list.
 */
@Getter
public final class RouteOption
{
	/**
	 * The full tile path, as returned by the pathfinder.
	 */
	private final List<PathStep> path;
	/**
	 * The teleport/transport methods used along the path, in travel order. Empty for a walk-only route.
	 */
	private final List<TeleportMethod> methods;
	/**
	 * Path index of the edge each method sits on, parallel to {@link #methods}: the index of the path
	 * step the method arrives at. Authoritative method positions for the directions overlay — the
	 * route was computed with the planning config's availability, so its methods cannot be reliably
	 * re-derived against the main config's.
	 */
	private final List<Integer> methodEdgeIndexes;
	/**
	 * Travel time of each method's transport in game ticks, parallel to {@link #methods} — for the
	 * directions overlay's time estimates.
	 */
	private final List<Integer> methodDurations;
	/**
	 * Blended search cost in CostUnits (run-tiles, 0.3s each): time-normalized walk + transport
	 * travel, plus any configured method weights. Routes are ordered by this; lower is faster.
	 */
	private final int totalCost;
	/**
	 * The path's cost without any configured weights, in CostUnits (run-tiles, 0.3s each): its ETA.
	 * Equal to {@link #totalCost} when no weights applied to this route.
	 */
	private final int rawCost;
	/**
	 * Whether the path actually reaches the exact target. When false it ends at the closest reachable
	 * tile (mirrors Shortest Path's own behaviour for unreachable targets, e.g. an NPC/object tile).
	 */
	private final boolean reached;
	/**
	 * The subset of {@link #methods} that require an item that must first be withdrawn from the bank —
	 * the path includes a walk to a bank before each of these is used. Empty when the route needs no
	 * bank visit. Lets the panel say <em>which</em> method the bank detour is for.
	 */
	private final Set<TeleportMethod> bankMethods;
	/**
	 * Tiles walked before each method, parallel to {@link #methods} (walking to a minecart, a fairy
	 * ring, a bank, ...). Plain connectors along the way (doors, stairs, shortcuts) count into the leg.
	 */
	private final List<Integer> walkBeforeSteps;
	/**
	 * Tiles walked after the last method to the destination; the whole walk for walk-only routes.
	 */
	private final int trailingWalkSteps;
	/**
	 * For round trips (out to the destination and back to the start): the path index of the
	 * turnaround — the destination tile where the outbound leg ends. -1 for ordinary routes.
	 * A round trip's path ends where it began, and its return leg often retraces the outbound
	 * street, so progress tracking and the arrival check gate the return leg behind this index —
	 * otherwise walking OUT reads as coming back (instant false arrival).
	 */
	private final int turnaroundIndex;

	public RouteOption(List<PathStep> path, List<TeleportMethod> methods, List<Integer> methodEdgeIndexes,
		List<Integer> methodDurations, int totalCost, int rawCost, boolean reached, Set<TeleportMethod> bankMethods,
		List<Integer> walkBeforeSteps, int trailingWalkSteps)
	{
		this(path, methods, methodEdgeIndexes, methodDurations, totalCost, rawCost, reached, bankMethods,
			walkBeforeSteps, trailingWalkSteps, -1);
	}

	public RouteOption(List<PathStep> path, List<TeleportMethod> methods, List<Integer> methodEdgeIndexes,
		List<Integer> methodDurations, int totalCost, int rawCost, boolean reached, Set<TeleportMethod> bankMethods,
		List<Integer> walkBeforeSteps, int trailingWalkSteps, int turnaroundIndex)
	{
		this.path = path;
		this.methods = methods;
		this.methodEdgeIndexes = methodEdgeIndexes;
		this.methodDurations = methodDurations;
		this.totalCost = totalCost;
		this.rawCost = rawCost;
		this.reached = reached;
		this.bankMethods = bankMethods;
		this.walkBeforeSteps = walkBeforeSteps;
		this.trailingWalkSteps = trailingWalkSteps;
		this.turnaroundIndex = turnaroundIndex;
	}

	/** Whether this route is a round trip — see {@link #turnaroundIndex}. */
	public boolean isRoundTrip()
	{
		return turnaroundIndex >= 0;
	}

	/**
	 * Tiles walked before the method at {@code index}, or 0 when unknown.
	 */
	public int walkBefore(int index)
	{
		return (index >= 0 && index < walkBeforeSteps.size()) ? walkBeforeSteps.get(index) : 0;
	}

	/**
	 * Whether one of this route's methods requires a bank withdrawal first (see {@link #bankMethods}).
	 */
	public boolean isViaBank()
	{
		return !bankMethods.isEmpty();
	}

	/**
	 * The method the route leans on first; the one the panel offers to exclude. Null for walk-only.
	 */
	public TeleportMethod primaryMethod()
	{
		return methods.isEmpty() ? null : methods.get(0);
	}

	public boolean isWalkOnly()
	{
		return methods.isEmpty();
	}
}
