package gps;

import org.junit.Test;

public class DirectSeaLegTest
{
	@Test
	public void closeWaterPinGetsADirectLeg()
	{
		int start = WorldPointUtil.packWorldPoint(2744, 2989, 0);
		int pin = WorldPointUtil.packWorldPoint(2692, 2941, 0);
		System.out.println("start sailable: " + SailingSea.isSailable(start)
			+ " pin sailable: " + SailingSea.isSailable(pin));
		System.out.println("seaDistanceBetween: " + SailingSea.seaDistanceBetween(start, pin));
		java.util.List<gps.transport.Transport> legs =
			SailingSea.aboardLegTransports(start, java.util.Set.of(pin));
		// Field capture 232906: the route detoured disembark+re-embark through Cairn Isle
		// because the direct leg was missing live (own-wake obstacle poisoning). Headless,
		// the direct leg must exist and be dramatically cheaper than the port chain.
		int distance = SailingSea.seaDistanceBetween(start, pin);
		org.junit.Assert.assertTrue("direct sea distance sane: " + distance,
			distance > 4000 && distance < 12000);
		boolean direct = false;
		for (gps.transport.Transport leg : legs)
		{
			direct |= "Sail to the destination".equals(leg.getDisplayInfo())
				&& leg.getDestination() == pin && leg.getDuration() < 60;
		}
		org.junit.Assert.assertTrue("direct leg to the pin must be synthesized", direct);
	}

	/**
	 * Task 16 (findings 6-7): the aboard legs must cover EVERY sea-connected port, matrix-
	 * composed past the wet flood's ~12-endpoint settle horizon — one continuous
	 * "Disembark at Grimstone" from the Pandemonium instead of unreachable-or-chain. Whether
	 * the ROUTER prefers it is the service layer's cost call (teleport+short-hop chains
	 * legitimately win for Grimstone via Weiss); the leg's existence is what this pins.
	 */
	@Test
	public void aboardLegsReachEveryPortViaTheMatrix()
	{
		int pandemonium = WorldPointUtil.packWorldPoint(3093, 2981, 0);
		java.util.List<gps.transport.Transport> legs =
			SailingSea.aboardLegTransports(pandemonium, java.util.Set.of());
		int disembarks = 0;
		gps.transport.Transport grimstone = null;
		int nearest = Integer.MAX_VALUE;
		for (gps.transport.Transport leg : legs)
		{
			String info = leg.getDisplayInfo();
			if (info != null && info.startsWith("Disembark at "))
			{
				disembarks++;
				nearest = Math.min(nearest, leg.getDuration());
				if (info.equals("Disembark at Grimstone"))
				{
					grimstone = leg;
				}
			}
		}
		org.junit.Assert.assertTrue("aboard legs must cover nearly every port, got " + disembarks,
			disembarks >= 50);
		org.junit.Assert.assertTrue("the far-north Grimstone berth must get a composed leg",
			grimstone != null);
		org.junit.Assert.assertTrue("composed duration must be sane (ticks): " + grimstone.getDuration(),
			grimstone.getDuration() > nearest && grimstone.getDuration() < 3000);
	}
}
