package gps;

import gps.pathfinder.PathfinderConfig;
import java.util.function.BooleanSupplier;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.config.ConfigManager;

/**
 * What the client knows about the bank this session, and the cross-session snapshot (plan step
 * L16, out of the plugin class). The bank container is only populated once the bank has been
 * opened; until then "+ Bank" routes and the catalog's in-bank availability fall back to the
 * previous session's snapshot, persisted per character (RSProfile-scoped) when the setting is
 * on, and replaced by the live contents the first time the bank opens.
 */
final class BankSnapshotService
{
	/** RSProfile-scoped (per character, per world type): {@code id:quantity} pairs. */
	static final String CONFIG_KEY_BANK_SNAPSHOT = "bankSnapshot";

	private final ConfigManager configManager;
	private final String configGroup;
	private final BooleanSupplier rememberBank;
	private final PathfinderConfig pathfinderConfig;

	// Whether the bank's contents are known this session (live or restored). The panel uses it to
	// explain why Bank mode finds nothing.
	private volatile boolean known;
	// True when the knowledge came from a previous session's snapshot rather than the bank being
	// opened this session; cleared the moment the live bank is seen. The panel shows the source.
	private volatile boolean restored;
	// The bank changed since it was last persisted; saved once when the bank closes, not per deposit.
	private boolean saveDirty;
	// The RS profile key captured while the bank was seen, so the save still lands in the right
	// profile if it happens after logout (when the current profile is no longer available).
	private String saveProfileKey;

	BankSnapshotService(ConfigManager configManager, String configGroup, BooleanSupplier rememberBank,
		PathfinderConfig pathfinderConfig)
	{
		this.configManager = configManager;
		this.configGroup = configGroup;
		this.rememberBank = rememberBank;
		this.pathfinderConfig = pathfinderConfig;
	}

	boolean isKnown()
	{
		return known;
	}

	boolean isRestored()
	{
		return restored;
	}

	/** Plugin start with the bank already populated this session: adopt it, nothing to stage. */
	void adoptLive(ItemContainer liveBank)
	{
		if (liveBank != null && liveBank.getItems().length > 0)
		{
			pathfinderConfig.bank = liveBank;
			pathfinderConfig.setBankSnapshot(liveBank.getItems());
			known = true;
		}
	}

	/**
	 * The bank container changed while open. Snapshots the items now (the client may empty the
	 * live container once the interface closes) and stages the cross-session save with the
	 * profile key of the moment. True on the first sight of the bank this session.
	 */
	boolean bankOpened(ItemContainer bank)
	{
		pathfinderConfig.bank = bank;
		pathfinderConfig.setBankSnapshot(bank.getItems());
		boolean firstSight = !known;
		known = true;
		restored = false;
		if (rememberBank.getAsBoolean())
		{
			saveDirty = true;
			saveProfileKey = configManager.getRSProfileKey();
		}
		return firstSight;
	}

	/**
	 * Writes the staged snapshot to RSProfile-scoped config so a later session can start with
	 * it. One write per bank session: bank close, logout, plugin shutdown.
	 */
	void persist()
	{
		if (!saveDirty || saveProfileKey == null)
		{
			return;
		}
		String encoded = encode(pathfinderConfig.getBankSnapshot());
		if (encoded == null)
		{
			// Bank seen but nothing in it: drop any stale saved snapshot rather than keeping it.
			configManager.unsetConfiguration(configGroup, saveProfileKey, CONFIG_KEY_BANK_SNAPSHOT);
		}
		else
		{
			configManager.setConfiguration(configGroup, saveProfileKey, CONFIG_KEY_BANK_SNAPSHOT, encoded);
		}
		saveDirty = false;
	}

	/**
	 * Loads the previous session's snapshot for the current character, if one was saved and the
	 * bank has not been seen live. Login and plugin start.
	 */
	void restore()
	{
		if (!rememberBank.getAsBoolean() || known)
		{
			return;
		}
		Item[] items = decode(configManager.getRSProfileConfiguration(configGroup, CONFIG_KEY_BANK_SNAPSHOT));
		if (items == null)
		{
			return;
		}
		pathfinderConfig.setBankSnapshot(items);
		known = true;
		restored = true;
	}

	/** Logout: save the staged snapshot, then forget so the next character does not inherit it. */
	void forget()
	{
		persist();
		known = false;
		restored = false;
		pathfinderConfig.clearBank();
	}

	/** The setting turned on with the bank already seen live: save right away. */
	void rememberNow(boolean loggedIn)
	{
		if (known && !restored && loggedIn)
		{
			saveDirty = true;
			saveProfileKey = configManager.getRSProfileKey();
			persist();
		}
	}

	/**
	 * The setting turned off: drop the stored snapshot. True when this session's knowledge came
	 * from it (dropped as well, so the caller regenerates).
	 */
	boolean forgetStored()
	{
		configManager.unsetRSProfileConfiguration(configGroup, CONFIG_KEY_BANK_SNAPSHOT);
		saveDirty = false;
		if (!restored)
		{
			return false;
		}
		restored = false;
		known = false;
		pathfinderConfig.clearBank();
		return true;
	}

	/**
	 * Serializes bank items as {@code id:quantity} pairs joined by commas. Empty slots and
	 * placeholders (quantity 0) carry no information and are dropped. Null when there is nothing
	 * worth saving.
	 */
	static String encode(Item[] items)
	{
		if (items == null)
		{
			return null;
		}
		StringBuilder sb = new StringBuilder(items.length * 10);
		for (Item item : items)
		{
			if (item == null || item.getId() < 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(item.getId()).append(':').append(item.getQuantity());
		}
		return sb.length() > 0 ? sb.toString() : null;
	}

	/** Parses {@link #encode}'s format back into items. Null on missing or malformed data. */
	static Item[] decode(String encoded)
	{
		if (encoded == null || encoded.isEmpty())
		{
			return null;
		}
		String[] pairs = encoded.split(",");
		Item[] items = new Item[pairs.length];
		try
		{
			for (int i = 0; i < pairs.length; i++)
			{
				int sep = pairs[i].indexOf(':');
				if (sep <= 0)
				{
					return null;
				}
				items[i] = new Item(Integer.parseInt(pairs[i].substring(0, sep)),
					Integer.parseInt(pairs[i].substring(sep + 1)));
			}
		}
		catch (NumberFormatException e)
		{
			return null;
		}
		return items;
	}
}
