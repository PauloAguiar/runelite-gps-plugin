package gps;

import com.google.gson.Gson;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
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
 * Plan step L35: the generation controller, out of the plugin class. A mode change saves the
 * mode and regenerates once with the last inputs (the same mode again does nothing); opening the
 * panel re-checks the auto-compute decision, which fires once per target set and never before the
 * generator exists; show more widens both the route budget and the cost band before regenerating.
 */
@RunWith(MockitoJUnitRunner.class)
public class RouteControllerTest
{
	private static final int START = WorldPointUtil.packWorldPoint(3200, 3200, 0);
	private static final int TARGET = WorldPointUtil.packWorldPoint(3210, 3205, 0);

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

	private RouteSession session;
	private RouteController controller;

	@Before
	public void setUp()
	{
		// The client thread runs inline: the controller hops to it from the panel's entry points.
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

		ChoiceStore choices = new ChoiceStore(() -> configManager, Gson::new, "gps");
		MethodExclusions exclusions = new MethodExclusions(choices, () -> { });
		session = new RouteSession();
		controller = new RouteController(plugin, session, exclusions, choices,
			new PluginMessageBridge(plugin, () -> null));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void aModeChangeSavesAndRegeneratesOnceWithTheLastInputs()
	{
		controller.start(service);
		controller.trigger(START, Set.of(TARGET));
		controller.setMode(AlternativeRoutesMode.ALL_EVERYTHING);
		controller.setMode(AlternativeRoutesMode.ALL_EVERYTHING);
		controller.setMode(null);

		verify(configManager).setConfiguration("gps", ChoiceStore.CONFIG_KEY_MODE, "ALL_EVERYTHING");
		ArgumentCaptor<Set<Integer>> targets = ArgumentCaptor.forClass(Set.class);
		ArgumentCaptor<AlternativeRoutesMode> modes = ArgumentCaptor.forClass(AlternativeRoutesMode.class);
		verify(service, times(2)).generate(eq(START), targets.capture(), any(), modes.capture(), anyInt(), anyInt(),
			anyBoolean(), any());
		assertEquals(List.of(AlternativeRoutesMode.OWNED_INVENTORY, AlternativeRoutesMode.ALL_EVERYTHING), modes.getAllValues());
		assertEquals("the same destination, regenerated under the new mode", Set.of(TARGET), targets.getAllValues().get(1));
		assertEquals(AlternativeRoutesMode.ALL_EVERYTHING, controller.mode());
	}

	@Test
	public void openingThePanelRechecksTheAutoComputeDecisionOncePerTargetSet()
	{
		when(plugin.getPathTargets()).thenReturn(Set.of(TARGET));
		controller.maybeAutoCompute();
		verify(service, never()).generate(anyInt(), any(), any(), any(), anyInt(), anyInt(), anyBoolean(), any());

		when(plugin.altStart()).thenReturn(START);
		controller.start(service);
		controller.setPanelVisible(true);
		controller.setPanelVisible(true);
		controller.maybeAutoCompute();
		verify(service, times(1)).generate(eq(START), eq(Set.of(TARGET)), any(), any(), eq(10), anyInt(),
			anyBoolean(), any());
	}

	@Test
	public void showMoreWidensTheBudgetAndTheBandThenRegenerates()
	{
		controller.start(service);
		controller.trigger(START, Set.of(TARGET));
		// More routes are likely only once a non-empty page settled under a budget below the cap.
		when(service.wasMoreLikely()).thenReturn(true);
		when(plugin.sortByEffectiveOrder(any())).thenAnswer(i -> i.getArgument(0));
		RouteOption route = new RouteOption(List.of(), List.of(), List.of(), List.of(), 10, 10, true, Set.of(), List.of(), 0);
		controller.onUpdate(List.of(route), List.of(), Map.of(), true);
		assertTrue(controller.canLoadMore());
		controller.loadMore();

		ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
		ArgumentCaptor<Integer> multiples = ArgumentCaptor.forClass(Integer.class);
		verify(service, times(2)).generate(eq(START), eq(Set.of(TARGET)), any(), any(), limits.capture(),
			multiples.capture(), anyBoolean(), any());
		assertTrue("the route budget grew", limits.getAllValues().get(1) > limits.getAllValues().get(0));
		assertTrue("the cost band grew", multiples.getAllValues().get(1) > multiples.getAllValues().get(0));
	}
}
