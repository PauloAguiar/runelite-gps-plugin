package gps;

import com.google.gson.Gson;
import gps.transport.TransportType;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan step L14: the ranking preferences, out of the plugin class. Tiers shift a route's
 * EFFECTIVE rank (cost plus seconds-based adjustments) without touching the search; the walk
 * preference gives the plain walking route slack; the bank bias shifts via-bank routes; reached
 * routes always outrank unreached ones; at the helm pure-sail routes lead; excluded methods read
 * back as the EXCLUDED tier, a mask over the stored tier; tiers and biases persist and reload.
 */
@RunWith(MockitoJUnitRunner.class)
public class RoutePreferencesTest
{
	private static final String GROUP = "gps";

	@Mock
	ConfigManager configManager;

	private final Set<TeleportMethod> exclusions = new HashSet<>();
	private boolean keepSailing;

	private static RouteOption route(int cost, TeleportMethod... methods)
	{
		return new RouteOption(List.of(), List.of(methods), List.of(), List.of(),
			cost, cost, true, Set.of(), List.of(), 0);
	}

	private RoutePreferences preferences(Map<TeleportMethod, MethodPriority> priorities, int walkPreferenceSeconds)
	{
		RoutePreferences preferences = new RoutePreferences(exclusions, () -> keepSailing,
			() -> configManager, Gson::new, GROUP);
		priorities.forEach(preferences::setTier);
		preferences.setWalkPreferenceSeconds(walkPreferenceSeconds);
		return preferences;
	}

	@Test
	public void preferredMethodOutranksARawFasterRoute()
	{
		TeleportMethod cloak = new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Ardougne cloak", 1);
		TeleportMethod minigame = new TeleportMethod(TransportType.TELEPORTATION_MINIGAME, "Rat Pits", 2);
		RoutePreferences preferences = preferences(Map.of(cloak, MethodPriority.PREFER_2), 0);

		RouteOption cloakRoute = route(300, cloak);      // effective 300 - 33 = 267
		RouteOption minigameRoute = route(280, minigame); // effective 280
		List<RouteOption> sorted = preferences.sorted(List.of(minigameRoute, cloakRoute));

		assertEquals(cloakRoute, sorted.get(0));
		assertEquals(-10, preferences.adjustmentSeconds(cloakRoute));
		assertEquals(0, preferences.adjustmentSeconds(minigameRoute));
	}

	@Test
	public void avoidedMethodSinksBelowASlowerRoute()
	{
		TeleportMethod fairy = new TeleportMethod(TransportType.FAIRY_RING, "C L S", 3);
		TeleportMethod tree = new TeleportMethod(TransportType.SPIRIT_TREE, "Tree", 4);
		RoutePreferences preferences = preferences(Map.of(fairy, MethodPriority.AVOID_3), 0);

		RouteOption fairyRoute = route(250, fairy); // effective 250 + 67 = 317
		RouteOption treeRoute = route(300, tree);   // effective 300
		List<RouteOption> sorted = preferences.sorted(List.of(fairyRoute, treeRoute));

		assertEquals(treeRoute, sorted.get(0));
		assertEquals(20, preferences.adjustmentSeconds(fairyRoute));
	}

	@Test
	public void walkPreferenceGivesThePureWalkRouteSlack()
	{
		TeleportMethod tab = new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Tablet", 5);
		RoutePreferences preferences = preferences(Map.of(), 15);

		RouteOption walk = route(320);          // effective 320 - 50 = 270
		RouteOption teleport = route(280, tab); // effective 280
		List<RouteOption> sorted = preferences.sorted(List.of(teleport, walk));

		assertEquals("walking wins: the method is not 15s better", walk, sorted.get(0));
		assertEquals(-15, preferences.adjustmentSeconds(walk));

		RouteOption fastTeleport = route(240, tab); // effective 240 beats 270
		assertEquals(fastTeleport, preferences.sorted(List.of(walk, fastTeleport)).get(0));
	}

	@Test
	public void bankBiasShiftsViaBankRoutes()
	{
		TeleportMethod tab = new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Tablet", 10);
		RoutePreferences preferences = preferences(Map.of(), 0);
		preferences.setBankPreferenceSeconds(10); // prefer bank routes by 10s

		RouteOption viaBank = new RouteOption(List.of(), List.of(tab), List.of(), List.of(),
			300, 300, true, Set.of(tab), List.of(), 0);
		RouteOption direct = route(280, tab);
		assertEquals(-10, preferences.adjustmentSeconds(viaBank));
		assertEquals("bank route effective 267 beats direct 280",
			viaBank, preferences.sorted(List.of(direct, viaBank)).get(0));

		preferences.setBankPreferenceSeconds(-10); // avoid bank routes
		assertEquals(10, preferences.adjustmentSeconds(viaBank));
		assertEquals(direct, preferences.sorted(List.of(viaBank, direct)).get(0));
	}

	@Test
	public void adjustmentsNeverPromoteAnUnreachedRoute()
	{
		TeleportMethod tab = new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Tablet", 6);
		RoutePreferences preferences = preferences(Map.of(tab, MethodPriority.PREFER_3), 0);

		RouteOption unreachedPreferred = new RouteOption(List.of(), List.of(tab), List.of(),
			List.of(), 100, 100, false, Set.of(), List.of(), 0);
		RouteOption reachedPlain = route(500);
		assertEquals(reachedPlain, preferences.sorted(List.of(unreachedPreferred, reachedPlain)).get(0));
	}

