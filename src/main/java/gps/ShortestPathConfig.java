package gps;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@SuppressWarnings("SameReturnValue")
@ConfigGroup(ShortestPathPlugin.CONFIG_GROUP)
public interface ShortestPathConfig extends Config {
    @ConfigSection(
        name = "Settings",
        description = "Pathfinding options",
        position = 0
    )
    String sectionSettings = "sectionSettings";

    @ConfigItem(
        hidden = true,
        keyName = "avoidWilderness",
        name = "Avoid the wilderness",
        description = "Route around the wilderness whenever possible; routes still enter it when the destination is inside",
        position = 1,
        section = sectionSettings
    )
    default boolean avoidWilderness() {
        return true;
    }

    @ConfigItem(
        keyName = "defaultRouteCount",
        name = "Routes per page",
        description = "Routes listed per search; each press of the panel's + adds this many more",
        position = 1,
        section = sectionSettings
    )
    @Range(min = 1, max = 25)
    default int defaultRouteCount() {
        return 10;
    }

    @ConfigItem(
        hidden = true,
        keyName = "useSailing",
        name = "Use sailing routes",
        description = "Sail your own boat between moorings and port berths; times assume a mid-tier hull",
        position = 158,
        section = sectionSettings
    )
    default boolean useSailing() {
        return true;
    }

    @ConfigItem(
        hidden = true,
        keyName = "sailingTeleportAbandon",
        name = "Teleports may abandon the boat",
        description = "Aboard, teleport routes may leave the boat at sea; off, routes only disembark at moorings and berths",
        position = 159,
        section = sectionSettings
    )
    default boolean sailingTeleportAbandon() {
        return false;
    }

    @ConfigItem(
        hidden = true,
        keyName = "sailingAssumeSummon",
        name = "Assume Summon Boat spell",
        description = "Routes may board at any mooring, summoning the boat there (56 Magic, Pandemonium); off, sailing starts only where your boat is moored",
        position = 160,
        section = sectionSettings
    )
    default boolean sailingAssumeSummon() {
        return false;
    }

@ConfigItem(
        hidden = true,
        keyName = "sailingKeepSailing",
        name = "Keep sailing while at the helm",
        description = "Aboard, routes that stay on the water rank first; disembark-and-teleport chains list below them",
        position = 160,
        section = sectionSettings
    )
    default boolean sailingKeepSailing() {
        return true;
    }

    @ConfigItem(
        hidden = true,
        keyName = "useHotAirBalloons",
        name = "Use balloon routes",
        description = "Include hot air balloon flights (Enlightened Journey); each flight burns one log of the destination's type, from your inventory or the stations' Log storage",
        position = 10,
        section = sectionSettings
    )
    default boolean useHotAirBalloons() {
        return true;
    }

    @ConfigItem(
        hidden = true,
        keyName = "useSpiritTrees",
        name = "Use spirit trees",
        description = "Include spirit trees",
        position = 15,
        section = sectionSettings
    )
    default boolean useSpiritTrees() {
        return true;
    }

    @ConfigItem(
        hidden = true,
        keyName = "spiritTreeSmartMode",
        name = "Smart tracking",
        description = "Route only through the farmable spirit trees you have grown, read from the travel menu; off assumes all of them",
        position = 154,
        section = sectionSettings
    )
    default boolean spiritTreeSmartMode() {
        return true;
    }

    @ConfigItem(
        hidden = true,
        keyName = "pohSmartDetect",
        name = "Auto-detect furniture",
        description = "Inside your house, tick the furniture GPS recognises (jewellery box, fairy ring, spirit tree, obelisk); it never unticks, and portals and mounts stay yours to set",
        position = 155,
        section = sectionSettings
    )
    default boolean pohSmartDetect() {
        return true;
    }

    @ConfigItem(
        hidden = true,
        keyName = "hideWarningBanners",
        name = "Hide panel warning banners",
        description = "Collapse the panel's warning banners behind the N warnings row",
        position = 157,
        section = sectionSettings
    )
    default boolean hideWarningBanners() {
        return false;
    }

    @ConfigItem(
        hidden = true,
        keyName = "rememberBank",
        name = "Remember between sessions",
        description = "Save your bank's contents when it closes and load them at login, so + Bank routes see banked items without a visit",
        position = 156,
        section = sectionSettings
    )
    default boolean rememberBank() {
        return true;
    }

