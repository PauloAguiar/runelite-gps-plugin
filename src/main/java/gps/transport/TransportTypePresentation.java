package gps.transport;

import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.Quest;

/**
 * What the player sees of a transport type, in one table (plan step L5): the catalog category
 * it lists under, the vehicle word of a route label ("Glider to Gandius"), the Travel-options
 * checkbox that owns it (for lock reasons), and the quest that gates the whole network. Four
 * switches used to carry this across the method identity, the config and the panel, and a type
 * added to the enum could fall through their defaults unnoticed; the test proves every type has
 * a row.
 */
public final class TransportTypePresentation
{
	/** The travel option named for a type no checkbox owns. */
	static final String NO_TRAVEL_OPTION = "That travel option";

	private static final class Row
	{
		final String category;
		final String vehicle;
		final String travelOption;
		final Quest gateQuest;

		Row(String category, String vehicle, String travelOption, Quest gateQuest)
		{
			this.category = category;
			this.vehicle = vehicle;
			this.travelOption = travelOption;
			this.gateQuest = gateQuest;
		}
	}

	private static final Map<TransportType, Row> ROWS = new EnumMap<>(TransportType.class);

	static
	{
		//  type                                  category              vehicle           travel option              gate quest
		row(TransportType.TRANSPORT,              "Other",              null,             NO_TRAVEL_OPTION,          null);
		row(TransportType.AGILITY_SHORTCUT,       "Other",              null,             "Agility shortcuts",       null);
		row(TransportType.GRAPPLE_SHORTCUT,       "Other",              null,             "Grapple shortcuts",       null);
		row(TransportType.BOAT,                   "Boats & ships",      "Boat",           "Boats",                   null);
		row(TransportType.CANOE,                  "Canoes",             "Canoe",          "Canoes",                  null);
		row(TransportType.CHARTER_SHIP,           "Boats & ships",      "Charter ship",   "Charter ships",           null);
		row(TransportType.SHIP,                   "Boats & ships",      "Ship",           "Ships",                   null);
		row(TransportType.SAILING,                "Other",              null,             NO_TRAVEL_OPTION,          null);
		row(TransportType.FAIRY_RING,             "Fairy rings",        null,             "Fairy rings",             null);
		row(TransportType.GNOME_GLIDER,           "Gnome gliders",      "Glider",         "Gnome gliders",           Quest.THE_GRAND_TREE);
		row(TransportType.HOT_AIR_BALLOON,        "Hot air balloons",   "Balloon",        "Hot air balloons",        null);
		row(TransportType.MAGIC_CARPET,           "Magic carpets",      "Magic carpet",   "Magic carpets",           null);
		row(TransportType.MAGIC_MUSHTREE,         "Mushtrees",          "Mushtree",       "Magic mushtrees",         Quest.BONE_VOYAGE);
		row(TransportType.MINECART,               "Minecarts",          "Minecart",       "Minecarts",               null);
		row(TransportType.MOUNTAIN_GUIDE,         "Mountain guides",    "Mountain guide", "Mountain guides",         null);
		row(TransportType.QUETZAL,                "Quetzals",           "Quetzal",        "Quetzals",                null);
		row(TransportType.QUETZAL_WHISTLE,        "Quetzals",           null,             "Quetzals",                null);
		row(TransportType.SEASONAL_TRANSPORTS,    "Seasonal",           null,             "Seasonal transports",     null);
		row(TransportType.SPIRIT_TREE,            "Spirit trees",       "Spirit tree",    "Spirit trees",            Quest.TREE_GNOME_VILLAGE);
		row(TransportType.TELEPORTATION_BOX,      "Jewellery box",      "Jewellery box",  NO_TRAVEL_OPTION,          null);
		row(TransportType.TELEPORTATION_ITEM,     "Items",              null,             NO_TRAVEL_OPTION,          null);
		row(TransportType.TELEPORTATION_LEVER,    "Levers",             null,             "Teleportation levers",    null);
		row(TransportType.TELEPORTATION_MINIGAME, "Minigame teleports", null,             "Teleportation minigames", null);
		row(TransportType.TELEPORTATION_PORTAL,   "Portals",            null,             "Teleportation portals",   null);
		row(TransportType.TELEPORTATION_PORTAL_POH, "Portals",          null,             NO_TRAVEL_OPTION,          null);
		row(TransportType.TELEPORTATION_SPELL,    "Spells",             null,             "Teleportation spells",    null);
		row(TransportType.WILDERNESS_OBELISK,     "Obelisks",           "Obelisk",        "Wilderness obelisks",     null);
	}

	private static void row(TransportType type, String category, String vehicle, String travelOption, Quest gateQuest)
	{
		ROWS.put(type, new Row(category, vehicle, travelOption, gateQuest));
	}

	private TransportTypePresentation()
	{
	}

	/** Whether the table has a row for the type (the test requires one for every type). */
	public static boolean hasRow(TransportType type)
	{
		return ROWS.containsKey(type);
	}

	/** The catalog category ("Spells", "Boats & ships"); "Other" for a null type or no row. */
	public static String categoryOf(TransportType type)
	{
		Row row = type == null ? null : ROWS.get(type);
		return row == null ? "Other" : row.category;
	}

	/** The vehicle word of a route label ("Glider to Gandius"), or null when the label is the destination alone. */
	public static String vehicleOf(TransportType type)
	{
		Row row = type == null ? null : ROWS.get(type);
		return row == null ? null : row.vehicle;
	}

	/** The Travel-options checkbox that owns the type, for lock reasons. */
	public static String travelOptionOf(TransportType type)
	{
		Row row = type == null ? null : ROWS.get(type);
		return row == null ? NO_TRAVEL_OPTION : row.travelOption;
	}

	/** The quest that gates the whole network at the type level, or null. */
	public static Quest gateQuestOf(TransportType type)
	{
		Row row = type == null ? null : ROWS.get(type);
		return row == null ? null : row.gateQuest;
	}
}
