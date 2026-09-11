package gps;

import gps.pathfinder.PathfinderConfig;
import java.util.HashSet;
import java.util.Set;

/**
 * The GPS destination (plan step L36, out of the plugin class): the single source of truth for
 * where the player is going, and every way of setting it. A map pin or a searched place expands
 * to its walkable ring (a pin on furniture, a fence or an NPC's tile can never be settled by the
 * search; the world-map pin stays on the tile itself); an amenity category targets every site so
 * the nearest surfaces first, optionally as a round trip; another plugin's request arrives
 * already expanded. Setting a destination refreshes the live pathfinder config, filters the
 * ends, resets the route budget and the off-route tracker, arms the journey clock and places the
 * pin; the routes themselves come from the auto-compute on the next tick, keyed on the target
 * set, or from an explicit recompute. Written on the client thread, read from ticks and overlays.
 */
final class DestinationController
{
	private final ShortestPathPlugin plugin;
	private final RouteSession session;
	private final RouteController routes;
	private final WorldMapMarker marker;
	private final OffRouteTracker offRoute;
	private final JourneyTracker journey;

	private volatile int start = WorldPointUtil.UNDEFINED;
	private volatile Set<Integer> targets = Set.of();
	// Where the destination came from, for the GPS header: "map pin" for manual targets, the
	// sender's self-declared source for plugin messages (else "another plugin"), null when unset.
	private volatile String source;
	// Whether the destination is a round trip (out and back, "nearest bank (and back)"): set by
	// the round-trip entry point after the targets, carried into every generation for this
	// destination (refresh, show more), cleared when a new target is set.
	private volatile boolean roundTrip;

	DestinationController(ShortestPathPlugin plugin, RouteSession session, RouteController routes,
		WorldMapMarker marker, OffRouteTracker offRoute, JourneyTracker journey)
	{
		this.plugin = plugin;
		this.session = session;
		this.routes = routes;
		this.marker = marker;
		this.offRoute = offRoute;
		this.journey = journey;
	}

	int start()
	{
		return start;
	}

	Set<Integer> targets()
	{
		return targets;
	}

	boolean hasTargets()
	{
		return !targets.isEmpty();
	}

	String source()
	{
		return source;
	}

	/** Attributes the destination for the GPS header: the sender's source, "map pin", or null. */
	void setSource(String source)
	{
		this.source = source;
	}

	boolean isRoundTrip()
	{
		return roundTrip;
	}

	/** "Set GPS Target" from the map menu: the pick is attributed to the map pin. Client thread. */
	void pin(int packed)
	{
		source = "map pin";
		setTarget(packed, false);
	}

	/** "Find closest" from a world-map icon: every destination of that kind, the nearest wins. */
	void findClosest(String destinationType)
	{
		source = "map pin";
		setTargets(plugin.getPathfinderConfig().getDestinations(destinationType), true);
	}

	/** Clears the destination and its attribution, on the client thread (a tick, a hotkey, a clear request). */
	void clear()
	{
		source = null;
		setTarget(WorldPointUtil.UNDEFINED, false);
	}

	/** Clears the destination from another thread (the panel's Clear button). */
	void clearLater()
	{
		plugin.getClientThread().invokeLater(this::clear);
	}

	/**
	 * A searched place or amenity (the panel search box), attributed to {@code source}. A label
	 * can sit on an unwalkable tile (a fountain): it expands to the nearest walkable ring, like a
	 * map pin, while the world-map pin stays on the place itself.
	 */
	void setSearched(int packedPosition, String source)
	{
		plugin.getClientThread().invokeLater(() ->
		{
			this.source = source;
			Set<Integer> expanded = new HashSet<>(walkable(packedPosition));
			if (expanded.size() > 1)
			{
				marker.pinNextAt(packedPosition);
			}
			setTargets(expanded, false);
		});
	}

	/**
	 * The NEAREST of an amenity category (bank, altar, ...): every tile of the category is a
	 * target, so the shortest paths with the teleports currently available surface first,
	 * whichever site they reach. A round trip additionally routes BACK to the current position:
	 * every route goes out to a site and home again, ranked by the combined cost (the best
	 * round-trip bank is not necessarily the nearest one-way bank).
	 */
	void setNearestCategory(Set<Integer> tiles, String source, boolean roundTrip)
	{
		if (tiles == null || tiles.isEmpty())
		{
			return;
		}
		plugin.getClientThread().invokeLater(() ->
		{
			this.source = source;
			setTargets(new HashSet<>(tiles), false);
			// After setTargets: it resets the round-trip flag for ordinary destinations.
			this.roundTrip = roundTrip;
			routes.recompute();
		});
	}