    @ConfigItem(
        hidden = true,
        keyName = "useTeleportationItems",
        name = "Use teleportation items",
        description = "Which teleport items routes may use; (perm) means permanent items only, and the All options skip skill, quest and item checks",
        position = 16,
        section = sectionSettings
    )
    default TeleportationItem useTeleportationItems() {
        return TeleportationItem.INVENTORY_NON_CONSUMABLE;
    }

    @ConfigItem(
        keyName = "useSeasonalTransports",
        name = "Enable seasonal transports",
        description = "Include Leagues transports; off hides them everywhere, so turn this on only on a seasonal world",
        position = 22,
        section = sectionSettings
    )
    default boolean useSeasonalTransports() {
        return false;
    }

    @ConfigItem(
        hidden = true,
        keyName = "includeBankPath",
        name = "Include path to bank",
        description = "Suggest teleports from the bank by routing through the closest bank",
        position = 23,
        section = sectionSettings
    )
    default boolean includeBankPath() {
        return false;
    }

    @ConfigItem(
        hidden = true,
        keyName = "balloonStoredLogs",
        name = "Balloon stored logs",
        description = "Logs in the balloon Log storage, read from chat",
        position = 96,
        section = sectionSettings
    )
    default int balloonStoredLogs() {
        return 0;
    }

    @ConfigItem(
        hidden = true,
        keyName = "balloonStoredOakLogs",
        name = "Balloon stored oak logs",
        description = "Oak logs in the balloon Log storage, read from chat",
        position = 97,
        section = sectionSettings
    )
    default int balloonStoredOakLogs() {
        return 0;
    }

    @ConfigItem(
        hidden = true,
        keyName = "balloonStoredWillowLogs",
        name = "Balloon stored willow logs",
        description = "Willow logs in the balloon Log storage, read from chat",
        position = 98,
        section = sectionSettings
    )
    default int balloonStoredWillowLogs() {
        return 0;
    }

    @ConfigItem(
        hidden = true,
        keyName = "balloonStoredYewLogs",
        name = "Balloon stored yew logs",
        description = "Yew logs in the balloon Log storage, read from chat",
        position = 99,
        section = sectionSettings
    )
    default int balloonStoredYewLogs() {
        return 0;
    }

    @ConfigItem(
        hidden = true,
        keyName = "balloonStoredMagicLogs",
        name = "Balloon stored magic logs",
        description = "Magic logs in the balloon Log storage, read from chat",
        position = 100,
        section = sectionSettings
    )
    default int balloonStoredMagicLogs() {
        return 0;
    }

    @ConfigItem(
        hidden = true,
        keyName = "balloonSmartMode",
        name = "Smart Log storage",
        description = "Track the stations' Log storage from chat so flights can be paid from it; off, a flight needs its log in your inventory",
        position = 101,
        section = sectionSettings
    )
    default boolean balloonSmartMode() {
        return true;
    }

    @Range(max = 100)
    @ConfigItem(
        hidden = true,
        keyName = "balloonLogWarningThreshold",
        name = "Warn below",
        description = "Warn when an unlocked route's Log storage falls below this many logs; 0 never warns",
        position = 102,
        section = sectionSettings
    )
    default int balloonLogWarningThreshold() {
        return 10;
    }

    @ConfigItem(
        hidden = true,
        keyName = "balloonStorageSynced",
        name = "Balloon storage synced",
        description = "Whether the Log storage counts have been read from chat at least once",
        position = 103,
        section = sectionSettings
    )
    default boolean balloonStorageSynced() {
        return false;
    }

    @ConfigItem(
        hidden = true,
        keyName = "currencyThreshold",
        name = "Currency threshold",
        description = "Most coins, trading sticks, ecto-tokens or warrior guild tokens one transport may cost",
        position = 24,
        section = sectionSettings
    )
    default int currencyThreshold() {
        return 100000;
    }

    @ConfigItem(
        keyName = "autoRecalculate",
        name = "Auto-recalculate route",
        description = "Recompute the route when you stray past the recalculate distance; off keeps the route and only warns",
        position = 25,
        section = sectionSettings
    )
    default boolean autoRecalculate() {
        return true;
    }

    @ConfigItem(
        keyName = "cancelInstead",
        name = "Cancel instead of recalculating",
        description = "Cancel the path instead of recalculating it past the recalculate distance",
        position = 26,
        section = sectionSettings
    )
    default boolean cancelInstead() {
        return false;
    }

