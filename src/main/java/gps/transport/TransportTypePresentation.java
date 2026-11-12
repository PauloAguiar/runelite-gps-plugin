package gps.transport;

import java.util.*;
import lombok.RequiredArgsConstructor;
import net.runelite.api.*;
import static gps.transport.TransportType.*;

/**
 * What the player sees of a transport type, in one table (plan step L5): the catalog category
 * it lists under, the vehicle word of a route label ("Glider to Gandius"), the Travel-options
 * checkbox that owns it (for lock reasons), and the quest that gates the whole network. Four
 * switches used to carry this across the method identity, the config and the panel, and a type
 * added to the enum could fall through their defaults unnoticed; the test proves every type has
 * a row.
 */
public final class TransportTypePresentation {
    /** The travel option named for a type no checkbox owns. */
    static final String NO_TRAVEL_OPTION = "That travel option";

    @RequiredArgsConstructor private static final class Row {
        final String category;
        final String vehicle;
        final String travelOption;
        final Quest gateQuest;

    }

    private static final Map<TransportType, Row> ROWS = new EnumMap<>(TransportType.class);

    static {
        //  type                                  category              vehicle           travel option              gate quest
        row(TRANSPORT,              "Other",              null,             NO_TRAVEL_OPTION,          null);
        row(AGILITY_SHORTCUT,       "Other",              null,             "Agility shortcuts",       null);
        row(GRAPPLE_SHORTCUT,       "Other",              null,             "Grapple shortcuts",       null);
        row(BOAT,                   "Boats & ships",      "Boat",           "Boats",                   null);
        row(CANOE,                  "Canoes",             "Canoe",          "Canoes",                  null);
        row(CHARTER_SHIP,           "Boats & ships",      "Charter ship",   "Charter ships",           null);
        row(SHIP,                   "Boats & ships",      "Ship",           "Ships",                   null);
        row(SAILING,                "Other",              null,             NO_TRAVEL_OPTION,          null);
        row(FAIRY_RING,             "Fairy rings",        null,             "Fairy rings",             null);
        row(GNOME_GLIDER,           "Gnome gliders",      "Glider",         "Gnome gliders",           Quest.THE_GRAND_TREE);
        row(HOT_AIR_BALLOON,        "Hot air balloons",   "Balloon",        "Hot air balloons",        null);
        row(MAGIC_CARPET,           "Magic carpets",      "Magic carpet",   "Magic carpets",           null);
        row(MAGIC_MUSHTREE,         "Mushtrees",          "Mushtree",       "Magic mushtrees",         Quest.BONE_VOYAGE);
        row(MINECART,               "Minecarts",          "Minecart",       "Minecarts",               null);
        row(MOUNTAIN_GUIDE,         "Mountain guides",    "Mountain guide", "Mountain guides",         null);
        row(QUETZAL,                "Quetzals",           "Quetzal",        "Quetzals",                null);
        row(QUETZAL_WHISTLE,        "Quetzals",           null,             "Quetzals",                null);
        row(SEASONAL_TRANSPORTS,    "Seasonal",           null,             "Seasonal transports",     null);
        row(SPIRIT_TREE,            "Spirit trees",       "Spirit tree",    "Spirit trees",            Quest.TREE_GNOME_VILLAGE);
        row(TELEPORTATION_BOX,      "Jewellery box",      "Jewellery box",  NO_TRAVEL_OPTION,          null);
        row(TELEPORTATION_ITEM,     "Items",              null,             NO_TRAVEL_OPTION,          null);
        row(TELEPORTATION_LEVER,    "Levers",             null,             "Teleportation levers",    null);
        row(TELEPORTATION_MINIGAME, "Minigame teleports", null,             "Teleportation minigames", null);
        row(TELEPORTATION_PORTAL,   "Portals",            null,             "Teleportation portals",   null);
        row(TELEPORTATION_PORTAL_POH, "Portals",          null,             NO_TRAVEL_OPTION,          null);
        row(TELEPORTATION_SPELL,    "Spells",             null,             "Teleportation spells",    null);
        row(WILDERNESS_OBELISK,     "Obelisks",           "Obelisk",        "Wilderness obelisks",     null);
    }

    private static void row(TransportType type, String category, String vehicle, String travelOption, Quest gateQuest) {
        ROWS.put(type, new Row(category, vehicle, travelOption, gateQuest));
    }

    /** Whether the table has a row for the type (the test requires one for every type). */
    public static boolean hasRow(TransportType type) { return ROWS.containsKey(type); }

    /** The catalog category ("Spells", "Boats & ships"); "Other" for a null type or no row. */
    public static String categoryOf(TransportType type) {
        Row row = type == null ? null : ROWS.get(type);
        return row == null ? "Other" : row.category;
    }

    /** The vehicle word of a route label ("Glider to Gandius"), or null when the label is the destination alone. */
    public static String vehicleOf(TransportType type) {
        Row row = type == null ? null : ROWS.get(type);
        return row == null ? null : row.vehicle;
    }

    /** The Travel-options checkbox that owns the type, for lock reasons. */
    public static String travelOptionOf(TransportType type) {
        Row row = type == null ? null : ROWS.get(type);
        return row == null ? NO_TRAVEL_OPTION : row.travelOption;
    }

    /** The quest that gates the whole network at the type level, or null. */
    public static Quest gateQuestOf(TransportType type) {
        Row row = type == null ? null : ROWS.get(type);
        return row == null ? null : row.gateQuest;
    }
}
