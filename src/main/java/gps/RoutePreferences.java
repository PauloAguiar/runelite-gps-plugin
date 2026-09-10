package gps;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * The ranking preferences (plan step L14, out of the plugin class): per-method tiers (see
 * MethodPriority), the walk preference and the bank bias, and the effective order they produce.
 * Preferences re-rank the routes a generation found; they never touch the search. Exclusion is
 * not a tier here: it lives in the plugin's exclusion set (it affects the search) and reads back
 * as EXCLUDED, a mask over the stored tier so re-including restores the user's tuning.
 */
@Slf4j
final class RoutePreferences
{
	static final String CONFIG_KEY_PRIORITIES = "methodPriorities";
	static final String CONFIG_KEY_WALK_PREFERENCE = "walkPreferenceSeconds";
	static final String CONFIG_KEY_BANK_PREFERENCE = "bankPreferenceSeconds";

	/** One serialized priority entry (method identity + tier), for the config JSON. */
	private static final class PriorityEntry
	{
		TeleportMethod method;
		MethodPriority priority;
	}

	// Method -> ranking-bias tier. EXCLUDED and NORMAL never appear here.
	private final Map<TeleportMethod, MethodPriority> priorities = new ConcurrentHashMap<>();
	// The plugin's live exclusion set, read for the EXCLUDED mask.
	private final Set<TeleportMethod> exclusions;
	private final BooleanSupplier keepSailingFirst;
	// Suppliers: the plugin's injected services arrive after its fields initialise.
	private final Supplier<ConfigManager> configManager;
	private final Supplier<Gson> gson;
	private final String configGroup;

	private volatile int walkPreferenceSeconds;
	private volatile int bankPreferenceSeconds;

	RoutePreferences(Set<TeleportMethod> exclusions, BooleanSupplier keepSailingFirst,
		Supplier<ConfigManager> configManager, Supplier<Gson> gson, String configGroup)
	{
		this.exclusions = exclusions;
		this.keepSailingFirst = keepSailingFirst;
		this.configManager = configManager;
		this.gson = gson;
		this.configGroup = configGroup;
	}

	/** The method's tier: EXCLUDED when in the exclusion set, else its stored tier or NORMAL. */
	MethodPriority priorityOf(TeleportMethod method)
	{
		if (exclusions.contains(method))
		{
			return MethodPriority.EXCLUDED;
		}
		return priorities.getOrDefault(method, MethodPriority.NORMAL);
	}

	/** Stores a ranking tier (NORMAL removes the entry) and persists. EXCLUDED is not a stored tier. */
	void setTier(TeleportMethod method, MethodPriority tier)
	{
		if (tier == MethodPriority.EXCLUDED)
		{
			throw new IllegalArgumentException("exclusion is not a stored tier");
		}
		if (tier == MethodPriority.NORMAL)
		{
			priorities.remove(method);
		}
		else
		{
			priorities.put(method, tier);
		}
		save();
	}

	/** The walk-preference bias in seconds (negative effective ETA for the pure-walk route). */
	int walkPreferenceSeconds()
	{
		return walkPreferenceSeconds;
	}

	void setWalkPreferenceSeconds(int seconds)
	{
		configManager.get().setConfiguration(configGroup, CONFIG_KEY_WALK_PREFERENCE, seconds);
		walkPreferenceSeconds = seconds;
	}

	/** The bank-detour bias in seconds: positive prefers via-bank routes, negative avoids them. */
	int bankPreferenceSeconds()
	{
		return bankPreferenceSeconds;
	}

	void setBankPreferenceSeconds(int seconds)
	{
		configManager.get().setConfiguration(configGroup, CONFIG_KEY_BANK_PREFERENCE, seconds);
		bankPreferenceSeconds = seconds;
	}

	/**
	 * The route's ranking adjustment in seconds: the sum of its methods' tiers, less the bank
	 * bias for a via-bank route; or, for the pure-walk route, minus the walk preference (walking
	 * wins ties up to that many seconds).
	 */
	int adjustmentSeconds(RouteOption route)
	{
		if (route.getMethods().isEmpty())
		{
			return -walkPreferenceSeconds;
		}
		int seconds = 0;
		for (TeleportMethod method : route.getMethods())
		{
			seconds += priorities.getOrDefault(method, MethodPriority.NORMAL).adjustSeconds;
		}
		if (route.isViaBank())
		{
			seconds -= bankPreferenceSeconds;
		}
		return seconds;
	}

	/** Effective sort key: reached routes first, then raw cost plus the priority adjustment. */
	Comparator<RouteOption> effectiveOrder()
	{
		return Comparator
			.comparingInt((RouteOption r) -> r.isReached() ? 0 : 1)
			// At the helm, routes that STAY ON THE WATER outrank disembark-and-teleport chains
			// (capture 20260829-204334: every offer abandoned the boat at the nearest mooring
			// because the tick math favors teleports; a sailor mid-task wants the sea route
			// first, the land chains listed below). Sailing-section toggle, on by default.
			.thenComparingInt(r -> keepSailingFirst.getAsBoolean() && !r.isPureSail() ? 1 : 0)
			.thenComparingInt(r -> r.getTotalCost() + MethodPriority.unitsFromSeconds(adjustmentSeconds(r)));
	}

	/** A copy of the routes in effective order (stable). */
	List<RouteOption> sorted(List<RouteOption> routes)
	{
		List<RouteOption> sorted = new ArrayList<>(routes);
		sorted.sort(effectiveOrder());
		return sorted;
	}

	private void save()
	{
		try
		{
			List<PriorityEntry> entries = new ArrayList<>();
			for (Map.Entry<TeleportMethod, MethodPriority> e : priorities.entrySet())
			{
				PriorityEntry entry = new PriorityEntry();
				entry.method = e.getKey();
				entry.priority = e.getValue();
				entries.add(entry);
			}
			configManager.get().setConfiguration(configGroup, CONFIG_KEY_PRIORITIES, gson.get().toJson(entries));
		}
		catch (Exception e)
		{
			log.warn("Failed to save method priorities", e);
		}
	}

	/** Loads the persisted tiers and biases (plugin start). */
	void load()
	{
		ConfigManager manager = configManager.get();
		try
		{
			String json = manager.getConfiguration(configGroup, CONFIG_KEY_PRIORITIES);
			if (json != null && !json.isEmpty())
			{
				PriorityEntry[] saved = gson.get().fromJson(json, PriorityEntry[].class);
				if (saved != null)
				{
					for (PriorityEntry entry : saved)
					{
						if (entry != null && entry.method != null && entry.method.getType() != null
							&& entry.priority != null && entry.priority != MethodPriority.NORMAL
							&& entry.priority != MethodPriority.EXCLUDED)
						{
							priorities.put(entry.method, entry.priority);
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to load method priorities", e);
		}
		Integer walk = manager.getConfiguration(configGroup, CONFIG_KEY_WALK_PREFERENCE, Integer.class);
		walkPreferenceSeconds = walk != null ? walk : 0;
		Integer bank = manager.getConfiguration(configGroup, CONFIG_KEY_BANK_PREFERENCE, Integer.class);
		bankPreferenceSeconds = bank != null ? bank : 0;
	}
}
