package gps;

import gps.pathfinder.PathStep;
import gps.transport.Transport;
import gps.transport.TransportType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Plan step L8: the plugin-message wire format, out of the plugin class. A "path" request may
 * carry its start and target as packed integers or world points (targets also as a set of
 * either), an optional config override and an optional source; a malformed target drops the
 * whole request rather than half of it, and an override-only message is not a path request.
 * The published transports keep the parallel-list shape older consumers read.
 */
public class PluginMessageCodecTest
{
	private static final WorldPoint LUMBRIDGE = new WorldPoint(3222, 3218, 0);
	private static final int VARROCK = WorldPointUtil.packWorldPoint(3212, 3422, 0);

	@Test
	public void bothNamespacesAreOurs()
	{
		assertTrue(PluginMessageCodec.isOurs("gps"));
		assertTrue("the pre-fork namespace keeps older integrations working", PluginMessageCodec.isOurs("shortestpath"));
		assertFalse(PluginMessageCodec.isOurs("questhelper"));
	}

	@Test
	public void startAndTargetInEitherShape()
	{
		Map<String, Object> data = new HashMap<>();
		data.put("start", LUMBRIDGE);
		data.put("target", VARROCK);
		PluginMessageCodec.PathRequest request = PluginMessageCodec.parsePath(data);
		assertEquals(WorldPointUtil.packWorldPoint(LUMBRIDGE), request.start);
		assertEquals(Set.of(VARROCK), request.targets);
		assertEquals("no source given", PluginMessageCodec.DEFAULT_SOURCE, request.source);

		data.put("start", null);
		data.put("target", new WorldPoint(3212, 3422, 0));
		data.put("source", "Quest Helper");
		request = PluginMessageCodec.parsePath(data);
		assertEquals("no start: the player stands in for it", WorldPointUtil.UNDEFINED, request.start);
		assertEquals(Set.of(VARROCK), request.targets);
		assertEquals("Quest Helper", request.source);
	}

	@Test
	public void aSetOfTargetsMixesShapesAndDropsTheRequestOnABadMember()
	{
		Map<String, Object> data = new HashMap<>();
		data.put("target", Set.of(VARROCK, new WorldPoint(3222, 3218, 0)));
		PluginMessageCodec.PathRequest request = PluginMessageCodec.parsePath(data);
		assertEquals(Set.of(VARROCK, WorldPointUtil.packWorldPoint(LUMBRIDGE)), request.targets);

		data.put("target", Set.of(VARROCK, WorldPointUtil.UNDEFINED));
		assertNull("an undefined member drops the whole request", PluginMessageCodec.parsePath(data));
		data.put("target", WorldPointUtil.UNDEFINED);
		assertNull(PluginMessageCodec.parsePath(data));
	}

	@Test
	public void overrideOnlyIsNotAPathRequestAndAnUnknownTargetTypeKeepsTheDestination()
	{
		Map<String, Object> data = new HashMap<>();
		data.put("config", Map.of("avoidWilderness", true));
		assertNull("neither start nor target", PluginMessageCodec.parsePath(data));
		assertEquals(Map.of("avoidWilderness", true), PluginMessageCodec.configOverrideOf(data));

		data.put("config", "not a map");
		assertTrue(PluginMessageCodec.configOverrideOf(data).isEmpty());

		data.put("start", LUMBRIDGE);
		data.put("target", "somewhere");
		PluginMessageCodec.PathRequest request = PluginMessageCodec.parsePath(data);
		assertTrue("an unknown target type means keep the current destination", request.targets.isEmpty());
	}

	@Test
	public void publishedTransportsKeepTheParallelListShape()
	{
		int a = WorldPointUtil.packWorldPoint(3200, 3200, 0);
		int b = WorldPointUtil.packWorldPoint(3201, 3200, 0);
		int c = WorldPointUtil.packWorldPoint(3300, 3300, 0);
		List<PathStep> path = List.of(new PathStep(a, false), new PathStep(b, false), new PathStep(c, false));
		Transport ride = new Transport.TransportBuilder().origin(b).destination(c)
			.type(TransportType.GNOME_GLIDER).displayInfo("Gandius").objectInfo("Glider").build();
		Map<String, Object> data = PluginMessageCodec.encodeTransports(path,
			(from, to) -> from.getPackedPosition() == b ? List.of(ride) : List.of());
		assertEquals(List.of(WorldPointUtil.unpackWorldPoint(b)), data.get("origin"));
		assertEquals(List.of(WorldPointUtil.unpackWorldPoint(c)), data.get("destination"));
		assertEquals(List.of("Glider"), data.get("objectInfo"));
		assertEquals(List.of("Gandius"), data.get("displayInfo"));
	}
}
