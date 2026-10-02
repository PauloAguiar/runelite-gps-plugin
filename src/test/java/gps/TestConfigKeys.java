package gps;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import net.runelite.client.config.ConfigItem;

/**
 * What the running plugin learns from the ConfigManager's descriptor at start, for tests that have
 * no ConfigManager: every @ConfigItem of ShortestPathConfig by key. Reflection is fine here;
 * shipped code may not use it (the hub rejects it), which is why the plugin reads the descriptor.
 */
public final class TestConfigKeys
{
	private TestConfigKeys()
	{
	}

	/** The item behind a key, or null. */
	public static ConfigItem item(String key)
	{
		for (Method method : ShortestPathConfig.class.getMethods())
		{
			ConfigItem item = method.getAnnotation(ConfigItem.class);
			if (item != null && item.keyName().equals(key))
			{
				return item;
			}
		}
		return null;
	}

	/** Declares every item key to ConfigOverrides, as the plugin does at start. */
	public static void declare()
	{
		Set<String> keys = new HashSet<>();
		for (Method method : ShortestPathConfig.class.getMethods())
		{
			ConfigItem item = method.getAnnotation(ConfigItem.class);
			if (item != null)
			{
				keys.add(item.keyName());
			}
		}
		ConfigOverrides.declareKeys(keys);
	}
}
