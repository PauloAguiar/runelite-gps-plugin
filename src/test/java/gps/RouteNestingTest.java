package gps;

import gps.transport.TransportType;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The nested-route filter (AlternativeRoutesService.nestsAKeptRoute): a candidate that detours
 * and then runs an already-kept route anyway ("dueling ring + glory + carts" next to
 * "glory + carts") is noise and must be dropped — a field capture showed seven such wrappers
 * around one Keldagrim route. Walking never dominates, and bank-fetch candidates are spared.
 */
public class RouteNestingTest
{
	private static final TeleportMethod GLORY =
		new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Amulet of glory: Edgeville", 1);
	private static final TeleportMethod CARTS =
		new TeleportMethod(TransportType.MINECART, "Keldagrim", 2);
	private static final TeleportMethod DUELING =
		new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Ring of dueling: Castle Wars", 3);
	private static final TeleportMethod VARROCK =
		new TeleportMethod(TransportType.TELEPORTATION_SPELL, "Varrock Teleport", 4);

	private static RouteOption route(List<TeleportMethod> methods, Set<TeleportMethod> bankMethods)
	{
		return new RouteOption(List.of(), methods, List.of(), List.of(), 100, 100, true,
			bankMethods, List.of(), 0);
	}

	@Test
	public void detourPlusKeptRouteIsNested()
	{
		List<RouteOption> kept = List.of(route(List.of(GLORY, CARTS), Set.of()));
		assertTrue(AlternativeRoutesService.nestsAKeptRoute(
			List.of(DUELING, GLORY, CARTS), false, kept));
		assertTrue("double-teleport prefix still nests", AlternativeRoutesService.nestsAKeptRoute(
			List.of(VARROCK, DUELING, GLORY, CARTS), false, kept));
	}

	@Test
	public void genuinelyDifferentEndingsSurvive()
	{
		List<RouteOption> kept = List.of(route(List.of(GLORY, CARTS), Set.of()));
		// Varrock -> carts: same carts, different opening — carts alone is not the kept SEQUENCE.
		assertFalse(AlternativeRoutesService.nestsAKeptRoute(
			List.of(VARROCK, CARTS), false, kept));
		// Same length = same signature territory, not nesting.
		assertFalse(AlternativeRoutesService.nestsAKeptRoute(
			List.of(DUELING, CARTS), false, kept));
	}

	@Test
	public void walkOnlyNeverDominatesAndBankFetchIsSpared()
	{
		// A walk-only kept route has an empty sequence — matching "every suffix" would kill all.
		List<RouteOption> keptWalk = List.of(route(List.of(), Set.of()));
		assertFalse(AlternativeRoutesService.nestsAKeptRoute(
			List.of(GLORY, CARTS), false, keptWalk));
		// Bank-fetching candidate vs a non-bank kept route: the detour withdraws the item — real.
		List<RouteOption> kept = List.of(route(List.of(GLORY, CARTS), Set.of()));
		assertFalse(AlternativeRoutesService.nestsAKeptRoute(
			List.of(DUELING, GLORY, CARTS), true, kept));
		// But bank candidate vs bank kept route: nested is nested.
		List<RouteOption> keptBank = List.of(route(List.of(GLORY, CARTS), Set.of(GLORY)));
		assertTrue(AlternativeRoutesService.nestsAKeptRoute(
			List.of(DUELING, GLORY, CARTS), true, keptBank));
	}
}
