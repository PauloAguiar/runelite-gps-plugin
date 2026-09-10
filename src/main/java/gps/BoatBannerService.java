package gps;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;

/**
 * The panel's boat banner (plan step L7, out of the plugin class): the owned boats as
 * {name, port label, hull} rows, read from the sailing varbits once they are seen this session
 * and restored from the character's profile before that. Routing does NOT read this;
 * PathfinderConfig reads the boat varbits itself at refresh. Berth changes arrive as varbit
 * bursts (login sync, docking), so a change only marks the banner dirty and the next game tick
 * rebuilds it at most once. Written on the client thread, read from the Swing thread.
 */
final class BoatBannerService
{
	/** RSProfile-scoped: owned boats' last seen berths, "name|port|hull" rows joined by ';'. */
	static final String CONFIG_KEY_BOAT_PORTS = "boatPorts";

	private static final int[][] BOAT_VARBITS = {
		{VarbitID.SAILING_BOAT_1_OWNED, VarbitID.SAILING_BOAT_1_PORT, VarbitID.SAILING_BOAT_1_NAME_1,
			VarbitID.SAILING_BOAT_1_NAME_2, VarbitID.SAILING_BOAT_1_NAME_3, VarbitID.SAILING_BOAT_1_TYPE},
		{VarbitID.SAILING_BOAT_2_OWNED, VarbitID.SAILING_BOAT_2_PORT, VarbitID.SAILING_BOAT_2_NAME_1,
			VarbitID.SAILING_BOAT_2_NAME_2, VarbitID.SAILING_BOAT_2_NAME_3, VarbitID.SAILING_BOAT_2_TYPE},
		{VarbitID.SAILING_BOAT_3_OWNED, VarbitID.SAILING_BOAT_3_PORT, VarbitID.SAILING_BOAT_3_NAME_1,
			VarbitID.SAILING_BOAT_3_NAME_2, VarbitID.SAILING_BOAT_3_NAME_3, VarbitID.SAILING_BOAT_3_TYPE},
		{VarbitID.SAILING_BOAT_4_OWNED, VarbitID.SAILING_BOAT_4_PORT, VarbitID.SAILING_BOAT_4_NAME_1,
			VarbitID.SAILING_BOAT_4_NAME_2, VarbitID.SAILING_BOAT_4_NAME_3, VarbitID.SAILING_BOAT_4_TYPE},
		{VarbitID.SAILING_BOAT_5_OWNED, VarbitID.SAILING_BOAT_5_PORT, VarbitID.SAILING_BOAT_5_NAME_1,
			VarbitID.SAILING_BOAT_5_NAME_2, VarbitID.SAILING_BOAT_5_NAME_3, VarbitID.SAILING_BOAT_5_TYPE},
	};
	private static final Set<Integer> BOAT_VARBIT_IDS = Arrays.stream(BOAT_VARBITS)
		.flatMapToInt(Arrays::stream).boxed().collect(Collectors.toSet());

	private final Client client;
	private final ConfigManager configManager;
	private final String configGroup;
	/** Told after every rebuild or restore, so the panel's sailing section can relabel itself. */
	private final Runnable onChanged;

	/** The rows; null when never collected for this character. */
	private volatile List<String[]> banner;
	private volatile boolean live;
	private volatile boolean dirty;

	BoatBannerService(Client client, ConfigManager configManager, String configGroup, Runnable onChanged)
	{
		this.client = client;
		this.configManager = configManager;
		this.configGroup = configGroup;
		this.onChanged = onChanged;
	}

	/** Whether a varbit change concerns a boat's ownership, berth, name or hull. */
	boolean tracks(int varbitId)
	{
		return BOAT_VARBIT_IDS.contains(varbitId);
	}

	/** A tracked varbit changed: rebuild on the next tick (one rebuild per burst). */
	void markDirty()
	{
		dirty = true;
	}

	/** Client thread, once per game tick. */
	void onTick()
	{
		if (dirty)
		{
			dirty = false;
			refresh();
		}
	}

	/** Logged out: the next character starts from its own snapshot. */
	void reset()
	{
		banner = null;
		live = false;
		dirty = false;
	}