	/**
	 * Records the destination (after the wilderness filter) and refreshes the live config so
	 * display lookups (transport labels, house exits) see current availability. Route
	 * computation happens in the generation, auto-triggered on the next tick by the target-set
	 * change. Hops to the client thread.
	 */
	void set(int newStart, Set<Integer> ends, boolean canReviveFiltered)
	{
		plugin.getClientThread().invokeLater(() ->
		{
			PathfinderConfig pathfinderConfig = plugin.getPathfinderConfig();
			// The panel's method catalog is the single customization surface: methods the user has
			// excluded there are also excluded here.
			pathfinderConfig.setExcludedMethods(plugin.getUserExclusions());
			pathfinderConfig.refresh();
			pathfinderConfig.filterLocations(ends, canReviveFiltered);
			if (ends.isEmpty())
			{
				setTarget(WorldPointUtil.UNDEFINED, false);
			}
			else
			{
				start = newStart;
				targets = Set.copyOf(ends);
			}
		});
	}

	/**
	 * Regenerates from the player's current position for the same targets (the off-route
	 * recalculation): explicit, because the auto-compute is keyed on the target set, which has
	 * not changed. The stale selection is dropped so the fresh generation's route takes over
	 * rather than the overlay clinging to the old line. Client thread.
	 */
	void recalculateFrom(int newStart, Set<Integer> currentTargets)
	{
		session.clearSelection();
		session.resetBudget(routes.defaultLimit());
		Set<Integer> ends = new HashSet<>(currentTargets);
		start = newStart;
		routes.trigger(newStart, ends);
	}

	/** A single tile, expanded to its walkable ring; UNDEFINED clears. Client thread. */
	private void setTarget(int target, boolean append)
	{
		Set<Integer> expanded = new HashSet<>();
		if (target != WorldPointUtil.UNDEFINED)
		{
			// A pin on an unwalkable tile (furniture, a fence, an NPC's tile from Quest Helper) can
			// never be settled by the search: it would explore the entire map and fall back to a
			// closest-tile path (captured in-game: 11 exhausted searches, 8.2 s). Target the nearest
			// walkable ring instead; walkable pins stay exact, and the map pin stays on the tile.
			Set<Integer> ring = walkable(target);
			if (ring.size() > 1)
			{
				marker.pinNextAt(target);
			}
			expanded.addAll(ring);
		}
		setTargets(expanded, append);
	}

	private Set<Integer> walkable(int packed)
	{
		PathfinderConfig pathfinderConfig = plugin.getPathfinderConfig();
		return Destinations.walkableTargets(pathfinderConfig != null ? pathfinderConfig.getMap() : null, packed,
			pathfinderConfig != null ? pathfinderConfig::isTransportOrigin : null);
	}

	/** The target set as given (already expanded); empty clears. Client thread. */
	private void setTargets(Set<Integer> newTargets, boolean append)
	{
		// Ordinary destinations are one-way; the round-trip entry point re-sets this after.
		roundTrip = false;
		// A fresh destination starts at the default cost band; "show more" widens it from there
		// (it bumps the multiple and regenerates without coming through here).
		session.resetCostMultiple();
		if (newTargets == null || newTargets.isEmpty())
		{
			start = WorldPointUtil.UNDEFINED;
			targets = Set.of();
			marker.clear();
			session.clearSelection();
			session.setLimit(routes.defaultLimit());
			// Keep the teleport-methods catalog visible with no target selected.
			routes.trigger(WorldPointUtil.UNDEFINED, new HashSet<>());
			return;
		}
		int here = plugin.getPlayerLocation();
		if (here == WorldPointUtil.UNDEFINED)
		{
			return;
		}
		marker.place(newTargets);
		offRoute.reset(here);
		Set<Integer> destinations = new HashSet<>(newTargets);
		if (append)
		{
			destinations.addAll(targets);
		}
		// Arm the journey timer: it starts counting from the player's first movement.
		journey.arm();
		// The routes themselves come from the tick-level auto-compute (keyed on the target-set
		// change) or the panel's "Find routes" button.
		set(here, destinations, append);
	}
}
