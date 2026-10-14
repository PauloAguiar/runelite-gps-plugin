package gps;

import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.TransportType;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * "Teleport portals & nexus" was a blanket switch: on, every portal chamber portal was offered
 * whatever stood in the house (a player with an empty chamber was routed through a Trollheim
 * portal, 2026-10-11). The house scan now reads the built portals by object id: once scanned,
 * a portal row stays only when its portal was seen, a nexus keeps every row until its unlocks
 * can be read, and an unscanned house changes nothing.
 */
public class PohPortalScanTest
{
	private static final int LUMBRIDGE_TEAK = 13616;
	private static final String NOT_BUILT = "Not built in your house (House section)";

	@Test
	public void onlyTheScannedPortalsRemain()
	{
		PathfinderConfig config = catalog(Set.of(LUMBRIDGE_TEAK), false);
		assertEquals(MethodAvailability.AVAILABLE, availability(config, "Lumbridge Portal"));
		assertEquals(MethodAvailability.LOCKED, availability(config, "Trollheim Portal"));
		assertEquals(NOT_BUILT, detail(config, "Trollheim Portal"));
		assertNull(detail(config, "Lumbridge Portal"));
	}

	@Test
	public void aNexusKeepsEveryPortalForNow()
	{
		PathfinderConfig config = catalog(Set.of(), true);
		assertEquals(MethodAvailability.AVAILABLE, availability(config, "Lumbridge Portal"));
		assertEquals(MethodAvailability.AVAILABLE, availability(config, "Trollheim Portal"));
	}

	@Test
	public void anUnscannedHouseChangesNothing()
	{
		PathfinderConfig config = catalog(null, false);
		assertEquals(MethodAvailability.AVAILABLE, availability(config, "Trollheim Portal"));
	}

	@Test
	public void theScannerKnowsEveryPortalRowsObject()
	{
		Map<Integer, String> names = catalog(null, false).getPohPortalNames();
		assertEquals("Trollheim Portal", names.get(60790));
		assertEquals("Lumbridge Portal", names.get(LUMBRIDGE_TEAK));
		assertEquals(127, names.size());
	}

	private static MethodAvailability availability(PathfinderConfig config, String portal)
	{
		return config.getMethodAvailability().get(method(config, portal));
	}

	private static String detail(PathfinderConfig config, String portal)
	{
		return config.getMethodAvailabilityDetail().get(method(config, portal));
	}

	private static TeleportMethod method(PathfinderConfig config, String portal)
	{
		for (TeleportMethod method : config.getMethodAvailability().keySet())
		{
			if (TransportType.TELEPORTATION_PORTAL_POH.equals(method.getType()) && portal.equals(method.getDisplayInfo()))
			{
				return method;
			}
		}
		throw new AssertionError("no catalog entry for " + portal);
	}

	/** The planning catalog of a house whose scan found the given portals (null: never scanned). */
	private static PathfinderConfig catalog(Set<Integer> builtPortals, boolean nexus)
	{
		final Thread clientThread = Thread.currentThread();
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getGameState":
						return GameState.LOGGED_IN;
					case "getClientThread":
						return clientThread;
					case "getBoostedSkillLevel":
						return 99;
					default:
						return HybridPageFillTest.defaultValue(method.getReturnType());
				}
			});
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class, Mockito.withSettings().stubOnly());
		Mockito.when(cfg.calculationCutoff()).thenReturn(120);
		Mockito.when(cfg.usePoh()).thenReturn(true);
		Mockito.when(cfg.useTeleportationPortalsPoh()).thenReturn(true);
		PathfinderConfig base = new TestPathfinderConfig(client, cfg);
		base.setPohPortalScan(builtPortals, nexus);
		PathfinderConfig config = base.copyForPlanning();
		config.refresh();
		return config;
	}
}
