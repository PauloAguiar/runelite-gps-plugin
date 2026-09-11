package gps;

import gps.pathfinder.PathStep;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Plan step L28: the route verdicts, out of the plugin class. A reached route is never too far
 * and always reaches; a route that stopped short counts by how far its endpoint is from the
 * nearest target against the tolerance; without a path or targets, "too far" is false and
 * "reaches" is true (not enough information to declare it unreachable); a null route reaches
 * nothing.
 */
public class RouteVerdictsTest
{
	private static final int TARGET = WorldPointUtil.packWorldPoint(3200, 3200, 0);

	private static RouteOption endingAt(int x, int y, boolean reached)
	{
		List<PathStep> path = List.of(new PathStep(WorldPointUtil.packWorldPoint(3100, 3100, 0), false),
			new PathStep(WorldPointUtil.packWorldPoint(x, y, 0), false));
		return new RouteOption(path, List.of(), List.of(), List.of(), 10, 10, reached, Set.of(), List.of(), 0);
	}

	@Test
	public void aRouteThatStoppedShortIsJudgedByItsEndpoint()
	{
		RouteOption beside = endingAt(3202, 3201, false); // 2 tiles: an object destination
		RouteOption far = endingAt(3230, 3200, false);    // 30 tiles: the target is cut off
		assertFalse(RouteVerdicts.endTooFar(beside, Set.of(TARGET), 5));
		assertTrue(RouteVerdicts.reachesTarget(beside, Set.of(TARGET), 5));
		assertTrue(RouteVerdicts.endTooFar(far, Set.of(TARGET), 5));
		assertFalse(RouteVerdicts.reachesTarget(far, Set.of(TARGET), 5));
		assertFalse("a wider tolerance forgives it", RouteVerdicts.endTooFar(far, Set.of(TARGET), 30));
	}

	@Test
	public void aReachedRouteAndMissingInformation()
	{
		RouteOption reached = endingAt(3230, 3200, true);
		assertFalse(RouteVerdicts.endTooFar(reached, Set.of(TARGET), 5));
		assertTrue(RouteVerdicts.reachesTarget(reached, Set.of(TARGET), 5));

		RouteOption far = endingAt(3230, 3200, false);
		assertFalse("no targets: not too far", RouteVerdicts.endTooFar(far, Set.of(), 5));
		assertTrue("no targets: cannot be declared unreachable", RouteVerdicts.reachesTarget(far, Set.of(), 5));
		assertFalse(RouteVerdicts.reachesTarget(null, Set.of(TARGET), 5));
	}
}
