package gps.pathfinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import gps.WorldPointUtil;

/**
 * Structure-of-Arrays store for pathfinding nodes.
 * <p>
 * The previous design allocated one {@link Object} per explored tile (a {@code Node} or
 * {@code TransportNode}). Heavy searches explore hundreds of thousands of tiles, so this produced
 * hundreds of thousands of live objects, each carrying a ~16-byte header plus several object
 * references (issue #491). Here every node is instead an {@code int} index into parallel primitive
 * arrays, so a whole search holds only a handful of arrays regardless of how many nodes it visits.
 * <p>
 * The fields packed per node are: packed world position, the index of the previous node
 * ({@link #NO_NODE} for the start), accumulated cost, a set of boolean flags, and the
 * {@link AbstractNodeKind} ordinal for abstract nodes. The priority-queue ordering key is
 * EXACTLY the accumulated cost plus (in A* mode) a consistent heuristic — never anything else:
 * any ordering-only term breaks the first-settle-is-optimal invariant the searches rely on.
 * <p>
 * <strong>Threading.</strong> The search runs on a single worker thread, but the render thread
 * reads the partial path while the search is still running (progressive rendering via
 * {@code Pathfinder.getPath()}). Node data is write-once and is published to the render thread by
 * the single volatile {@code Pathfinder.bestLastNode} handoff rather than by marking these arrays
 * {@code volatile} (which would cripple the hot loop, see the field comment). The chain walks
 * ({@link #getPathSteps} / {@link #getClosestTilePosition}) snapshot the page tables into locals
 * and tolerate an index that is out of bounds or released. This means a walk concurrent with a
 * grow/release can never throw; at worst it yields a one-frame-stale path.
 * <p>
 * <strong>Paging.</strong> Storage is paged (fixed {@link #PAGE_SIZE} nodes per page): growing
 * allocates one more page per array and never copies the nodes already written. The flat
 * design grew by 50% with Arrays.copyOf, which for a two-million-node search copied ~36 MB
 * a dozen times and then released it all - hundreds of megabytes of garbage per generation
 * (plan step N2; SearchAllocationTest measures bytes per settled node).
 */
public class NodeGraph
{
	public static final int NO_NODE = -1;

	private static final byte FLAG_BANK_VISITED = 1;       // bit0
	private static final byte FLAG_ABSTRACT = 1 << 1;      // bit1
	private static final byte FLAG_TRANSPORT = 1 << 3;     // bit3

	// Enum.values() copies on every call, so cache it for the abstractKind lookup.
	private static final AbstractNodeKind[] ABSTRACT_KINDS = AbstractNodeKind.values();

	/** Nodes per page: 16K keeps a small search at one ~300 KB page set. */
	static final int PAGE_SHIFT = 14;
	static final int PAGE_SIZE = 1 << PAGE_SHIFT;
	private static final int PAGE_MASK = PAGE_SIZE - 1;

	// The page tables are NOT volatile: making them volatile forces every accessor (packedPosition,
	// cost, isTile, orderCost, the append() writes, ...) to re-read the reference on each call and
	// blocks the JIT from caching the base in a register. The hot loop touches these hundreds of
	// times per node, so volatile reads roughly halved field throughput (~1.6x slower searches).
	// Safe publication to the render thread is provided instead by the single volatile
	// Pathfinder.bestLastNode handoff: the worker writes the node data, then volatile-writes
	// bestLastNode; the render thread volatile-reads bestLastNode before walking, which establishes
	// happens-before for all the plain writes above it. The walk methods snapshot the page tables
	// into locals and tolerate a null (post-release) or stale-but-valid (mid-grow: a grown table is
	// a copy that keeps every existing page) table, so a concurrent grow/release never throws.
	private int[][] packedPosition;
	private int[][] previous;
	private int[][] cost;
	// The priority-queue ordering key, precomputed at append time: the accumulated cost plus -
	// when a heuristic is attached (A* mode) - the node's heuristic value. With no heuristic it
	// equals the cost exactly, so Dijkstra-mode ordering is pure g.
	private int[][] orderCost;
	private byte[][] flags;
	private byte[][] abstractKind;
	private int size;
	private int capacity;
	// Optional A* heuristic; null = uninformed (Dijkstra) ordering.
	private final SearchHeuristic heuristic;

	public NodeGraph(int initialCapacity)
	{
		this(initialCapacity, null);
	}

	public NodeGraph(int initialCapacity, SearchHeuristic heuristic)
	{
		final int pages = Math.max(1, (Math.max(1, initialCapacity) + PAGE_MASK) >>> PAGE_SHIFT);
		packedPosition = new int[pages][];
		previous = new int[pages][];
		cost = new int[pages][];
		orderCost = new int[pages][];
		flags = new byte[pages][];
		abstractKind = new byte[pages][];
		for (int page = 0; page < pages; page++)
		{
			allocatePage(page);
		}
		capacity = pages * PAGE_SIZE;
		this.heuristic = heuristic;
	}