    @Range(
        min = -1,
        max = 20000
    )
    @ConfigItem(
        keyName = "recalculateDistance",
        name = "Recalculate distance",
        description = "Tiles off the path before it is recalculated; -1 never",
        position = 27,
        section = sectionSettings
    )
    default int recalculateDistance() {
        return 18;
    }

    @Range(
        min = 0,
        max = 20000
    )
    @ConfigItem(
        keyName = "offRouteWarnDistance",
        name = "Off-route warning distance",
        description = "Tiles off the path before the overlay warns; past the recalculate distance the route recomputes",
        position = 28,
        section = sectionSettings
    )
    default int offRouteWarnDistance() {
        return 10;
    }

    @Range(
        min = -1,
        max = 50
    )
    @ConfigItem(
        keyName = "finishDistance",
        name = "Finish distance",
        description = "Walking steps from the destination at which the journey counts as complete; -1 never, 0 the exact tile",
        position = 29,
        section = sectionSettings
    )
    default int reachedDistance() {
        return 5;
    }

    @Range(
        max = 30
    )
    @ConfigItem(
        keyName = "seaReachedDistance",
        name = "Sea finish distance",
        description = "Tiles from a water destination at which parking the boat counts as arrival",
        position = 29,
        section = sectionSettings
    )
    default int seaReachedDistance() {
        return 12;
    }

    @ConfigItem(
        hidden = true,
        keyName = "questHelperBannerDismissed",
        name = "Quest Helper banner dismissed",
        description = "Whether the Quest Helper routing banner was dismissed",
        position = 161,
        section = sectionSettings
    )
    default boolean questHelperBannerDismissed() {
        return false;
    }

    @Range(
        max = 20000
    )
    @ConfigItem(
        keyName = "unreachableTargetDistanceThreshold",
        name = "Unreachable target distance",
        description = "Tiles from the target beyond which a finished path counts as not reaching it",
        position = 30,
        section = sectionSettings
    )
    default int unreachableTargetDistance() {
        return 2;
    }



    @Units(
        value = Units.TICKS
    )
    @Range(
        min = 1,
        max = 30
    )
    @ConfigItem(
        keyName = "calculationCutoff",
        name = "Calculation cutoff",
        description = "Ticks without progress toward the target before a calculation stops",
        position = 33,
        section = sectionSettings
    )
    default int calculationCutoff() {
        return 5;
    }

    @ConfigItem(
        keyName = "showTransportInfo",
        name = "Show transport info",
        description = "Show which chat option or object to click for a transport",
        position = 34,
        section = sectionSettings
    )
    default boolean showTransportInfo() {
        return true;
    }

    @ConfigItem(
        keyName = "showBankPickupInfo",
        name = "Show transport hint at pickup",
        description = "At a bank on the path, also show the hint for the next step that needs an item pickup",
        position = 35,
        section = sectionSettings
    )
    default boolean showBankPickupInfo() {
        return false;
    }

    @ConfigItem(
        hidden = true,
        keyName = "usePoh",
        name = "Use my house for routes",
        description = "Master switch: off, no house teleport is ever routed",
        position = 36,
        section = sectionSettings
    )
    default boolean usePoh() {
        return true;
    }

    @ConfigItem(
        hidden = true,
        keyName = "usePohFairyRing",
        name = "Fairy ring",
        description = "Built in your house (85 Construction)",
        position = 37,
        section = sectionSettings
    )
    default boolean usePohFairyRing() {
        return true;
    }

    @ConfigItem(
        hidden = true,
        keyName = "usePohSpiritTree",
        name = "Spirit tree",
        description = "Built in your house (75 Construction, 83 Farming)",
        position = 38,
        section = sectionSettings
    )
    default boolean usePohSpiritTree() {
        return true;
    }

    @ConfigItem(
        hidden = true,
        keyName = "useTeleportationPortalsPoh",
        name = "Teleport portals & nexus",
        description = "Portal chamber and portal nexus destinations in your house",
        position = 39,
        section = sectionSettings
    )
    default boolean useTeleportationPortalsPoh() {
        return true;
    }

    @ConfigItem(
        hidden = true,
        keyName = "pohJewelleryBoxTier",
        name = "Jewellery box",
        description = "The tier built in your house; each tier includes the ones below it, None disables it",
        position = 40,
        section = sectionSettings
    )
    default JewelleryBoxTier pohJewelleryBoxTier() {
        return JewelleryBoxTier.ORNATE;
    }

