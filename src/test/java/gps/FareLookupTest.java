package gps;

import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.Transport;
import java.lang.reflect.Proxy;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertTrue;

/**
 * Fares label edges from the UNFILTERED transport data, not availability — capture
 * 20260814-205518: a via-bank route's "Ship to Brimhaven" step lost its "— 30 gp fare"
 * because the coins sat in the bank, which had dropped the row from every availability
 * set exactly when the route was about to spend them.
 */
public class FareLookupTest
{
	/** Rimmington dock -> Brimhaven, Captain Barnaby, the capture's edge. */
	private static final int RIMMINGTON_DOCK = WorldPointUtil.packWorldPoint(2915, 3225, 0);
	private static final int BRIMHAVEN_DECK = WorldPointUtil.packWorldPoint(2775, 3234, 1);

	@Test
	public void bankedFareEdgeStillNamesItsCoins()
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (proxy, method, args) ->
			{
				if ("getGameState".equals(method.getName()))
				{
					return GameState.LOGIN_SCREEN;
				}
				return HybridPageFillTest.defaultValue(method.getReturnType());
			});
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class,
			Mockito.withSettings().stubOnly());
		PathfinderConfig config = new TestPathfinderConfig(client, cfg);

		// Deliberately NO refresh: availability is empty, exactly like a row filtered out
		// by possession — the edge lookup must not care.
		List<Transport> rows = config.transportsOnEdge(RIMMINGTON_DOCK, BRIMHAVEN_DECK);
		assertTrue("the Barnaby edge must resolve from raw data, got " + rows.size(), !rows.isEmpty());
		boolean fare = false;
		for (Transport row : rows)
		{
			fare |= row.getItemRequirements() != null;
		}
		assertTrue("the edge's fare requirement must be visible", fare);
	}
}