	private void allocatePage(int page)
	{
		packedPosition[page] = new int[PAGE_SIZE];
		previous[page] = new int[PAGE_SIZE];
		cost[page] = new int[PAGE_SIZE];
		orderCost[page] = new int[PAGE_SIZE];
		flags[page] = new byte[PAGE_SIZE];
		abstractKind[page] = new byte[PAGE_SIZE];
	}

	public int size()
	{
		return size;
	}

	private void ensureCapacity()
	{
		if (size < capacity)
		{
			return;
		}
		// One more page per array. The page tables are small (one reference per 16K nodes), so
		// growing them by copy is cheap and keeps every existing page for a concurrent reader.
		final int pages = packedPosition.length;
		final int newPages = pages + Math.max(1, pages >> 1);
		packedPosition = Arrays.copyOf(packedPosition, newPages);
		previous = Arrays.copyOf(previous, newPages);
		cost = Arrays.copyOf(cost, newPages);
		orderCost = Arrays.copyOf(orderCost, newPages);
		flags = Arrays.copyOf(flags, newPages);
		abstractKind = Arrays.copyOf(abstractKind, newPages);
		allocatePage(pages);
		capacity = (pages + 1) * PAGE_SIZE;
	}

	private int append(int packed, int prev, int nodeCost, byte flagBits, byte kind)
	{
		ensureCapacity();
		final int id = size;
		final int page = id >>> PAGE_SHIFT;
		final int slot = id & PAGE_MASK;
		if (packedPosition[page] == null)
		{
			allocatePage(page);
			capacity = Math.max(capacity, (page + 1) * PAGE_SIZE);
		}
		packedPosition[page][slot] = packed;
		previous[page][slot] = prev;
		cost[page][slot] = nodeCost;
		orderCost[page][slot] = nodeCost + (heuristic == null ? 0 : heuristic.of(packed));
		abstractKind[page][slot] = kind;
		flags[page][slot] = flagBits;
		size = id + 1;
		return id;
	}

	private int costOf(int id)
	{
		return id == NO_NODE ? 0 : cost[id >>> PAGE_SHIFT][id & PAGE_MASK];
	}

	/**
	 * The search root. Carries no previous node and zero cost.
	 */
	public int createStart(int packedPosition)
	{
		return append(packedPosition, NO_NODE, 0, (byte) 0, (byte) 0);
	}

	/**
	 * A concrete walkable tile. Travel cost is the walking distance from the previous node, but
	 * only when the previous node is itself a tile (mirrors the old {@code Node.cost}); reaching a
	 * tile from an abstract node adds no travel cost. {@code extraCost} is a one-off surcharge on this
	 * edge (used to charge the bank-pickup penalty on the step that first enters the banked state).
	 * The edge is clamped at free: configured weights may be negative (favoring), but a negative-cost
	 * edge would break the search's Dijkstra invariant.
	 */
	public int createTile(int packedPosition, int previous, boolean bankVisited, int extraCost)
	{
		final int travelTime = (previous != NO_NODE && isTile(previous))
			? WorldPointUtil.distanceBetween(packedPosition(previous), packedPosition)
			: 0;
		final byte flagBits = bankVisited ? FLAG_BANK_VISITED : 0;
		return append(packedPosition, previous, costOf(previous) + Math.max(0, travelTime + extraCost),
			flagBits, (byte) 0);
	}

	/**
	 * A transport destination tile. Cost is the previous cost plus the transport's travel time (in
	 * {@link CostUnits}) and any additional configured cost; there is no walking-distance term
	 * (mirrors the old {@code TransportNode}). The edge is clamped at free: a negative configured
	 * weight can make a transport free but never cheaper than free (Dijkstra needs non-negative edges).
	 */
	public int createTransport(int packedPosition, int previous, int travelTime, int additionalCost,
		boolean bankVisited)
	{
		byte flagBits = FLAG_TRANSPORT;
		if (bankVisited)
		{
			flagBits |= FLAG_BANK_VISITED;
		}
		return append(packedPosition, previous, costOf(previous) + Math.max(0, travelTime + additionalCost),
			flagBits, (byte) 0);
	}

	/**
	 * An abstract search-state node (global teleports). Has no world position and inherits the
	 * previous node's cost (mirrors the old {@code Node.abstractNode}). {@code extraCost} is a one-off
	 * surcharge (the bank-pickup penalty when this abstract state is first entered via a bank tile), so
	 * global teleports expanded from it inherit the penalty. Clamped at free like every other edge.
	 */
	public int createAbstract(AbstractNodeKind abstractKind, int previous, boolean bankVisited, int extraCost)
	{
		byte flagBits = FLAG_ABSTRACT;
		if (bankVisited)
		{
			flagBits |= FLAG_BANK_VISITED;
		}
		return append(WorldPointUtil.UNDEFINED, previous, costOf(previous) + Math.max(0, extraCost), flagBits,
			(byte) abstractKind.ordinal());
	}

	public int packedPosition(int id)
	{
		return packedPosition[id >>> PAGE_SHIFT][id & PAGE_MASK];
	}

