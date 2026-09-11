package gps;

import com.google.gson.Gson;
import gps.pathfinder.PathfinderConfig;
import java.util.Set;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan step L36: the destination, out of the plugin class. A pin records where the player
 * stands, the target, its source and a fresh one-way budget, arms the journey and resets the
 * off-route tracker; a clear forgets everything and keeps the catalog streaming; without a
 * player position nothing changes; a round-trip category keeps its flag past the target setter
 * and recomputes at once, and the next ordinary pin drops it; find-closest appends the category's
 * sites to the current targets; a recalculation moves the start, drops the pick and regenerates.
 */
@RunWith(MockitoJUnitRunner.class)
public class DestinationControllerTest
{
	private static final int HERE = WorldPointUtil.packWorldPoint(3200, 3200, 0);
	private static final int THERE = WorldPointUtil.packWorldPoint(3210, 3205, 0);
	private static final int BANK = WorldPointUtil.packWorldPoint(3185, 3436, 0);

	@Mock
	ShortestPathPlugin plugin;
	@Mock
	ClientThread clientThread;
	@Mock
	ShortestPathConfig config;
	@Mock
	ConfigManager configManager;
	@Mock
	AlternativeRoutesService service;
	@Mock
	PathfinderConfig pathfinderConfig;
	@Mock
	WorldMapPointManager mapPoints;

	private RouteSession session;
	private WorldMapMarker marker;
	private OffRouteTracker offRoute;
	private JourneyTracker journey;
	private DestinationController destination;

	@Before
	public void setUp()
	{
		lenient().doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(clientThread).invoke(any(Runnable.class));
		lenient().doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));
		when(plugin.getClientThread()).thenReturn(clientThread);
		when(plugin.getGpsConfig()).thenReturn(config);
		when(config.defaultRouteCount()).thenReturn(10);
		lenient().when(plugin.getPathfinderConfig()).thenReturn(pathfinderConfig);
		lenient().when(plugin.getPlayerLocation()).thenReturn(HERE);
		// The route controller reads the destination through the plugin's delegates.
		lenient().when(plugin.getPathTargets()).thenAnswer(i -> destination.targets());
		lenient().when(plugin.isRoundTripWanted()).thenAnswer(i -> destination.isRoundTrip());
		lenient().when(plugin.altStart()).thenReturn(HERE);

		ChoiceStore choices = new ChoiceStore(() -> configManager, Gson::new, "gps");
		session = new RouteSession();
		RouteController routes = new RouteController(plugin, session, new MethodExclusions(choices, () -> { }), choices,
			new PluginMessageBridge(plugin, () -> null));
		routes.start(service);
		marker = new WorldMapMarker(() -> mapPoints);
		offRoute = new OffRouteTracker();
		journey = new JourneyTracker();
		destination = new DestinationController(plugin, session, routes, marker, offRoute, journey);
	}

	@Test
	public void aPinRecordsTheDestinationAndArmsTheJourney()
	{
		session.begin(HERE, Set.of(BANK));
		destination.pin(THERE);

		assertEquals(HERE, destination.start());
		assertEquals(Set.of(THERE), destination.targets());
		assertTrue(destination.hasTargets());
		assertEquals("map pin", destination.source());
		assertFalse(destination.isRoundTrip());
		assertEquals("the pin sits on the target", THERE, marker.pinnedTile());
		assertEquals("a fresh destination starts at the default cost band", RouteSession.DEFAULT_COST_MULTIPLE, session.costMultiple());
		verify(pathfinderConfig).refresh();
		verify(pathfinderConfig).filterLocations(Set.of(THERE), false);
		assertEquals("the journey is armed, not started", 0, journey.elapsedMillis(1000));
	}

	@Test
	public void aClearForgetsEverythingAndKeepsTheCatalogStreaming()
	{
		destination.pin(THERE);
		destination.clear();

		assertEquals(WorldPointUtil.UNDEFINED, destination.start());
		assertTrue(destination.targets().isEmpty());
		assertNull(destination.source());
		assertEquals(WorldPointUtil.UNDEFINED, marker.pinnedTile());
		verify(service).generate(eq(WorldPointUtil.UNDEFINED), eq(Set.of()), any(), any(), anyInt(), anyInt(), anyBoolean(), any());
	}

	@Test
	public void withoutAPlayerPositionNothingChanges()
	{
		when(plugin.getPlayerLocation()).thenReturn(WorldPointUtil.UNDEFINED);
		destination.pin(THERE);
		assertTrue(destination.targets().isEmpty());
		assertEquals(WorldPointUtil.UNDEFINED, marker.pinnedTile());
		verify(pathfinderConfig, never()).refresh();
	}

	@Test
	public void aRoundTripCategoryKeepsItsFlagAndRecomputesAtOnce()
	{
		destination.setNearestCategory(Set.of(BANK, THERE), "nearest bank", true);
		assertTrue(destination.isRoundTrip());
		assertEquals(Set.of(BANK, THERE), destination.targets());
		assertEquals("nearest bank", destination.source());
		assertEquals("a category has no single tile to pin", WorldPointUtil.UNDEFINED, marker.pinnedTile());
		verify(service, times(1)).generate(eq(HERE), eq(Set.of(BANK, THERE)), any(), any(), anyInt(), anyInt(), eq(true), any());

		destination.pin(THERE);
		assertFalse("an ordinary destination is one-way again", destination.isRoundTrip());
		destination.setNearestCategory(Set.of(), "nothing", true);
		assertEquals("an empty category is ignored", Set.of(THERE), destination.targets());
	}

	@Test
	public void findClosestAppendsTheCategoryToTheCurrentTargets()
	{
		when(pathfinderConfig.getDestinations("bank")).thenReturn(Set.of(BANK));
		destination.pin(THERE);
		destination.findClosest("bank");
		assertEquals(Set.of(THERE, BANK), destination.targets());
	}

	@Test
	public void aRecalculationMovesTheStartDropsThePickAndRegenerates()
	{
		destination.pin(THERE);
		int elsewhere = WorldPointUtil.packWorldPoint(3250, 3250, 0);
		destination.recalculateFrom(elsewhere, Set.of(THERE));
		assertEquals(elsewhere, destination.start());
		verify(service).generate(eq(elsewhere), eq(Set.of(THERE)), any(), any(), anyInt(), anyInt(), anyBoolean(), any());
	}
}
