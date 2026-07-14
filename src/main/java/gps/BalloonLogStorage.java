package gps;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the balloon log-storage chat messages into per-type stored counts. The storage crates'
 * contents are not exposed through varbits or varps — the game only reveals them in chat (verified
 * against the dedicated tictac7x-balloon hub plugin, which uses the same approach; the message
 * patterns below follow its battle-tested set). Counts persist in the plugin config, so they
 * survive sessions; they start at 0 (conservative: no flight is assumed payable from storage until
 * the game has confirmed logs are there) and re-sync whenever the player stores logs, checks the
 * crate, or flies.
 */
public final class BalloonLogStorage
{
	/** Config keys per log type, in {logs, oak, willow, yew, magic} order. */
	public static final String[] CONFIG_KEYS = {
		"balloonStoredLogs", "balloonStoredOakLogs", "balloonStoredWillowLogs",
		"balloonStoredYewLogs", "balloonStoredMagicLogs"};

	private static final Pattern LEFT_PLURAL = Pattern.compile("You have (?<count>\\d+) sets of (?<type>.*) left in storage\\.");
	private static final Pattern LEFT_SINGULAR = Pattern.compile("You have one set of (?<type>.*) left in storage\\.");
	private static final Pattern USED_LAST = Pattern.compile("You used the last of your (?<type>.*)\\.");
	private static final Pattern STORED = Pattern.compile("You put the (?<type>.*) in the crate\\. You now have (?<count>\\d+) stored\\.");
	private static final Pattern NEEDED = Pattern.compile("You need 1 (?<type>.*) logs to make this trip\\.");
	private static final Pattern CHECK = Pattern.compile(
		"This crate currently contains (?<regular>\\d+) logs, (?<oak>\\d+) oak logs, (?<willow>\\d+) willow logs, "
			+ "(?<yew>\\d+) yew logs and (?<magic>\\d+) magic logs\\.");

	private BalloonLogStorage()
	{
	}

	/**
	 * The stored-count updates a chat message implies: config key -> new count. Empty when the
	 * message is not a balloon-storage message.
	 */
	public static Map<String, Integer> parse(String message)
	{
		Map<String, Integer> updates = new LinkedHashMap<>();
		Matcher check = CHECK.matcher(message);
		if (check.find())
		{
			updates.put(CONFIG_KEYS[0], Integer.parseInt(check.group("regular")));
			updates.put(CONFIG_KEYS[1], Integer.parseInt(check.group("oak")));
			updates.put(CONFIG_KEYS[2], Integer.parseInt(check.group("willow")));
			updates.put(CONFIG_KEYS[3], Integer.parseInt(check.group("yew")));
			updates.put(CONFIG_KEYS[4], Integer.parseInt(check.group("magic")));
			return updates;
		}
		Matcher stored = STORED.matcher(message);
		if (stored.find())
		{
			put(updates, stored.group("type"), Integer.parseInt(stored.group("count")));
			return updates;
		}
		Matcher plural = LEFT_PLURAL.matcher(message);
		if (plural.find())
		{
			put(updates, plural.group("type"), Integer.parseInt(plural.group("count")));
			return updates;
		}
		Matcher singular = LEFT_SINGULAR.matcher(message);
		if (singular.find())
		{
			put(updates, singular.group("type"), 1);
			return updates;
		}
		Matcher last = USED_LAST.matcher(message);
		if (last.find())
		{
			put(updates, last.group("type"), 0);
			return updates;
		}
		Matcher needed = NEEDED.matcher(message);
		if (needed.find())
		{
			// "You need 1 X logs to make this trip" fires when neither inventory nor storage can
			// pay: the storage is empty for that type.
			put(updates, needed.group("type"), 0);
			return updates;
		}
		return updates;
	}

	private static void put(Map<String, Integer> updates, String type, int count)
	{
		String key = keyOf(type);
		if (key != null)
		{
			updates.put(key, count);
		}
	}

	private static String keyOf(String type)
	{
		switch (type)
		{
			case "Logs":
			case "normal":
			case "regular":
				return CONFIG_KEYS[0];
			case "Oak logs":
			case "oak":
				return CONFIG_KEYS[1];
			case "Willow logs":
			case "willow":
				return CONFIG_KEYS[2];
			case "Yew logs":
			case "yew":
				return CONFIG_KEYS[3];
			case "Magic logs":
			case "magic":
				return CONFIG_KEYS[4];
			default:
				return null;
		}
	}
}
