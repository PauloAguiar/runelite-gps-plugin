package gps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The ETA/priority separation: the card's ETA is the route's FULL configured cost (travel time,
 * bank detour, and the implicit cost modifiers — charged items, transport type, currency), while
 * explicit priorities stay outside as the green/red chip. List order is always ETA + chip; no
 * hidden component may reorder cards (the Combat-bracelet capture: a 13s-looking route ranked
 * last because its charged-item surcharge was invisible).
 */
public class RouteEtaTest
{
	private static RouteOption route(int totalCost, int rawCost, boolean viaBank)
	{
		TeleportMethod method = new TeleportMethod(
			gps.transport.TransportType.TELEPORTATION_ITEM, "Test", 1);
		return new RouteOption(java.util.List.of(), java.util.List.of(method), java.util.List.of(),
			java.util.List.of(), totalCost, rawCost, true,
			viaBank ? java.util.Set.of(method) : java.util.Set.of(), java.util.List.of(), 0);
	}

	@Test
	public void etaIsTheFullConfiguredCostIncludingModifiers()
	{
		// rawCost 84 with a +30 surcharge (bank pickup or charged-item modifier): the ETA shows
		// the full 114 — the surcharge is never hidden from the number the user compares.
		assertEquals(114, ShortestPathPanel.routeEtaUnits(route(114, 84, true)));
		// No modifiers: ETA == raw travel cost.
		assertEquals(104, ShortestPathPanel.routeEtaUnits(route(104, 104, false)));
	}

	/**
	 * The directions overlay's bank-withdraw step must count the same bank time the search put
	 * into totalCost: {@code bankWithdrawTicks} in cost units tracks {@code max(0, pickup)}
	 * within one-tick granularity, and a negative modifier (a ranking preference) is no time.
	 */
	@Test
	public void directionsBankTicksMatchTheChargedPickup()
	{
		for (int cost : new int[]{0, 15, 16, 30, 100})
		{
			int chargedUnits = Math.max(0, cost);
			int overlayUnits = RouteDirections.bankWithdrawTicks(cost)
				* gps.pathfinder.CostUnits.UNITS_PER_TICK;
			assertTrue("bank time must agree for costBankPickup=" + cost
				+ " (charged " + chargedUnits + " vs overlay " + overlayUnits + ")",
				Math.abs(chargedUnits - overlayUnits) <= 1);
		}
		assertEquals(0, RouteDirections.bankWithdrawTicks(-50));
	}

}
