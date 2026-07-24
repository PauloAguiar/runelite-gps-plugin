package gps.dev;

import gps.ShortestPathConfig;
import gps.WorldPointUtil;
import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import java.util.List;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Every machine-derived (~geometry) transport row in the Mount Karuulm dungeon must have
 * WALKABLE endpoints — a translated lane whose origin or landing is collision-blocked is a
 * dead edge at best and a wrong route at worst. Guards the capture lane expansion's output
 * and the retro-filled lanes alike.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class KaruulmDerivedRowsTest
{
	private static final int MIN_X = 1250;
	private static final int MAX_X = 1420;
	private static final int MIN_Y = 10150;
	private static final int MAX_Y = 10300;

	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	@Test
	public void derivedKaruulmRowsHaveWalkableEndpoints()
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		CollisionMap map = planning.getMap();

		List<MetaEdges.Entry> derived = MetaEdges.load().stream()
			.filter(e -> e.tags.contains("~geometry"))
			.filter(e -> inKaruulm(e.origin) || inKaruulm(e.destination))
			.collect(Collectors.toList());
		assertFalse("expected Karuulm ~geometry rows (lane fills + pipe)", derived.isEmpty());

		StringBuilder problems = new StringBuilder();
		for (MetaEdges.Entry entry : derived)
		{
			checkWalkable(map, entry.origin, "origin", entry, problems);
			checkWalkable(map, entry.destination, "destination", entry, problems);
		}
		assertTrue("blocked endpoints on derived rows:\n" + problems, problems.length() == 0);
	}

	private static boolean inKaruulm(int packed)
	{
		if (packed == WorldPointUtil.UNDEFINED)
		{
			return false;
		}
		int x = WorldPointUtil.unpackWorldX(packed);
		int y = WorldPointUtil.unpackWorldY(packed);
		return x >= MIN_X && x <= MAX_X && y >= MIN_Y && y <= MAX_Y;
	}

	private static void checkWalkable(CollisionMap map, int packed, String which,
		MetaEdges.Entry entry, StringBuilder problems)
	{
		if (packed == WorldPointUtil.UNDEFINED)
		{
			return;
		}
		if (map.isBlocked(WorldPointUtil.unpackWorldX(packed), WorldPointUtil.unpackWorldY(packed),
			WorldPointUtil.unpackWorldPlane(packed)))
		{
			problems.append(entry.menu).append(' ').append(which).append(' ')
				.append(WorldPointUtil.unpackWorldX(packed)).append(',')
				.append(WorldPointUtil.unpackWorldY(packed)).append(',')
				.append(WorldPointUtil.unpackWorldPlane(packed)).append('\n');
		}
	}
}
