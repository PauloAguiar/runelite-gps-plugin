package gps.pathfinder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import gps.PrimitiveIntHashMap;
import gps.TeleportMethod;
import gps.WorldPointUtil;
import gps.transport.Transport;

public final class TransportAvailability
{
	public static final Transport[] EMPTY_TRANSPORTS = new Transport[0];

	// Transports grouped by origin tile, stored as flat arrays. The per-origin HashSet/HashMap
	// wrappers used while building are not retained (issue #491).
	//
	// transportsPacked is the pathfinding view: a transport is reachable from its literal origin
	// tile, and POH transports are additionally reachable from the canonical landing tile.
	// displayTransports is the coarse display view used by overlays and getTransports(): POH origin
	// tiles are collapsed into the landing tile only. The two maps share their Transport[] arrays
	// for every non-POH origin.
	private final PrimitiveIntHashMap<Transport[]> transportsPacked;
	private final PrimitiveIntHashMap<Transport[]> displayTransports;
	private final Transport[] usableTeleports;

	TransportAvailability(
		PrimitiveIntHashMap<Transport[]> transportsPacked,
		PrimitiveIntHashMap<Transport[]> displayTransports,
		Transport[] usableTeleports)
	{
		this.transportsPacked = transportsPacked;
		this.displayTransports = displayTransports;
		this.usableTeleports = usableTeleports;
	}

	public PrimitiveIntHashMap<Transport[]> getTransportsPacked()
	{
		return transportsPacked;
	}

	public PrimitiveIntHashMap<Transport[]> getDisplayTransports()
	{
		return displayTransports;
	}

	public Transport[] getUsableTeleports()
	{
		return usableTeleports;
	}

	/**
	 * The transports that start at the given origin tile in the display view, or an empty array.
	 */
	public Transport[] getTransportsAt(int origin)
	{
		return displayTransports.getOrDefault(origin, EMPTY_TRANSPORTS);
	}

	/**
	 * A view of this availability with every transport whose {@link Transport#method() method}
	 * is in {@code excluded} removed and {@code extras} appended to their origins (plan step N3).
	 * Copy-on-write: an origin that holds no excluded transport keeps sharing this instance's
	 * array, a map is cloned only once an entry changes, and with nothing to remove or add this
	 * instance is returned. A per-search rebuild therefore costs a few array clones instead of
	 * re-grouping every usable transport (which allocated ~4 MB per search).
	 */
	public TransportAvailability filtered(Set<TeleportMethod> excluded, List<Transport> extras)
	{
		Copy copy = new Copy();
		if (!excluded.isEmpty())
		{
			transportsPacked.forEach((origin, transports) ->
			{
				Transport[] kept = without(transports, excluded);
				if (kept != transports)
				{
					copy.packed().put(origin, kept);
					if (displayTransports.get(origin) != null)
					{
						copy.display().put(origin, kept);
					}
				}
			});
		}
		Transport[] teleports = without(usableTeleports, excluded);
		for (Transport extra : extras)
		{
			int origin = extra.getOrigin();
			if (origin == Transport.UNDEFINED_ORIGIN)
			{
				teleports = append(teleports, extra);
				continue;
			}
			copy.packed().put(origin, append(copy.packed().getOrDefault(origin, EMPTY_TRANSPORTS), extra));
			copy.display().put(origin, append(copy.display().getOrDefault(origin, EMPTY_TRANSPORTS), extra));
		}
		if (copy.packed == null && copy.display == null && teleports == usableTeleports)
		{
			return this;
		}
		return new TransportAvailability(
			copy.packed == null ? transportsPacked : copy.packed,
			copy.display == null ? displayTransports : copy.display,
			teleports);
	}

	/** The maps a {@link #filtered} view has had to clone so far; none until an entry changes. */
	private final class Copy
	{
		private PrimitiveIntHashMap<Transport[]> packed;
		private PrimitiveIntHashMap<Transport[]> display;

		PrimitiveIntHashMap<Transport[]> packed()
		{
			if (packed == null)
			{
				packed = new PrimitiveIntHashMap<>(transportsPacked);
			}
			return packed;
		}