	@Test
	public void atTheHelmPureSailRanksFirstDespiteCost()
	{
		TeleportMethod sail = new TeleportMethod(TransportType.SAILING,
			"Disembark at Corsair Cove", WorldPointUtil.packWorldPoint(2589, 2851, 0));
		TeleportMethod glory = new TeleportMethod(TransportType.TELEPORTATION_ITEM,
			"Amulet of glory: Al Kharid", WorldPointUtil.packWorldPoint(3087, 3496, 0));
		RouteOption sailRoute = sailingRoute(666, sail);
		RouteOption chain = sailingRoute(182, sail, glory);
		RoutePreferences preferences = preferences(Map.of(), 0);

		keepSailing = true;
		assertSame("at the helm the pure-sail route leads even at 3x the cost",
			sailRoute, preferences.sorted(List.of(chain, sailRoute)).get(0));
		keepSailing = false;
		assertSame("ashore the cheaper chain leads", chain, preferences.sorted(List.of(sailRoute, chain)).get(0));
	}

	private static RouteOption sailingRoute(int cost, TeleportMethod... methods)
	{
		List<TeleportMethod> list = List.of(methods);
		List<Integer> edges = new java.util.ArrayList<>();
		List<Integer> durations = new java.util.ArrayList<>();
		for (int i = 0; i < list.size(); i++)
		{
			edges.add(i + 1);
			durations.add(1);
		}
		List<gps.pathfinder.PathStep> path = List.of(
			new gps.pathfinder.PathStep(WorldPointUtil.packWorldPoint(2746, 3216, 0), false),
			new gps.pathfinder.PathStep(WorldPointUtil.packWorldPoint(2638, 3009, 0), false));
		return new RouteOption(path, list, edges, durations, cost, cost, true, Set.of(), List.of(0), 0);
	}

	@Test
	public void excludedMethodsReadBackAsTheExcludedTier()
	{
		TeleportMethod tab = new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Tablet", 7);
		exclusions.add(tab);
		RoutePreferences preferences = preferences(Map.of(), 0);
		assertEquals(MethodPriority.EXCLUDED, preferences.priorityOf(tab));
		assertEquals(MethodPriority.NORMAL, preferences.priorityOf(
			new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Other", 8)));
	}

	@Test
	public void exclusionMasksButDoesNotEraseTheTier()
	{
		// Set a tier, then exclude (as the category toggle or the menu would): the tier is
		// shadowed while excluded and restored the moment the exclusion lifts.
		TeleportMethod tab = new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Tablet", 9);
		RoutePreferences preferences = preferences(Map.of(tab, MethodPriority.PREFER_2), 0);
		assertEquals(MethodPriority.PREFER_2, preferences.priorityOf(tab));

		exclusions.add(tab);
		assertEquals(MethodPriority.EXCLUDED, preferences.priorityOf(tab));

		exclusions.remove(tab);
		assertEquals("re-inclusion must restore the stored tier", MethodPriority.PREFER_2, preferences.priorityOf(tab));
	}

	@Test
	public void tiersAndBiasesPersistAndReload()
	{
		TeleportMethod tab = new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Tablet", 11);
		TeleportMethod fairy = new TeleportMethod(TransportType.FAIRY_RING, "C L S", 12);
		RoutePreferences saved = preferences(Map.of(tab, MethodPriority.PREFER_1, fairy, MethodPriority.AVOID_2), 15);
		saved.setBankPreferenceSeconds(-5);
		// NORMAL is the absence of a tier: setting it removes the entry.
		saved.setTier(fairy, MethodPriority.NORMAL);

		ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
		verify(configManager, atLeastOnce()).setConfiguration(eq(GROUP), eq(RoutePreferences.CONFIG_KEY_PRIORITIES), json.capture());
		verify(configManager).setConfiguration(GROUP, RoutePreferences.CONFIG_KEY_WALK_PREFERENCE, 15);
		verify(configManager).setConfiguration(GROUP, RoutePreferences.CONFIG_KEY_BANK_PREFERENCE, -5);

		when(configManager.getConfiguration(GROUP, RoutePreferences.CONFIG_KEY_PRIORITIES)).thenReturn(json.getValue());
		when(configManager.getConfiguration(GROUP, RoutePreferences.CONFIG_KEY_WALK_PREFERENCE, Integer.class)).thenReturn(15);
		when(configManager.getConfiguration(GROUP, RoutePreferences.CONFIG_KEY_BANK_PREFERENCE, Integer.class)).thenReturn(-5);
		RoutePreferences loaded = new RoutePreferences(exclusions, () -> keepSailing, () -> configManager, Gson::new, GROUP);
		loaded.load();
		assertEquals(MethodPriority.PREFER_1, loaded.priorityOf(tab));
		assertEquals("the removed tier did not persist", MethodPriority.NORMAL, loaded.priorityOf(fairy));
		assertEquals(15, loaded.walkPreferenceSeconds());
		assertEquals(-5, loaded.bankPreferenceSeconds());
	}
}
