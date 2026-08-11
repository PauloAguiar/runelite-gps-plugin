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
import static org.mockito.Mockito.mock;
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
		when(config.useTeleportationItems()).thenReturn(gps.TeleportationItem.ALL);
		when(config.useSailing()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		TestPathfinderConfig testConfig = new TestPathfinderConfig(client, config);
		// Universal bank: reachable means "a player CAN get there" — any obtainable item is
		// assumed owned (dashboard's BANK-preset pattern), else item-gated teleports like the
		// Sailors' amulet would wrongly strand their destinations (Port Roberts).
		net.runelite.api.ItemContainer universalBank = mock(net.runelite.api.ItemContainer.class);
		net.runelite.api.Item[] everyItem = new net.runelite.api.Item[40000];
		for (int i = 0; i < everyItem.length; i++)
		{
			everyItem[i] = new net.runelite.api.Item(i, 1000);
		}
		when(universalBank.getItems()).thenReturn(everyItem);
		testConfig.bank = universalBank;
		PathfinderConfig planning = testConfig.copyForPlanning();
		planning.refresh();
		map = planning.getMap();

		// Transport edges by origin, so the flood can jump the way a route can. Bank-visited
		// availability is the superset (it also offers banked-item teleports).
		PrimitiveIntHashMap<Transport[]> transports = planning.getTransportsPacked(true);
		Set<Integer> seen = new HashSet<>(1 << 20);
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		seen.add(LUMBRIDGE);
		queue.add(LUMBRIDGE);
		// ORIGIN-LESS teleports (jewellery, tablets, spells) are usable ANYWHERE, so their
		// landing tiles are reachable outright. Keyed under UNDEFINED_ORIGIN rather than a
		// tile, they were invisible to a walk-the-graph flood — which made every
		// teleport-only destination, Port Roberts included, look sealed.
		Transport[] anywhere = transports.get(Transport.UNDEFINED_ORIGIN);
		if (anywhere != null)
		{
			for (Transport transport : anywhere)
			{
				if (transport.getDestination() != WorldPointUtil.UNDEFINED
					&& seen.add(transport.getDestination()))
				{
					queue.add(transport.getDestination());
				}
			}
		}
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
					// Transport origins may be collision-blocked (a fairy ring blocks its
					// own tile); the engine admits them via isTransportOrigin, so the flood
					// must too — without this, ALL ring-only content (Zanaris) reads sealed.
					// ...and symmetrically OFF one: a transport delivers you ONTO its
					// blocked destination ring; without the exit clause Zanaris's hub ring
					// was a trap node and the whole realm stayed sealed.
					if (!seen.contains(next)
						&& (map.canStep(at, next) || transports.get(next) != null
							|| (transports.get(at) != null && !map.isBlocked(x + dx, y + dy, plane))))
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

	/**
	 * Known-unreachable IMPORTED pins; drive it down, never up. Re-based 647 -> 733 on
	 * 2026-07-29 when the collision substrate fix sealed the sailable ocean (the buoy-network
	 * flood, exactly): 86 pins had been reachable only by WALKING ON THE SEA. The honest
	 * backlog shrinks via the snap pass and island landings.
	 */
	// 733 -> 747 with the 2026-07-30 cache refresh (rev 2644): a world update legitimately
	// moves this number; the ratchet exists to catch OUR data regressions between refreshes.
	private static final int RATCHET = 745;

	/**
	 * Real content living inside the instance template band, enforced by the invariant like
	 * anywhere else. The band is MOSTLY scenery copies (POH layouts, cutscenes), but a few
	 * genuine places are built there — the blanket skip hid a fully sealed Motherlode Mine
	 * pin. Transport-proximity was tried and over-matched (POH portal rows qualified whole
	 * template houses); an explicit box per known place is honest and auditable.
	 */
	private static final int[][] BAND_CONTENT_BOXES = {
		{3700, 5620, 3780, 5710}, // Motherlode Mine
		{2360, 4340, 2500, 4480}, // Zanaris
	};

	private boolean bandContent(int packed)
	{
		int x = WorldPointUtil.unpackWorldX(packed);
		int y = WorldPointUtil.unpackWorldY(packed);
		for (int[] box : BAND_CONTENT_BOXES)
		{
			if (x >= box[0] && y >= box[1] && x <= box[2] && y <= box[3])
			{
				return true;
			}
		}
		return false;
	}

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
			if (y >= INSTANCE_MIN_Y && y <= INSTANCE_MAX_Y && !bandContent(entry.packedPosition))
			{
				continue; // template junk: no transports anywhere near
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
		// Band-content offenders get named first: they are enforced by an explicit box, so
		// a failure here is either a data gap in a REAL band place or a wrong box.
		List<String> bandOffenders = new ArrayList<>();
		for (String entry : unreachable)
		{
			String[] at = entry.substring(entry.lastIndexOf('@') + 1).split(",");
			int y = Integer.parseInt(at[1]);
			if (y >= INSTANCE_MIN_Y && y <= INSTANCE_MAX_Y)
			{
				bandOffenders.add(entry);
			}
		}
		assertTrue("BAND-CONTENT offenders (" + bandOffenders.size() + "): "
			+ String.join("; ", bandOffenders) + " ||| "
			+ unreachable.size() + " of " + checked + " imported destinations are unreachable"
			+ " (ratchet: " + RATCHET + "). New unreachable pins are not acceptable; the existing"
			+ " backlog is being worked down. First offenders:\n  "
			+ String.join("\n  ", unreachable.subList(0, Math.min(40, unreachable.size()))),
			unreachable.size() <= RATCHET);
	}
}
