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
			SailingSea.aboardLegTransports(start, java.util.Set.of(pin), 6);
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
}
