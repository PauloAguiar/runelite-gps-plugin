package gps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.ChatMessageType;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;

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

	/** Display names per log type, in the same order as {@link #CONFIG_KEYS}. */
	public static final String[] TYPE_NAMES = {"normal", "oak", "willow", "yew", "magic"};

	/** Item ids per log type (for icons and requirement checks), in {@link #CONFIG_KEYS} order. */
	public static final int[] ITEM_IDS = {
		ItemID.LOGS, ItemID.OAK_LOGS, ItemID.WILLOW_LOGS, ItemID.YEW_LOGS, ItemID.MAGIC_LOGS};

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

	/**
	 * The log types worth warning about: below the threshold in storage AND belonging to a balloon
	 * route the player has unlocked (a locked route's empty storage is noise, not a warning).
	 * Arrays follow the {@link #TYPE_NAMES} order; returns display names.
	 */
	/**
	 * Tracks the storage from a chat line (plan step L34, out of the plugin class): the crates'
	 * contents have no varbit, chat is the game's only client-side signal (the approach the
	 * dedicated tictac7x-balloon plugin uses). Counts persist in config and let balloon flights be
	 * paid from storage; the first count also marks the storage synced. Only with smart mode on,
	 * and only the game (spam) and dialog (mesbox) channels carry these lines.
	 */
	public static void track(ChatMessageType type, String message, ShortestPathConfig config,
		ConfigManager configManager, String configGroup)
	{
		if (!config.balloonSmartMode() || (type != ChatMessageType.SPAM && type != ChatMessageType.MESBOX))
		{
			return;
		}
		Map<String, Integer> updates = parse(message);
		for (Map.Entry<String, Integer> update : updates.entrySet())
		{
			configManager.setConfiguration(configGroup, update.getKey(), update.getValue());
		}
		if (!updates.isEmpty() && !config.balloonStorageSynced())
		{
			configManager.setConfiguration(configGroup, "balloonStorageSynced", true);
		}
	}

	public static List<String> lowTypes(int[] stored, boolean[] unlocked, int threshold)
	{
		List<String> low = new ArrayList<>();
		for (int i = 0; i < TYPE_NAMES.length; i++)
		{
			if (unlocked[i] && stored[i] < threshold)
			{
				low.add(TYPE_NAMES[i]);
			}
		}
		return low;
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
