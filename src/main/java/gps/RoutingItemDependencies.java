package gps;

import gps.pathfinder.PathfinderConfig;
import gps.transport.Transport;
import gps.transport.requirement.ItemRequirement;
import gps.transport.requirement.TransportItems;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;

/**
 * Precomputed item-dependency index: which item IDs, at which quantity thresholds, can change
 * any transport's availability. Built once from the loaded transports; the plugin fingerprints
 * the routing-relevant slice of the inventory and equipment on every container change and only
 * marks the teleport catalog dirty when that slice actually changed - most inventory traffic
 * (logs, ore, food, loot) touches nothing any transport cares about (issues #23/#24).
 */
public final class RoutingItemDependencies
{
	/** Item id -> sorted distinct quantities at which some requirement flips. */
	private final Map<Integer, int[]> thresholds;

	private RoutingItemDependencies(Map<Integer, int[]> thresholds)
	{
		this.thresholds = thresholds;
	}

	public static RoutingItemDependencies build(Transport[] transports)
	{
		Map<Integer, TreeSet<Integer>> collected = new HashMap<>();
		for (Transport transport : transports)
		{
			TransportItems items = transport.getItemRequirements();
			if (items == null)
			{
				continue;
			}
			for (ItemRequirement req : items.getRequirements())
			{
				// Threshold 1 is always included alongside the required quantity, so checks
				// with presence semantics (staff substitution) are covered regardless of the
				// possession logic's internals; extra thresholds only cost a rare false dirty.
				collect(collected, req.getItemIds(), req.getQuantity());
				collect(collected, req.getStaffIds(), 1);
				collect(collected, req.getOffhandIds(), 1);
			}
		}
		// Implicit requirements that appear in no transport row: fairy rings demand a wielded
		// or carried Dramen/Lunar staff (PathfinderConfig's DRAMEN_STAFF special case), and a
		// rune pouch in the inventory unlocks its varbit-tracked runes for spell requirements.
		collect(collected, ItemVariations.DRAMEN_STAFF.getIds(), 1);
		collect(collected, ItemVariations.staves(ItemVariations.DRAMEN_STAFF), 1);
		collect(collected, ItemVariations.offhands(ItemVariations.DRAMEN_STAFF), 1);
		for (int pouch : PathfinderConfig.RUNE_POUCHES)
		{
			collect(collected, new int[]{pouch}, 1);
		}

		Map<Integer, int[]> thresholds = new HashMap<>(collected.size() * 2);
		for (Map.Entry<Integer, TreeSet<Integer>> entry : collected.entrySet())
		{
			int[] sorted = new int[entry.getValue().size()];
			int i = 0;
			for (int quantity : entry.getValue())
			{
				sorted[i++] = quantity;
			}
			thresholds.put(entry.getKey(), sorted);
		}
		return new RoutingItemDependencies(thresholds);
	}

	private static void collect(Map<Integer, TreeSet<Integer>> collected, int[] ids, int quantity)
	{
		if (ids == null)
		{
			return;
		}
		for (int id : ids)
		{
			TreeSet<Integer> set = collected.computeIfAbsent(id, k -> new TreeSet<>());
			set.add(1);
			if (quantity > 1)
			{
				set.add(quantity);
			}
		}
	}

	/** Whether this item id can influence any transport's availability at all. */
	public boolean isRelevant(int itemId)
	{
		return thresholds.containsKey(itemId);
	}

	public int size()
	{
		return thresholds.size();
	}

	/**
	 * Order-independent fingerprint of the routing-relevant item state across the inventory and
	 * equipment. Two states with the same fingerprint satisfy exactly the same requirement
	 * thresholds, so a transport availability pass would classify every method identically -
	 * equal fingerprints mean the catalog refresh can be skipped. The container matters (a
	 * wielded staff counts differently from a carried one), so each is folded in separately.
	 */
	public long fingerprint(ItemContainer inventory, ItemContainer equipment)
	{
		long acc = 0;
		acc += containerDigest(inventory, 1);
		acc += containerDigest(equipment, 2);
		return acc;
	}

	private long containerDigest(ItemContainer container, int tag)
	{
		if (container == null)
		{
			return 0;
		}
		// Duplicate un-stacked items (two dramen staffs) aggregate per id before thresholding.
		Map<Integer, Integer> quantities = null;
		for (Item item : container.getItems())
		{
			if (item == null || !thresholds.containsKey(item.getId()))
			{
				continue;
			}
			if (quantities == null)
			{
				quantities = new HashMap<>();
			}
			quantities.merge(item.getId(), item.getQuantity(), Integer::sum);
		}
		if (quantities == null)
		{
			return 0;
		}
		long digest = 0;
		for (Map.Entry<Integer, Integer> entry : quantities.entrySet())
		{
			int[] sorted = thresholds.get(entry.getKey());
			int met = metCount(sorted, entry.getValue());
			if (met > 0)
			{
				// Commutative sum of well-mixed terms keeps the digest independent of slot order.
				digest += mix64(((long) tag << 40) ^ ((long) entry.getKey() << 8) ^ met);
			}
		}
		return digest;
	}

	private static int metCount(int[] sorted, int quantity)
	{
		int idx = Arrays.binarySearch(sorted, quantity);
		return idx >= 0 ? idx + 1 : -idx - 1;
	}

	/** splitmix64 finalizer; also the mixer behind the refresh's usable-transport fingerprint. */
	public static long mix64(long z)
	{
		z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
		z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
		return z ^ (z >>> 31);
	}
}