	/** Owned boats as {name, port label, hull} rows; null when never collected for this character. */
	List<String[]> banner()
	{
		return banner;
	}

	/** Whether the banner reflects this session's live varbits rather than a restored snapshot. */
	boolean isLive()
	{
		return live;
	}

	/** Client thread: re-read every boat's ownership, berth, name and hull, persist, and notify. */
	void refresh()
	{
		if (!GameState.LOGGED_IN.equals(client.getGameState()))
		{
			return;
		}
		List<String[]> rows = new ArrayList<>();
		for (int slot = 0; slot < BOAT_VARBITS.length; slot++)
		{
			int[] varbits = BOAT_VARBITS[slot];
			// Owned varbit alone is unreliable (Where's My Boat's field lesson); a set name
			// descriptor also proves ownership, and covers Port Sarim's port id 0.
			if (client.getVarbitValue(varbits[0]) <= 0 && client.getVarbitValue(varbits[3]) <= 0)
			{
				continue;
			}
			BoatHull hull = BoatHull.fromVarbit(client.getVarbitValue(varbits[5]));
			rows.add(new String[]{decodeBoatName(slot, varbits),
				SailingPorts.portName(client.getVarbitValue(varbits[1])),
				hull == null ? "" : hull.displayName()});
		}
		banner = rows;
		live = true;
		configManager.setRSProfileConfiguration(configGroup, CONFIG_KEY_BOAT_PORTS, encode(rows));
		changed();
	}

	/** Restores the persisted snapshot when nothing has been collected yet this session. */
	void restore()
	{
		if (banner != null)
		{
			return;
		}
		String raw = configManager.getRSProfileConfiguration(configGroup, CONFIG_KEY_BOAT_PORTS);
		if (raw != null)
		{
			banner = decode(raw);
			changed();
		}
	}

	private void changed()
	{
		if (onChanged != null)
		{
			onChanged.run();
		}
	}

	/** "name|port|hull" rows joined by ';'. */
	static String encode(List<String[]> rows)
	{
		return rows.stream().map(r -> r[0] + "|" + r[1] + "|" + r[2]).collect(Collectors.joining(";"));
	}

	/** The inverse of {@link #encode}; old snapshots lack the hull, and rows without a name are dropped. */
	static List<String[]> decode(String raw)
	{
		List<String[]> rows = new ArrayList<>();
		for (String row : raw.split(";"))
		{
			String[] parts = row.split("\\|", 3);
			if (parts.length >= 2 && !parts[0].isEmpty())
			{
				rows.add(new String[]{parts[0], parts[1], parts.length > 2 ? parts[2] : ""});
			}
		}
		return rows;
	}

	/**
	 * The three name varbits index the game's own name-part tables (prefix, descriptor, noun),
	 * the same decode Where's My Boat ships. Any surprise falls back to a slot label.
	 */
	private String decodeBoatName(int slot, int[] varbits)
	{
		try
		{
			int[] rowIds = {DBTableID.SailingBoatNameOptions.Row.SAILING_BOAT_NAME_PREFIX_OPTIONS,
				DBTableID.SailingBoatNameOptions.Row.SAILING_BOAT_NAME_DESCRIPTOR_OPTIONS,
				DBTableID.SailingBoatNameOptions.Row.SAILING_BOAT_NAME_NOUN_OPTIONS};
			List<String> parts = new ArrayList<>();
			for (int part = 0; part < 3; part++)
			{
				int index = client.getVarbitValue(varbits[2 + part]) - 1;
				if (index > 0)
				{
					Object[] options = client.getDBTableField(rowIds[part],
						DBTableID.SailingBoatNameOptions.COL_OPTION, 0);
					if (index < options.length && options[index] instanceof String
						&& !((String) options[index]).isEmpty())
					{
						parts.add((String) options[index]);
					}
				}
			}
			if (!parts.isEmpty())
			{
				return String.join(" ", parts);
			}
		}
		catch (RuntimeException e)
		{
			// Name tables unavailable (cache quirk): the slot label below still identifies it.
		}
		return "Boat " + (slot + 1);
	}
}
