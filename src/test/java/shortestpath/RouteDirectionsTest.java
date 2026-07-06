package shortestpath;

import org.junit.Assert;
import org.junit.Test;
import shortestpath.transport.TransportType;

public class RouteDirectionsTest
{
	private static String fairy(String displayInfo)
	{
		return RouteDirections.methodText(new TeleportMethod(TransportType.FAIRY_RING, displayInfo, 0));
	}

	@Test
	public void fairyRingCodesAreCompacted()
	{
		Assert.assertEquals("🍄 Fairy ring to AIQ", fairy("A I Q"));
	}

	@Test
	public void chainedFairyRingHopsBecomeAnArrowSequence()
	{
		Assert.assertEquals("🍄 Fairy ring to AIR → DLR → DJQ → AJS",
			fairy("A I R - D L R - D J Q - A J S"));
	}

	@Test
	public void namedFairyRingDestinationsAreTitleCased()
	{
		Assert.assertEquals("🍄 Fairy ring to Zanaris", fairy("ZANARIS"));
	}

	@Test
	public void otherMethodsKeepUseWording()
	{
		Assert.assertEquals("Use Varrock Teleport",
			RouteDirections.methodText(new TeleportMethod(
				TransportType.TELEPORTATION_SPELL, "Varrock Teleport", 0)));
	}
}
