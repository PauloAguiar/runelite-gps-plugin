package gps;

import gps.pathfinder.PathStep;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

/**
 * The directions are built once per route and reused every frame, but their item callouts read
 * what the player carries and wears: the cache also rebuilds when the plugin's item version
 * moves, or "Equip Dramen staff" would stay up after the staff is wielded.
 */
public class DirectionsCacheTest
{
	private static RouteOption route()
	{
		// One step: build() returns a fresh empty list without asking the plugin anything.
		List<PathStep> path = List.of(new PathStep(WorldPointUtil.packWorldPoint(3200, 3200, 0), false, 0));
		return new RouteOption(path, List.of(), List.of(), List.of(), 0, 0, true, Set.of(), List.of(), 0);
	}

	@Test
	public void rebuiltForAnotherRouteOrWhenTheItemsChange()
	{
		ShortestPathPlugin plugin = Mockito.mock(ShortestPathPlugin.class);
		DirectionsCache cache = new DirectionsCache();
		RouteOption route = route();

		List<RouteDirections.Step> first = cache.of(plugin, route);
		assertSame("reused while nothing changed", first, cache.of(plugin, route));

		Mockito.when(plugin.itemsVersion()).thenReturn(1);
		List<RouteDirections.Step> afterItems = cache.of(plugin, route);
		assertNotSame("an inventory or equipment change rebuilds", first, afterItems);
		assertSame(afterItems, cache.of(plugin, route));

		assertNotSame("another route rebuilds", afterItems, cache.of(plugin, route()));
	}
}
