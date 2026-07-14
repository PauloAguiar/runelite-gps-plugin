package gps.pathfinder;

import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;
import gps.ShortestPathConfig;
import gps.WorldPointUtil;
import gps.transport.Transport;
import gps.transport.TransportType;

/**
 * Farmable spirit trees (Port Sarim, Etceteria, Brimhaven, Hosidius, Farming Guild). Smart off:
 * all are assumed available (catalog is the only gate). Smart on: only the trees the travel menu
 * confirmed are planted route, and until the menu is seen none do. The confirmed set lives on the
 * main config; planning copies (the search engine) must read it through their source, or planted
 * trees would never route at all (the bug this feature also fixed).
 */
@RunWith(MockitoJUnitRunner.class)
public class SpiritTreeSmartModeTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	@Before
	public void before()
	{
		when(config.calculationCutoff()).thenReturn(30);
		when(config.useSpiritTrees()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
	}

	private static boolean isPortSarimTile(int packed)
	{
		return "Port Sarim".equals(PathfinderConfig.getPlantedSpiritTreeName(
			WorldPointUtil.unpackWorldX(packed), WorldPointUtil.unpackWorldY(packed)));
	}

	/** Whether any spirit-tree transport touching the Port Sarim patch is usable in the engine. */
	private static boolean portSarimTreeRoutes(PathfinderConfig cfg)
	{
		for (int origin : cfg.getTransportsPacked(false).keys())
		{
			for (Transport transport : cfg.getTransportsPacked(false).get(origin))
			{
				if (TransportType.SPIRIT_TREE.equals(transport.getType())
					&& (isPortSarimTile(origin) || isPortSarimTile(transport.getDestination())))
				{
					return true;
				}
			}
		}
		return false;
	}

	private PathfinderConfig search(boolean smart, Set<String> availableOnSource)
	{
		when(config.spiritTreeSmartMode()).thenReturn(smart);
		TestPathfinderConfig main = new TestPathfinderConfig(client, config);
		main.availableSpiritTrees = availableOnSource;
		PathfinderConfig planning = main.copyForPlanning();
		planning.refresh();
		return planning;
	}

	@Test
	public void smartOffAssumesAllFarmableTrees()
	{
		// Off = assume every farmable tree is grown; routing doesn't depend on the detected set.
		assertTrue("with smart tracking off, farmable trees are assumed available",
			portSarimTreeRoutes(search(false, null)));
	}

	@Test
	public void smartOnButUnsyncedIsConservative()
	{
		assertFalse("before the travel menu is seen, farmable trees must not be assumed",
			portSarimTreeRoutes(search(true, null)));
	}

	@Test
	public void smartOnAndPlantedRoutesThroughTheSource()
	{
		// The planted set is on the MAIN config; the planning copy must see it via its source.
		assertTrue("a planted, detected tree must route in the search engine",
			portSarimTreeRoutes(search(true, new HashSet<>(Set.of("Port Sarim")))));
	}

	@Test
	public void smartOnButNotPlantedStaysBlocked()
	{
		assertFalse("a farmable location the player has NOT planted must not route",
			portSarimTreeRoutes(search(true, new HashSet<>(Set.of("Etceteria")))));
	}
}
