package gps;

import gps.transport.TransportType;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The RimWorld-style method priority system: tiers shift a route's EFFECTIVE rank (cost plus
 * seconds-based adjustments) without touching the search; the walk preference gives the plain
 * walking route ranking slack; excluded methods read back as the EXCLUDED tier.
 */
public class MethodPriorityTest
{
	@Test
	public void secondsConvertToCostUnits()
	{
		// 1 tick = 0.6s, 2 units per tick.
		assertEquals(17, MethodPriority.unitsFromSeconds(5));
		assertEquals(33, MethodPriority.unitsFromSeconds(10));
		assertEquals(67, MethodPriority.unitsFromSeconds(20));
		assertEquals(-17, MethodPriority.unitsFromSeconds(-5));
		assertEquals(0, MethodPriority.unitsFromSeconds(0));
	}

	@Test
	public void chipTextIsSignedSeconds()
	{
		assertEquals("−10s", MethodPriority.PREFER_2.chipText());
		assertEquals("+5s", MethodPriority.AVOID_1.chipText());
		assertEquals("", MethodPriority.NORMAL.chipText());
	}

	private static RouteOption route(int cost, TeleportMethod... methods)
	{
		return new RouteOption(List.of(), List.of(methods), List.of(), List.of(),
			cost, cost, true, Set.of(), List.of(), 0);
	}

	@SuppressWarnings("unchecked")
	private static ShortestPathPlugin pluginWith(Map<TeleportMethod, MethodPriority> priorities,
		int walkPreferenceSeconds, Set<TeleportMethod> exclusions) throws Exception
	{
		ShortestPathPlugin plugin = new ShortestPathPlugin();
		Field priorityField = ShortestPathPlugin.class.getDeclaredField("methodPriorities");
		priorityField.setAccessible(true);
		((Map<TeleportMethod, MethodPriority>) priorityField.get(plugin)).putAll(priorities);
		Field walkField = ShortestPathPlugin.class.getDeclaredField("cachedWalkPreferenceSeconds");
		walkField.setAccessible(true);
		walkField.setInt(plugin, walkPreferenceSeconds);
		Field exclusionField = ShortestPathPlugin.class.getDeclaredField("userExclusions");
		exclusionField.setAccessible(true);
		((Set<TeleportMethod>) exclusionField.get(plugin)).addAll(exclusions);
		return plugin;
	}

	@Test
	public void preferredMethodOutranksARawFasterRoute() throws Exception
	{
		TeleportMethod cloak = new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Ardougne cloak", 1);
		TeleportMethod minigame = new TeleportMethod(TransportType.TELEPORTATION_MINIGAME, "Rat Pits", 2);
		ShortestPathPlugin plugin = pluginWith(
			Map.of(cloak, MethodPriority.PREFER_2), 0, Set.of());

		RouteOption cloakRoute = route(300, cloak);      // effective 300 - 33 = 267
		RouteOption minigameRoute = route(280, minigame); // effective 280
		List<RouteOption> sorted = plugin.sortByEffectiveOrder(List.of(minigameRoute, cloakRoute));

		assertEquals(cloakRoute, sorted.get(0));
		assertEquals(-10, plugin.routeAdjustmentSeconds(cloakRoute));
		assertEquals(0, plugin.routeAdjustmentSeconds(minigameRoute));
	}

	@Test
	public void avoidedMethodSinksBelowASlowerRoute() throws Exception
	{
		TeleportMethod fairy = new TeleportMethod(TransportType.FAIRY_RING, "C L S", 3);
		TeleportMethod tree = new TeleportMethod(TransportType.SPIRIT_TREE, "Tree", 4);
		ShortestPathPlugin plugin = pluginWith(Map.of(fairy, MethodPriority.AVOID_3), 0, Set.of());

		RouteOption fairyRoute = route(250, fairy); // effective 250 + 67 = 317
		RouteOption treeRoute = route(300, tree);   // effective 300
		List<RouteOption> sorted = plugin.sortByEffectiveOrder(List.of(fairyRoute, treeRoute));

		assertEquals(treeRoute, sorted.get(0));
		assertEquals(20, plugin.routeAdjustmentSeconds(fairyRoute));
	}

	@Test
	public void walkPreferenceGivesThePureWalkRouteSlack() throws Exception
	{
		TeleportMethod tab = new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Tablet", 5);
		ShortestPathPlugin plugin = pluginWith(Map.of(), 15, Set.of());

		RouteOption walk = route(320);          // effective 320 - 50 = 270
		RouteOption teleport = route(280, tab); // effective 280
		List<RouteOption> sorted = plugin.sortByEffectiveOrder(List.of(teleport, walk));

		assertEquals("walking wins: the method is not 15s better", walk, sorted.get(0));
		assertEquals(-15, plugin.routeAdjustmentSeconds(walk));

		RouteOption fastTeleport = route(240, tab); // effective 240 beats 270
		assertEquals(fastTeleport,
			plugin.sortByEffectiveOrder(List.of(walk, fastTeleport)).get(0));
	}

	@Test
	public void adjustmentsNeverPromoteAnUnreachedRoute() throws Exception
	{
		TeleportMethod tab = new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Tablet", 6);
		ShortestPathPlugin plugin = pluginWith(Map.of(tab, MethodPriority.PREFER_3), 0, Set.of());

		RouteOption unreachedPreferred = new RouteOption(List.of(), List.of(tab), List.of(),
			List.of(), 100, 100, false, Set.of(), List.of(), 0);
		RouteOption reachedPlain = route(500);
		assertEquals(reachedPlain,
			plugin.sortByEffectiveOrder(List.of(unreachedPreferred, reachedPlain)).get(0));
	}

	@Test
	public void excludedMethodsReadBackAsTheExcludedTier() throws Exception
	{
		TeleportMethod tab = new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Tablet", 7);
		ShortestPathPlugin plugin = pluginWith(Map.of(), 0, Set.of(tab));
		assertEquals(MethodPriority.EXCLUDED, plugin.getMethodPriority(tab));
		assertEquals(MethodPriority.NORMAL, plugin.getMethodPriority(
			new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Other", 8)));
	}

	@Test
	public void exclusionMasksButDoesNotEraseTheTier() throws Exception
	{
		// Set a tier, then exclude (as the category toggle or the menu would): the tier is
		// shadowed while excluded and restored the moment the exclusion lifts.
		TeleportMethod tab = new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Tablet", 9);
		ShortestPathPlugin plugin = pluginWith(Map.of(tab, MethodPriority.PREFER_2), 0, Set.of());
		assertEquals(MethodPriority.PREFER_2, plugin.getMethodPriority(tab));

		Field exclusionField = ShortestPathPlugin.class.getDeclaredField("userExclusions");
		exclusionField.setAccessible(true);
		@SuppressWarnings("unchecked")
		Set<TeleportMethod> exclusions = (Set<TeleportMethod>) exclusionField.get(plugin);
		exclusions.add(tab);
		assertEquals(MethodPriority.EXCLUDED, plugin.getMethodPriority(tab));

		exclusions.remove(tab);
		assertEquals("re-inclusion must restore the stored tier",
			MethodPriority.PREFER_2, plugin.getMethodPriority(tab));
	}
}
