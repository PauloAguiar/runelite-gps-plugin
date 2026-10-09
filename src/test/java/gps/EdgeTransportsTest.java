package gps;

import gps.pathfinder.PathStep;
import gps.pathfinder.PathfinderConfig;
import gps.transport.Transport;
import gps.transport.TransportType;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan step L23: the transports a rendered path edge rides, out of the plugin class. Local
 * transports from the current tile and anywhere-teleports landing on the next tile are the
 * candidates; a teleport sharing destinations with a local type on the edge is dropped, and so
 * is one within its shared type's radius (the path is walking to the landing); a same-plane
 * adjacent step hints no teleport at all.
 */
public class EdgeTransportsTest
{
	private static final int QUETZAL_STAND = WorldPointUtil.packWorldPoint(3200, 3200, 0);
	private static final int LANDING = WorldPointUtil.packWorldPoint(3400, 3400, 0);

	private static Transport transport(TransportType type, int origin, int destination)
	{
		Transport transport = mock(Transport.class);
		when(transport.getType()).thenReturn(type);
		when(transport.getOrigin()).thenReturn(origin);
		when(transport.getDestination()).thenReturn(destination);
		return transport;
	}

	private static PathStep at(int packed)
	{
		return new PathStep(packed, false);
	}

	private final Transport quetzal = transport(TransportType.QUETZAL, QUETZAL_STAND, LANDING);
	private final Transport whistle = transport(TransportType.QUETZAL_WHISTLE, Transport.UNDEFINED_ORIGIN, LANDING);
	private final Transport tablet = transport(TransportType.TELEPORTATION_ITEM, Transport.UNDEFINED_ORIGIN, LANDING);

	private PathfinderConfig config()
	{
		PrimitiveIntHashMap<Transport[]> byOrigin = new PrimitiveIntHashMap<>(16);
		byOrigin.put(QUETZAL_STAND, new Transport[]{quetzal});
		PathfinderConfig config = mock(PathfinderConfig.class);
		when(config.getTransportsPacked(anyBoolean())).thenReturn(byOrigin);
		when(config.getUsableTeleports(anyBoolean())).thenReturn(new Transport[]{whistle, tablet});
		return config;
	}

	@Test
	public void aLocalTransportSuppressesTheTeleportThatSharesItsDestinations()
	{
		assertEquals(Set.of(quetzal, tablet), EdgeTransports.forEdge(config(), at(QUETZAL_STAND), at(LANDING)));
	}

	@Test
	public void aFarJumpWithoutTheLocalTypeKeepsBothTeleports()
	{
		PathStep elsewhere = at(WorldPointUtil.packWorldPoint(3000, 3000, 0));
		assertEquals(Set.of(whistle, tablet), EdgeTransports.forEdge(config(), elsewhere, at(LANDING)));
	}

	@Test
	public void walkingToTheLandingWithinTheRadiusDropsTheSharedTeleport()
	{
		PathStep nearby = at(WorldPointUtil.packWorldPoint(3397, 3400, 0)); // 3 tiles: inside the quetzal radius of 5
		assertEquals(Set.of(tablet), EdgeTransports.forEdge(config(), nearby, at(LANDING)));
	}

	@Test
	public void anAdjacentStepHintsNoTeleport()
	{
		PathStep adjacent = at(WorldPointUtil.packWorldPoint(3399, 3400, 0));
		assertEquals(Set.of(), EdgeTransports.forEdge(config(), adjacent, at(LANDING)));
		assertEquals(Set.of(), EdgeTransports.forEdge(config(), null, at(LANDING)));
	}

	/**
	 * Two usable teleports land on the same tile (Lumbridge Home Teleport and the Lumbridge
	 * Teleport spell, capture gps-capture-20261008-213827): the tile labelled both while the route
	 * cast only the spell. A method edge of the route resolves to the route's own method only.
	 */
	@Test
	public void aMethodEdgeOfTheRouteNarrowsToItsOwnMethod()
	{
		PathStep elsewhere = at(WorldPointUtil.packWorldPoint(3000, 3000, 0));
		TeleportMethod spell = new TeleportMethod(TransportType.TELEPORTATION_SPELL, "Lumbridge Teleport", LANDING);
		TeleportMethod home = new TeleportMethod(TransportType.TELEPORTATION_SPELL, "Lumbridge Home Teleport", LANDING);
		Transport cast = transport(TransportType.TELEPORTATION_SPELL, Transport.UNDEFINED_ORIGIN, LANDING);
		when(cast.method()).thenReturn(spell);
		Transport free = transport(TransportType.TELEPORTATION_SPELL, Transport.UNDEFINED_ORIGIN, LANDING);
		when(free.method()).thenReturn(home);
		PathfinderConfig config = mock(PathfinderConfig.class);
		when(config.getTransportsPacked(anyBoolean())).thenReturn(new PrimitiveIntHashMap<>(16));
		when(config.getUsableTeleports(anyBoolean())).thenReturn(new Transport[]{free, cast});
		List<PathStep> path = List.of(elsewhere, at(LANDING));
		RouteOption route = new RouteOption(path, List.of(spell), List.of(1), List.of(4), 4, 4, true, Set.of(), List.of(0), 0);

		assertEquals("both are usable", Set.of(free, cast), EdgeTransports.forEdge(config, elsewhere, at(LANDING)));
		assertEquals("the route casts the spell", Set.of(cast), EdgeTransports.forRouteEdge(config, route, elsewhere, at(LANDING)));
		assertEquals("no route: every usable one", Set.of(free, cast), EdgeTransports.forRouteEdge(config, null, elsewhere, at(LANDING)));
	}

	@Test
	public void theNextStep()
	{
		List<PathStep> path = List.of(at(QUETZAL_STAND), at(LANDING));
		assertSame(path.get(1), EdgeTransports.nextStep(path, 0));
		assertNull(EdgeTransports.nextStep(path, 1));
		assertNull(EdgeTransports.nextStep(path, -1));
		assertNull(EdgeTransports.nextStep(null, 0));
	}
}
