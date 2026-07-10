package gps.pathfinder;

import static net.runelite.api.Constants.REGION_SIZE;

import gps.WorldPointUtil;

/**
 * The best tentative cost seen so far for each (tile, bank) search state — the duplicate-enqueue
 * pruner for cost-ordered (settle-dedup) searches. Without it every settled tile re-enqueues each
 * unsettled neighbour, so a map-wide flood appends and queues each tile ~3-4 times; recording the
 * best candidate cost at enqueue time lets non-improving duplicates be skipped BEFORE the node is
 * even created, restoring the enqueue-once economy the old FIFO search had — without its claim-by-
 * first-enqueue incorrectness, because an IMPROVING duplicate still goes through and wins the
 * settle. Same lazily-allocated region layout as {@link VisitedTiles}, with a short per tile.
 * <p>
 * Costs above {@link #MAX_PRUNABLE} are never pruned or recorded (they don't fit the short), so
 * pathological modifier configs lose the optimisation, never correctness.
 */
class TentativeCosts
{
	private static final short EMPTY = -1;
	private static final int MAX_PRUNABLE = 30000;

	private final SplitFlagMap.RegionExtent regionExtents;
	private final int widthInclusive;
	private final short[][] regionsWithoutBank;
	private final short[][] regionsWithBank;
	private final CollisionMap map;

	TentativeCosts(CollisionMap map)
	{
		this.map = map;
		regionExtents = SplitFlagMap.getRegionExtents();
		widthInclusive = regionExtents.getWidth() + 1;
		final int heightInclusive = regionExtents.getHeight() + 1;
		regionsWithoutBank = new short[widthInclusive * heightInclusive][];
		regionsWithBank = new short[widthInclusive * heightInclusive][];
	}

	/**
	 * Whether an arrival at {@code packedPoint} costing {@code candidate} is known not to improve
	 * on an already-queued arrival (prune it); records the candidate as the new best otherwise.
	 */
	boolean shouldPrune(int packedPoint, boolean bankVisited, int candidate)
	{
		if (candidate > MAX_PRUNABLE || candidate < 0)
		{
			return false;
		}
		final int x = WorldPointUtil.unpackWorldX(packedPoint);
		final int y = WorldPointUtil.unpackWorldY(packedPoint);
		final int plane = WorldPointUtil.unpackWorldPlane(packedPoint);
		final int regionIndex = getRegionIndex(x / REGION_SIZE, y / REGION_SIZE);
		final short[][] regions = bankVisited ? regionsWithBank : regionsWithoutBank;
		if (regionIndex < 0 || regionIndex >= regions.length)
		{
			return false;
		}
		short[] region = regions[regionIndex];
		if (region == null)
		{
			final byte planeCount = map.getRegionPlaneCounts(regionIndex);
			region = new short[Math.max(1, planeCount) * REGION_SIZE * REGION_SIZE];
			java.util.Arrays.fill(region, EMPTY);
			regions[regionIndex] = region;
		}
		final int index = (plane * REGION_SIZE + (y % REGION_SIZE)) * REGION_SIZE + (x % REGION_SIZE);
		if (index >= region.length)
		{
			return false;
		}
		final short current = region[index];
		if (current != EMPTY && current <= candidate)
		{
			return true;
		}
		region[index] = (short) candidate;
		return false;
	}

	private int getRegionIndex(int regionX, int regionY)
	{
		return (regionX - regionExtents.minX) + (regionY - regionExtents.minY) * widthInclusive;
	}

	void clear()
	{
		for (int i = 0; i < regionsWithoutBank.length; i++)
		{
			regionsWithoutBank[i] = null;
			regionsWithBank[i] = null;
		}
	}
}
