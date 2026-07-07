package shortestpath;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportType;

/**
 * Covers the seed-teleport candidate selection ({@link AlternativeRoutesService#rankSeedCandidates}):
 * candidates are ranked by estimated arrival cost (teleport duration + straight-line landing
 * distance), far-landing candidates are kept rather than dropped (an obstacle between start and
 * target makes straight-line comparisons lie), cross-plane landings rank last instead of
 * overflowing to first, duplicate landings keep the best-ranked candidate, user exclusions are
 * honoured, and the attempt cap bounds the list.
 */
public class SeedCandidateRankingTest
{
	private static final int TARGET = WorldPointUtil.packWorldPoint(3200, 3200, 0);

	private static Transport teleport(String name, int x, int y, int plane, int duration)
	{
		return new Transport.TransportBuilder()
			.type(TransportType.TELEPORTATION_SPELL)
			.destination(WorldPointUtil.packWorldPoint(x, y, plane))
			.duration(duration)
			.displayInfo(name)
			.build();
	}

	@Test
	public void farLandingCandidateIsKeptAndRankedByEstimate()
	{
		// The old drop rule discarded any candidate landing straight-line-farther than the start —
		// wrong when an obstacle separates start and target (start looks close but the walk is long).
		// A far-landing candidate must now be kept, ranked after closer ones.
		Transport near = teleport("near", 3205, 3200, 0, 0);   // 5 tiles from target
		Transport far = teleport("far", 3230, 3200, 0, 0);     // 30 tiles from target

		List<Transport> attempts = AlternativeRoutesService.rankSeedCandidates(
			new ArrayList<>(List.of(far, near)), TARGET, Set.of(), 10);

		assertEquals("Both candidates must be kept (no distance-based drop)", 2, attempts.size());
		assertEquals("Closer landing must rank first", "near", attempts.get(0).getDisplayInfo());
		assertEquals("Farther landing must still be attempted", "far", attempts.get(1).getDisplayInfo());
	}

	@Test
	public void teleportDurationIsChargedIntoTheRanking()
	{
		// A fast teleport landing a bit farther beats a slow one landing nearer: the rank is the
		// engine's own cost blend (duration ticks + walk tiles), not landing distance alone.
		Transport fastFarther = teleport("fast", 3220, 3200, 0, 4);  // 4 + 20 = 24
		Transport slowNearer = teleport("slow", 3210, 3200, 0, 30);  // 30 + 10 = 40

		List<Transport> attempts = AlternativeRoutesService.rankSeedCandidates(
			new ArrayList<>(List.of(slowNearer, fastFarther)), TARGET, Set.of(), 10);

		assertEquals("Duration must be part of the estimate", "fast", attempts.get(0).getDisplayInfo());
	}

	@Test
	public void crossPlaneLandingRanksLastNotFirst()
	{
		// A landing on another plane has straight-line distance Integer.MAX_VALUE; with int
		// arithmetic adding the duration would overflow negative and rank it FIRST. It must rank
		// last — but still be kept (the old drop rule discarded every seed for cross-plane targets).
		Transport samePlane = teleport("surface", 3300, 3300, 0, 10);
		Transport crossPlane = teleport("dungeon", 3201, 3200, 1, 10);

		List<Transport> attempts = AlternativeRoutesService.rankSeedCandidates(
			new ArrayList<>(List.of(crossPlane, samePlane)), TARGET, Set.of(), 10);

		assertEquals("Cross-plane candidates must be kept", 2, attempts.size());
		assertEquals("Same-plane landing must rank first", "surface", attempts.get(0).getDisplayInfo());
		assertEquals("Cross-plane landing must rank last (no overflow)", "dungeon", attempts.get(1).getDisplayInfo());
	}

	@Test
	public void duplicateLandingsKeepTheBestRankedCandidate()
	{
		// Tab and spell to the same tile: only one attempt is spent, on the better-estimated one.
		Transport slow = teleport("slow-duplicate", 3210, 3200, 0, 30);
		Transport fast = teleport("fast-duplicate", 3210, 3200, 0, 4);

		List<Transport> attempts = AlternativeRoutesService.rankSeedCandidates(
			new ArrayList<>(List.of(slow, fast)), TARGET, Set.of(), 10);

		assertEquals("Duplicate landings collapse to one attempt", 1, attempts.size());
		assertEquals("The better-ranked duplicate wins", "fast-duplicate", attempts.get(0).getDisplayInfo());
	}

	@Test
	public void userExcludedCandidatesAreDropped()
	{
		Transport kept = teleport("kept", 3210, 3200, 0, 0);
		Transport excluded = teleport("excluded", 3205, 3200, 0, 0);

		List<Transport> attempts = AlternativeRoutesService.rankSeedCandidates(
			new ArrayList<>(List.of(kept, excluded)), TARGET,
			Set.of(TeleportMethod.fromTransport(excluded)), 10);

		assertEquals("Excluded method must not get an attempt", 1, attempts.size());
		assertEquals("kept", attempts.get(0).getDisplayInfo());
	}

	@Test
	public void attemptCapKeepsTheBestRankedCandidates()
	{
		List<Transport> candidates = new ArrayList<>();
		for (int i = 1; i <= 5; i++)
		{
			candidates.add(teleport("t" + i, 3200 + 5 * i, 3200, 0, 0)); // 5, 10, ... 25 tiles out
		}

		List<Transport> attempts = AlternativeRoutesService.rankSeedCandidates(candidates, TARGET, Set.of(), 3);

		assertEquals("Cap must bound the attempts", 3, attempts.size());
		for (int i = 0; i < attempts.size(); i++)
		{
			assertEquals("The cap must keep the best-ranked candidates in order",
				"t" + (i + 1), attempts.get(i).getDisplayInfo());
		}
		assertTrue("Zero cap yields no attempts",
			AlternativeRoutesService.rankSeedCandidates(candidates, TARGET, Set.of(), 0).isEmpty());
	}
}
