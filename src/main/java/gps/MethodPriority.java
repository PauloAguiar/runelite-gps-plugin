package gps;

import gps.pathfinder.CostUnits;

/**
 * A user-set ranking bias for a travel method — RimWorld-style tiers. Priorities shift a route's
 * EFFECTIVE ETA (the sort key and the card's adjustment chip) by a fixed number of seconds per
 * prioritised method used; they never change the search itself, so adjusting them re-sorts the
 * existing list instantly with no recalculation. EXCLUDED is the terminal tier and delegates to
 * the existing exclusion machinery (which does affect the search).
 */
public enum MethodPriority
{
	PREFER_3(-20, "Strongly prefer"),
	PREFER_2(-10, "Prefer"),
	PREFER_1(-5, "Slightly prefer"),
	NORMAL(0, "Normal"),
	AVOID_1(5, "Slightly avoid"),
	AVOID_2(10, "Avoid"),
	AVOID_3(20, "Strongly avoid"),
	EXCLUDED(0, "Exclude");

	/** Seconds added to the route's effective ETA (negative = preferred, ranks earlier). */
	public final int adjustSeconds;
	public final String label;

	MethodPriority(int adjustSeconds, String label)
	{
		this.adjustSeconds = adjustSeconds;
		this.label = label;
	}

	/** The adjustment in search cost units (1 tick = 0.6s), rounded to the nearest unit. */
	public int adjustUnits()
	{
		return unitsFromSeconds(adjustSeconds);
	}

	/** Seconds -> cost units: seconds / 0.6s-per-tick * units-per-tick, away-from-zero rounding. */
	public static int unitsFromSeconds(int seconds)
	{
		return (int) Math.round(seconds / 0.6 * CostUnits.UNITS_PER_TICK);
	}

	/** The +Ns / -Ns chip text ("" for no adjustment). */
	public String chipText()
	{
		if (adjustSeconds == 0)
		{
			return "";
		}
		return (adjustSeconds > 0 ? "+" : "−") + Math.abs(adjustSeconds) + "s";
	}
}