    @ConfigItem(
        hidden = true,
        keyName = "usePohMountedItems",
        name = "Mounted items",
        description = "Mounted glory, Xeric's talisman, digsite pendant or mythical cape, picked below",
        position = 41,
        section = sectionSettings
    )
    default boolean usePohMountedItems() {
        return true;
    }

    @ConfigItem(
        hidden = true,
        keyName = "pohMountGlory",
        name = "Amulet of glory",
        description = "Mounted Amulet of glory: Edgeville, Karamja, Draynor, Al Kharid",
        position = 42,
        section = sectionSettings
    )
    default boolean pohMountGlory() {
        return true;
    }

    @ConfigItem(
        hidden = true,
        keyName = "pohMountXerics",
        name = "Xeric's talisman",
        description = "Mounted Xeric's talisman: Lookout, Glade, Inferno, Heart, Honour",
        position = 43,
        section = sectionSettings
    )
    default boolean pohMountXerics() {
        return true;
    }

    @ConfigItem(
        hidden = true,
        keyName = "pohMountDigsite",
        name = "Digsite pendant",
        description = "Mounted Digsite pendant: Digsite, Fossil Island, Lithkren",
        position = 44,
        section = sectionSettings
    )
    default boolean pohMountDigsite() {
        return true;
    }

    @ConfigItem(
        hidden = true,
        keyName = "pohMountMythical",
        name = "Mythical cape",
        description = "Mounted Mythical cape: Myths' Guild",
        position = 45,
        section = sectionSettings
    )
    default boolean pohMountMythical() {
        return true;
    }

    @ConfigItem(
        hidden = true,
        keyName = "usePohObelisk",
        name = "Wilderness obelisk",
        description = "Built in your house (80 Construction)",
        position = 42,
        section = sectionSettings
    )
    default boolean usePohObelisk() {
        return true;
    }

