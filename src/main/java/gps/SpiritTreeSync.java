package gps;

import gps.pathfinder.PathfinderConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;

/**
 * The planted spirit trees (plan step L17, out of the plugin class): read once per session from
 * the spirit-tree travel menu when it opens, persisted per character so the next session starts
 * synced, and restored from that snapshot until the live menu is seen. The pathfinder config
 * holds the set (null = never synced: farmable trees are then treated conservatively).
 */
final class SpiritTreeSync
{
	/** RSProfile-scoped: the planted spirit trees detected from the travel menu, comma-separated. */
	static final String CONFIG_KEY_SPIRIT_TREES = "plantedSpiritTrees";

	private static final Pattern LABEL_MENU = Pattern.compile("<col=735a28>(.+)</col>: (<col=5f5f5f>)?(.+)");
	private static final Pattern LABEL_MENU_NEW = Pattern.compile("<col=ffffff>(.+)</col>: (<col=5f5f5f>)?(.+)");
	// Tree Gnome Village is always the first row and always available; a cheap length check
	// before the regex. "<col=735a28>1</col>: Tree Gnome Village" and its new-menu twin are 39.
	private static final int FIRST_ROW_LENGTH = 39;

	private final Client client;
	private final ClientThread clientThread;
	private final ConfigManager configManager;
	private final String configGroup;
	private final PathfinderConfig pathfinderConfig;
	private final Runnable onSynced;

	// Whether the travel menu has been parsed THIS session. Distinct from the set being non-null:
	// a restored snapshot fills the set but must not block the fresher live read when the menu
	// opens (a newly planted tree only shows up in the menu).
	private boolean parsedLive;

	SpiritTreeSync(Client client, ClientThread clientThread, ConfigManager configManager, String configGroup,
		PathfinderConfig pathfinderConfig, Runnable onSynced)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.configManager = configManager;
		this.configGroup = configGroup;
		this.pathfinderConfig = pathfinderConfig;
		this.onSynced = onSynced;
	}

	/** Whether the travel menu has been seen this session or restored, so the planted set is known. */
	boolean isSynced()
	{
		return pathfinderConfig.availableSpiritTrees != null;
	}

	/** Whether the set came from the live menu this session rather than the snapshot. */
	boolean isParsedLive()
	{
		return parsedLive;
	}

	/** The farmable trees detected as planted-and-grown (menu order), or empty when not synced. */
	List<String> planted()
	{
		Set<String> available = pathfinderConfig.availableSpiritTrees;
		if (available == null)
		{
			return List.of();
		}
		List<String> planted = new ArrayList<>();
		for (String name : PathfinderConfig.FARMABLE_SPIRIT_TREES)
		{
			if (available.contains(name))
			{
				planted.add(name);
			}
		}
		return planted;
	}

	/** A widget opened: the travel menu (either interface) is parsed once per session. */
	void widgetLoaded(int groupId)
	{
		if (parsedLive)
		{
			return;
		}
		switch (groupId)
		{
			case InterfaceID.MENU:
				clientThread.invokeLater(() -> parseMenu(false));
				break;
			case InterfaceID.MENU_NEW:
				clientThread.invokeLater(() -> parseMenu(true));
				break;
			default:
				break;
		}
	}

	/** Restores the persisted set when none is known yet (login, plugin start). */
	void restore()
	{
		if (pathfinderConfig.availableSpiritTrees != null)
		{
			return;
		}
		String raw = configManager.getRSProfileConfiguration(configGroup, CONFIG_KEY_SPIRIT_TREES);
		if (raw != null)
		{
			pathfinderConfig.availableSpiritTrees = raw.isEmpty()
				? new HashSet<>() : new HashSet<>(Arrays.asList(raw.split(",")));
		}
	}

	/** Logged out: the next character starts from its own snapshot. */
	void reset()
	{
		pathfinderConfig.availableSpiritTrees = null;
		parsedLive = false;
	}

	private void parseMenu(boolean newMenu)
	{
		// Referencing
		// https://github.com/trs/runelite-teleport-maps/blob/e006270494500ab8e4826903b377bb945ca9fc96/src/main/java/com/mjhylkema/TeleportMaps/components/adventureLog/SpiritTreeMap.java#L141
		Widget container = newMenu ? client.getWidget(InterfaceID.MENU_NEW, 9) : client.getWidget(InterfaceID.MENU, 3);
		if (container == null)
		{
			return;
		}
		Widget[] children = container.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			return;
		}
		String[] rows = new String[children.length];
		for (int i = 0; i < children.length; i++)
		{
			rows[i] = children[i].getText();
		}
		Set<String> available = parseLabels(rows, newMenu);
		if (available == null)
		{
			return;
		}
		pathfinderConfig.availableSpiritTrees = available;
		parsedLive = true;
		// Persist per character, so next session starts synced instead of asking for a travel-menu
		// visit again. (Comma-safe: no spirit tree location name contains a comma.)
		configManager.setRSProfileConfiguration(configGroup, CONFIG_KEY_SPIRIT_TREES, String.join(",", available));
		onSynced.run();
	}

	/**
	 * The usable trees named in the menu rows, or null when the rows are not the spirit-tree
	 * travel menu. A greyed row (group 2, the disabled colour tag) is a tree the player cannot use.
	 */
	static Set<String> parseLabels(String[] rows, boolean newMenu)
	{
		if (rows == null || rows.length == 0 || rows[0] == null || rows[0].length() != FIRST_ROW_LENGTH)
		{
			return null;
		}
		Pattern pattern = newMenu ? LABEL_MENU_NEW : LABEL_MENU;
		Set<String> available = new HashSet<>();
		for (String row : rows)
		{
			if (row == null)
			{
				continue;
			}
			Matcher matcher = pattern.matcher(row);
			if (!matcher.matches() || matcher.group(2) != null)
			{
				continue;
			}
			available.add(matcher.group(3));
		}
		return available;
	}
}
