package gps;

import gps.transport.TransportType;
import java.awt.Color;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigItem;

/**
 * Config values another plugin overrides through the path plugin message (plan step L21, out
 * of the plugin class): every config read that a message may override goes through
 * {@code override(key, configValue)}, which answers the override while one is set and the
 * config value otherwise. Also the one place that knows which keys change what the routing
 * engine computes. Static, like the reads that consult it from the engine.
 */
@Slf4j
public final class ConfigOverrides
{
	// Every config key the routing engine reads (PathfinderConfig.refresh / TransportTypeConfig):
	// a change to one of these regenerates the routes. RouteAffectingKeysTest scans the engine's
	// sources and fails when a key it reads is missing here; the pohMount*/sailing* toggles were
	// silently inert while this list was maintained by hand.
	private static final Pattern ROUTE_AFFECTING = Pattern.compile("^(avoidWilderness|includeBankPath|currencyThreshold|calculationCutoff|pohJewelleryBoxTier|pohMount\\w+|sailingAssumeSummon|sailingTeleportAbandon|balloonSmartMode|balloonStored\\w+|spiritTreeSmartMode|use\\w+|cost\\w+)$");

	private static final Map<String, Object> OVERRIDES = new HashMap<>(50);
	private static volatile Set<String> knownKeysCache;

	private ConfigOverrides()
	{
	}

	/** Every @ConfigItem key ShortestPathConfig declares: the only keys a message may override. */
	static Set<String> knownKeys()
	{
		Set<String> keys = knownKeysCache;
		if (keys == null)
		{
			keys = new HashSet<>();
			for (Method method : ShortestPathConfig.class.getMethods())
			{
				ConfigItem item = method.getAnnotation(ConfigItem.class);
				if (item != null)
				{
					keys.add(item.keyName());
				}
			}
			knownKeysCache = Collections.unmodifiableSet(keys);
		}
		return keys;
	}

	/** Whether a change to this config key changes what the routing engine computes. */
	public static boolean affectsRouting(String key)
	{
		return key != null && ROUTE_AFFECTING.matcher(key).find();
	}

	/**
	 * Replaces the override set with a message's. An unknown key would sit in the map forever
	 * and never be diagnosable from either side, so it is rejected loudly instead.
	 */
	static void apply(Map<String, Object> overrides)
	{
		OVERRIDES.clear();
		for (Map.Entry<String, Object> entry : overrides.entrySet())
		{
			if (!knownKeys().contains(entry.getKey()))
			{
				log.warn("Plugin message config override ignored: unknown key '{}'", entry.getKey());
				continue;
			}
			OVERRIDES.put(entry.getKey(), entry.getValue());
		}
	}

	static void clear()
	{
		OVERRIDES.clear();
	}

	public static boolean override(String key, boolean defaultValue)
	{
		Object value = OVERRIDES.isEmpty() ? null : OVERRIDES.get(key);
		return value instanceof Boolean ? (boolean) value : defaultValue;
	}

	/** Override for a transport type's enabled state, by the config key the enum names. */
	public static boolean override(TransportType type, boolean defaultValue)
	{
		String key = type.getEnabledKey();
		return key != null ? override(key, defaultValue) : defaultValue;
	}

	/** Override for a transport type's cost threshold, by the config key the enum names. */
	public static int override(TransportType type, int defaultValue)
	{
		String key = type.getCostKey();
		return key != null ? override(key, defaultValue) : defaultValue;
	}

	public static int override(String key, int defaultValue)
	{
		Object value = OVERRIDES.isEmpty() ? null : OVERRIDES.get(key);
		return value instanceof Integer ? (int) value : defaultValue;
	}

	public static TeleportationItem override(String key, TeleportationItem defaultValue)
	{
		Object value = OVERRIDES.isEmpty() ? null : OVERRIDES.get(key);
		if (value instanceof String)
		{
			TeleportationItem item = TeleportationItem.fromType((String) value);
			if (item != null)
			{
				return item;
			}
		}
		return defaultValue;
	}

	public static JewelleryBoxTier override(String key, JewelleryBoxTier defaultValue)
	{
		Object value = OVERRIDES.isEmpty() ? null : OVERRIDES.get(key);
		if (value instanceof String)
		{
			JewelleryBoxTier tier = JewelleryBoxTier.fromType((String) value);
			if (tier != null)
			{
				return tier;
			}
		}
		return defaultValue;
	}

	public static Color override(String key, Color defaultValue)
	{
		Object value = OVERRIDES.isEmpty() ? null : OVERRIDES.get(key);
		return value instanceof Color ? (Color) value : defaultValue;
	}
}
