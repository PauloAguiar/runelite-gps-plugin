package gps;

import java.util.Locale;
import java.util.Map;

/**
 * Player vocabulary for the destination search (plan step N11): query tokens that are neither a
 * prefix nor a subsequence of the place they mean. Prefix forms ("brim", "cath", "dray") already
 * match through the tiers, so only the genuinely different spellings live here.
 */
final class SearchAliases
{
	private static final Map<String, String> ALIASES = Map.ofEntries(
		Map.entry("ge", "grand exchange"),
		Map.entry("wildy", "wilderness"),
		Map.entry("lumby", "lumbridge"),
		Map.entry("fally", "falador"),
		Map.entry("cammy", "camelot"),
		Map.entry("ardy", "ardougne"),
		Map.entry("priff", "prifddinas"),
		Map.entry("pc", "pest control"),
		Map.entry("cw", "castle wars"),
		Map.entry("cwars", "castle wars"),
		Map.entry("gwd", "god wars dungeon"),
		Map.entry("cox", "chambers of xeric"),
		Map.entry("tob", "theatre of blood"),
		Map.entry("toa", "tombs of amascut"),
		Map.entry("sw", "soul wars"),
		Map.entry("mta", "mage training arena"),
		Map.entry("wt", "wintertodt"),
		Map.entry("bf", "blast furnace"),
		Map.entry("nmz", "nightmare zone"),
		Map.entry("barb", "barbarian"),
		Map.entry("kq", "kalphite queen"),
		Map.entry("zmi", "ourania"));

	private SearchAliases()
	{
	}

	/**
	 * The query with every alias token replaced by what it stands for, lower-cased; the query
	 * itself (lower-cased, trimmed) when no token is an alias.
	 */
	static String expand(String query)
	{
		String[] tokens = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
		StringBuilder out = new StringBuilder();
		boolean changed = false;
		for (String token : tokens)
		{
			String expansion = ALIASES.get(token);
			if (out.length() > 0)
			{
				out.append(' ');
			}
			out.append(expansion != null ? expansion : token);
			changed |= expansion != null;
		}
		return changed ? out.toString() : query.toLowerCase(Locale.ROOT).trim();
	}
}
