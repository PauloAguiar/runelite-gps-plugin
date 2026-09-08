package gps;

/**
 * The player's boat hull tiers (plan step L5), as the hull type varbit counts them in
 * acquisition order: the Pandemonium quest raft is 0, the level-15 skiff 1, the level-50 sloop
 * 2 (verified against a capture with all three owned). An unknown future tier maps to nothing
 * and the panel simply shows no type.
 */
public enum BoatHull
{
	RAFT(0, "Raft"),
	SKIFF(1, "Skiff"),
	SLOOP(2, "Sloop");

	private final int varbitValue;
	private final String displayName;

	BoatHull(int varbitValue, String displayName)
	{
		this.varbitValue = varbitValue;
		this.displayName = displayName;
	}

	public String displayName()
	{
		return displayName;
	}

	/** The hull for a hull-type varbit value, or null for a value this build does not know. */
	public static BoatHull fromVarbit(int value)
	{
		for (BoatHull hull : values())
		{
			if (hull.varbitValue == value)
			{
				return hull;
			}
		}
		return null;
	}

	/** The hull for a persisted display name ("Raft"), or null for anything else. */
	public static BoatHull fromName(String name)
	{
		for (BoatHull hull : values())
		{
			if (hull.displayName.equals(name))
			{
				return hull;
			}
		}
		return null;
	}
}
