package gps;

/**
 * When the teleport-methods catalog needs a re-classification after an inventory or equipment
 * change (plan step L29, out of the plugin class; issue #5). Only the routing-relevant slice of
 * the items counts: the dependency index fingerprints every item id (and quantity threshold) a
 * transport requirement can read, so logs, ore, food and loot pass through without ever
 * scheduling a refresh (issues #23/#24). The catalog exists for the sidebar, so a dirty flag
 * waits while the panel is hidden (every pickup, drop and gear switch used to run a full planning
 * refresh, hundreds of quest clientscripts, on the client thread: a per-action micro stutter for
 * players who never open the panel); route generations rebuild the catalog themselves, so routing
 * never sees the deferral. Bursts while the panel is open coalesce through a short cooldown; the
 * flag stays set, so no change is lost, only delayed a few ticks.
 */
final class CatalogRefresher
{
	/** Ticks between two claimed refreshes while changes keep coming. */
	static final int COOLDOWN_TICKS = 5;

	private volatile boolean dirty;
	private int backoffTick;
	private long fingerprint;
	private boolean fingerprintValid;

	/** The catalog must be re-classified (no fingerprint available to compare). */
	void markDirty()
	{
		dirty = true;
	}

	/**
	 * An inventory or equipment change: dirties the catalog only when the routing-relevant item
	 * fingerprint differs from the last one seen (the first one always counts). True when it did.
	 */
	boolean noteItems(long routingItemsFingerprint)
	{
		if (fingerprintValid && routingItemsFingerprint == fingerprint)
		{
			return false;
		}
		fingerprint = routingItemsFingerprint;
		fingerprintValid = true;
		dirty = true;
		return true;
	}

	/**
	 * Whether a refresh should run now: the catalog is dirty, the panel is shown, no generation is
	 * in flight (it re-snapshots anyway), the player is logged in and the cooldown has elapsed.
	 * Claiming clears the flag and arms the cooldown from {@code tick}.
	 */
	boolean claim(int tick, boolean panelVisible, boolean generationInFlight, boolean loggedIn)
	{
		if (!dirty || !panelVisible || generationInFlight || !loggedIn || tick < backoffTick)
		{
			return false;
		}
		backoffTick = tick + COOLDOWN_TICKS;
		dirty = false;
		return true;
	}
}
