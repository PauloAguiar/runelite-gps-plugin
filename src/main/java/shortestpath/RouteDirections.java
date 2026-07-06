package shortestpath;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import shortestpath.pathfinder.PathStep;
import shortestpath.transport.Transport;

/**
 * Builds the "Google Maps"-style step list for a route: walking legs with their length and target,
 * "Use ..." entries for each teleport/transport method, bank detours with what to withdraw, and
 * climb steps (stairs, ladders, trapdoors). Each step records the span of path indexes it covers so
 * the overlay can track execution progress from the player's position. Built once per displayed
 * route and cached by the plugin; rendered by {@link RouteDirectionsOverlay}.
 */
final class RouteDirections
{
	/**
	 * Running covers 2 tiles per 0.6s game tick; estimates assume the player runs.
	 */
	static final double SECONDS_PER_TICK = 0.6;
	private static final int BANK_WITHDRAW_TICKS = 8;

	/**
	 * One direction step, the route-path index range [start, end] it spans, and its estimated
	 * duration in game ticks (for wall-time ETAs).
	 */
	@Getter
	static final class Step
	{
		private final String text;
		private final int startIndex;
		private final int endIndex;
		private final int ticks;
		// True for "Use <method>" steps: rides the overlay can interpolate progress through
		// (carpet/canoe/glider flights) rather than freezing the ETA until landing.
		private final boolean transport;

		private Step(String text, int startIndex, int endIndex, int ticks)
		{
			this(text, startIndex, endIndex, ticks, false);
		}

		private Step(String text, int startIndex, int endIndex, int ticks, boolean transport)
		{
			this.text = text;
			this.startIndex = startIndex;
			this.endIndex = endIndex;
			this.ticks = ticks;
			this.transport = transport;
		}
	}

	private RouteDirections()
	{
	}

	/**
	 * The steps for the route, in travel order. Method steps come from the route's own recorded edge
	 * indexes — the route was computed with the planning config's availability, so re-deriving its
	 * methods against the main config would silently miss some (and count a missed teleport's jump as
	 * a thousand-tile walking leg). Only climbs are matched against the main config: they're plain
	 * always-enabled transports, present in any mode.
	 */
	static List<Step> build(ShortestPathPlugin plugin, RouteOption route)
	{
		List<Step> steps = new ArrayList<>();
		List<PathStep> path = route.getPath();
		if (path == null || path.size() < 2)
		{
			return steps;
		}

		// Edge index -> position in the route's method list.
		List<Integer> methodEdges = route.getMethodEdgeIndexes();
		int nextMethod = 0;

		int walk = 0;
		int legStart = 0;
		boolean banked = false;
		for (int i = 1; i < path.size(); i++)
		{
			PathStep from = path.get(i - 1);
			PathStep to = path.get(i);

			// The tile where the path first enters the banked state is the bank it withdraws at —
			// but only worth a step when the route actually needs the bank for a method's item.
			if (!banked && to.isBankVisited())
			{
				banked = true;
				if (route.isViaBank())
				{
					steps.add(new Step(walkText(walk, "the bank"), legStart, i, walkTicks(walk)));
					walk = 0;
					legStart = i;
					steps.add(new Step("Withdraw item for: " + joinLabels(route.getBankMethods()), i, i,
						BANK_WITHDRAW_TICKS));
				}
			}

			if (nextMethod < methodEdges.size() && methodEdges.get(nextMethod) == i)
			{
				flushWalk(steps, walk, legStart, i - 1);
				walk = 0;
				steps.add(new Step("Use " + route.getMethods().get(nextMethod).label(), i - 1, i,
					route.getMethodDurations().get(nextMethod), true));
				nextMethod++;
				legStart = i;
				continue;
			}

			Transport climb = findClimb(plugin, from, to);
			if (climb != null)
			{
				flushWalk(steps, walk, legStart, i - 1);
				walk = 0;
				steps.add(new Step(climbText(climb), i - 1, i, Math.max(1, climb.getDuration())));
				legStart = i;
			}
			else
			{
				// Plain walking, or a small connector (door, shortcut, dungeon entrance). Cap the
				// contribution so an unrecognised transport edge can never inflate the leg with its
				// coordinate distance.
				int distance = WorldPointUtil.distanceBetween(from.getPackedPosition(), to.getPackedPosition());
				walk += Math.max(1, Math.min(distance, 10));
			}
		}
		if (walk > 0)
		{
			steps.add(new Step(walkText(walk, "the destination"), legStart, path.size() - 1, walkTicks(walk)));
		}
		return steps;
	}

	/**
	 * Game ticks to cover a walking leg, assuming the player runs (2 tiles per tick).
	 */
	private static int walkTicks(int tiles)
	{
		return (tiles + 1) / 2;
	}

	private static Transport findClimb(ShortestPathPlugin plugin, PathStep from, PathStep to)
	{
		Set<Transport> candidates = plugin.transportsForEdge(from, to);
		for (Transport transport : candidates)
		{
			if (climbText(transport) != null)
			{
				return transport;
			}
		}
		return null;
	}

	private static void flushWalk(List<Step> steps, int walk, int legStart, int endIndex)
	{
		if (walk <= 0)
		{
			return;
		}
		// No coordinates: they mean nothing to the player. The leg's endpoint is marked in the
		// world instead (section marker / destination pulse), and the next step names what's there.
		steps.add(new Step("Walk (" + walk + ")", legStart, endIndex, walkTicks(walk)));
	}

	private static String walkText(int tiles, String target)
	{
		return "Walk to " + target + " (" + tiles + ")";
	}

	private static String joinLabels(Set<TeleportMethod> methods)
	{
		StringBuilder sb = new StringBuilder();
		for (TeleportMethod method : methods)
		{
			if (sb.length() > 0)
			{
				sb.append(", ");
			}
			sb.append(method.label());
		}
		return sb.toString();
	}

	/**
	 * The hint text for a climb transport (stairs, ladders, trapdoor climbs): its menu text with the
	 * trailing object id stripped, e.g. "Climb-up Staircase 16671" -> "Climb-up Staircase". Null for
	 * transports that aren't climbs.
	 */
	static String climbText(Transport transport)
	{
		String objectInfo = transport.getObjectInfo();
		if (objectInfo == null || !objectInfo.startsWith("Climb"))
		{
			return null;
		}
		int lastSpace = objectInfo.lastIndexOf(' ');
		if (lastSpace > 0 && objectInfo.substring(lastSpace + 1).chars().allMatch(Character::isDigit))
		{
			return objectInfo.substring(0, lastSpace);
		}
		return objectInfo;
	}
}
