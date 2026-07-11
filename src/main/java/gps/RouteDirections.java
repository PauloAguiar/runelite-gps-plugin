package gps;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.Getter;
import gps.pathfinder.PathStep;
import gps.transport.Transport;
import gps.transport.TransportType;

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
		// True for "Open <door>" steps: their edge gates progress until actually crossed —
		// straight-line proximity sees through the closed door.
		private final boolean door;
		// True for object-transport obstacles the player must click to cross (agility shortcuts,
		// stairs/ladders, ...). Like a closed door, the player can't click-walk PAST it, so the
		// path beyond it is drawn blocked until they use it. Doors carry {@link #door} instead.
		private final boolean obstacle;

		private Step(String text, int startIndex, int endIndex, int ticks)
		{
			this(text, startIndex, endIndex, ticks, false, false, false);
		}

		private Step(String text, int startIndex, int endIndex, int ticks, boolean transport)
		{
			this(text, startIndex, endIndex, ticks, transport, false, false);
		}

		private Step(String text, int startIndex, int endIndex, int ticks, boolean transport, boolean door)
		{
			this(text, startIndex, endIndex, ticks, transport, door, false);
		}

		private Step(String text, int startIndex, int endIndex, int ticks,
			boolean transport, boolean door, boolean obstacle)
		{
			this.text = text;
			this.startIndex = startIndex;
			this.endIndex = endIndex;
			this.ticks = ticks;
			this.transport = transport;
			this.door = door;
			this.obstacle = obstacle;
		}

		/** Whether the player must interact with something to cross this edge (door or shortcut). */
		boolean gatesWalk()
		{
			return door || obstacle;
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
		// When the target can't be reached, the route stops at the closest tile — the final leg walks
		// "as close as possible", not "to the destination", so the overlay doesn't imply arrival.
		boolean reaches = plugin.routeReachesTarget(route);

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
					steps.add(new Step(walkText("the bank"), legStart, i, walkTicks(walk)));
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
				steps.add(new Step(methodText(route.getMethods().get(nextMethod)), i - 1, i,
					route.getMethodDurations().get(nextMethod), true));
				nextMethod++;
				legStart = i;
				continue;
			}

			Transport object = findObjectTransport(plugin, from, to);
			ClosedDoors.Door door = object == null
				? ClosedDoors.doorBetween(from.getPackedPosition(), to.getPackedPosition())
				: null;
			if (object != null)
			{
				// Any transport with menu-style object info becomes its own step: climbs
				// ("Climb-up Staircase"), agility shortcuts ("Walk-across Log balance"),
				// mapped doors ("Open Door" — flagged so the door progress gate applies), ...
				flushWalk(steps, walk, legStart, i - 1);
				walk = 0;
				String text = objectText(object);
				boolean isDoor = text.startsWith("Open ");
				// Doors carry the door flag (closed-state gated); every other object transport
				// (climbs, agility shortcuts, tunnels) is an obstacle the player must click to
				// cross, so the path beyond it is blocked until used.
				steps.add(new Step(text, i - 1, i, Math.max(1, object.getDuration()),
					false, isDoor, !isDoor));
				legStart = i;
			}
			else if (door != null)
			{
				// A doorway splits the walking leg: walk up to the door, open it, walk on. Whether
				// it will actually be closed is live scene state the world overlay handles; as a
				// step it is a stable landmark either way.
				flushWalk(steps, walk, legStart, i - 1);
				walk = 0;
				steps.add(new Step("Open " + door.name, i - 1, i, 1, false, true));
				legStart = i;
			}
			else
			{
				// Plain walking, or a small connector (shortcut, dungeon entrance). Cap the
				// contribution so an unrecognised transport edge can never inflate the leg with its
				// coordinate distance.
				int distance = WorldPointUtil.distanceBetween(from.getPackedPosition(), to.getPackedPosition());
				walk += Math.max(1, Math.min(distance, 10));
			}
		}
		if (walk > 0)
		{
			steps.add(new Step(reaches ? walkText("the destination") : "Walk as close as possible (can't reach the target)",
				legStart, path.size() - 1, walkTicks(walk)));
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

	private static Transport findObjectTransport(ShortestPathPlugin plugin, PathStep from, PathStep to)
	{
		Set<Transport> candidates = plugin.transportsForEdge(from, to);
		for (Transport transport : candidates)
		{
			if (objectText(transport) != null)
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
		// No coordinates or tile counts: they mean nothing to the player. The leg's endpoint is
		// marked in the world instead (section marker / destination pulse), the next step names
		// what's there, and the time column already sizes the leg.
		steps.add(new Step("Walk", legStart, endIndex, walkTicks(walk)));
	}

	private static String walkText(String target)
	{
		return "Walk to " + target;
	}

	/**
	 * The instruction for a method step. Fairy rings get their own glyph and phrasing: the raw
	 * data label is a spaced dial code ("A I Q", chained hops "A I R - D L R"), which reads
	 * better compacted the way players write them ("AIQ", "AIR → DLR"); named destinations
	 * (ZANARIS) are shouted in the data and get title-cased. Everything else keeps "Use X".
	 */
	static String methodText(TeleportMethod method)
	{
		if (TransportType.FAIRY_RING.equals(method.getType()) && method.getDisplayInfo() != null)
		{
			// The mushroom renders via the JVM's system-font fallback (verified in-game on
			// Windows, monochrome); if other platforms report missing-glyph boxes, drop it.
			return "🍄 Fairy ring to " + fairyRingLabel(method.getDisplayInfo());
		}
		return "Use " + method.label();
	}

	private static String fairyRingLabel(String displayInfo)
	{
		StringBuilder sb = new StringBuilder();
		for (String hop : displayInfo.split(" - "))
		{
			if (sb.length() > 0)
			{
				sb.append(" → ");
			}
			String code = hop.replace(" ", "");
			if (code.length() > 3)
			{
				code = code.charAt(0) + code.substring(1).toLowerCase(Locale.ROOT);
			}
			sb.append(code);
		}
		return sb.toString();
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
	 * The menu-style instruction carried by an object transport's data ("Climb-up Staircase",
	 * "Walk-across Log balance", "Open Door"), with the trailing object id stripped. Null when
	 * the transport carries no object info.
	 */
	static String objectText(Transport transport)
	{
		String objectInfo = transport.getObjectInfo();
		if (objectInfo == null || objectInfo.isEmpty())
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
