package gps.pathfinder;

import java.util.ArrayList;

/**
 * A monotone priority queue of {@code int} node ids keyed by small non-negative integer costs —
 * Dial's algorithm: one FIFO bucket per cost value, popped in cost order by a forward-only cursor.
 * Push and pop are O(1), which is what lets the uninformed (no-heuristic) searches keep their old
 * FIFO-era speed after moving to cost-ordered settling: a binary heap pays log n per operation on
 * exactly the searches that explore the most nodes (map-wide "nearest X" floods).
 * <p>
 * Correct for Dijkstra because pops are monotone: every inserted key is {@code >=} the key being
 * expanded (non-negative edges), so the cursor never needs to move backwards. FIFO order within a
 * bucket preserves the creation-order tie-break the searches always had. Single-threaded (worker
 * only), like the heap it complements.
 */
class IntBucketQueue
{
	private final ArrayList<IntDeque> buckets = new ArrayList<>();
	// The lowest bucket that can still hold anything; only ever moves forward (monotone inserts).
	private int cursor;
	private int size;

	int size()
	{
		return size;
	}

	boolean isEmpty()
	{
		return size == 0;
	}

	void add(int id, int key)
	{
		if (key < cursor)
		{
			// Monotonicity safety net: never true with non-negative edges, but a backwards insert
			// must not be silently skipped by the cursor.
			cursor = key;
		}
		while (buckets.size() <= key)
		{
			buckets.add(null);
		}
		IntDeque bucket = buckets.get(key);
		if (bucket == null)
		{
			bucket = new IntDeque(16);
			buckets.set(key, bucket);
		}
		bucket.addLast(id);
		size++;
	}

	/**
	 * @return the minimum-key element, or {@link NodeGraph#NO_NODE} if empty.
	 */
	int poll()
	{
		if (size == 0)
		{
			return NodeGraph.NO_NODE;
		}
		while (cursor < buckets.size())
		{
			IntDeque bucket = buckets.get(cursor);
			if (bucket != null && !bucket.isEmpty())
			{
				size--;
				return bucket.pollFirst();
			}
			cursor++;
		}
		size = 0;
		return NodeGraph.NO_NODE;
	}

	void clear()
	{
		buckets.clear();
		cursor = 0;
		size = 0;
	}
}
