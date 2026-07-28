package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.Transport;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * DATA INVARIANT: every searchable destination must be somewhere a player can actually get to.
 *
 * A pin on an unreachable tile is worse than a missing pin — the search explores the whole map,
 * gives up, and drops the player at "the closest reachable point", which is the pathology that
 * produced the Kingdom Divided report. Rather than pathfind to thousands of pins (minutes), this
 * floods ONCE from Lumbridge across walking edges plus every transport, then asserts each
 * destination's {@link Destinations#walkableTargets} set intersects that reachable region — the
 * same expansion the real search performs, so a pin on a fence still passes if its surroundings
 * are reachable.
 *
 * Failures are reported in bulk with coordinates, so a bad import is one glance to triage.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class DestinationsReachableTest
{
	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	/** Instanced template bands: pins there are scenery copies, not places (POH, raids, cutscenes). */
	private static final int INSTANCE_MIN_Y = 4160;
	private static final int INSTANCE_MAX_Y = 8000;

	private static Set<Integer> reachable;
	private static CollisionMap map;

	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	@BeforeClass
	public static void beforeClass()
	{
		reachable = null;
	}

	private void flood()
	{
		if (reachable != null)
		{
			return;
		}
		when(config.calculationCutoff()).thenReturn(120);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		map = planning.getMap();

		// Transport edges by origin, so the flood can jump the way a route can. Bank-visited
		// availability is the superset (it also offers banked-item teleports).
		PrimitiveIntHashMap<Transport[]> transports = planning.getTransportsPacked(true);
		Set<Integer> seen = new HashSet<>(1 << 20);
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		seen.add(LUMBRIDGE);
		queue.add(LUMBRIDGE);
		while (!queue.isEmpty())
		{
			int at = queue.poll();
			int x = WorldPointUtil.unpackWorldX(at);
			int y = WorldPointUtil.unpackWorldY(at);
			int plane = WorldPointUtil.unpackWorldPlane(at);
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					if (dx == 0 && dy == 0)
					{
						continue;
					}
					int next = WorldPointUtil.packWorldPoint(x + dx, y + dy, plane);
					if (!seen.contains(next) && map.canStep(at, next))
					{
						seen.add(next);
						queue.add(next);
					}
				}
			}
			Transport[] fromHere = transports.get(at);
			if (fromHere != null)
			{
				for (Transport transport : fromHere)
				{
					int destination = transport.getDestination();
					if (destination != WorldPointUtil.UNDEFINED && seen.add(destination))
					{
						queue.add(destination);
					}
				}
			}
		}
		reachable = seen;
	}

	/**
	 * Curated pins whose ENTRANCE has no transport row yet — real data gaps, not bad pins, each
	 * needing the cave/dungeon entrance mapped (audit field capture). Shrink this list; never
	 * add to it. Found by this test on 2026-07-27.
	 */
	private static final Set<String> KNOWN_GAPS = Set.of(
		"Ferox Enclave Dungeon", "Mogre Camp", "Ruins of Mokhaiotl", "Kendal's Lair");

	/** Known-unreachable IMPORTED pins when this test was written; drive it down, never up. */
	private static final int RATCHET = 1166;

	private boolean anyReachable(int packed)
	{
		for (int target : Destinations.walkableTargets(map, packed))
		{
			if (reachable.contains(target))
			{
				return true;
			}
		}
		return false;
	}

	/** The hand-curated pins are ours to author, so the bar is zero unreachable. */
	@Test
	public void everyCuratedDestinationIsReachable() throws java.io.IOException
	{
		flood();
		List<String> unreachable = new ArrayList<>();
		int checked = 0;
		try (java.io.InputStream in =
			ShortestPathPlugin.class.getResourceAsStream("/destinations-curated.tsv"))
		{
			java.util.Scanner scanner = new java.util.Scanner(in, "UTF-8");
			scanner.nextLine(); // header
			while (scanner.hasNextLine())
			{
				String[] fields = scanner.nextLine().split("\t");
				if (fields.length < 5 || fields[0].startsWith("#"))
				{
					continue;
				}
				int packed = WorldPointUtil.packWorldPoint(Integer.parseInt(fields[2].trim()),
					Integer.parseInt(fields[3].trim()), Integer.parseInt(fields[4].trim()));
				int py = WorldPointUtil.unpackWorldY(packed);
				if (py >= INSTANCE_MIN_Y && py <= INSTANCE_MAX_Y || KNOWN_GAPS.contains(fields[1]))
				{
					continue;
				}
				checked++;
				if (!anyReachable(packed))
				{
					unreachable.add(fields[1] + " @" + fields[2] + "," + fields[3] + "," + fields[4]);
				}
			}
		}
		assertTrue("expected the curated pins to load", checked > 20);
		assertTrue("curated destinations must be reachable, these are not:\n  "
			+ String.join("\n  ", unreachable), unreachable.isEmpty());
	}

	/**
	 * The bulk imports (wiki + amenity dumps) carry known debt — 1166 of 5598 when written,
	 * largely upper-floor altars whose staircases have no transport row. A RATCHET rather than a
	 * hard zero, so imports can't quietly add unreachable pins while the backlog is worked down.
	 */
	@Test
	public void importedDestinationsDoNotRegress()
	{
		flood();
		List<String> unreachable = new ArrayList<>();
		int checked = 0;
		for (Destinations.Entry entry : Destinations.resourceEntries())
		{
			int y = WorldPointUtil.unpackWorldY(entry.packedPosition);
			if (y >= INSTANCE_MIN_Y && y <= INSTANCE_MAX_Y)
			{
				continue; // instance template band: not a real place
			}
			checked++;
			if (!anyReachable(entry.packedPosition))
			{
				unreachable.add(entry.category + " / " + entry.name + " @"
					+ WorldPointUtil.unpackWorldX(entry.packedPosition) + ","
					+ WorldPointUtil.unpackWorldY(entry.packedPosition) + ","
					+ WorldPointUtil.unpackWorldPlane(entry.packedPosition));
			}
		}
		assertTrue("checked at least a few hundred destinations", checked > 200);
		assertTrue(unreachable.size() + " of " + checked + " imported destinations are unreachable"
			+ " (ratchet: " + RATCHET + "). New unreachable pins are not acceptable; the existing"
			+ " backlog is being worked down. First offenders:\n  "
			+ String.join("\n  ", unreachable.subList(0, Math.min(40, unreachable.size()))),
			unreachable.size() <= RATCHET);
	}
}
