package gps.pathfinder;

import java.util.Arrays;

/**
 * A binary min-heap of primitive {@code int} node ids ordered by {@link NodeGraph#orderCost} (the
 * accumulated cost, plus the heuristic in A* mode).
 * <p>
 * Replaces the {@code PriorityQueue<TransportNode>} pending queue in {@link Pathfinder} so transport
 * candidates are stored as int ids rather than boxed node objects. The ordering key is fixed when a
 * node is created, so no decrease-key support is needed; the pathfinder discards stale costlier
 * duplicates with its dequeue-time visited re-check. Single-threaded (worker only), matching the
 * queue it replaces.
 */
class IntMinHeap {
    private final NodeGraph graph;
    private int[] heap;
    private int size;

    IntMinHeap(NodeGraph graph, int initialCapacity) {
        this.graph = graph;
        this.heap = new int[Math.max(1, initialCapacity)];
    }

    boolean isEmpty() {
        return size == 0;
    }

    /**
     * @return the minimum-cost element, or {@link NodeGraph#NO_NODE} if empty.
     */
    int peek() {
        return size == 0 ? NodeGraph.NO_NODE : heap[0];
    }

    void add(int id) {
        if (size == heap.length)
            heap = Arrays.copyOf(heap, heap.length << 1);
        heap[size] = id;
        siftUp(size);
        size++;
    }

    /**
     * @return the removed minimum-cost element, or {@link NodeGraph#NO_NODE} if empty.
     */
    int poll() {
        if (size == 0)
            return NodeGraph.NO_NODE;
        final int top = heap[0];
        size--;
        if (size > 0) {
            heap[0] = heap[size];
            siftDown(0);
        }
        return top;
    }

    void clear() {
        size = 0;
    }

    /**
     * Ordering: orderCost (f = g + h) first, then HIGHER g, then node id (creation order).
     * <p>
     * The g tie-break is what keeps a near-exact heuristic from flooding its own plateau: every
     * tile on any optimal corridor shares the same f, so f-only ordering explores that whole
     * plateau breadth-first — for a long walk that is the entire diamond of equal-cost grid paths
     * (hundreds of thousands of tiles) — before the target can pop. Preferring the higher g among
     * equal f always advances the deepest node (the one closest to the goal), so the search dives
     * down one corridor and reaches the target having settled little beside it. Any tie-break
     * among equal f is optimality-neutral under a consistent heuristic.
     * <p>
     * The final id (creation order) tie-break keeps the claims of same-f same-g tiles mirroring
     * the FIFO search's (cardinals are generated first), which produces the long straight runs
     * that match the game's own click-walk movement, and makes searches fully deterministic.
     */
    private boolean less(int a, int b) {
        final int costA = graph.orderCost(a);
        final int costB = graph.orderCost(b);
        if (costA != costB)
            return costA < costB;
        final int gA = graph.cost(a);
        final int gB = graph.cost(b);
        if (gA != gB)
            return gA > gB;
        return a < b;
    }

    private void siftUp(int index) {
        final int id = heap[index];
        while (index > 0) {
            final int parent = (index - 1) >> 1;
            if (!less(id, heap[parent]))
                break;
            heap[index] = heap[parent];
            index = parent;
        }
        heap[index] = id;
    }

    private void siftDown(int index) {
        final int id = heap[index];
        final int half = size >> 1;
        while (index < half) {
            int child = (index << 1) + 1;
            final int right = child + 1;
            if (right < size && less(heap[right], heap[child]))
                child = right;
            if (!less(heap[child], id))
                break;
            heap[index] = heap[child];
            index = child;
        }
        heap[index] = id;
    }
}
