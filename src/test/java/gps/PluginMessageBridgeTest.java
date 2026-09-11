package gps;

import gps.pathfinder.PathStep;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Plan step L31: the plugin-message flow, out of the plugin class. A foreign namespace is
 * ignored; a new target arms the journey and expands the tile; an empty target keeps the current
 * destination and the running journey; without a start the player's position stands in, or the
 * request is dropped when there is none; overrides apply before the request and a clear drops
 * both; the displayed route's transports go out on both namespaces, only when enabled and routed.
 */
@RunWith(MockitoJUnitRunner.class)
public class PluginMessageBridgeTest
{
	private static final int START = WorldPointUtil.packWorldPoint(3200, 3200, 0);
	private static final int TARGET = WorldPointUtil.packWorldPoint(3210, 3205, 0);

	@Mock
	ShortestPathPlugin plugin;
	@Mock
	EventBus eventBus;
	@Mock
	ShortestPathConfig config;

	private PluginMessageBridge bridge;

	@Before
	public void setUp()
	{
		bridge = new PluginMessageBridge(plugin, () -> eventBus);
	}

	private static PluginMessage path(Map<String, Object> data)
	{
		return new PluginMessage(PluginMessageCodec.NAMESPACE_LEGACY, PluginMessageCodec.ACTION_PATH, data);
	}

	@Test
	public void foreignNamespacesAreIgnored()
	{
		bridge.receive(new PluginMessage("other", PluginMessageCodec.ACTION_PATH,
			Map.of(PluginMessageCodec.KEY_TARGET, new WorldPoint(3210, 3205, 0))));
		verifyNoInteractions(plugin, eventBus);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void aNewTargetArmsTheJourneyAndExpandsTheTile()
	{
		Map<String, Object> data = new HashMap<>();
		data.put(PluginMessageCodec.KEY_START, new WorldPoint(3200, 3200, 0));
		data.put(PluginMessageCodec.KEY_TARGET, new WorldPoint(3210, 3205, 0));
		data.put(PluginMessageCodec.KEY_SOURCE, "Quest Helper");
		bridge.receive(path(data));

		verify(plugin).setTargetSource("Quest Helper");
		verify(plugin).armJourney();
		ArgumentCaptor<Set<Integer>> ends = ArgumentCaptor.forClass(Set.class);
		verify(plugin).setDestination(eq(START), ends.capture(), eq(false));
		assertTrue("the target tile is among the ends", ends.getValue().contains(TARGET));
		verify(plugin, never()).applyConfigOverrides(any());
	}

	@Test
	public void anEmptyTargetKeepsTheCurrentDestinationAndTheRunningJourney()
	{
		when(plugin.hasPathTargets()).thenReturn(true);
		when(plugin.getPathTargets()).thenReturn(Set.of(TARGET));
		bridge.receive(path(Map.of(PluginMessageCodec.KEY_START, new WorldPoint(3200, 3200, 0))));

		verify(plugin, never()).armJourney();
		verify(plugin).setDestination(START, Set.of(TARGET), true);
	}

	@Test
	public void withoutAStartThePlayerStandsInOrTheRequestIsDropped()
	{
		Map<String, Object> data = Map.of(PluginMessageCodec.KEY_TARGET, new WorldPoint(3210, 3205, 0));
		when(plugin.getPlayerLocation()).thenReturn(WorldPointUtil.UNDEFINED);
		bridge.receive(path(data));
		verify(plugin, never()).setDestination(anyInt(), any(), anyBoolean());

		when(plugin.getPlayerLocation()).thenReturn(START);
		bridge.receive(path(data));
		verify(plugin).setDestination(eq(START), any(), eq(false));
	}

	@Test
	public void overridesApplyBeforeTheRequestAndAClearDropsBoth()
	{
		Map<String, Object> data = new HashMap<>();
		data.put(PluginMessageCodec.KEY_START, new WorldPoint(3200, 3200, 0));
		data.put(PluginMessageCodec.KEY_TARGET, new WorldPoint(3210, 3205, 0));
		data.put(PluginMessageCodec.KEY_CONFIG_OVERRIDE, Map.of("avoidWilderness", true));
		bridge.receive(path(data));
		verify(plugin).applyConfigOverrides(Map.of("avoidWilderness", true));
		verify(plugin).setDestination(eq(START), any(), eq(false));

		bridge.receive(new PluginMessage(PluginMessageCodec.NAMESPACE, PluginMessageCodec.ACTION_CLEAR, Map.of()));
		verify(plugin).clearConfigOverrides();
		verify(plugin).clearPinnedTarget();
	}

	@Test
	public void transportsGoOutOnBothNamespacesOnlyWhenEnabledAndRouted()
	{
		when(plugin.hasPathTargets()).thenReturn(true);
		when(plugin.getGpsConfig()).thenReturn(config);
		when(config.postTransports()).thenReturn(false);
		bridge.postTransports();
		verifyNoInteractions(eventBus);

		when(config.postTransports()).thenReturn(true);
		when(plugin.getDisplayPath()).thenReturn(List.of());
		bridge.postTransports();
		verifyNoInteractions(eventBus);

		when(plugin.getDisplayPath()).thenReturn(List.of(new PathStep(START, false), new PathStep(TARGET, false)));
		when(plugin.transportsForEdge(any(), any())).thenReturn(Set.of());
		bridge.postTransports();
		ArgumentCaptor<PluginMessage> posted = ArgumentCaptor.forClass(PluginMessage.class);
		verify(eventBus, times(2)).post(posted.capture());
		List<String> namespaces = new ArrayList<>();
		for (PluginMessage message : posted.getAllValues())
		{
			assertEquals(PluginMessageCodec.ACTION_TRANSPORTS, message.getName());
			namespaces.add(message.getNamespace());
		}
		assertEquals(List.of(PluginMessageCodec.NAMESPACE, PluginMessageCodec.NAMESPACE_LEGACY), namespaces);
	}
}
