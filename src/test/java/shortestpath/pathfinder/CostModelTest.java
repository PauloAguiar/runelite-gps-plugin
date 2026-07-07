package shortestpath.pathfinder;

import static org.junit.Assert.assertEquals;
import org.junit.Test;
import shortestpath.WorldPointUtil;

/**
 * Covers the time-normalized cost model: one cost unit = one run-tile = half a game tick (0.3s),
 * transport travel time converts at 2 units per tick, and every edge is clamped at free so a
 * negative configured weight can make a method free but never pay the route back (Dijkstra needs
 * non-negative edges).
 */
public class CostModelTest
{
	private static final int A = WorldPointUtil.packWorldPoint(3200, 3200, 0);
	private static final int B = WorldPointUtil.packWorldPoint(3201, 3200, 0);
	private static final int C = WorldPointUtil.packWorldPoint(3250, 3250, 0);

	@Test
	public void transportTravelTimeConvertsAtTwoUnitsPerTick()
	{
		assertEquals("A game tick covers 2 run-tiles", 2, CostUnits.UNITS_PER_TICK);
		assertEquals(10, CostUnits.fromTicks(5));
		assertEquals(0, CostUnits.fromTicks(0));
		// The unit really is real time: units * seconds-per-unit == ticks * 0.6s.
		assertEquals(5 * 0.6, CostUnits.fromTicks(5) * CostUnits.SECONDS_PER_UNIT, 1e-9);
	}

	@Test
	public void walkingEdgeCostsItsTileDistance()
	{
		NodeGraph graph = new NodeGraph(16);
		int start = graph.createStart(A);
		int step = graph.createTile(B, start, false, 0);
		assertEquals("One walked tile = one unit", 1, graph.cost(step));
	}

	@Test
	public void transportEdgeChargesTravelTimePlusWeight()
	{
		NodeGraph graph = new NodeGraph(16);
		int start = graph.createStart(A);
		int viaTransport = graph.createTransport(C, start, CostUnits.fromTicks(5), 7, false, false, 0);
		assertEquals("5 ticks of travel (10 units) + weight 7", 17, graph.cost(viaTransport));
	}

	@Test
	public void negativeWeightClampsTheTransportEdgeAtFree()
	{
		NodeGraph graph = new NodeGraph(16);
		int start = graph.createStart(A);
		int walked = graph.createTile(B, start, false, 0); // cost 1
		// Travel 3 ticks (6 units) with a -20 favor weight: the edge is free, not -14 — the route
		// must never get cheaper by using a favored method (no negative edges).
		int viaTransport = graph.createTransport(C, walked, CostUnits.fromTicks(3), -20, false, false, 0);
		assertEquals("The favored transport is free but never pays back", 1, graph.cost(viaTransport));
	}

	@Test
	public void negativeExtraCostClampsWalkAndAbstractEdgesAtFree()
	{
		NodeGraph graph = new NodeGraph(16);
		int start = graph.createStart(A);
		int walked = graph.createTile(B, start, false, -5);
		assertEquals("A negative surcharge can't make walking pay back", 0, graph.cost(walked));
		int abstractNode = graph.createAbstract(AbstractNodeKind.fromWildernessLevel(0), walked, false, -7);
		assertEquals("A negative surcharge can't make the abstract hop pay back", 0, graph.cost(abstractNode));
	}
}
