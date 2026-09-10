package gps;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;

/**
 * The other plugins GPS cares about (plan step L20, out of the plugin class), matched by
 * descriptor name because each hub plugin has its own classloader and class identity cannot be
 * compared across them. The original Shortest Path plugin draws paths and answers the same
 * plugin-message integrations, so running both doubles the rendering: the panel recommends
 * disabling it. Quest Helper hands quest-step destinations over that integration only when its
 * own "Use Shortest Path plugin" option is on ({@code questhelper.useShortestPath}, default
 * off): enabled with the option off, it draws its own lines and GPS never hears about the step,
 * so the panel shows a dismissable banner.
 */
final class CompanionPlugins
{
	static final String SHORTEST_PATH = "Shortest Path";
	static final String QUEST_HELPER = "Quest Helper";

	private final PluginManager pluginManager;
	private final ConfigManager configManager;
	private final Plugin self;
	private final Runnable onChanged;

	private volatile boolean shortestPathConflict;
	private volatile boolean questHelperPathingOff;

	CompanionPlugins(PluginManager pluginManager, ConfigManager configManager, Plugin self, Runnable onChanged)
	{
		this.pluginManager = pluginManager;
		this.configManager = configManager;
		this.self = self;
		this.onChanged = onChanged;
	}

	/** Whether the original Shortest Path plugin is also enabled. */
	boolean isShortestPathConflict()
	{
		return shortestPathConflict;
	}

	/** Whether Quest Helper runs WITHOUT its "Use Shortest Path plugin" option. */
	boolean isQuestHelperPathingOff()
	{
		return questHelperPathingOff;
	}

	/** Re-reads both verdicts (plugin start, a plugin toggled, the Quest Helper option changed). */
	void refresh()
	{
		boolean conflict = false;
		boolean off = false;
		for (Plugin other : pluginManager.getPlugins())
		{
			if (other == self)
			{
				continue;
			}
			PluginDescriptor descriptor = other.getClass().getAnnotation(PluginDescriptor.class);
			if (descriptor == null || !pluginManager.isPluginEnabled(other))
			{
				continue;
			}
			if (SHORTEST_PATH.equals(descriptor.name()))
			{
				conflict = true;
			}
			else if (QUEST_HELPER.equals(descriptor.name()))
			{
				off = !Boolean.parseBoolean(configManager.getConfiguration("questhelper", "useShortestPath"));
			}
		}
		boolean changed = conflict != shortestPathConflict || off != questHelperPathingOff;
		shortestPathConflict = conflict;
		questHelperPathingOff = off;
		if (changed)
		{
			onChanged.run();
		}
	}
}
