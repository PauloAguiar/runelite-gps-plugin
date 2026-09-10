package gps;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * The persisted user choices (plan step L15, out of the plugin class): the excluded methods, the
 * routes mode, the search history and the favourite destinations, each as one config value.
 * The plugin owns the live copies; this is where they are read at startup and written on change.
 */
@Slf4j
final class ChoiceStore
{
	static final String CONFIG_KEY_EXCLUSIONS = "alternativeRoutesExclusions";
	static final String CONFIG_KEY_MODE = "alternativeRoutesMode";
	// The search box's recent selections (most recent first).
	static final String CONFIG_KEY_SEARCH_HISTORY = "searchHistory";
	static final String CONFIG_KEY_FAVORITES = "favoriteDestinations";
	static final int FAVORITES_LIMIT = 100;

	// Suppliers: the plugin's injected services arrive after its fields initialise.
	private final Supplier<ConfigManager> configManager;
	private final Supplier<Gson> gson;
	private final String configGroup;

	ChoiceStore(Supplier<ConfigManager> configManager, Supplier<Gson> gson, String configGroup)
	{
		this.configManager = configManager;
		this.gson = gson;
		this.configGroup = configGroup;
	}

	void saveExclusions(Set<TeleportMethod> exclusions)
	{
		try
		{
			configManager.get().setConfiguration(configGroup, CONFIG_KEY_EXCLUSIONS,
				gson.get().toJson(new ArrayList<>(exclusions)));
		}
		catch (Exception e)
		{
			log.warn("Failed to save alternative-route exclusions", e);
		}
	}

	/**
	 * The persisted exclusion set. Entries without a type are dropped, and so are seasonal
	 * methods: they used to be seeded here as the "disabled by default" mechanism and are gated by
	 * the "Enable seasonal transports" toggle now, so any a prior version persisted would linger
	 * in the set (and in debug captures) forever. The cleaned set is written back once.
	 */
	Set<TeleportMethod> loadExclusions()
	{
		Set<TeleportMethod> exclusions = new HashSet<>();
		try
		{
			String json = configManager.get().getConfiguration(configGroup, CONFIG_KEY_EXCLUSIONS);
			if (json == null || json.isEmpty())
			{
				return exclusions;
			}
			TeleportMethod[] saved = gson.get().fromJson(json, TeleportMethod[].class);
			if (saved == null)
			{
				return exclusions;
			}
			boolean dropped = false;
			for (TeleportMethod method : saved)
			{
				if (method == null || method.getType() == null
					|| method.getType() == gps.transport.TransportType.SEASONAL_TRANSPORTS)
				{
					dropped = true;
					continue;
				}
				exclusions.add(method);
			}
			if (dropped)
			{
				saveExclusions(exclusions);
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to load alternative-route exclusions", e);
		}
		return exclusions;
	}

	void saveRoutesMode(AlternativeRoutesMode mode)
	{
		configManager.get().setConfiguration(configGroup, CONFIG_KEY_MODE, mode.name());
	}

	/** The persisted routes mode, or null when none was saved (the plugin keeps its default). */
	AlternativeRoutesMode loadRoutesMode()
	{
		return decodeRoutesMode(configManager.get().getConfiguration(configGroup, CONFIG_KEY_MODE));
	}

	/** A mode by name, the legacy 3-mode names mapped onto the Owned/All split, else null. */
	static AlternativeRoutesMode decodeRoutesMode(String value)
	{
		if (value == null || value.isEmpty())
		{
			return null;
		}
		try
		{
			return AlternativeRoutesMode.valueOf(value);
		}
		catch (IllegalArgumentException e)
		{
			switch (value)
			{
				case "AVAILABLE":
					return AlternativeRoutesMode.OWNED_INVENTORY;
				case "AVAILABLE_WITH_BANK":
					return AlternativeRoutesMode.OWNED_WITH_BANK;
				case "ALL_TELEPORTS":
				case "ALL_UNLOCKED":
					// The unlocked-only middle mode was folded into All.
					return AlternativeRoutesMode.ALL_EVERYTHING;
				default:
					log.warn("Unknown alternative-routes mode '{}'", value);
					return null;
			}
		}
	}

	void saveSearchHistory(List<Destinations.Entry> history)
	{
		configManager.get().setConfiguration(configGroup, CONFIG_KEY_SEARCH_HISTORY, SearchHistory.serialize(history));
	}

	List<Destinations.Entry> loadSearchHistory()
	{
		return SearchHistory.deserialize(configManager.get().getConfiguration(configGroup, CONFIG_KEY_SEARCH_HISTORY));
	}

	void saveFavorites(List<Destinations.Entry> favorites)
	{
		configManager.get().setConfiguration(configGroup, CONFIG_KEY_FAVORITES, SearchHistory.serialize(favorites));
	}

	List<Destinations.Entry> loadFavorites()
	{
		return SearchHistory.deserialize(configManager.get().getConfiguration(configGroup, CONFIG_KEY_FAVORITES), FAVORITES_LIMIT);
	}
}
