package gps;

import gps.transport.Transport;
import gps.transport.TransportType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Issue #19: the mounted Xeric's talisman shares the jewellery-box transport TYPE, and labels
 * were per type — a Xeric's route read "Jewellery box to 2: Glade". Mounted items now name
 * their furniture; the box itself is unchanged; identity (exclusions) ignores the mount.
 */
public class TeleportMethodLabelTest
{
	private static Transport box(String objectInfo, String displayInfo)
	{
		return new Transport.TransportBuilder()
			.origin(WorldPointUtil.packWorldPoint(1886, 5760, 0))
			.destination(WorldPointUtil.packWorldPoint(1752, 3566, 0))
			.type(TransportType.TELEPORTATION_BOX)
			.objectInfo(objectInfo)
			.displayInfo(displayInfo)
			.build();
	}

	@Test
	public void mountedXericsNamesItsFurniture()
	{
		TeleportMethod xerics = TeleportMethod.fromTransport(box("Glade Xeric's Talisman 33412", "2: Glade"));
		assertEquals("Xeric's talisman to 2: Glade", xerics.routeLabel());
		assertEquals("Xeric's talisman: 2: Glade", xerics.label());
	}

	@Test
	public void jewelleryBoxStaysJewelleryBox()
	{
		TeleportMethod box = TeleportMethod.fromTransport(box("Teleport-menu Jewellery Box 29156", "Edgeville"));
		assertEquals("Jewellery box to Edgeville", box.routeLabel());
		assertEquals("Edgeville", box.label());
	}

	@Test
	public void charterNamesItself()
	{
		Transport charter = new Transport.TransportBuilder()
			.origin(WorldPointUtil.packWorldPoint(1743, 3136, 0))
			.destination(WorldPointUtil.packWorldPoint(3038, 3192, 0))
			.type(TransportType.CHARTER_SHIP)
			.objectInfo("Charter Trader Crewmember 1330")
			.displayInfo("Port Sarim")
			.build();
		TeleportMethod method = TeleportMethod.fromTransport(charter);
		assertEquals("Charter: Port Sarim", method.label());
		assertEquals("Charter ship to Port Sarim", method.routeLabel());
	}

	@Test
	public void mountIsMetadataNotIdentity()
	{
		TeleportMethod a = TeleportMethod.fromTransport(box("Glade Xeric's Talisman 33412", "2: Glade"));
		TeleportMethod b = new TeleportMethod(TransportType.TELEPORTATION_BOX, "2: Glade",
			WorldPointUtil.packWorldPoint(1752, 3566, 0));
		assertTrue("a persisted exclusion built without object info must still match", a.equals(b));
		assertEquals(a.hashCode(), b.hashCode());
	}
}
