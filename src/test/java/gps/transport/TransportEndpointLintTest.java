package gps.transport;

import gps.ShortestPathConfig;
import gps.TeleportationItem;
import gps.WorldPointUtil;
import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Plan step N15 (data lint): every transport endpoint must be a tile the player can stand on.
 * An origin is standable when it is unblocked, or blocked with an unblocked cardinal neighbour
 * (the forward rule that lets a path step onto a fairy ring platform); a destination when it is
 * unblocked, or blocked with a step-off neighbour (any unblocked cardinal, or a diagonal with
 * both flanking cardinals open: the rule the distance field mirrors). An endpoint that fails
 * both is a row the search can never use: a typo, a stale object position, or a cache update
 * that moved a wall. Sea legs, boat tiles and the instance template band are out of scope here.
 */
@RunWith(MockitoJUnitRunner.class)
public class TransportEndpointLintTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	private static final int INSTANCE_MIN_Y = 4160;
	private static final int INSTANCE_MAX_Y = 8000;

	/**
	 * The known offenders, one per line in the audit's own wording, so a NEW one fails by name
	 * and a listed one that is fixed must be removed (the list only shrinks honestly). Regenerate
	 * after a cache refresh with -Dgps.writeExpectedEndpoints=true.
	 */
	private static final String EXPECTED = "src/test/resources/expected-unstandable-endpoints.tsv";

	private static java.util.Set<String> loadExpected() throws java.io.IOException
	{
		java.nio.file.Path path = java.nio.file.Paths.get(EXPECTED);
		if (!java.nio.file.Files.exists(path))
		{
			return null;
		}
		java.util.Set<String> expected = new java.util.HashSet<>();
		for (String line : java.nio.file.Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8))
		{
			if (!line.isEmpty() && !line.startsWith("#"))
			{
				expected.add(line);
			}
		}
		return expected;
	}

	private static void writeExpected(List<String> offenders) throws java.io.IOException
	{
		List<String> lines = new ArrayList<>();
		lines.add("# Transport endpoints known not to be standable (TransportEndpointLintTest): blocked tiles with no");
		lines.add("# step-off neighbour on the shipped collision map. Generated with -Dgps.writeExpectedEndpoints=true;");
		lines.add("# fix rows or the map to shrink it, never add by hand. One audit line per endpoint.");
		lines.addAll(new java.util.TreeSet<>(offenders));
		java.nio.file.Files.write(java.nio.file.Paths.get(EXPECTED), lines, java.nio.charset.StandardCharsets.UTF_8);
	}

	@Test
	public void everyTransportEndpointIsStandable() throws java.io.IOException
	{
		when(config.calculationCutoff()).thenReturn(120);
		lenient().when(config.currencyThreshold()).thenReturn(10000000);
		lenient().when(config.useTeleportationItems()).thenReturn(TeleportationItem.ALL);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(i -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		PathfinderConfig pathConfig = new TestPathfinderConfig(client, config, QuestState.FINISHED, true, true);
		pathConfig.refresh();
		CollisionMap map = pathConfig.getMap();

		List<String> offenders = new ArrayList<>();
		int checked = 0;
		for (Transport transport : pathConfig.getAllTransports())
		{
			if (transport.getType() == TransportType.SAILING)
			{
				continue;
			}
			int origin = transport.getOrigin();
			int destination = transport.getDestination();
			if (origin != Transport.UNDEFINED_ORIGIN && !inBand(origin) && !gps.SailingSea.isSailable(origin))
			{
				checked++;
				if (!standableOrigin(map, origin))
				{
					offenders.add("origin " + describe(transport, origin));
				}
			}
			if (destination != WorldPointUtil.UNDEFINED && !inBand(destination) && !gps.SailingSea.isSailable(destination))
			{
				checked++;
				if (!standableLanding(map, destination))
				{
					offenders.add("destination " + describe(transport, destination));
				}
			}
		}
		System.out.println("ENDPOINTLINT checked=" + checked + " offenders=" + offenders.size());
		assertTrue("premise: thousands of endpoints", checked > 5000);
		if (Boolean.getBoolean("gps.writeExpectedEndpoints"))
		{
			writeExpected(offenders);
		}
		java.util.Set<String> expected = loadExpected();
		assertTrue("the expected-endpoints list is missing (" + EXPECTED + "): generate it with"
			+ " -Dgps.writeExpectedEndpoints=true and check it in", expected != null);
		java.util.Set<String> offenderSet = new java.util.HashSet<>(offenders);
		List<String> fresh = new ArrayList<>();
		for (String offender : offenders)
		{
			if (!expected.contains(offender))
			{
				fresh.add(offender);
			}
		}
		List<String> stale = new ArrayList<>();
		for (String entry : expected)
		{
			if (!offenderSet.contains(entry))
			{
				stale.add(entry);
			}
		}
		java.util.Collections.sort(stale);
		assertTrue(fresh.size() + " NEW transport endpoints are not standable (unblocked, or blocked with a step-off"
			+ " neighbour); " + offenders.size() + " in total, " + expected.size() + " expected. A data regression to fix,"
			+ " or a cache refresh: then regenerate with -Dgps.writeExpectedEndpoints=true. First:\n  "
			+ String.join("\n  ", fresh.subList(0, Math.min(60, fresh.size()))), fresh.isEmpty());
		assertTrue(stale.size() + " listed endpoints are standable now: remove them from " + EXPECTED
			+ " (regenerate with -Dgps.writeExpectedEndpoints=true). First:\n  "
			+ String.join("\n  ", stale.subList(0, Math.min(40, stale.size()))), stale.isEmpty());
	}

	private static boolean inBand(int packed)
	{
		int y = WorldPointUtil.unpackWorldY(packed);
		return y >= INSTANCE_MIN_Y && y <= INSTANCE_MAX_Y;
	}

	private static boolean standableOrigin(CollisionMap map, int packed)
	{
		int x = WorldPointUtil.unpackWorldX(packed);
		int y = WorldPointUtil.unpackWorldY(packed);
		int plane = WorldPointUtil.unpackWorldPlane(packed);
		if (!map.isBlocked(x, y, plane))
		{
			return true;
		}
		return !map.isBlocked(x - 1, y, plane) || !map.isBlocked(x + 1, y, plane)
			|| !map.isBlocked(x, y - 1, plane) || !map.isBlocked(x, y + 1, plane);
	}

	private static boolean standableLanding(CollisionMap map, int packed)
	{
		int x = WorldPointUtil.unpackWorldX(packed);
		int y = WorldPointUtil.unpackWorldY(packed);
		int plane = WorldPointUtil.unpackWorldPlane(packed);
		if (!map.isBlocked(x, y, plane))
		{
			return true;
		}
		boolean w = !map.isBlocked(x - 1, y, plane);
		boolean e = !map.isBlocked(x + 1, y, plane);
		boolean s = !map.isBlocked(x, y - 1, plane);
		boolean n = !map.isBlocked(x, y + 1, plane);
		return w || e || s || n
			|| (!map.isBlocked(x - 1, y - 1, plane) && w && s) || (!map.isBlocked(x + 1, y - 1, plane) && e && s)
			|| (!map.isBlocked(x - 1, y + 1, plane) && w && n) || (!map.isBlocked(x + 1, y + 1, plane) && e && n);
	}

	private static String describe(Transport transport, int packed)
	{
		return WorldPointUtil.unpackWorldX(packed) + "," + WorldPointUtil.unpackWorldY(packed) + ","
			+ WorldPointUtil.unpackWorldPlane(packed) + " " + transport.getType()
			+ (transport.getDisplayInfo() != null ? " " + transport.getDisplayInfo() : "")
			+ (transport.getObjectInfo() != null ? " [" + transport.getObjectInfo() + "]" : "");
	}
}
