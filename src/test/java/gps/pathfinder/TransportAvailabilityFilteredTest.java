package gps.pathfinder;

import gps.TeleportMethod;
import gps.WorldPointUtil;
import gps.transport.Transport;
import gps.transport.TransportType;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Plan step N3: a per-search rebuild is a filtered copy of the base availability - only the
 * origins that hold an excluded transport get a new array, every other origin keeps sharing the
 * base array, extras are appended, and with nothing to exclude the base objects are reused.
 */
public class TransportAvailabilityFilteredTest
{
	private static final int A = WorldPointUtil.packWorldPoint(3200, 3200, 0);
	private static final int B = WorldPointUtil.packWorldPoint(3210, 3210, 0);
	private static final int LANDING = WorldPointUtil.packWorldPoint(3220, 3220, 0);

	private static Transport row(int origin, int destination, TransportType type, String info)
	{
		return new Transport.TransportBuilder()
			.origin(origin)
			.destination(destination)
			.type(type)
			.displayInfo(info)
			.build();
	}

	private static TransportAvailability base(Transport... rows)
	{
		TransportAvailability.Builder builder = new TransportAvailability.Builder(rows.length);
		for (Transport row : rows)
		{
			builder.add(row);
		}
		return builder.build();
	}

	@Test
	public void nothingExcludedReusesTheBaseArrays()
	{
		Transport a1 = row(A, LANDING, TransportType.TRANSPORT, "a1");
		Transport teleport = row(Transport.UNDEFINED_ORIGIN, LANDING, TransportType.TELEPORTATION_SPELL, "Tele");
		TransportAvailability base = base(a1, teleport);

		TransportAvailability filtered = base.filtered(Set.of(), List.of());
		assertSame("the base arrays are shared when nothing changes", base.getTransportsPacked().get(A), filtered.getTransportsPacked().get(A));
		assertEquals(1, filtered.getUsableTeleports().length);
	}

	@Test
	public void onlyTheTouchedOriginGetsANewArray()
	{
		Transport a1 = row(A, LANDING, TransportType.TRANSPORT, "a1");
		Transport a2 = row(A, B, TransportType.TRANSPORT, "a2");
		Transport b1 = row(B, LANDING, TransportType.TRANSPORT, "b1");
		Transport teleport = row(Transport.UNDEFINED_ORIGIN, LANDING, TransportType.TELEPORTATION_SPELL, "Tele");
		Transport teleport2 = row(Transport.UNDEFINED_ORIGIN, B, TransportType.TELEPORTATION_SPELL, "Tele2");
		TransportAvailability base = base(a1, a2, b1, teleport, teleport2);

		TransportAvailability filtered = base.filtered(Set.of(a2.method(), teleport.method()), List.of());
		Transport[] atA = filtered.getTransportsPacked().get(A);
		assertNotNull(atA);
		assertEquals("a2 removed", 1, atA.length);
		assertSame(a1, atA[0]);
		assertSame("an untouched origin keeps sharing its array", base.getTransportsPacked().get(B), filtered.getTransportsPacked().get(B));
		assertEquals("the excluded teleport is gone", 1, filtered.getUsableTeleports().length);
		assertSame(teleport2, filtered.getUsableTeleports()[0]);
		assertSame("the display view follows", atA, filtered.getDisplayTransports().get(A));
		// The base is untouched.
		assertEquals(2, base.getTransportsPacked().get(A).length);
		assertEquals(2, base.getUsableTeleports().length);
	}

	@Test
	public void extrasAreAppendedToTheirOrigin()
	{
		Transport a1 = row(A, LANDING, TransportType.TRANSPORT, "a1");
		TransportAvailability base = base(a1);
		Transport extraAtA = row(A, B, TransportType.SAILING, "Sail");
		Transport extraAtB = row(B, LANDING, TransportType.SAILING, "Sail 2");

		TransportAvailability filtered = base.filtered(Set.of(), List.of(extraAtA, extraAtB));
		assertEquals(2, filtered.getTransportsPacked().get(A).length);
		assertEquals(1, filtered.getTransportsPacked().get(B).length);
		assertSame(extraAtB, filtered.getTransportsPacked().get(B)[0]);
		assertTrue("the base is untouched", base.getTransportsPacked().get(B) == null);
	}

	@Test
	public void methodIdentityIsCachedAndFollowsARemappedDestination()
	{
		Transport a1 = row(A, LANDING, TransportType.TRANSPORT, "a1");
		TeleportMethod first = a1.method();
		assertSame("one identity per row", first, a1.method());
		a1.setDestination(B);
		assertEquals("a remapped destination changes the identity", B, a1.method().getDestination());
	}
}
