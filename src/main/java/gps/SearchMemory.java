package gps;

import java.util.ArrayList;
import java.util.List;

/**
 * What the destination search remembers (plan step L27, out of the plugin class): the recent
 * selections, most recent first, and the saved favourite positions in saved order. Both live
 * as immutable snapshots the panel reads on the Swing thread and persist on every change.
 */
final class SearchMemory
{
	private final ChoiceStore store;
	private volatile List<Destinations.Entry> history = new ArrayList<>();
	private volatile List<Destinations.Entry> favorites = new ArrayList<>();

	SearchMemory(ChoiceStore store)
	{
		this.store = store;
	}

	void load()
	{
		history = store.loadSearchHistory();
		favorites = store.loadFavorites();
	}

	/** The search box's recent selections, most recent first. */
	List<Destinations.Entry> history()
	{
		return history;
	}

	/** Records a search selection at the front of the history (deduplicated, capped). */
	void recordSelection(Destinations.Entry entry)
	{
		List<Destinations.Entry> updated = SearchHistory.push(history, entry);
		history = updated;
		store.saveSearchHistory(updated);
	}

	/** The saved favourite positions, in saved order. */
	List<Destinations.Entry> favorites()
	{
		return favorites;
	}

	/** Saves a favourite position; a favourite with the same label is replaced; the list is capped. */
	void addFavorite(String label, int packedPosition)
	{
		List<Destinations.Entry> updated = new ArrayList<>();
		for (Destinations.Entry entry : favorites)
		{
			if (!entry.name.equals(label))
			{
				updated.add(entry);
			}
		}
		if (updated.size() < ChoiceStore.FAVORITES_LIMIT)
		{
			updated.add(new Destinations.Entry("favorite", label, packedPosition));
		}
		favorites = updated;
		store.saveFavorites(updated);
	}

	/** Removes the favourite with that label and position. */
	void removeFavorite(Destinations.Entry favorite)
	{
		List<Destinations.Entry> updated = new ArrayList<>();
		for (Destinations.Entry entry : favorites)
		{
			if (!entry.name.equals(favorite.name) || entry.packedPosition != favorite.packedPosition)
			{
				updated.add(entry);
			}
		}
		favorites = updated;
		store.saveFavorites(updated);
	}
}
