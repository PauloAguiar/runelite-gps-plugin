package gps;

import java.util.Locale;

/**
 * Fuzzy name matching for the destination search. {@link #score} returns 0 for no match and a
 * positive score otherwise, tiered so tighter matches always outrank looser ones:
 * exact name &gt; name prefix &gt; word prefixes ("var pal" finds "Varrock Palace") &gt;
 * substring &gt; subsequence (query letters appear in order with gaps — typo-tolerant
 * abbreviations like "brmhvn" for "Brimhaven"). Within a tier, shorter names rank higher.
 */
final class SearchMatcher
{
	private static final int TIER_EXACT = 1000;
	private static final int TIER_PREFIX = 900;
	private static final int TIER_WORD_PREFIX = 800;
	private static final int TIER_SUBSTRING = 700;
	private static final int TIER_SUBSEQUENCE = 300;
	// Within-tier penalty for longer names; kept below the tier spacing so tiers never cross.
	private static final int MAX_LENGTH_PENALTY = 80;
	// Subsequence matching needs a few characters to say anything — 1-2 letters match everything.
	private static final int MIN_SUBSEQUENCE_QUERY = 3;

	private SearchMatcher()
	{
	}

	static int score(String name, String query)
	{
		final String literal = query.toLowerCase(Locale.ROOT).trim();
		final String expanded = SearchAliases.expand(query);
		final int direct = scoreLiteral(name, literal);
		return expanded.equals(literal) ? direct : Math.max(direct, scoreLiteral(name, expanded));
	}

	private static int scoreLiteral(String name, String query)
	{
		final String n = name.toLowerCase(Locale.ROOT);
		final String q = query;
		if (q.isEmpty())
		{
			return 0;
		}
		final int lengthPenalty = Math.min(n.length(), MAX_LENGTH_PENALTY);
		if (n.equals(q))
		{
			return TIER_EXACT;
		}
		if (n.startsWith(q))
		{
			return TIER_PREFIX - lengthPenalty;
		}
		if (wordPrefixes(n, q))
		{
			return TIER_WORD_PREFIX - lengthPenalty;
		}
		if (n.contains(q))
		{
			return TIER_SUBSTRING - lengthPenalty;
		}
		final String letters = q.replaceAll("\\s+", "");
		if (letters.length() >= MIN_SUBSEQUENCE_QUERY && isSubsequence(n, letters))
		{
			return TIER_SUBSEQUENCE - lengthPenalty;
		}
		return 0;
	}

	/** Every whitespace-separated query token prefixes a distinct name word, in order. */
	private static boolean wordPrefixes(String name, String query)
	{
		final String[] tokens = query.split("\\s+");
		final String[] words = name.split("[^a-z0-9]+");
		int w = 0;
		for (String token : tokens)
		{
			boolean matched = false;
			while (w < words.length)
			{
				if (words[w++].startsWith(token))
				{
					matched = true;
					break;
				}
			}
			if (!matched)
			{
				return false;
			}
		}
		return true;
	}

	/** The query's letters appear in the name in order, gaps allowed. */
	private static boolean isSubsequence(String name, String letters)
	{
		int i = 0;
		for (int j = 0; j < name.length() && i < letters.length(); j++)
		{
			if (name.charAt(j) == letters.charAt(i))
			{
				i++;
			}
		}
		return i == letters.length();
	}
}
