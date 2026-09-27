package gps;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The destination a nearest-bank trip replaced (a player suggestion): resumed once the bank trip
 * completes, only when the bank click replaced an active destination, and forgotten by any other
 * change of destination, so a resume never surprises.
 */
public class BankDetourTest
{
	private static final Set<Integer> VARROCK = Set.of(WorldPointUtil.packWorldPoint(3213, 3424, 0));
	private static final Set<Integer> BANKS = Set.of(
		WorldPointUtil.packWorldPoint(3185, 3436, 0), WorldPointUtil.packWorldPoint(3253, 3420, 0));

	@Test
	public void aBankTripResumesTheDestinationItReplaced()
	{
		BankDetour detour = new BankDetour();
		detour.begin(detour.replacing(BankDetour.Route.of(VARROCK, "map pin", false, WorldPointUtil.UNDEFINED)));
		assertTrue(detour.isActive());

		BankDetour.Route resumed = detour.complete();
		assertNotNull(resumed);
		assertEquals(VARROCK, resumed.targets);
		assertEquals("map pin", resumed.source);
		assertFalse(detour.isActive());
		assertNull("a completed trip resumes once", detour.complete());
	}

	@Test
	public void aBankTripWithNothingActiveResumesNothing()
	{
		assertNull("no destination, no route", BankDetour.Route.of(Set.of(), null, false, WorldPointUtil.UNDEFINED));
		BankDetour detour = new BankDetour();
		detour.begin(detour.replacing(null));
		assertTrue(detour.isActive());
		assertNull(detour.complete());
	}

	@Test
	public void anyOtherDestinationChangeForgetsTheRoute()
	{
		BankDetour detour = new BankDetour();
		detour.begin(detour.replacing(BankDetour.Route.of(VARROCK, "search", false, WorldPointUtil.UNDEFINED)));
		// A new pin, a clear, another plugin's target: the trip was replaced, not completed.
		detour.cancel();
		assertFalse(detour.isActive());
		assertNull(detour.complete());
	}

	@Test
	public void aSecondBankClickKeepsTheOriginalRoute()
	{
		BankDetour detour = new BankDetour();
		detour.begin(detour.replacing(BankDetour.Route.of(VARROCK, "map pin", false, WorldPointUtil.UNDEFINED)));

		// "And back" pressed during the one-way trip: what it replaces is the bank trip itself,
		// so the route kept is still the original. The plugin's target setter forgets the trip
		// between the two calls, exactly as here.
		BankDetour.Route kept = detour.replacing(BankDetour.Route.of(BANKS, "nearest bank", false, WorldPointUtil.UNDEFINED));
		detour.cancel();
		detour.begin(kept);
		assertEquals(VARROCK, detour.complete().targets);
	}

	@Test
	public void aRouteCopiesItsTargetsAndKeepsItsShape()
	{
		Set<Integer> live = new HashSet<>(VARROCK);
		int pin = WorldPointUtil.packWorldPoint(3212, 3423, 0);
		BankDetour.Route route = BankDetour.Route.of(live, "search", true, pin);
		live.clear();
		assertEquals(VARROCK, route.targets);
		assertTrue(route.roundTrip);
		assertEquals(pin, route.marker);
	}
}
