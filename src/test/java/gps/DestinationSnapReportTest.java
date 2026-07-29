package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.Transport;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Triage for the unreachable-destination backlog (enable with -DsnapReport=true).
 *
 * Splits the failures into the two populations that need completely different fixes:
 *   SNAPPABLE — a reachable tile sits within a few tiles, so the pin is merely on the wrong
 *   tile (amenity dumps pin the OBJECT, not the tile you stand on). Fixable programmatically
 *   by rewriting the coordinate.
 *   SEALED — nothing reachable anywhere near, so the area genuinely has no mapped way in
 *   (missing staircase, door or cave entrance). Needs data, not a coordinate nudge.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class DestinationSnapReportTest
{
	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int INSTANCE_MIN_Y = 4160;
	private static final int INSTANCE_MAX_Y = 8000;
	private static final int MAX_SNAP = 12;

	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	@Test
	public void report()
	{
		Assume.assumeTrue(Boolean.getBoolean("snapReport"));
		when(config.calculationCutoff()).thenReturn(120);
		when(config.useTeleportationItems()).thenReturn(gps.TeleportationItem.ALL);
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
		CollisionMap map = planning.getMap();
		PrimitiveIntHashMap<Transport[]> transports = planning.getTransportsPacked(true);

		Set<Integer> reachable = new HashSet<>(1 << 20);
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		reachable.add(LUMBRIDGE);
		queue.add(LUMBRIDGE);
		// ORIGIN-LESS teleports (jewellery, tablets, spells) work ANYWHERE, so their landing
		// tiles are reachable outright. Keyed under UNDEFINED_ORIGIN rather than a tile, they
		// were invisible to a walk-the-graph flood — which made every teleport-only place,
		// Port Roberts included, look sealed.
		Transport[] anywhere = transports.get(Transport.UNDEFINED_ORIGIN);
		if (anywhere != null)
		{
			for (Transport transport : anywhere)
			{
				if (transport.getDestination() != WorldPointUtil.UNDEFINED
					&& reachable.add(transport.getDestination()))
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
					int next = WorldPointUtil.packWorldPoint(x + dx, y + dy, plane);
					if ((dx != 0 || dy != 0) && !reachable.contains(next) && map.canStep(at, next))
					{
						reachable.add(next);
						queue.add(next);
					}
				}
			}
			Transport[] from = transports.get(at);
			if (from != null)
			{
				for (Transport t : from)
				{
					if (t.getDestination() != WorldPointUtil.UNDEFINED && reachable.add(t.getDestination()))
					{
						queue.add(t.getDestination());
					}
				}
			}
		}

		int[] snapBuckets = new int[MAX_SNAP + 1];
		int sealed = 0;
		int total = 0;
		Map<String, Integer> sealedByArea = new TreeMap<>();
		Map<String, Integer> sealedByCategory = new TreeMap<>();
		for (Destinations.Entry entry : Destinations.resourceEntries())
		{
			int x = WorldPointUtil.unpackWorldX(entry.packedPosition);
			int y = WorldPointUtil.unpackWorldY(entry.packedPosition);
			int plane = WorldPointUtil.unpackWorldPlane(entry.packedPosition);
			if (y >= INSTANCE_MIN_Y && y <= INSTANCE_MAX_Y)
			{
				continue;
			}
			boolean ok = false;
			for (int target : Destinations.walkableTargets(map, entry.packedPosition))
			{
				if (reachable.contains(target))
				{
					ok = true;
					break;
				}
			}
			if (ok)
			{
				continue;
			}
			total++;
			int found = -1;
			for (int radius = 1; radius <= MAX_SNAP && found < 0; radius++)
			{
				for (int dx = -radius; dx <= radius && found < 0; dx++)
				{
					for (int dy = -radius; dy <= radius && found < 0; dy++)
					{
						if (Math.max(Math.abs(dx), Math.abs(dy)) != radius)
						{
							continue;
						}
						if (reachable.contains(WorldPointUtil.packWorldPoint(x + dx, y + dy, plane)))
						{
							found = radius;
						}
					}
				}
			}
			if (found > 0)
			{
				snapBuckets[found]++;
			}
			else
			{
				sealed++;
				sealedByArea.merge((x / 64 * 64) + "," + (y / 64 * 64) + " p" + plane, 1, Integer::sum);
				sealedByCategory.merge(entry.category, 1, Integer::sum);
			}
		}

		System.out.println("UNREACHABLE TOTAL: " + total);
		int running = 0;
		for (int r = 1; r <= MAX_SNAP; r++)
		{
			running += snapBuckets[r];
			System.out.println("  snappable within " + r + " tiles: " + snapBuckets[r]
				+ "   (cumulative " + running + ")");
		}
		System.out.println("SEALED (no reachable tile within " + MAX_SNAP + "): " + sealed);
		System.out.println("--- sealed by category ---");
		sealedByCategory.entrySet().stream()
			.sorted((a, b) -> b.getValue() - a.getValue()).limit(15)
			.forEach(e -> System.out.println("  " + e.getValue() + "  " + e.getKey()));
		System.out.println("--- worst sealed areas (64x64 blocks) ---");
		sealedByArea.entrySet().stream()
			.sorted((a, b) -> b.getValue() - a.getValue()).limit(20)
			.forEach(e -> System.out.println("  " + e.getValue() + "  @" + e.getKey()));
	}
}
