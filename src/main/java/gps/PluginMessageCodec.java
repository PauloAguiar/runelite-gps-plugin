package gps;

import gps.pathfinder.PathStep;
import gps.transport.Transport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import net.runelite.api.coords.WorldPoint;

/**
 * The plugin-message wire format (plan step L8, out of the plugin class): what other plugins
 * send GPS ("path" with a start, a target or targets, an optional config override and an optional
 * source; "clear") and what GPS publishes back (the displayed route's transports). Pure parsing
 * and encoding; the plugin keeps the actions. Both the current namespace and the pre-fork one
 * are honoured so older integrations keep working.
 */
final class PluginMessageCodec
{
	static final String NAMESPACE = "gps";
	static final String NAMESPACE_LEGACY = "shortestpath";
	static final String ACTION_PATH = "path";
	static final String ACTION_CLEAR = "clear";
	static final String ACTION_TRANSPORTS = "transports";
	static final String KEY_START = "start";
	static final String KEY_TARGET = "target";
	static final String KEY_CONFIG_OVERRIDE = "config";
	static final String KEY_SOURCE = "source";
	/** PluginMessage does not identify its sender; this is all GPS can say without a "source". */
	static final String DEFAULT_SOURCE = "another plugin";

	/** A parsed "path" request. */
	static final class PathRequest
	{
		/** The packed start, or {@link WorldPointUtil#UNDEFINED} for "where the player stands". */
		final int start;
		/** The packed targets; empty means "keep the current destination". */
		final Set<Integer> targets;
		/** The attribution shown in the GPS header. */
		final String source;

		PathRequest(int start, Set<Integer> targets, String source)
		{
			this.start = start;
			this.targets = targets;
			this.source = source;
		}
	}

	private PluginMessageCodec()
	{
	}

	static boolean isOurs(String namespace)
	{
		return NAMESPACE.equals(namespace) || NAMESPACE_LEGACY.equals(namespace);
	}

	/** The config override carried by the message, or an empty map when there is none. */
	@SuppressWarnings("unchecked")
	static Map<String, Object> configOverrideOf(Map<String, Object> data)
	{
		Object override = data.get(KEY_CONFIG_OVERRIDE);
		return override instanceof Map<?, ?> ? (Map<String, Object>) override : Map.of();
	}

	/**
	 * Parses a "path" request. Null when the message carries neither a start nor a target (an
	 * override-only message), or a target that is not a world point (an integer that is the
	 * undefined sentinel, or a set holding one): the request is dropped whole, never half-applied.
	 * A target of an unknown type counts as no target.
	 */
	static PathRequest parsePath(Map<String, Object> data)
	{
		Object objStart = data.get(KEY_START);
		Object objTarget = data.get(KEY_TARGET);
		if (objStart == null && objTarget == null)
		{
			return null;
		}
		int start = objStart instanceof WorldPoint ? WorldPointUtil.packWorldPoint((WorldPoint) objStart)
			: objStart instanceof Integer ? (Integer) objStart : WorldPointUtil.UNDEFINED;

		Set<Integer> targets = new HashSet<>();
		if (objTarget instanceof Set<?>)
		{
			for (Object member : (Set<?>) objTarget)
			{
				int packed = packed(member);
				if (packed == WorldPointUtil.UNDEFINED)
				{
					return null;
				}
				targets.add(packed);
			}
		}
		else if (objTarget instanceof Integer || objTarget instanceof WorldPoint)
		{
			int packed = packed(objTarget);
			if (packed == WorldPointUtil.UNDEFINED)
			{
				return null;
			}
			targets.add(packed);
		}

		Object objSource = data.get(KEY_SOURCE);
		String source = objSource instanceof String && !((String) objSource).isEmpty()
			? (String) objSource : DEFAULT_SOURCE;
		return new PathRequest(start, targets, source);
	}

	private static int packed(Object value)
	{
		if (value instanceof Integer)
		{
			return (Integer) value;
		}
		if (value instanceof WorldPoint)
		{
			return WorldPointUtil.packWorldPoint((WorldPoint) value);
		}
		return WorldPointUtil.UNDEFINED;
	}

	/**
	 * The "transports" payload for a displayed path: one entry per transport edge, as parallel
	 * lists of origins, destinations, object infos and display infos (the shape older consumers
	 * expect). {@code transportsForEdge} resolves the transports a path edge rides.
	 */
	static Map<String, Object> encodeTransports(List<PathStep> path,
		BiFunction<PathStep, PathStep, ? extends Iterable<Transport>> transportsForEdge)
	{
		List<WorldPoint> origins = new ArrayList<>();
		List<WorldPoint> destinations = new ArrayList<>();
		List<String> objectInfos = new ArrayList<>();
		List<String> displayInfos = new ArrayList<>();
		for (int i = 1; i < path.size(); i++)
		{
			PathStep from = path.get(i - 1);
			PathStep to = path.get(i);
			for (Transport transport : transportsForEdge.apply(from, to))
			{
				origins.add(WorldPointUtil.unpackWorldPoint(from.getPackedPosition()));
				destinations.add(WorldPointUtil.unpackWorldPoint(to.getPackedPosition()));
				objectInfos.add(transport.getObjectInfo());
				displayInfos.add(transport.getDisplayInfo());
			}
		}
		Map<String, Object> data = new HashMap<>();
		data.put("origin", origins);
		data.put("destination", destinations);
		data.put("objectInfo", objectInfos);
		data.put("displayInfo", displayInfos);
		return data;
	}
}
