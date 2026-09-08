package gps.pathfinder;

import lombok.Getter;

@Getter
public final class PathStep
{
	private final int packedPosition;
	private final boolean bankVisited;
	/**
	 * The search's cumulative cost at this step, in cost units, or {@link #UNKNOWN_COST} for a
	 * step not produced by a search (plan step N9: the directions overlay's ETA derives from
	 * these, so it is the same number as the route card's total by construction).
	 */
	private final int cost;

	public static final int UNKNOWN_COST = -1;

	public PathStep(int packedPosition, boolean bankVisited)
	{
		this(packedPosition, bankVisited, UNKNOWN_COST);
	}

	public PathStep(int packedPosition, boolean bankVisited, int cost)
	{
		this.packedPosition = packedPosition;
		this.bankVisited = bankVisited;
		this.cost = cost;
	}
}
