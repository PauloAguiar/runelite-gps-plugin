package gps.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import gps.TeleportMethod;
import gps.WorldPointUtil;

/**
 * Every method-type transport must carry display info: the panel's method rows and the direction
 * steps label methods by it, and the fallback is a bare coordinate pair ("Portals (2440, 3089)") —
 * meaningless to a player. Permutation networks inherit the destination row's info, so this
 * effectively requires every direct row and every destination-role row to be named.
 */
public class MethodDisplayInfoTest
{
	@Test
	public void everyMethodTransportIsNamed()
	{
		List<String> unnamed = new ArrayList<>();
		for (Set<Transport> transports : TransportLoader.loadAllFromResources().values())
		{
			for (Transport transport : transports)
			{
				if (!TeleportMethod.isMethodType(transport.getType()))
				{
					continue;
				}
				String info = transport.getDisplayInfo();
				if (info == null || info.isEmpty())
				{
					int d = transport.getDestination();
					unnamed.add(transport.getType() + " -> (" + WorldPointUtil.unpackWorldX(d)
						+ ", " + WorldPointUtil.unpackWorldY(d) + ", " + WorldPointUtil.unpackWorldPlane(d) + ")");
				}
			}
		}
		assertTrue("method transports without display info (would label as coordinates): " + unnamed,
			unnamed.isEmpty());
	}

	/**
	 * Every network RIDE must cost at least a tick: a missing Duration cell prices the ride at
	 * zero, and the search then uses it as a free shortcut (the Keldagrim train rode to White Wolf
	 * Mountain and back to cross 18 tiles — user capture 20260713-233919).
	 */
	@Test
	public void everyNetworkRideHasADuration()
	{
		Set<TransportType> rides = java.util.EnumSet.of(
			TransportType.BOAT, TransportType.CANOE, TransportType.CHARTER_SHIP, TransportType.SHIP,
			TransportType.GNOME_GLIDER, TransportType.HOT_AIR_BALLOON, TransportType.MAGIC_CARPET,
			TransportType.MAGIC_MUSHTREE, TransportType.MINECART, TransportType.MOUNTAIN_GUIDE,
			TransportType.QUETZAL, TransportType.SPIRIT_TREE, TransportType.WILDERNESS_OBELISK);
		List<String> free = new ArrayList<>();
		for (Set<Transport> transports : TransportLoader.loadAllFromResources().values())
		{
			for (Transport transport : transports)
			{
				if (rides.contains(transport.getType()) && transport.getDuration() < 1)
				{
					free.add(transport.getType() + " -> " + transport.getDisplayInfo());
				}
			}
		}
		assertTrue("rides with no duration (searched as free shortcuts): " + free, free.isEmpty());
	}
}