	public int previous(int id)
	{
		return previous[id >>> PAGE_SHIFT][id & PAGE_MASK];
	}

	public int cost(int id)
	{
		return cost[id >>> PAGE_SHIFT][id & PAGE_MASK];
	}

	/**
	 * The precomputed priority-queue key: the accumulated cost plus the A* heuristic value when
	 * one is attached (equal to {@link #cost} otherwise).
	 */
	public int orderCost(int id)
	{
		return orderCost[id >>> PAGE_SHIFT][id & PAGE_MASK];
	}

	public boolean bankVisited(int id)
	{
		return (flags[id >>> PAGE_SHIFT][id & PAGE_MASK] & FLAG_BANK_VISITED) != 0;
	}

	public boolean isTile(int id)
	{
		return (flags[id >>> PAGE_SHIFT][id & PAGE_MASK] & FLAG_ABSTRACT) == 0;
	}

	public boolean isAbstract(int id)
	{
		return (flags[id >>> PAGE_SHIFT][id & PAGE_MASK] & FLAG_ABSTRACT) != 0;
	}

	public boolean isTransport(int id)
	{
		return (flags[id >>> PAGE_SHIFT][id & PAGE_MASK] & FLAG_TRANSPORT) != 0;
	}


	public AbstractNodeKind abstractKind(int id)
	{
		return ABSTRACT_KINDS[abstractKind[id >>> PAGE_SHIFT][id & PAGE_MASK]];
	}

	/** A node id the snapshotted page tables can serve (every page up to the table's length exists). */
	private static boolean readable(int id, int[][] prev, int[][] packed, byte[][] flg)
	{
		if (id == NO_NODE)
		{
			return false;
		}
		final int page = id >>> PAGE_SHIFT;
		return page < prev.length && page < packed.length && page < flg.length
			&& prev[page] != null && packed[page] != null && flg[page] != null;
	}

	/**
	 * Walks the previous chain from {@code id} to the start, collecting the tile nodes (abstract
	 * nodes are skipped) into an ordered list of path steps.
	 * <p>
	 * Safe to call from the render thread during the search: the page tables are snapshotted into
	 * locals and the walk is bounds-tolerant, so a concurrent grow or {@link #release()} yields an
	 * empty or one-frame-stale result rather than throwing.
	 */
	public List<PathStep> getPathSteps(int id)
	{
		final int[][] prev = previous;
		final int[][] packed = packedPosition;
		final byte[][] flg = flags;
		if (prev == null || packed == null || flg == null || id == NO_NODE)
		{
			return new ArrayList<>();
		}

		int node = id;
		int n = 0;
		while (readable(node, prev, packed, flg))
		{
			if ((flg[node >>> PAGE_SHIFT][node & PAGE_MASK] & FLAG_ABSTRACT) == 0)
			{
				n++;
			}
			node = prev[node >>> PAGE_SHIFT][node & PAGE_MASK];
		}

		final List<PathStep> pathSteps = new ArrayList<>(n);
		for (int i = 0; i < n; i++)
		{
			pathSteps.add(null);
		}

		node = id;
		int i = n;
		while (readable(node, prev, packed, flg) && i > 0)
		{
			final int page = node >>> PAGE_SHIFT;
			final int slot = node & PAGE_MASK;
			if ((flg[page][slot] & FLAG_ABSTRACT) == 0)
			{
				pathSteps.set(--i, new PathStep(packed[page][slot], (flg[page][slot] & FLAG_BANK_VISITED) != 0));
			}
			node = prev[page][slot];
		}

		return pathSteps;
	}

	/**
	 * Walks the previous chain from {@code id} until the first tile node and returns its packed
	 * position, or {@link WorldPointUtil#UNDEFINED} if none. Same threading guarantees as
	 * {@link #getPathSteps}.
	 */
	public int getClosestTilePosition(int id)
	{
		final int[][] prev = previous;
		final int[][] packed = packedPosition;
		final byte[][] flg = flags;
		if (prev == null || packed == null || flg == null)
		{
			return WorldPointUtil.UNDEFINED;
		}
		int node = id;
		while (readable(node, prev, packed, flg) && (flg[node >>> PAGE_SHIFT][node & PAGE_MASK] & FLAG_ABSTRACT) != 0)
		{
			node = prev[node >>> PAGE_SHIFT][node & PAGE_MASK];
		}
		return readable(node, prev, packed, flg) ? packed[node >>> PAGE_SHIFT][node & PAGE_MASK] : WorldPointUtil.UNDEFINED;
	}

	/**
	 * Releases the backing pages once the search is finished and the final path has been
	 * materialised, so the large per-search working set becomes eligible for garbage collection
	 * (the old design dropped the explored {@code Node} objects the same way by clearing the
	 * frontier collections). A render-thread walk in flight keeps its own local references and
	 * finishes safely.
	 */
	public void release()
	{
		packedPosition = null;
		previous = null;
		cost = null;
		orderCost = null;
		flags = null;
		abstractKind = null;
		size = 0;
		capacity = 0;
	}
}
