package gps;

import gps.pathfinder.PathStep;
import gps.pathfinder.PathfinderConfig;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;

/**
 * The plugin-message integration (plan step L31, out of the plugin class). Other plugins set or
 * clear the GPS destination, with config overrides, and hear the displayed route's transports
 * back. GPS answers on its own namespace and on Shortest Path's legacy one: Quest Helper and
 * others drive the pathfinder through that channel (set path/target, config overrides) and
 * listen for its broadcasts, and GPS supersedes Shortest Path, so inbound messages are accepted
 * on either and broadcasts go out on both (no listener subscribes to both today, so nothing
 * double-processes; drop the legacy channel only if that ever changes). Decoding lives in
 * {@link PluginMessageCodec}; this is the flow: which start, which ends, whether the journey
 * re-arms.
 */
final class PluginMessageBridge
{
	private final ShortestPathPlugin plugin;
	// A supplier: the plugin builds the bridge at field initialisation, before injection.
	private final Supplier<EventBus> eventBus;

	PluginMessageBridge(ShortestPathPlugin plugin, Supplier<EventBus> eventBus)
	{
		this.plugin = plugin;
		this.eventBus = eventBus;
	}

	/** An inbound message on either namespace: a path request or a clear. */
	void receive(PluginMessage event)
	{
		if (!PluginMessageCodec.isOurs(event.getNamespace()))
		{
			return;
		}
		String action = event.getName();
		if (PluginMessageCodec.ACTION_PATH.equals(action))
		{
			Map<String, Object> data = event.getData();
			Map<String, Object> overrides = PluginMessageCodec.configOverrideOf(data);
			if (!overrides.isEmpty())
			{
				plugin.applyConfigOverrides(overrides);
			}
			PluginMessageCodec.PathRequest request = PluginMessageCodec.parsePath(data);
			if (request == null)
			{
				return;
			}
			int start = request.start;
			if (start == WorldPointUtil.UNDEFINED)
			{
				start = plugin.getPlayerLocation();
				if (start == WorldPointUtil.UNDEFINED)
				{
					return;
				}
			}
			// Attribute the destination for the GPS header (a "source" the sender chose, else
			// all we can say is that a plugin asked for it).
			plugin.setTargetSource(request.source);
			boolean useOld = request.targets.isEmpty() && plugin.hasPathTargets();
			Set<Integer> ends;
			if (useOld)
			{
				ends = new HashSet<>(plugin.getPathTargets());
			}
			else
			{
				// A NEW destination from another plugin bypasses the manual target path, so the
				// journey timer is armed here too; otherwise the arrival time would carry over from
				// whatever destination was last set. Reusing the previous target keeps the running
				// journey.
				plugin.armJourney();
				// An NPC's or object's own tile expands like a map pin; a transport origin among
				// the expansion is the interactable side (see Destinations.externalTargets).
				PathfinderConfig pathfinderConfig = plugin.getPathfinderConfig();
				ends = Destinations.externalTargets(request.targets,
					pathfinderConfig != null ? pathfinderConfig.getMap() : null,
					pathfinderConfig != null ? pathfinderConfig::isTransportOrigin : null);
			}
			plugin.setDestination(start, ends, useOld);
		}
		else if (PluginMessageCodec.ACTION_CLEAR.equals(action))
		{
			plugin.clearConfigOverrides();
			plugin.clearPinnedTarget();
		}
	}

	/**
	 * Publishes the displayed route's transports to other plugins (the {@code postTransports}
	 * integration) on both namespaces. Called when the displayed route settles: the displayed
	 * route is what the player actually follows.
	 */
	void postTransports()
	{
		if (!plugin.hasPathTargets()
			|| !ConfigOverrides.override("postTransports", plugin.getGpsConfig().postTransports()))
		{
			return;
		}
		List<PathStep> currentPath = plugin.getDisplayPath();
		if (currentPath.isEmpty())
		{
			return;
		}
		Map<String, Object> data = PluginMessageCodec.encodeTransports(currentPath, plugin::transportsForEdge);
		EventBus bus = eventBus.get();
		bus.post(new PluginMessage(PluginMessageCodec.NAMESPACE, PluginMessageCodec.ACTION_TRANSPORTS, data));
		bus.post(new PluginMessage(PluginMessageCodec.NAMESPACE_LEGACY, PluginMessageCodec.ACTION_TRANSPORTS, data));
	}
}
