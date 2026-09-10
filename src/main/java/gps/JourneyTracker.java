package gps;

/**
 * The journey wall-clock reported on arrival (plan step L6, out of the plugin class). Armed by a
 * new destination or a newly chosen path, it starts counting on the player's first ACTION after
 * that: a tick where the player moved, or one where the player is performing an animation
 * (casting or using a teleport). The animation case matters for long teleport channels such as
 * Lumbridge Home Teleport, where the player stays put for the whole cast; a move-only trigger
 * would start the clock after landing and lose that time. Standing still after setting a
 * destination inflates nothing. Written on the client thread, read by the overlay's render.
 */
final class JourneyTracker
{
	/** 0 while armed and waiting for the first action. */
	private volatile long startMillis;
	private int lastLocation = WorldPointUtil.UNDEFINED;

	/** Re-arms the timer so it recounts from the player's next action. */
	void arm()
	{
		startMillis = 0;
		lastLocation = WorldPointUtil.UNDEFINED;
	}

	/**
	 * One game tick at {@code location} (the boat-aware packed position), {@code acting} when the
	 * player has an animation running. Starts the clock at {@code nowMillis} on the first tick
	 * that moved since arming or acted; later ticks only track the location.
	 */
	void tick(int location, boolean acting, long nowMillis)
	{
		boolean moved = lastLocation != WorldPointUtil.UNDEFINED && location != lastLocation;
		if (startMillis == 0 && (moved || acting))
		{
			startMillis = nowMillis;
		}
		lastLocation = location;
	}

	/** The wall-clock start, or 0 while the journey has not begun. */
	long startMillis()
	{
		return startMillis;
	}

	/** Elapsed time at {@code nowMillis}; 0 for a journey that never started (arrived without moving). */
	long elapsedMillis(long nowMillis)
	{
		long start = startMillis;
		return start == 0 ? 0 : Math.max(0, nowMillis - start);
	}
}
