package gps;

import java.util.List;

/**
 * The step-by-step directions of the displayed route, built once per route instance (plan step
 * L28, out of the plugin class): the overlay renders every frame, and the path scan only reruns
 * when the displayed route object changes. One immutable holder, not two fields: the render
 * thread and the client thread both read it, and a two-field cache could publish one route's
 * key beside another's steps.
 */
final class DirectionsCache
{
	private static final class Entry
	{
		final RouteOption route;
		final List<RouteDirections.Step> steps;

		Entry(RouteOption route, List<RouteDirections.Step> steps)
		{
			this.route = route;
			this.steps = steps;
		}
	}

	private volatile Entry entry = new Entry(null, List.of());

	/** The directions for {@code route}, built through the plugin on the first ask per route. */
	List<RouteDirections.Step> of(ShortestPathPlugin plugin, RouteOption route)
	{
		Entry cached = entry;
		if (route != cached.route)
		{
			cached = new Entry(route, RouteDirections.build(plugin, route));
			entry = cached;
		}
		return cached.steps;
	}
}
