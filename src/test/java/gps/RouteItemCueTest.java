package gps;

import gps.pathfinder.PathStep;
import gps.transport.Transport;
import gps.transport.TransportLoader;
import gps.transport.TransportType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The widget highlight follows the route's NEXT item step only: the first method edge the player
 * has not reached, when its transport is an item teleport. "Worn-only" is read from the item's
 * inventory options: an item with no option that uses it where it lies (the Camulet: Wear, Check)
 * teleports from the worn slot, so the bag copy gets an "Equip" tag.
 */
public class RouteItemCueTest
{
	private static final int CAMULET = 6707;

	private static Transport camulet()
	{
		Map<Integer, Set<Transport>> transports = new HashMap<>();
		TransportLoader.addTransportsFromContents(transports,
			"# Destination\tmenuOption menuTarget objectID\tSkills\tItems\tQuests\tDuration\tDisplay info\tConsumable\tWilderness level\n"
				+ "3106 9315 2\t\t\t" + CAMULET + "=1\t\t4\tCamulet: Inside Enakhra's Temple\tF\t20\n",
			TransportType.TELEPORTATION_ITEM, 0);
		return transports.values().iterator().next().iterator().next();
	}

	private static Transport ring()
	{
		Map<Integer, Set<Transport>> transports = new HashMap<>();
		TransportLoader.addTransportsFromContents(transports,
			"# Origin\tDestination\tmenuOption menuTarget objectID\tSkills\tQuests\tVarbits\tDuration\tDisplay info\n"
				+ "3106 9316 2\t3106 9317 2\tConfigure Fairy ring 29560\t\t\t\t5\tAKQ\n",
			TransportType.FAIRY_RING, 0);
		return transports.values().iterator().next().iterator().next();
	}

	private static Set<Integer> staves()
	{
		Set<Integer> staves = new java.util.HashSet<>();
		for (int id : ItemVariations.DRAMEN_STAFF.getIds())
		{
			staves.add(id);
		}
		return staves;
	}

