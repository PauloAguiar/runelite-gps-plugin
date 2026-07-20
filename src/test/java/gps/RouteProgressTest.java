package gps;

import gps.pathfinder.PathStep;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * The route progress tracker's maze rules: straight-line proximity is wall-blind, so eligibility
 * is based on walking distance. A path tile one tile away across a wall, or a corridor doubling
 * back a few tiles over, must not capture progress — the exact bugs seen in the field ("it thinks
 * I'm at a point in the route that is unreachable from where I stand").
 */
public class RouteProgressTest
{
	private static final int NO_GATE = Integer.MAX_VALUE;

	private static int tile(int x, int y)
	{
		return WorldPointUtil.packWorldPoint(x, y, 0);
	}

	/** A straight east-west path at y=3200: indexes 0..length-1 map to x=3200+i. */
	private static List<PathStep> corridor(int length)
	{
		List<PathStep> path = new ArrayList<>();
		for (int i = 0; i < length; i++)
		{
			path.add(new PathStep(tile(3200 + i, 3200), false));
		}
		return path;
	}

	/** Walking distances where every listed tile is openly reachable (walk == straight line). */
	private static Map<Integer, Integer> openField(int playerPacked, int radius)
	{
		Map<Integer, Integer> walk = new HashMap<>();
		int px = WorldPointUtil.unpackWorldX(playerPacked);
		int py = WorldPointUtil.unpackWorldY(playerPacked);
		for (int dx = -radius; dx <= radius; dx++)
		{
			for (int dy = -radius; dy <= radius; dy++)
			{
				walk.put(tile(px + dx, py + dy), Math.max(Math.abs(dx), Math.abs(dy)));
			}
		}
		return walk;
	}

	@Test
	public void adjacentTileAcrossAWallDoesNotCount()
	{
		// Player at (3205,3201): one tile north of path index 5 — but a wall is between (the
		// BFS never reached any path tile). Legacy straight-line rules would have snapped here.
		List<PathStep> path = corridor(20);
		int player = tile(3205, 3201);
		Map<Integer, Integer> walk = new HashMap<>();
		walk.put(player, 0); // only the player's own tile is reachable
		assertNull("wall-adjacent tile must not capture progress",
			RouteProgress.select(path, 3, NO_GATE, NO_GATE, player, walk));
	}

	@Test
	public void mazeDoubleBackDoesNotTeleportProgress()
	{
		// A U-shaped corridor: outbound leg y=3200 (indexes 0..9), return leg y=3202
		// (indexes 30..39) — 2 tiles apart straight-line, but the wall between means the real
		// walk goes around (14+ steps). The player stands on index 4; index ~34 is 2 tiles away.
		List<PathStep> path = new ArrayList<>();
		for (int i = 0; i < 10; i++)
		{
			path.add(new PathStep(tile(3200 + i, 3200), false));
		}
		for (int i = 10; i < 30; i++)
		{
			path.add(new PathStep(tile(3215, 3200 + (i - 10) / 10), false)); // filler far away
		}
		for (int i = 30; i < 40; i++)
		{
			path.add(new PathStep(tile(3209 - (i - 30), 3202), false));
		}
		int player = tile(3204, 3200);
		Map<Integer, Integer> walk = new HashMap<>();
		// Reachable along the outbound corridor only; the return leg is walled off (absent).
		for (int i = 0; i < 10; i++)
		{
			walk.put(tile(3200 + i, 3200), Math.abs(3200 + i - 3204));
		}
		RouteProgress.Result result = RouteProgress.select(path, 4, NO_GATE, NO_GATE, player, walk);
		assertNotNull(result);
		assertEquals("progress stays on the outbound leg, not across the wall", 4, result.index);
	}

	@Test
	public void doubleBackWithinReachStillPrefersTheNearWalk()
	{
		// Same shape but the wall has a gap: the return tile IS walkable — in 14 steps. Straight
		// line says 2; walking says 14 > 2 + slack, so the near-line match is rejected as
		// through-a-wall even though the tile is technically reachable.
		List<PathStep> path = new ArrayList<>();
		for (int i = 0; i < 10; i++)
		{
			path.add(new PathStep(tile(3200 + i, 3200), false));
		}
		for (int i = 10; i < 12; i++)
		{
			path.add(new PathStep(tile(3204 - (i - 10), 3202), false));
		}
		int player = tile(3204, 3200);
		Map<Integer, Integer> walk = new HashMap<>();
		for (int i = 0; i < 10; i++)
		{
			walk.put(tile(3200 + i, 3200), Math.abs(3200 + i - 3204));
		}
		walk.put(tile(3204, 3202), 11); // reachable, but only the long way round
		walk.put(tile(3203, 3202), 12);
		RouteProgress.Result result = RouteProgress.select(path, 4, NO_GATE, NO_GATE, player, walk);
		assertNotNull(result);
		assertEquals(4, result.index);
	}

	@Test
	public void honestWalkingAdvancesAlongTheCorridor()
	{
		List<PathStep> path = corridor(20);
		int player = tile(3207, 3200); // standing exactly on index 7
		RouteProgress.Result result = RouteProgress.select(
			path, 4, NO_GATE, NO_GATE, player, openField(player, RouteProgress.REACH_RADIUS));
		assertNotNull(result);
		assertEquals(7, result.index);
		assertEquals(0, result.distance);
	}

	@Test
	public void teleportLandingOnThePathJumpsAnyIndexDistance()
	{
		// Landing exactly on index 18 from reachedIndex 2: far outside STEP_WINDOW, but standing
		// ON the path counts anywhere (transport landings).
		List<PathStep> path = corridor(20);
		int player = tile(3218, 3200);
		RouteProgress.Result result = RouteProgress.select(
			path, 2, NO_GATE, NO_GATE, player, openField(player, RouteProgress.REACH_RADIUS));
		assertNotNull(result);
		assertEquals(18, result.index);
	}

	@Test
	public void beyondAnUncrossedDoorOnlyExactPresenceCounts()
	{
		List<PathStep> path = corridor(20);
		int doorGate = 10;
		// One tile from index 12 (beyond the door), openly walkable — still not enough.
		RouteProgress.Result near = RouteProgress.select(
			path, 9, doorGate, NO_GATE, tile(3212, 3201), openField(tile(3212, 3201), RouteProgress.REACH_RADIUS));
		assertEquals("only pre-door tiles are eligible near the door", 9, near.index);
		// Standing exactly on index 12: the door was evidently crossed.
		RouteProgress.Result exact = RouteProgress.select(
			path, 9, doorGate, NO_GATE, tile(3212, 3200), openField(tile(3212, 3200), RouteProgress.REACH_RADIUS));
		assertEquals(12, exact.index);
	}

	@Test
	public void returnLegIsIneligibleUntilTheTurnaround()
	{
		// Round trip retracing the same street: outbound 0..9, return 10..19 on the SAME tiles.
		List<PathStep> path = new ArrayList<>();
		for (int i = 0; i < 10; i++)
		{
			path.add(new PathStep(tile(3200 + i, 3200), false));
		}
		for (int i = 0; i < 10; i++)
		{
			path.add(new PathStep(tile(3209 - i, 3200), false));
		}
		int player = tile(3204, 3200);
		RouteProgress.Result result = RouteProgress.select(
			path, 4, NO_GATE, 9, player, openField(player, RouteProgress.REACH_RADIUS));
		assertNotNull(result);
		assertEquals("walking out must not read as coming back", 4, result.index);
	}

	@Test
	public void legacyStraightLineRulesApplyWithoutACollisionMap()
	{
		// No walk map (collision not loaded): the old behaviour — including its wall-blindness —
		// keeps the tracker functional rather than frozen.
		List<PathStep> path = corridor(20);
		RouteProgress.Result result = RouteProgress.select(
			path, 3, NO_GATE, NO_GATE, tile(3205, 3201), null);
		assertNotNull(result);
		// Indexes 4 and 5 are both straight-line distance 1; ties break toward the previous
		// position — and crucially, the wall-adjacent snap IS allowed in legacy mode.
		assertEquals(4, result.index);
	}

	@Test
	public void offRouteDetourHoldsTheEstimate()
	{
		List<PathStep> path = corridor(20);
		int player = tile(3240, 3240); // nowhere near
		assertNull(RouteProgress.select(
			path, 4, NO_GATE, NO_GATE, player, openField(player, RouteProgress.REACH_RADIUS)));
	}
}
