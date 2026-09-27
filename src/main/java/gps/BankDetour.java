package gps;

import java.util.Set;

/**
 * The destination a nearest-bank trip replaced, resumed once the trip completes (a player
 * suggestion: bank mid-route, then carry on). Only the nearest-bank action starts a trip and only
 * an active destination is remembered; any other change of destination (a new pin, a clear,
 * another plugin's target) forgets it, so a resume never surprises. A second bank click during a
 * trip keeps the original destination: what that click replaces is the bank trip itself.
 */
final class BankDetour
{
	/** A replaced destination: its targets, label, round-trip flag and world-map pin. */
	static final class Route
	{
		final Set<Integer> targets;
		final String source;
		final boolean roundTrip;
		final int marker;

		private Route(Set<Integer> targets, String source, boolean roundTrip, int marker)
		{
			this.targets = targets;
			this.source = source;
			this.roundTrip = roundTrip;
			this.marker = marker;
		}

		/** The route for a destination, or null when none is set. */
		static Route of(Set<Integer> targets, String source, boolean roundTrip, int marker)
		{
			return targets == null || targets.isEmpty()
				? null : new Route(Set.copyOf(targets), source, roundTrip, marker);
		}
	}

	private Route saved;
	private boolean active;

	/** Whether a bank trip is under way. */
	synchronized boolean isActive()
	{
		return active;
	}

	/**
	 * What a new bank trip replaces: the original destination while a trip is already under way,
	 * otherwise {@code current}. Read before the destination changes, since the change forgets it.
	 */
	synchronized Route replacing(Route current)
	{
		return active ? saved : current;
	}

	/** A bank trip started; {@code replaced} is resumed when it completes (null: nothing). */
	synchronized void begin(Route replaced)
	{
		saved = replaced;
		active = true;
	}

	/** Another destination replaced or cleared the trip: nothing will be resumed. */
	synchronized void cancel()
	{
		saved = null;
		active = false;
	}

	/** The trip completed: the destination to resume (null if none), forgotten either way. */
	synchronized Route complete()
	{
		Route route = active ? saved : null;
		cancel();
		return route;
	}
}