	/** A four-step path with one method per listed edge (the Camulet's name for all of them). */
	private static RouteOption route(List<Integer> methodEdges)
	{
		List<PathStep> path = List.of(
			new PathStep(WorldPointUtil.packWorldPoint(3200, 3200, 0), false, 0),
			new PathStep(WorldPointUtil.packWorldPoint(3106, 9315, 2), false, 4),
			new PathStep(WorldPointUtil.packWorldPoint(3106, 9316, 2), false, 5),
			new PathStep(WorldPointUtil.packWorldPoint(3106, 9317, 2), false, 6));
		List<TeleportMethod> methods = new java.util.ArrayList<>();
		List<Integer> durations = new java.util.ArrayList<>();
		List<Integer> walkBefore = new java.util.ArrayList<>();
		for (int edge : methodEdges)
		{
			methods.add(new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Camulet: Inside Enakhra's Temple", path.get(edge).getPackedPosition()));
			durations.add(4);
			walkBefore.add(0);
		}
		return new RouteOption(path, methods, methodEdges, durations, 6, 6, true, Set.of(), walkBefore, 2);
	}

	@Test
	public void theNextItemStepNamesItsItemsUntilItIsPassed()
	{
		Transport camulet = camulet();
		RouteOption route = route(List.of(1));
		RouteItemCue cue = RouteItemCue.next(route, 0, (a, b) -> Set.of(camulet), true);
		assertEquals(Set.of(CAMULET), cue.itemIds);
		assertTrue("the Camulet is operated, worn or not", cue.wornDone.isEmpty());
		assertNull("teleported: the step is behind the player", RouteItemCue.next(route, 1, (a, b) -> Set.of(camulet), true));
		assertNull("a route without methods has no item step", RouteItemCue.next(route(List.of()), 0, (a, b) -> Set.of(camulet), true));
		assertNull("no route", RouteItemCue.next(null, 0, (a, b) -> Set.of(camulet), true));
	}

	@Test
	public void aSpellStepNamesTheSpellAndNoItems()
	{
		Map<Integer, Set<Transport>> transports = new HashMap<>();
		TransportLoader.addTransportsFromContents(transports,
			"# Destination\tmenuOption menuTarget objectID\tSkills\tItems\tQuests\tDuration\tDisplay info\tConsumable\tWilderness level\n"
				+ "3106 9315 2\t\t\tLAW_RUNE=1\t\t4\tVarrock Teleport: GE\tT\t20\n",
			TransportType.TELEPORTATION_SPELL, 0);
		Transport spell = transports.values().iterator().next().iterator().next();
		RouteItemCue cue = RouteItemCue.next(route(List.of(1)), 0, (a, b) -> Set.of(spell), true);
		assertEquals("the spellbook is clicked, not the runes", Set.of(), cue.itemIds);
		assertEquals("Varrock Teleport: GE", cue.spell);
		assertNull("an item step names no spell", RouteItemCue.next(route(List.of(1)), 0, (a, b) -> Set.of(camulet()), true).spell);
	}

	@Test
	public void aFairyRingStepAsksForTheStaffUnlessTheDiaryWaivesIt()
	{
		Transport ring = ring();
		RouteItemCue cue = RouteItemCue.next(route(List.of(3)), 1, (a, b) -> Set.of(ring), true);
		assertEquals("a Dramen or Lunar staff, any of them", staves(), cue.itemIds);
		assertEquals("the staff works worn: nothing to do once it is", staves(), cue.wornDone);
		assertNull("Lumbridge Elite done: the ring needs nothing", RouteItemCue.next(route(List.of(3)), 1, (a, b) -> Set.of(ring), false));
	}

	@Test
	public void theStaffIsAskedForOneMethodAhead()
	{
		// Camulet at edge 1, fairy ring at edge 3: while the Camulet is the next step, the staff is
		// already cued so it can be wielded before the ring, and the Camulet keeps its own rules.
		Transport camulet = camulet();
		Transport ring = ring();
		RouteOption route = route(List.of(1, 3));
		RouteItemCue cue = RouteItemCue.next(route, 0, (a, b) -> b.getPackedPosition() == route.getPath().get(1).getPackedPosition()
			? Set.of(camulet) : Set.of(ring), true);
		Set<Integer> expected = new java.util.HashSet<>(staves());
		expected.add(CAMULET);
		assertEquals(expected, cue.itemIds);
		assertEquals("only the staff is done once worn", staves(), cue.wornDone);
		assertNull(cue.spell);
		assertEquals("the diary waives the staff ahead too", Set.of(CAMULET),
			RouteItemCue.next(route, 0, (a, b) -> b.getPackedPosition() == route.getPath().get(1).getPackedPosition()
				? Set.of(camulet) : Set.of(ring), false).itemIds);
	}

	@Test
	public void theBankStepIsWhereThePathTurnsBankedOnARouteViaTheBank()
	{
		List<PathStep> path = List.of(
			new PathStep(WorldPointUtil.packWorldPoint(3200, 3200, 0), false, 0),
			new PathStep(WorldPointUtil.packWorldPoint(3201, 3200, 0), false, 1),
			new PathStep(WorldPointUtil.packWorldPoint(3202, 3200, 0), true, 2),
			new PathStep(WorldPointUtil.packWorldPoint(3106, 9315, 2), true, 6));
		TeleportMethod camulet = new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Camulet: Inside Enakhra's Temple", path.get(3).getPackedPosition());
		RouteOption viaBank = new RouteOption(path, List.of(camulet), List.of(3), List.of(4), 6, 6, true, Set.of(camulet), List.of(2), 0);
		assertEquals(2, RouteItemCue.bankStep(viaBank));
		RouteOption owned = new RouteOption(path, List.of(camulet), List.of(3), List.of(4), 6, 6, true, Set.of(), List.of(2), 0);
		assertEquals("a route that only passes a bank withdraws nothing", -1, RouteItemCue.bankStep(owned));
		assertEquals(-1, RouteItemCue.bankStep(null));
	}

	@Test
	public void anItemWithNoOptionThatUsesItFromTheBagIsWornOnly()
	{
		assertTrue("the Camulet: its teleports are worn options", RouteItemCue.wornOnly(new String[]{"Wear", "Check", null, null, null}));
		assertTrue(RouteItemCue.wornOnly(null));
		assertFalse("jewellery rubs from the bag", RouteItemCue.wornOnly(new String[]{"Rub", "Wear", null, null, null}));
		assertFalse("tablets break", RouteItemCue.wornOnly(new String[]{"Break", null, null, null, null}));
		assertFalse("capes teleport, whatever the destination word", RouteItemCue.wornOnly(new String[]{"Wear", "Monastery Teleport", null, null, null}));
	}
}