		PrimitiveIntHashMap<Transport[]> display()
		{
			if (display == null)
			{
				display = new PrimitiveIntHashMap<>(displayTransports);
			}
			return display;
		}
	}

	/** {@code transports} without the excluded methods, or the same array when nothing is excluded. */
	private static Transport[] without(Transport[] transports, Set<TeleportMethod> excluded)
	{
		if (excluded.isEmpty())
		{
			return transports;
		}
		int kept = 0;
		for (Transport transport : transports)
		{
			if (!excluded.contains(transport.method()))
			{
				kept++;
			}
		}
		if (kept == transports.length)
		{
			return transports;
		}
		Transport[] result = new Transport[kept];
		int index = 0;
		for (Transport transport : transports)
		{
			if (!excluded.contains(transport.method()))
			{
				result[index++] = transport;
			}
		}
		return result;
	}

	private static Transport[] append(Transport[] transports, Transport extra)
	{
		Transport[] result = new Transport[transports.length + 1];
		System.arraycopy(transports, 0, result, 0, transports.length);
		result[transports.length] = extra;
		return result;
	}

	/*
	 * Build a TransportAvailability by incrementally adding available transports.
	 */
	static final class Builder
	{
		// Temporary accumulation; converted to flat arrays in build() and not retained afterwards.
		// Primitive-keyed by origin tile: rebuildAvailabilityWithExclusions runs this per search, and a
		// Map<Integer, ...> boxed the int origin on every transport added. Each origin holds no
		// duplicate transports (every transport is added at most once), so a List is enough — no Set.
		private final PrimitiveIntHashMap<List<Transport>> transportsByOrigin;
		private final List<Transport> usableTeleports;
		private final Set<Integer> pohOrigins = new HashSet<>();

		Builder(int expectedTransportCount)
		{
			this.transportsByOrigin = new PrimitiveIntHashMap<>(Math.max(8, expectedTransportCount / 2));
			this.usableTeleports = new ArrayList<>(Math.max(8, expectedTransportCount / 20));
		}

		void add(Transport transport)
		{
			if (transport.getOrigin() == WorldPointUtil.UNDEFINED)
			{
				usableTeleports.add(transport);
				return;
			}

			List<Transport> atOrigin = transportsByOrigin.get(transport.getOrigin());
			if (atOrigin == null)
			{
				atOrigin = new ArrayList<>(2);
				transportsByOrigin.put(transport.getOrigin(), atOrigin);
			}
			atOrigin.add(transport);
		}

		void remapPohTransports()
		{
			int pohLanding = WorldPointUtil.packWorldPoint(1923, 5709, 0);
			List<Transport> pohTransports = new ArrayList<>();

			for (int origin : transportsByOrigin.keys())
			{
				int originX = WorldPointUtil.unpackWorldX(origin);
				int originY = WorldPointUtil.unpackWorldY(origin);
				if (gps.ShortestPathPlugin.isInsidePoh(originX, originY))
				{
					pohTransports.addAll(transportsByOrigin.get(origin));
					// Kept in the pathfinding view, collapsed out of the display view.
					pohOrigins.add(origin);
				}
			}

			if (!pohTransports.isEmpty())
			{
				List<Transport> landing = transportsByOrigin.get(pohLanding);
				if (landing == null)
				{
					landing = new ArrayList<>(pohTransports.size());
					transportsByOrigin.put(pohLanding, landing);
				}
				landing.addAll(pohTransports);
			}
		}

		TransportAvailability build()
		{
			int expected = Math.max(1, transportsByOrigin.size());
			PrimitiveIntHashMap<Transport[]> packed = new PrimitiveIntHashMap<>(expected);
			PrimitiveIntHashMap<Transport[]> display = new PrimitiveIntHashMap<>(expected);
			for (int origin : transportsByOrigin.keys())
			{
				Transport[] transports = transportsByOrigin.get(origin).toArray(EMPTY_TRANSPORTS);
				packed.put(origin, transports);
				if (!pohOrigins.contains(origin))
				{
					display.put(origin, transports);
				}
			}
			return new TransportAvailability(packed, display, usableTeleports.toArray(EMPTY_TRANSPORTS));
		}
	}
}