    @ConfigSection(
        name = "Travel method modifiers",
        description = "Run-tile cost added when a route uses a method (2 tiles = 1 tick): 0 judges by travel time alone, positive avoids it, negative favours it",
        position = 43,
        closedByDefault = true
    )
    String sectionThresholds = "sectionThresholds";

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costAgilityShortcuts",
        name = "Agility shortcut modifier",
        description = "Run-tile cost added when a route uses an agility shortcut",
        position = 43,
        section = sectionThresholds
    )
    default int costAgilityShortcuts() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costGrappleShortcuts",
        name = "Grapple shortcut modifier",
        description = "Run-tile cost added when a route uses a grapple shortcut",
        position = 44,
        section = sectionThresholds
    )
    default int costGrappleShortcuts() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costBoats",
        name = "Boat modifier",
        description = "Run-tile cost added when a route uses a small boat",
        position = 45,
        section = sectionThresholds
    )
    default int costBoats() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costCanoes",
        name = "Canoe modifier",
        description = "Run-tile cost added when a route uses a canoe",
        position = 46,
        section = sectionThresholds
    )
    default int costCanoes() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costCharterShips",
        name = "Charter ship modifier",
        description = "Run-tile cost added when a route uses a charter ship",
        position = 47,
        section = sectionThresholds
    )
    default int costCharterShips() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costShips",
        name = "Ship modifier",
        description = "Run-tile cost added when a route uses a passenger ship",
        position = 48,
        section = sectionThresholds
    )
    default int costShips() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        hidden = true,
        keyName = "costSailing",
        name = "Sailing modifier",
        description = "Run-tile cost added when a route uses your own boat",
        position = 159,
        section = sectionThresholds
    )
    default int costSailing() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costFairyRings",
        name = "Fairy ring modifier",
        description = "Run-tile cost added when a route uses a fairy ring",
        position = 49,
        section = sectionThresholds
    )
    default int costFairyRings() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costGnomeGliders",
        name = "Gnome glider modifier",
        description = "Run-tile cost added when a route uses a gnome glider",
        position = 50,
        section = sectionThresholds
    )
    default int costGnomeGliders() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costHotAirBalloons",
        name = "Hot air balloon modifier",
        description = "Run-tile cost added when a route uses a hot air balloon",
        position = 51,
        section = sectionThresholds
    )
    default int costHotAirBalloons() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costMagicCarpets",
        name = "Magic carpet modifier",
        description = "Run-tile cost added when a route uses a magic carpet",
        position = 52,
        section = sectionThresholds
    )
    default int costMagicCarpets() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costMagicMushtrees",
        name = "Magic mushtree modifier",
        description = "Run-tile cost added when a route uses a magic mushtree",
        position = 53,
        section = sectionThresholds
    )
    default int costMagicMushtrees() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costMinecarts",
        name = "Minecart modifier",
        description = "Run-tile cost added when a route uses a minecart",
        position = 54,
        section = sectionThresholds
    )
    default int costMinecarts() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costMountainGuides",
        name = "Mountain guide modifier",
        description = "Run-tile cost added when a route uses a mountain guide",
        position = 54,
        section = sectionThresholds
    )
    default int costMountainGuides() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costQuetzals",
        name = "Quetzal modifier",
        description = "Run-tile cost added when a route uses a quetzal",
        position = 55,
        section = sectionThresholds
    )
    default int costQuetzals() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costQuetzalWhistle",
        name = "Quetzal whistle modifier",
        description = "Run-tile cost added when a route uses a quetzal whistle teleport",
        position = 56,
        section = sectionThresholds
    )
    default int costQuetzalWhistle() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costSpiritTrees",
        name = "Spirit tree modifier",
        description = "Run-tile cost added when a route uses a spirit tree",
        position = 57,
        section = sectionThresholds
    )
    default int costSpiritTrees() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costNonConsumableTeleportationItems",
        name = "Teleport item (reusable) modifier",
        description = "Run-tile cost added when a route uses a reusable teleport item",
        position = 58,
        section = sectionThresholds
    )
    default int costNonConsumableTeleportationItems() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costConsumableTeleportationItems",
        name = "Teleport item (consumable) modifier",
        description = "Run-tile cost added when a route uses a consumable teleport item",
        position = 59,
        section = sectionThresholds
    )
    default int costConsumableTeleportationItems() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costTeleportationBoxes",
        name = "Jewellery box modifier",
        description = "Run-tile cost added when a route uses a jewellery box",
        position = 60,
        section = sectionThresholds
    )
    default int costTeleportationBoxes() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costTeleportationLevers",
        name = "Teleport lever modifier",
        description = "Run-tile cost added when a route uses a teleport lever",
        position = 61,
        section = sectionThresholds
    )
    default int costTeleportationLevers() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costTeleportationPortals",
        name = "Teleport portal modifier",
        description = "Run-tile cost added when a route uses a teleport portal",
        position = 62,
        section = sectionThresholds
    )
    default int costTeleportationPortals() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costTeleportationSpells",
        name = "Teleport spell modifier",
        description = "Run-tile cost added when a route uses a teleport spell",
        position = 63,
        section = sectionThresholds
    )
    default int costTeleportationSpells() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costTeleportationMinigames",
        name = "Minigame teleport modifier",
        description = "Run-tile cost added when a route uses a minigame teleport",
        position = 64,
        section = sectionThresholds
    )
    default int costTeleportationMinigames() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costWildernessObelisks",
        name = "Wilderness obelisk modifier",
        description = "Run-tile cost added when a route uses a wilderness obelisk",
        position = 65,
        section = sectionThresholds
    )
    default int costWildernessObelisks() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costSeasonalTransports",
        name = "Seasonal transport modifier",
        description = "Run-tile cost added when a route uses a Leagues transport",
        position = 66,
        section = sectionThresholds
    )
    default int costSeasonalTransports() {
        return 0;
    }

    @Range(
        min = -10000,
        max = 10000
    )
    @ConfigItem(
        keyName = "costBankPickup",
        name = "Bank pickup modifier",
        description = "Run-tile cost added when a route detours through a bank to withdraw a teleport item (+ Bank mode)",
        position = 67,
        section = sectionThresholds
    )
    default int costBankPickup() {
        // Non-zero unlike the travel methods: banking mid-route is a real interruption, so a small
        // friction cost avoids trivial detours that save only a tile or two.
        return 15;
    }

    @ConfigSection(
        name = "Display",
        description = "The path on the world map, minimap and game tiles",
        position = 67
    )
    String sectionDisplay = "sectionDisplay";

    @ConfigItem(
        keyName = "drawMap",
        name = "Draw path on world map",
        description = "Draw the path on the world map",
        position = 68,
        section = sectionDisplay
    )
    default boolean drawMap() {
        return true;
    }

    @ConfigItem(
        keyName = "drawMinimap",
        name = "Draw path on minimap",
        description = "Draw the path on the minimap",
        position = 69,
        section = sectionDisplay
    )
    default boolean drawMinimap() {
        return true;
    }

    @ConfigItem(
        keyName = "drawTiles",
        name = "Draw path on tiles",
        description = "Draw the path on the game tiles",
        position = 70,
        section = sectionDisplay
    )
    default boolean drawTiles() {
        return true;
    }

    @ConfigItem(
        keyName = "highlightRouteItem",
        name = "Highlight the next item",
        description = "Outline the item the route uses next in the inventory, equipment and bank; a worn-only item in the bag says Equip",
        position = 71,
        section = sectionDisplay
    )
    default boolean highlightRouteItem() {
        return true;
    }

    @ConfigItem(
        keyName = "showTeleportPulse",
        name = "Teleport pulse",
        description = "Pulse the tile where the path says to use a teleport",
        position = 72,
        section = sectionDisplay
    )
    default boolean showTeleportPulse() {
        return true;
    }

    @ConfigItem(
        keyName = "showPathPulse",
        name = "Path pulse",
        description = "Animate a pulse flowing along the path on the game tiles",
        position = 73,
        section = sectionDisplay
    )
    default boolean showPathPulse() {
        return true;
    }

    @ConfigItem(
        keyName = "showPathArrows",
        name = "Path arrows",
        description = "Draw arrow heads along the path on the game tiles",
        position = 74,
        section = sectionDisplay
    )
    default boolean showPathArrows() {
        return true;
    }

    @ConfigItem(
        keyName = "showDirections",
        name = "Directions overlay",
        description = "Show a movable step-by-step directions panel for the route on the map",
        position = 75,
        section = sectionDisplay
    )
    default boolean showDirections() {
        return true;
    }

    @ConfigItem(
        keyName = "overrideOverlayTransparency",
        name = "Override RuneLite transparency",
        description = "Use the transparency below for the directions overlay instead of RuneLite's overlay background",
        position = 76,
        section = sectionDisplay
    )
    default boolean overrideOverlayTransparency() {
        return false;
    }

    @Range(min = 0, max = 100)
    @Units(Units.PERCENT)
    @ConfigItem(
        keyName = "overlayTransparency",
        name = "Overlay transparency",
        description = "Overlay background transparency with the override on; 100% invisible, 0% solid, RuneLite's look is about 40%",
        position = 77,
        section = sectionDisplay
    )
    default int overlayTransparency() {
        return 100;
    }

    @ConfigItem(
        keyName = "overlayFontSize",
        name = "Overlay text size",
        description = "Directions overlay text size; Small and Normal are the native faces, Extra large pixel-doubles them, Large is a softer 1.5x",
        position = 78,
        section = sectionDisplay
    )
    default OverlayFontSize overlayFontSize() {
        return OverlayFontSize.NORMAL;
    }

    @ConfigItem(
        keyName = "arrivalAutoDismiss",
        name = "Auto-dismiss arrival",
        description = "Hide the Arrived panel after a delay; off, it stays until clicked",
        position = 79,
        section = sectionDisplay
    )
    default boolean arrivalAutoDismiss() {
        return false;
    }

    @Range(min = 1, max = 300)
    @Units(Units.SECONDS)
    @ConfigItem(
        keyName = "arrivalDismissSeconds",
        name = "Arrival dismiss delay",
        description = "Seconds the Arrived panel lingers before it hides itself",
        position = 80,
        section = sectionDisplay
    )
    default int arrivalDismissSeconds() {
        return 10;
    }

    @ConfigSection(
        name = "Colours",
        description = "Path, text and overlay colours",
        position = 72
    )
    String sectionColours = "sectionColours";

    @Alpha
    @ConfigItem(
        keyName = "colourPath",
        name = "Path",
        description = "Path tiles on the world map, minimap and game scene",
        position = 73,
        section = sectionColours
    )
    default Color colourPath() {
        return new Color(0, 255, 255);
    }

    @Alpha
    @ConfigItem(
        keyName = "colourPathSailing",
        name = "Sailing legs",
        description = "Sailed legs on the world map; amber, the buoy colour, reads over both the ocean and the walking path",
        position = 73,
        section = sectionColours
    )
    default Color colourPathSailing() {
        return new Color(255, 160, 0);
    }

    @Alpha
    @ConfigItem(
        keyName = "colourPathBlocked",
        name = "Blocked by door",
        description = "The path beyond a door not yet seen open",
        position = 73,
        section = sectionColours
    )
    default Color colourPathBlocked() {
        return new Color(224, 62, 62);
    }

    @Alpha
    @ConfigItem(
        keyName = "colourPathCalculating",
        name = "Calculating",
        description = "Path tiles while a calculation runs, and unused targets when there are several",
        position = 74,
        section = sectionColours
    )
    default Color colourPathCalculating() {
        return new Color(0, 0, 255);
    }

    @Alpha
    @ConfigItem(
        keyName = "colourPathUnreachable",
        name = "Unreachable",
        description = "Path tiles when the target is still out of reach after the calculation",
        position = 75,
        section = sectionColours
    )
    default Color colourPathUnreachable() {
        return new Color(200, 40, 240);
    }



    @Alpha
    @ConfigItem(
        keyName = "colourText",
        name = "Text",
        description = "Tile counter and fairy ring code text",
        position = 78,
        section = sectionColours
    )
    default Color colourText() {
        return Color.WHITE;
    }

    @ConfigItem(
        keyName = "colourTeleportPulse",
        name = "Teleport pulse",
        description = "The teleport pulse; defaults to the path colour",
        position = 79,
        section = sectionColours
    )
    default Color colourTeleportPulse() {
        return new Color(0, 255, 255);
    }

    @ConfigItem(
        keyName = "colourOverlayAccent",
        name = "Overlay accent",
        description = "Directions overlay accent: the active step, header pin, divider and ETA badge",
        position = 80,
        section = sectionColours
    )
    default Color colourOverlayAccent() {
        return new Color(0x4C, 0x8B, 0xF5);
    }

    @ConfigItem(
        keyName = "colourRouteItem",
        name = "Next item",
        description = "Outline of the item the route uses next, in the inventory, equipment and bank",
        position = 81,
        section = sectionColours
    )
    default Color colourRouteItem() {
        return new Color(0xF2, 0xC1, 0x4E);
    }

    @ConfigSection(
        name = "Hotkeys",
        description = "Keyboard shortcuts",
        position = 80
    )
    String sectionHotkeys = "sectionHotkeys";

    @ConfigItem(
        keyName = "clearPathHotkey",
        name = "Clear current path",
        description = "Clear the current path",
        position = 81,
        section = sectionHotkeys
    )
    default Keybind clearPathHotkey() {
        return Keybind.NOT_SET;
    }

    @ConfigItem(
        keyName = "focusSearchHotkey",
        name = "Focus search box",
        description = "Open the GPS panel and focus the destination search",
        position = 82,
        section = sectionHotkeys
    )
    default Keybind focusSearchHotkey() {
        return Keybind.NOT_SET;
    }

    @ConfigItem(
        keyName = "nearestBankHotkey",
        name = "Go to nearest bank",
        description = "The panel's Bank button: route to the nearest bank, then resume the replaced route",
        position = 83,
        section = sectionHotkeys
    )
    default Keybind nearestBankHotkey() {
        return Keybind.NOT_SET;
    }

    @ConfigItem(
        keyName = "nearestBankAndBackHotkey",
        name = "Go to nearest bank and back",
        description = "The panel's Bank (and back) button: the nearest bank and back, then resume the replaced route",
        position = 84,
        section = sectionHotkeys
    )
    default Keybind nearestBankAndBackHotkey() {
        return Keybind.NOT_SET;
    }

    @ConfigSection(
        name = "Debug Options",
        description = "Diagnostics",
        position = 85,
        closedByDefault = true
    )
    String sectionDebug = "sectionDebug";




    @ConfigItem(
        keyName = "drawRecalculationRanges",
        name = "Draw off-route ranges",
        description = "Draw the off-route warning (amber) and recalculate (red) boundaries around the player",
        position = 87,
        section = sectionDebug
    )
    default boolean drawRecalculationRanges() {
        return false;
    }

    @ConfigItem(
        keyName = "postTransports",
        name = "Post transports",
        description = "Post the current path's transports as a PluginMessage event",
        position = 88,
        section = sectionDebug
    )
    default boolean postTransports() {
        return false;
    }

    @ConfigItem(
        keyName = "unreachableText",
        name = "",
        description = "Text on the player tile when the destination is unreachable",
        hidden = true
    )
    default String unreachableText() {
        return "Destination could not be reached";
    }

}
