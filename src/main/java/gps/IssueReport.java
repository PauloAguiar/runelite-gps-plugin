package gps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;

/**
 * The "report an issue" context: the routing state (mode, start, target, config, method counts,
 * carried items, detections, the routes found) as plain text the player copies BY HAND into a
 * GitHub issue. Nothing is sent anywhere by the plugin and the player sees exactly what they
 * share. Built on the client thread (item names come from the item definitions).
 */
final class IssueReport
{
	private final ShortestPathPlugin plugin;

	IssueReport(ShortestPathPlugin plugin)
	{
		this.plugin = plugin;
	}

	/** The context block, CLIENT THREAD. */
	String body()
	{
		RouteSession session = plugin.session();
		ShortestPathConfig config = plugin.getGpsConfig();
		StringBuilder body = new StringBuilder();
		// No "describe the issue" headings here: the GitHub issue template provides those; this
		// block is what the player pastes under them.
		body.append("*Auto-captured context — please keep:*\n");
		body.append("- GPS ").append(BuildInfo.pluginVersion()).append('\n');
		body.append("- Build ").append(BuildInfo.buildCommit()).append('\n');
		body.append("- Mode: ").append(plugin.getRoutesMode()).append(" · limit ").append(session.limit())
			.append(" · band x").append(session.costMultiple()).append('\n');
		body.append("- Start: ").append(pointText(session.lastStart())).append('\n');
		List<String> targets = new ArrayList<>();
		for (int target : session.lastTargets())
		{
			targets.add(pointText(target));
		}
		body.append("- Target(s): ").append(targets.isEmpty() ? "(none)" : String.join("; ", targets)).append('\n');
		// Only settings that genuinely affect routing here: avoidWilderness applies in every mode,
		// bankPickup weights the bank detour. The mode (above) already implies bank routing and the
		// item scope, so those are not repeated (they would show the overridden config value).
		body.append("- Config: avoidWilderness=")
			.append(ConfigOverrides.override("avoidWilderness", config.avoidWilderness()))
			.append(", bankPickup=").append(ConfigOverrides.override("costBankPickup", config.costBankPickup()))
			.append('\n');

		// Method availability at a glance: the full catalog is far too big, so counts per status
		// plus the user's own exclusions (the part that varies by choice, usually short).
		List<TeleportMethod> catalog = plugin.teleportCatalog();
		Map<TeleportMethod, MethodAvailability> unavailable = plugin.unavailableMethods();
		if (!catalog.isEmpty())
		{
			Map<MethodAvailability, Integer> counts = new EnumMap<>(MethodAvailability.class);
			for (MethodAvailability status : unavailable.values())
			{
				counts.merge(status, 1, Integer::sum);
			}
			body.append("- Methods: ").append(catalog.size() - unavailable.size()).append(" usable of ")
				.append(catalog.size());
			for (Map.Entry<MethodAvailability, Integer> entry : counts.entrySet())
			{
				body.append(" · ").append(entry.getValue()).append(' ')
					.append(entry.getKey().name().toLowerCase(Locale.ROOT).replace('_', ' '));
			}
			body.append('\n');
		}
		Set<TeleportMethod> userExclusions = plugin.getUserExclusions();
		if (!userExclusions.isEmpty())
		{
			List<String> excluded = new ArrayList<>();
			for (TeleportMethod method : userExclusions)
			{
				excluded.add(method.routeLabel());
			}
			Collections.sort(excluded);
			int cap = Math.min(excluded.size(), 10);
			body.append("- Excluded by user: ").append(String.join("; ", excluded.subList(0, cap)));
			if (excluded.size() > cap)
			{
				body.append(" … ").append(excluded.size() - cap).append(" more");
			}
			body.append('\n');
		}
		// What the player carries decides the Owned modes' teleports, so name it (user-reviewed
		// before submitting: they can trim anything they would rather not share).
		Client client = plugin.getClient();
		body.append("- Equipped: ").append(itemNames(client, InventoryID.WORN)).append('\n');
		body.append("- Inventory: ").append(itemNames(client, InventoryID.INV)).append('\n');
		body.append("- Bank contents known: ").append(plugin.isBankContentsKnown())
			.append(plugin.isBankRestored() ? " (restored from previous session)" : "").append('\n');
		body.append("- House scanned: ").append(plugin.isPohScanned());
		String pohEncoded = PohScanner.encode(plugin.pohDetection().detected());
		if (pohEncoded != null)
		{
			body.append(" (").append(pohEncoded).append(')');
		}
		body.append('\n');
		body.append("- Spirit trees synced: ").append(plugin.isSpiritTreeSynced())
			.append(plugin.spiritTreesParsedLive() ? " (live)" : "").append('\n');

		List<RouteOption> routes = session.routes();
		body.append("- Routes (").append(routes.size()).append("):\n");
		int shown = Math.min(routes.size(), 12);
		for (int i = 0; i < shown; i++)
		{
			RouteOption route = routes.get(i);
			body.append("  ").append(i).append(". ").append(route.getTotalCost())
				.append(route.isReached() ? "" : " (closest)").append(" · ").append(methodSummary(route)).append('\n');
		}
		if (routes.size() > shown)
		{
			body.append("  … ").append(routes.size() - shown).append(" more\n");
		}
		body.append("\nFor a full reproduction, attach the newest file from your `.runelite/gps-debug/` folder"
			+ " (use \"Save debug snapshot\" in the ⋯ menu first).\n");
		return body.toString();
	}

	/**
	 * The names of the items in a container, stacks as "xN", duplicates collapsed, CLIENT THREAD
	 * (item definitions). "(empty)" when nothing is carried, "(unknown)" when not logged in.
	 */
	static String itemNames(Client client, int inventoryId)
	{
		ItemContainer container = client.getItemContainer(inventoryId);
		if (container == null)
		{
			return "(unknown)";
		}
		Map<String, Integer> names = new LinkedHashMap<>();
		for (Item item : container.getItems())
		{
			if (item == null || item.getId() <= 0)
			{
				continue;
			}
			String name;
			try
			{
				ItemComposition definition = client.getItemDefinition(item.getId());
				name = definition != null ? definition.getName() : "item " + item.getId();
			}
			catch (RuntimeException e)
			{
				name = "item " + item.getId();
			}
			names.merge(name, Math.max(1, item.getQuantity()), Integer::sum);
		}
		if (names.isEmpty())
		{
			return "(empty)";
		}
		List<String> parts = new ArrayList<>(names.size());
		for (Map.Entry<String, Integer> entry : names.entrySet())
		{
			parts.add(entry.getValue() > 1 ? entry.getKey() + " x" + entry.getValue() : entry.getKey());
		}
		return String.join(", ", parts);
	}

	/** A packed point as "x, y, plane", or "(none)" when undefined. */
	static String pointText(int packed)
	{
		if (packed == WorldPointUtil.UNDEFINED)
		{
			return "(none)";
		}
		return WorldPointUtil.unpackWorldX(packed) + ", " + WorldPointUtil.unpackWorldY(packed)
			+ ", " + WorldPointUtil.unpackWorldPlane(packed);
	}

	/** A route's methods joined with " + ", or "walk" when it has none. */
	static String methodSummary(RouteOption route)
	{
		if (route.getMethods().isEmpty())
		{
			return "walk";
		}
		List<String> parts = new ArrayList<>();
		for (TeleportMethod method : route.getMethods())
		{
			parts.add(method.routeLabel());
		}
		return String.join(" + ", parts);
	}
}
