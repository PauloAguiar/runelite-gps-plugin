package gps;

import gps.pathfinder.PathStep;
import gps.pathfinder.PathfinderConfig;
import gps.transport.TransportType;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Field capture 20260824-183101: every route's fairy ring was bank-gated on the Dramen staff, but
 * the withdraw step named only the OTHER methods' items ("Withdraw: Salve graveyard tablet") — the
 * staff is a special requirement carried on no ring row, so the per-method pickup lines skipped
 * it. A bank-gated ring now appends its staff line.
 */
public class FairyRingWithdrawLineTest
{
	private static final int START = WorldPointUtil.packWorldPoint(3200, 3200, 0);
	private static final int RING = WorldPointUtil.packWorldPoint(3447, 3470, 0);

	@Test
	public void bankGatedFairyRingNamesItsStaff()
	{
		ShortestPathPlugin plugin = Mockito.mock(ShortestPathPlugin.class, Mockito.withSettings().lenient());
		Client client = Mockito.mock(Client.class, Mockito.withSettings().lenient());
		PathfinderConfig config = Mockito.mock(PathfinderConfig.class, Mockito.withSettings().lenient());
		when(plugin.getClient()).thenReturn(client);
		when(plugin.getPathfinderConfig()).thenReturn(config);
		when(client.getVarbitValue(anyInt())).thenReturn(0); // Lumbridge elite diary NOT complete
		when(config.getBankSnapshot()).thenReturn(new Item[]{new Item(ItemID.DRAMEN_STAFF, 1)});
		when(config.transportsOnEdge(anyInt(), anyInt())).thenReturn(List.of());
		when(config.teleportsOnEdge(anyInt(), Mockito.anyString())).thenReturn(List.of());
		ItemComposition dramen = Mockito.mock(ItemComposition.class);
		lenient().when(dramen.getName()).thenReturn("Dramen staff");
		lenient().when(client.getItemDefinition(ItemID.DRAMEN_STAFF)).thenReturn(dramen);

		TeleportMethod ring = new TeleportMethod(TransportType.FAIRY_RING, "C K S", RING);
		List<PathStep> path = List.of(new PathStep(START, false), new PathStep(RING, true));
		RouteOption route = new RouteOption(path, List.of(ring), List.of(1), List.of(5),
			100, 90, true, Set.of(ring), List.of(0), 0);

		List<String> lines = RouteDirections.pickupLines(plugin, route);
		assertEquals("exactly the staff line: " + lines, 1, lines.size());
		assertTrue("the staff is named: " + lines.get(0), lines.get(0).startsWith("Dramen staff — "));
		assertTrue("the ring is named: " + lines.get(0), lines.get(0).contains("C K S"));
	}

	@Test
	public void diaryCompleteNeedsNoStaffLine()
	{
		ShortestPathPlugin plugin = Mockito.mock(ShortestPathPlugin.class, Mockito.withSettings().lenient());
		Client client = Mockito.mock(Client.class, Mockito.withSettings().lenient());
		PathfinderConfig config = Mockito.mock(PathfinderConfig.class, Mockito.withSettings().lenient());
		when(plugin.getClient()).thenReturn(client);
		when(plugin.getPathfinderConfig()).thenReturn(config);
		when(client.getVarbitValue(anyInt())).thenReturn(1); // diary complete: rings are staff-free
		when(config.transportsOnEdge(anyInt(), anyInt())).thenReturn(List.of());
		when(config.teleportsOnEdge(anyInt(), Mockito.anyString())).thenReturn(List.of());

		TeleportMethod ring = new TeleportMethod(TransportType.FAIRY_RING, "C K S", RING);
		List<PathStep> path = List.of(new PathStep(START, false), new PathStep(RING, true));
		RouteOption route = new RouteOption(path, List.of(ring), List.of(1), List.of(5),
			100, 90, true, Set.of(ring), List.of(0), 0);

		assertTrue("no staff line when the diary waives it",
			RouteDirections.pickupLines(plugin, route).isEmpty());
	}
}
