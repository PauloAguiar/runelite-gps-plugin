package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Which destination tiles get sea legs. A water pin is one place and always does; a category
 * spread over the map ("nearest bank") does only for a player already aboard. Four bank tiles
 * stand on sailable water (the Bank Boat, the Fossil Island shipwreck's chest), and treating them
 * as water pins cost every on-foot "nearest bank" click four ocean floods, about 700 ms.
 */
public class SailingSeaWaterPinsTest
{
	private static final int PANDEMONIUM_WATER = WorldPointUtil.packWorldPoint(3093, 2981, 0);
	private static final int BANK_BOAT = WorldPointUtil.packWorldPoint(2280, 2544, 0);
	private static final Set<Integer> FOSSIL_ISLAND_BANK = Set.of(
		WorldPointUtil.packWorldPoint(3767, 3900, 0), WorldPointUtil.packWorldPoint(3767, 3898, 0));

	private static CollisionMap map;
	private static Set<Integer> nearestBank;

	@BeforeClass
	public static void load()
	{
		Client client = Mockito.mock(Client.class, Mockito.withSettings().lenient());
		Mockito.when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		PathfinderConfig config = new TestPathfinderConfig(client, new TestShortestPathConfig()).copyForPlanning();
		map = config.getMap();
		// The panel's "nearest bank": the amenity dump's bank tiles and the engine's own.
		nearestBank = new HashSet<>(Destinations.tilesForCategory("bank", null));
		nearestBank.addAll(config.getDestinations("bank"));
	}

	@Test
	public void aPinnedWaterTileIsAWaterPin()
	{
		assertEquals(Set.of(PANDEMONIUM_WATER), SailingSea.waterPins(map, Set.of(PANDEMONIUM_WATER), false));
		assertEquals(Set.of(PANDEMONIUM_WATER), SailingSea.waterPins(map, Set.of(PANDEMONIUM_WATER), true));
	}

	@Test
	public void aCategoryOnFootGetsNoSeaLegs()
	{
		assertTrue("the category really does hold the Bank Boat", nearestBank.contains(BANK_BOAT));
		assertEquals(Set.of(), SailingSea.waterPins(map, nearestBank, false));
	}

	@Test
	public void aCategoryAboardKeepsItsWaterTiles()
	{
		Set<Integer> aboard = SailingSea.waterPins(map, nearestBank, true);
		assertTrue("a sailor can still be routed to the Bank Boat by sea", aboard.contains(BANK_BOAT));
	}

	@Test
	public void aNamedPlaceIsCompactAndKeepsItsWaterTiles()
	{
		assertEquals(FOSSIL_ISLAND_BANK, SailingSea.waterPins(map, FOSSIL_ISLAND_BANK, false));
	}
}
