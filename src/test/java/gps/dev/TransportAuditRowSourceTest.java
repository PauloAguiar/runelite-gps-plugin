package gps.dev;

import gps.WorldPointUtil;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The audit panel lists the curated data and the operator's own rows (captures and builder
 * saves) as selectable entries that reload into the builder, so every TSV layout the data uses
 * has to parse back into the builder's fields: the standard transports layout the captures file
 * shares, a file with its own column order, and destination-only teleport rows.
 */
public class TransportAuditRowSourceTest
{
	private static final String[] STANDARD = {"Origin", "Destination", "menuOption menuTarget objectID",
		"Skills", "Items", "Quests", "Varbits", "VarPlayers", "Duration", "Display info"};

	@Test
	public void aCaptureRowKeepsItsFieldsAndTrailingTags()
	{
		TransportAuditPlugin.RowSource source = TransportAuditPlugin.RowSource.parse(STANDARD,
			"1481 9670 0\t1457 9649 1\tPass-through Entrance 51375\t\t\t\t\t\t1\t\t\t~geometry ~duration",
			"transport-captures.tsv");
		assertEquals(WorldPointUtil.packWorldPoint(1481, 9670, 0), source.origin);
		assertEquals(WorldPointUtil.packWorldPoint(1457, 9649, 1), source.destination);
		assertEquals("Pass-through Entrance 51375", source.menu);
		assertEquals(51375, source.objectId());
		assertEquals("1", source.duration);
		assertEquals("~geometry ~duration", source.tags);
		assertEquals("Pass-through Entrance 51375", source.label());
	}

	@Test
	public void aFileWithItsOwnColumnOrderMapsByHeader()
	{
		String[] boats = {"Origin", "Destination", "menuOption menuTarget objectID", "Skills", "Items",
			"Quests", "Varbits", "Duration", "Display info"};
		TransportAuditPlugin.RowSource source = TransportAuditPlugin.RowSource.parse(boats,
			"2707 3735 0\t2849 3967 0\tWeiss Boat 21176\t\t\tMaking Friends with My Arm\t\t12\tWeiss",
			"boats.tsv");
		assertEquals("Making Friends with My Arm", source.quests);
		assertEquals("12", source.duration);
		assertEquals("Weiss", source.displayInfo);
		assertEquals("", source.varPlayers);
		assertEquals("Weiss", source.label());
	}

	@Test
	public void aDestinationOnlyTeleportAnchorsOnItsLanding()
	{
		String[] items = {"Destination", "menuOption menuTarget objectID", "Skills", "Items", "Quests",
			"Duration", "Display info", "Consumable", "Wilderness level", "Varbits", "VarPlayers"};
		TransportAuditPlugin.RowSource source = TransportAuditPlugin.RowSource.parse(items,
			"3087 3496 0\t\t\tAMULET_OF_GLORY=1\t\t4\tAmulet of glory: Edgeville\tT\t30\t\t",
			"teleportation_items.tsv");
		assertEquals(WorldPointUtil.UNDEFINED, source.origin);
		assertEquals(WorldPointUtil.packWorldPoint(3087, 3496, 0), source.anchor());
		assertEquals("AMULET_OF_GLORY=1", source.items);
		assertEquals(-1, source.objectId());
	}

	@Test
	public void commentsAndShortLinesAreNotRows()
	{
		assertNull(TransportAuditPlugin.RowSource.parse(STANDARD, "# Lumbridge", "transports.tsv"));
		assertNull(TransportAuditPlugin.RowSource.parse(STANDARD, "3213 3221 0", "transports.tsv"));
	}
}
