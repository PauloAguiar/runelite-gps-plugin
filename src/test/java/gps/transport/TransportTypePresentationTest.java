package gps.transport;

import gps.BoatHull;
import net.runelite.api.Quest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Plan step L5: the per-type presentation (catalog category, vehicle word, owning travel
 * option, gate quest) is one table, and the hull tiers are an enum. These pin the values the
 * former switches produced and require a row for every type, so a new type cannot fall through
 * a default unnoticed.
 */
public class TransportTypePresentationTest
{
	@Test
	public void everyTypeHasARow()
	{
		for (TransportType type : TransportType.values())
		{
			assertTrue(type + " needs a presentation row", TransportTypePresentation.hasRow(type));
		}
	}

	@Test
	public void categoriesMatchTheCatalog()
	{
		assertEquals("Spells", TransportTypePresentation.categoryOf(TransportType.TELEPORTATION_SPELL));
		assertEquals("Boats & ships", TransportTypePresentation.categoryOf(TransportType.CHARTER_SHIP));
		assertEquals("Boats & ships", TransportTypePresentation.categoryOf(TransportType.BOAT));
		assertEquals("Portals", TransportTypePresentation.categoryOf(TransportType.TELEPORTATION_PORTAL_POH));
		assertEquals("Quetzals", TransportTypePresentation.categoryOf(TransportType.QUETZAL_WHISTLE));
		assertEquals("Seasonal", TransportTypePresentation.categoryOf(TransportType.SEASONAL_TRANSPORTS));
		assertEquals("Other", TransportTypePresentation.categoryOf(TransportType.TRANSPORT));
		assertEquals("Other", TransportTypePresentation.categoryOf(null));
	}

	@Test
	public void vehicleWordsMatchTheRouteLabels()
	{
		assertEquals("Glider", TransportTypePresentation.vehicleOf(TransportType.GNOME_GLIDER));
		assertEquals("Jewellery box", TransportTypePresentation.vehicleOf(TransportType.TELEPORTATION_BOX));
		assertEquals("Obelisk", TransportTypePresentation.vehicleOf(TransportType.WILDERNESS_OBELISK));
		assertNull("a spell label is the destination alone", TransportTypePresentation.vehicleOf(TransportType.TELEPORTATION_SPELL));
		assertNull(TransportTypePresentation.vehicleOf(null));
	}

	@Test
	public void travelOptionsAndGateQuests()
	{
		assertEquals("Quetzals", TransportTypePresentation.travelOptionOf(TransportType.QUETZAL_WHISTLE));
		assertEquals("Seasonal transports", TransportTypePresentation.travelOptionOf(TransportType.SEASONAL_TRANSPORTS));
		assertEquals(TransportTypePresentation.NO_TRAVEL_OPTION, TransportTypePresentation.travelOptionOf(TransportType.TRANSPORT));
		assertEquals(Quest.THE_GRAND_TREE, TransportTypePresentation.gateQuestOf(TransportType.GNOME_GLIDER));
		assertEquals(Quest.BONE_VOYAGE, TransportTypePresentation.gateQuestOf(TransportType.MAGIC_MUSHTREE));
		assertEquals(Quest.TREE_GNOME_VILLAGE, TransportTypePresentation.gateQuestOf(TransportType.SPIRIT_TREE));
		assertNull(TransportTypePresentation.gateQuestOf(TransportType.FAIRY_RING));
	}

	@Test
	public void hullTiers()
	{
		assertEquals(BoatHull.RAFT, BoatHull.fromVarbit(0));
		assertEquals(BoatHull.SKIFF, BoatHull.fromVarbit(1));
		assertEquals(BoatHull.SLOOP, BoatHull.fromVarbit(2));
		assertNull("an unknown tier shows no type", BoatHull.fromVarbit(3));
		assertEquals("Sloop", BoatHull.SLOOP.displayName());
		assertEquals(BoatHull.SKIFF, BoatHull.fromName("Skiff"));
		assertNull(BoatHull.fromName(""));
	}
}
