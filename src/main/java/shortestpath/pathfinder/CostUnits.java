package shortestpath.pathfinder;

/**
 * The pathfinder's cost currency, normalized to real time: one unit is the time a RUNNING player
 * takes to cover one tile — half a game tick (0.3 seconds). A walking edge costs its tile distance
 * (unchanged), and a transport's travel time in game ticks converts at {@link #UNITS_PER_TICK}
 * units per tick, so both kinds of edge are priced in the same wall-clock currency.
 * <p>
 * With no configured weights a path's cost IS its ETA ({@code units × 0.3s}), so routes ordered by
 * cost are ordered by estimated time. The per-method config weights are preference modifiers in
 * this same currency — positive disfavors a method, negative favors it — never a different unit,
 * which is what keeps the ordering consistent with the displayed ETAs.
 */
public final class CostUnits
{
	/** Cost units per game tick: one tick of transport travel time = two run-tiles of walking. */
	public static final int UNITS_PER_TICK = 2;
	/** Wall-clock seconds per cost unit (a 0.6s game tick covers 2 run-tiles). */
	public static final double SECONDS_PER_UNIT = 0.3;

	/** Converts a duration in game ticks to cost units. */
	public static int fromTicks(int ticks)
	{
		return UNITS_PER_TICK * ticks;
	}

	private CostUnits()
	{
	}
}
