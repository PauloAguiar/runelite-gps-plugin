package gps;

import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.Transport;
import gps.transport.TransportType;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * "Teleport portals & nexus" was a blanket switch: on, every portal chamber portal was offered
 * whatever stood in the house (a player with an empty chamber was routed through a Trollheim
 * portal, 2026-10-11). The house scan now reads the built portals by object id: once scanned,
 * a portal row stays only when its portal was seen, and an unscanned house changes nothing.
 * <p>
 * The portal nexus keeps its unlocked teleports in 45 slot varbits (POH_NEXUS_TELE_1..45,
 * each holding a teleport's key from the nexus enum: Trollheim is 32), with the left-click
 * teleport in POH_NEXUS_LEFT_CLICK (client script 2671, 2026-10-13). Its rows are gated on
 * those, and on a nexus standing in the house once it has been scanned.
 */
public class PohPortalScanTest
{
	private static final int LUMBRIDGE_TEAK = 13616;
	private static final int TROLLHEIM = 32;
	private static final String NOT_BUILT = "Not built in your house (House section)";
	private static final String NO_NEXUS = "No portal nexus in your house (House section)";

	@Test
	public void onlyTheScannedPortalsRemain()
	{
		PathfinderConfig config = catalog(Set.of(LUMBRIDGE_TEAK), false, Map.of());
		assertEquals(MethodAvailability.AVAILABLE, availability(config, "Lumbridge Portal"));
		assertEquals(MethodAvailability.LOCKED, availability(config, "Trollheim Portal"));
		assertEquals(NOT_BUILT, detail(config, "Trollheim Portal"));
		assertNull(detail(config, "Lumbridge Portal"));
	}

	@Test
	public void anUnscannedHouseChangesNothing()
	{
		PathfinderConfig config = catalog(null, false, Map.of());
		assertEquals(MethodAvailability.AVAILABLE, availability(config, "Trollheim Portal"));
	}

	@Test
	public void theNexusOffersTheTeleportsInItsSlots()
	{
		PathfinderConfig config = catalog(Set.of(), true, Map.of(VarbitID.POH_NEXUS_TELE_7, TROLLHEIM));
		assertEquals(MethodAvailability.AVAILABLE, availability(config, "Portal Nexus: Trollheim"));
		assertEquals(MethodAvailability.LOCKED, availability(config, "Portal Nexus: Varrock"));
		assertEquals("the chamber has no Trollheim portal", MethodAvailability.LOCKED, availability(config, "Trollheim Portal"));
		assertEquals(3, duration(config, "Portal Nexus: Trollheim"));
	}

	@Test
	public void theLeftClickTeleportIsQuicker()
	{
		PathfinderConfig config = catalog(null, false,
			Map.of(VarbitID.POH_NEXUS_TELE_45, TROLLHEIM, VarbitID.POH_NEXUS_LEFT_CLICK, TROLLHEIM));
		assertEquals("unscanned: the varbits alone decide", MethodAvailability.AVAILABLE, availability(config, "Portal Nexus: Trollheim"));
		assertEquals(1, duration(config, "Portal Nexus: Trollheim"));
	}

	@Test
	public void aScannedHouseWithoutANexusLocksItsRows()
	{
		PathfinderConfig config = catalog(Set.of(), false, Map.of(VarbitID.POH_NEXUS_TELE_1, TROLLHEIM));
		assertEquals(MethodAvailability.LOCKED, availability(config, "Portal Nexus: Trollheim"));
		assertEquals(NO_NEXUS, detail(config, "Portal Nexus: Trollheim"));
	}

	@Test
	public void theScannerKnowsEveryPortalRowsObject()
	{
		Map<Integer, String> names = catalog(null, false, Map.of()).getPohPortalNames();
		assertEquals("Trollheim Portal", names.get(60790));
		assertEquals("Lumbridge Portal", names.get(LUMBRIDGE_TEAK));
		assertEquals("the nexus is not a chamber portal", 127, names.size());
	}

	private static MethodAvailability availability(PathfinderConfig config, String portal)
	{
		return config.getMethodAvailability().get(method(config, portal));
	}

	private static String detail(PathfinderConfig config, String portal)
	{
		return config.getMethodAvailabilityDetail().get(method(config, portal));
	}

	/** The shortest usable row of the method, over every usable teleport and local transport. */
	private static int duration(PathfinderConfig config, String portal)
	{
		int best = Integer.MAX_VALUE;
		for (Transport transport : config.getUsableTeleports(false))
		{
			if (portal.equals(transport.getDisplayInfo()))
			{
				best = Math.min(best, transport.getDuration());
			}
		}
		for (Transport transport : config.getAllTransports())
		{
			if (portal.equals(transport.getDisplayInfo()) && !config.varbitChecks(transport))
			{
				best = Math.min(best, transport.getDuration());
			}
		}
		return best;
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

	/** The planning catalog of a house whose scan found the given portals (null: never scanned), with the given varbits set. */
	private static PathfinderConfig catalog(Set<Integer> builtPortals, boolean nexus, Map<Integer, Integer> varbits)
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
					case "getVarbitValue":
						return varbits.getOrDefault((int) args[0], 0);
					default:
						return HybridPageFillTest.defaultValue(method.getReturnType());
				}
			});
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class, Mockito.withSettings().stubOnly());
		Mockito.when(cfg.calculationCutoff()).thenReturn(120);
		Mockito.when(cfg.usePoh()).thenReturn(true);
		Mockito.when(cfg.useTeleportationPortalsPoh()).thenReturn(true);
		PathfinderConfig base = new TestPathfinderConfig(client, cfg, net.runelite.api.QuestState.FINISHED, false, true);
		base.setPohPortalScan(builtPortals, nexus);
		PathfinderConfig config = base.copyForPlanning();
		config.refresh();
		return config;
	}
}
