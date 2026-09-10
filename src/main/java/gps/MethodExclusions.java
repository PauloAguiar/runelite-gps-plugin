package gps;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The methods the user has excluded from the search (plan step L26, out of the plugin class).
 * Exclusions apply on the next generation, never at once: a change persists and refreshes the
 * panel (catalog icons and counts), and the route list reads as stale until the user refreshes.
 * The live set is shared by reference with the ranking preferences (the EXCLUDED mask) and the
 * generation.
 */
final class MethodExclusions
{
	private final Set<TeleportMethod> live = ConcurrentHashMap.newKeySet();
	// The exclusions the current route list was generated with; diverging from the live set
	// means the list is stale until the user refreshes.
	private volatile Set<TeleportMethod> generatedWith = Set.of();
	private final ChoiceStore store;
	private final Runnable onChanged;

	MethodExclusions(ChoiceStore store, Runnable onChanged)
	{
		this.store = store;
		this.onChanged = onChanged;
	}

	/** The live set itself (concurrent), for the readers that must see every change. */
	Set<TeleportMethod> live()
	{
		return live;
	}

	/** A snapshot copy, for callers that iterate off the client thread. */
	Set<TeleportMethod> copy()
	{
		return new HashSet<>(live);
	}

	boolean contains(TeleportMethod method)
	{
		return live.contains(method);
	}

	boolean isEmpty()
	{
		return live.isEmpty();
	}

	void load()
	{
		live.addAll(store.loadExclusions());
	}

	void exclude(TeleportMethod method)
	{
		if (method != null && live.add(method))
		{
			changed();
		}
	}

	void include(TeleportMethod method)
	{
		if (method != null && live.remove(method))
		{
			changed();
		}
	}

	void excludeAll(Collection<TeleportMethod> methods)
	{
		boolean changed = false;
		if (methods != null)
		{
			for (TeleportMethod method : methods)
			{
				changed |= live.add(method);
			}
		}
		if (changed)
		{
			changed();
		}
	}

	void includeAll(Collection<TeleportMethod> methods)
	{
		boolean changed = false;
		if (methods != null)
		{
			for (TeleportMethod method : methods)
			{
				changed |= live.remove(method);
			}
		}
		if (changed)
		{
			changed();
		}
	}

	void clear()
	{
		if (!live.isEmpty())
		{
			live.clear();
			changed();
		}
	}

	/** A generation started with the current set: the list is fresh until the set changes. */
	void markGenerated()
	{
		generatedWith = copy();
	}

	/** Whether the set changed since the current route list was generated. */
	boolean isStale()
	{
		return !live.equals(generatedWith);
	}

	private void changed()
	{
		store.saveExclusions(live);
		onChanged.run();
	}
}
