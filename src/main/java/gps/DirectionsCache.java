package gps;

import java.util.List;

/**
 * The step-by-step directions of the displayed route, built once per route instance (plan step
 * L28, out of the plugin class): the overlay renders every frame, and the path scan only reruns
 * when the displayed route object changes, or the player's items do (the steps' item callouts
 * read them). One immutable holder, not two fields: the render
 * thread and the client thread both read it, and a two-field cache could publish one route's
 * key beside another's steps.
 */
final class DirectionsCache {
    private static final class Entry {
        final RouteOption route;
        final int itemsVersion;
        final List<RouteDirections.Step> steps;

        Entry(RouteOption route, int itemsVersion, List<RouteDirections.Step> steps) {
            this.route = route;
            this.itemsVersion = itemsVersion;
            this.steps = steps;
        }
    }

    private volatile Entry entry = new Entry(null, 0, List.of());

    /** The directions for {@code route}, built through the plugin on the first ask per route. */
    List<RouteDirections.Step> of(ShortestPathPlugin plugin, RouteOption route) {
        Entry cached = entry;
        int itemsVersion = plugin.itemsVersion();
        if (route != cached.route || itemsVersion != cached.itemsVersion) {
            cached = new Entry(route, itemsVersion, RouteDirections.build(plugin, route));
            entry = cached;
        }
        return cached.steps;
    }
}
