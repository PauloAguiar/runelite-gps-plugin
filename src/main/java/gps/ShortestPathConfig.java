package gps;

import java.awt.Color;
import net.runelite.client.config.*;

@SuppressWarnings("SameReturnValue")
@ConfigGroup(ShortestPathPlugin.CONFIG_GROUP)
public interface ShortestPathConfig extends Config {
    @ConfigSection(name = "Settings", position = 0,
        description = "Pathfinding options")
    String sectionSettings = "sectionSettings";

    @ConfigItem(hidden = true, keyName = "avoidWilderness", name = "Avoid the wilderness", position = 1, section = sectionSettings,
        description = "Route around the wilderness whenever possible; routes still enter it when the destination is inside")
    default boolean avoidWilderness() {
        return true;
    }

    @ConfigItem(keyName = "defaultRouteCount", name = "Routes per page", position = 1, section = sectionSettings,
        description = "Routes listed per search; each press of the panel's + adds this many more")
    @Range(min = 1, max = 25)
    default int defaultRouteCount() {
        return 10;
    }

    @ConfigItem(hidden = true, keyName = "useSailing", name = "Use sailing routes", position = 158, section = sectionSettings,
        description = "Sail your own boat between moorings and port berths; times assume a mid-tier hull")
    default boolean useSailing() {
        return true;
    }

    @ConfigItem(hidden = true, keyName = "sailingTeleportAbandon", name = "Teleports may abandon the boat", position = 159, section = sectionSettings,
        description = "Aboard, teleport routes may leave the boat at sea; off, routes only disembark at moorings and berths")
    default boolean sailingTeleportAbandon() {
        return false;
    }

    @ConfigItem(hidden = true, keyName = "sailingAssumeSummon", name = "Assume Summon Boat spell", position = 160, section = sectionSettings,
        description = "Routes may board at any mooring, summoning the boat there (56 Magic, Pandemonium); off, sailing starts only where your boat is moored")
    default boolean sailingAssumeSummon() {
        return false;
    }

@ConfigItem(hidden = true, keyName = "sailingKeepSailing", name = "Keep sailing while at the helm", position = 160, section = sectionSettings,
    description = "Aboard, routes that stay on the water rank first; disembark-and-teleport chains list below them")
    default boolean sailingKeepSailing() {
        return true;
    }

    @ConfigItem(hidden = true, keyName = "useHotAirBalloons", name = "Use balloon routes", position = 10, section = sectionSettings,
        description = "Include hot air balloon flights (Enlightened Journey); each flight burns one log of the destination's type, from your inventory or the stations' Log storage")
    default boolean useHotAirBalloons() {
        return true;
    }

    @ConfigItem(keyName = "canoeAxeStored", name = "Axe stored at a canoe station", position = 11, section = sectionSettings,
        description = "An axe is stored with the canoe stations (any station, shared by all), so canoes need none on you; set from chat when you store or withdraw one, or tick it yourself")
    default boolean canoeAxeStored() {
        return false;
    }

    @ConfigItem(hidden = true, keyName = "useSpiritTrees", name = "Use spirit trees", position = 15, section = sectionSettings,
        description = "Include spirit trees")
    default boolean useSpiritTrees() {
        return true;
    }

    @ConfigItem(hidden = true, keyName = "spiritTreeSmartMode", name = "Smart tracking", position = 154, section = sectionSettings,
        description = "Route only through the farmable spirit trees you have grown, read from the travel menu; off assumes all of them")
    default boolean spiritTreeSmartMode() {
        return true;
    }

    @ConfigItem(hidden = true, keyName = "pohSmartDetect", name = "Auto-detect furniture", position = 155, section = sectionSettings,
        description = "Inside your house, tick the furniture GPS recognises (jewellery box, fairy ring, spirit tree, obelisk) and note which portal chamber portals and nexus stand there; it never unticks, and mounts stay yours to set")
    default boolean pohSmartDetect() {
        return true;
    }

    @ConfigItem(hidden = true, keyName = "hideWarningBanners", name = "Hide panel warning banners", position = 157, section = sectionSettings,
        description = "Collapse the panel's warning banners behind the N warnings row")
    default boolean hideWarningBanners() {
        return false;
    }

    @ConfigItem(hidden = true, keyName = "rememberBank", name = "Remember between sessions", position = 156, section = sectionSettings,
        description = "Save your bank's contents when it closes and load them at login, so + Bank routes see banked items without a visit")
    default boolean rememberBank() {
        return true;
    }

    @ConfigItem(hidden = true, keyName = "useTeleportationItems", name = "Use teleportation items", position = 16, section = sectionSettings,
        description = "Which teleport items routes may use; (perm) means permanent items only, and the All options skip skill, quest and item checks")
    default TeleportationItem useTeleportationItems() {
        return TeleportationItem.INVENTORY_NON_CONSUMABLE;
    }

    @ConfigItem(keyName = "useSeasonalTransports", name = "Enable seasonal transports", position = 22, section = sectionSettings,
        description = "Include Leagues transports; off hides them everywhere, so turn this on only on a seasonal world")
    default boolean useSeasonalTransports() {
        return false;
    }

    @ConfigItem(hidden = true, keyName = "includeBankPath", name = "Include path to bank", position = 23, section = sectionSettings,
        description = "Suggest teleports from the bank by routing through the closest bank")
    default boolean includeBankPath() {
        return false;
    }

    @ConfigItem(hidden = true, keyName = "balloonStoredLogs", name = "Balloon stored logs", position = 96, section = sectionSettings,
        description = "Logs in the balloon Log storage, read from chat")
    default int balloonStoredLogs() {
        return 0;
    }

    @ConfigItem(hidden = true, keyName = "balloonStoredOakLogs", name = "Balloon stored oak logs", position = 97, section = sectionSettings,
        description = "Oak logs in the balloon Log storage, read from chat")
    default int balloonStoredOakLogs() {
        return 0;
    }

    @ConfigItem(hidden = true, keyName = "balloonStoredWillowLogs", name = "Balloon stored willow logs", position = 98, section = sectionSettings,
        description = "Willow logs in the balloon Log storage, read from chat")
    default int balloonStoredWillowLogs() {
        return 0;
    }

    @ConfigItem(hidden = true, keyName = "balloonStoredYewLogs", name = "Balloon stored yew logs", position = 99, section = sectionSettings,
        description = "Yew logs in the balloon Log storage, read from chat")
    default int balloonStoredYewLogs() {
        return 0;
    }

    @ConfigItem(hidden = true, keyName = "balloonStoredMagicLogs", name = "Balloon stored magic logs", position = 100, section = sectionSettings,
        description = "Magic logs in the balloon Log storage, read from chat")
    default int balloonStoredMagicLogs() {
        return 0;
    }

    @ConfigItem(hidden = true, keyName = "balloonSmartMode", name = "Smart Log storage", position = 101, section = sectionSettings,
        description = "Track the stations' Log storage from chat so flights can be paid from it; off, a flight needs its log in your inventory")
    default boolean balloonSmartMode() {
        return true;
    }

    @Range(max = 100)
    @ConfigItem(hidden = true, keyName = "balloonLogWarningThreshold", name = "Warn below", position = 102, section = sectionSettings,
        description = "Warn when an unlocked route's Log storage falls below this many logs; 0 never warns")
    default int balloonLogWarningThreshold() {
        return 10;
    }

    @ConfigItem(hidden = true, keyName = "balloonStorageSynced", name = "Balloon storage synced", position = 103, section = sectionSettings,
        description = "Whether the Log storage counts have been read from chat at least once")
    default boolean balloonStorageSynced() {
        return false;
    }

    @ConfigItem(hidden = true, keyName = "currencyThreshold", name = "Currency threshold", position = 24, section = sectionSettings,
        description = "Most coins, trading sticks, ecto-tokens or warrior guild tokens one transport may cost")
    default int currencyThreshold() {
        return 100000;
    }

    @ConfigItem(keyName = "autoRecalculate", name = "Auto-recalculate route", position = 25, section = sectionSettings,
        description = "Recompute the route when you stray past the recalculate distance; off keeps the route and only warns")
    default boolean autoRecalculate() {
        return true;
    }

    @ConfigItem(keyName = "cancelInstead", name = "Cancel instead of recalculating", position = 26, section = sectionSettings,
        description = "Cancel the path instead of recalculating it past the recalculate distance")
    default boolean cancelInstead() {
        return false;
    }

    @Range(min = -1, max = 20000)
    @ConfigItem(keyName = "recalculateDistance", name = "Recalculate distance", position = 27, section = sectionSettings,
        description = "Tiles off the path before it is recalculated; -1 never")
    default int recalculateDistance() {
        return 18;
    }

    @Range(min = 0, max = 20000)
    @ConfigItem(keyName = "offRouteWarnDistance", name = "Off-route warning distance", position = 28, section = sectionSettings,
        description = "Tiles off the path before the overlay warns; past the recalculate distance the route recomputes")
    default int offRouteWarnDistance() {
        return 10;
    }

    @Range(min = -1, max = 50)
    @ConfigItem(keyName = "finishDistance", name = "Finish distance", position = 29, section = sectionSettings,
        description = "Walking steps from the destination at which the journey counts as complete; -1 never, 0 the exact tile")
    default int reachedDistance() {
        return 5;
    }

    @Range(max = 30)
    @ConfigItem(keyName = "seaReachedDistance", name = "Sea finish distance", position = 29, section = sectionSettings,
        description = "Tiles from a water destination at which parking the boat counts as arrival")
    default int seaReachedDistance() {
        return 12;
    }

    @ConfigItem(hidden = true, keyName = "questHelperBannerDismissed", name = "Quest Helper banner dismissed", position = 161, section = sectionSettings,
        description = "Whether the Quest Helper routing banner was dismissed")
    default boolean questHelperBannerDismissed() {
        return false;
    }

    @Range(max = 20000)
    @ConfigItem(keyName = "unreachableTargetDistanceThreshold", name = "Unreachable target distance", position = 30, section = sectionSettings,
        description = "Tiles from the target beyond which a finished path counts as not reaching it")
    default int unreachableTargetDistance() {
        return 2;
    }

    @Units(value = Units.TICKS)
    @Range(min = 1, max = 30)
    @ConfigItem(keyName = "calculationCutoff", name = "Calculation cutoff", position = 33, section = sectionSettings,
        description = "Ticks without progress toward the target before a calculation stops")
    default int calculationCutoff() {
        return 5;
    }

    @ConfigItem(keyName = "showTransportInfo", name = "Show transport info", position = 34, section = sectionSettings,
        description = "Show which chat option or object to click for a transport")
    default boolean showTransportInfo() {
        return true;
    }

    @ConfigItem(keyName = "showBankPickupInfo", name = "Show transport hint at pickup", position = 35, section = sectionSettings,
        description = "At a bank on the path, also show the hint for the next step that needs an item pickup")
    default boolean showBankPickupInfo() {
        return false;
    }

    @ConfigItem(hidden = true, keyName = "usePoh", name = "Use my house for routes", position = 36, section = sectionSettings,
        description = "Master switch: off, no house teleport is ever routed")
    default boolean usePoh() {
        return true;
    }

    @ConfigItem(hidden = true, keyName = "usePohFairyRing", name = "Fairy ring", position = 37, section = sectionSettings,
        description = "Built in your house (85 Construction)")
    default boolean usePohFairyRing() {
        return true;
    }

    @ConfigItem(hidden = true, keyName = "usePohSpiritTree", name = "Spirit tree", position = 38, section = sectionSettings,
        description = "Built in your house (75 Construction, 83 Farming)")
    default boolean usePohSpiritTree() {
        return true;
    }

    @ConfigItem(hidden = true, keyName = "useTeleportationPortalsPoh", name = "Teleport portals & nexus", position = 39, section = sectionSettings,
        description = "Portal chamber and portal nexus destinations in your house")
    default boolean useTeleportationPortalsPoh() {
        return true;
    }

    @ConfigItem(hidden = true, keyName = "pohJewelleryBoxTier", name = "Jewellery box", position = 40, section = sectionSettings,
        description = "The tier built in your house; each tier includes the ones below it, None disables it")
    default JewelleryBoxTier pohJewelleryBoxTier() {
        return JewelleryBoxTier.ORNATE;
    }

    @ConfigItem(hidden = true, keyName = "usePohMountedItems", name = "Mounted items", position = 41, section = sectionSettings,
        description = "Mounted glory, Xeric's talisman, digsite pendant or mythical cape, picked below")
    default boolean usePohMountedItems() {
        return true;
    }

    @ConfigItem(hidden = true, keyName = "pohMountGlory", name = "Amulet of glory", position = 42, section = sectionSettings,
        description = "Mounted Amulet of glory: Edgeville, Karamja, Draynor, Al Kharid")
    default boolean pohMountGlory() {
        return true;
    }

    @ConfigItem(hidden = true, keyName = "pohMountXerics", name = "Xeric's talisman", position = 43, section = sectionSettings,
        description = "Mounted Xeric's talisman: Lookout, Glade, Inferno, Heart, Honour")
    default boolean pohMountXerics() {
        return true;
    }

    @ConfigItem(hidden = true, keyName = "pohMountDigsite", name = "Digsite pendant", position = 44, section = sectionSettings,
        description = "Mounted Digsite pendant: Digsite, Fossil Island, Lithkren")
    default boolean pohMountDigsite() {
        return true;
    }

    @ConfigItem(hidden = true, keyName = "pohMountMythical", name = "Mythical cape", position = 45, section = sectionSettings,
        description = "Mounted Mythical cape: Myths' Guild")
    default boolean pohMountMythical() {
        return true;
    }

    @ConfigItem(hidden = true, keyName = "usePohObelisk", name = "Wilderness obelisk", position = 42, section = sectionSettings,
        description = "Built in your house (80 Construction)")
    default boolean usePohObelisk() {
        return true;
    }

    @ConfigSection(name = "Travel method modifiers", position = 43, closedByDefault = true,
        description = "Run-tile cost added when a route uses a method (2 tiles = 1 tick): 0 judges by travel time alone, positive avoids it, negative favours it")
    String sectionThresholds = "sectionThresholds";

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costTransports", name = "Object transport modifier", position = 42, section = sectionThresholds,
        description = "Run-tile cost added for each stairs, ladder or mapped door a route uses; the click costs a moment the ticks do not show")
    default int costTransports() {
        return 2;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costAgilityShortcuts", name = "Agility shortcut modifier", position = 43, section = sectionThresholds,
        description = "Run-tile cost added when a route uses an agility shortcut")
    default int costAgilityShortcuts() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costGrappleShortcuts", name = "Grapple shortcut modifier", position = 44, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a grapple shortcut")
    default int costGrappleShortcuts() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costBoats", name = "Boat modifier", position = 45, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a small boat")
    default int costBoats() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costCanoes", name = "Canoe modifier", position = 46, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a canoe")
    default int costCanoes() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costCharterShips", name = "Charter ship modifier", position = 47, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a charter ship")
    default int costCharterShips() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costShips", name = "Ship modifier", position = 48, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a passenger ship")
    default int costShips() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(hidden = true, keyName = "costSailing", name = "Sailing modifier", position = 159, section = sectionThresholds,
        description = "Run-tile cost added when a route uses your own boat")
    default int costSailing() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costFairyRings", name = "Fairy ring modifier", position = 49, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a fairy ring")
    default int costFairyRings() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costGnomeGliders", name = "Gnome glider modifier", position = 50, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a gnome glider")
    default int costGnomeGliders() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costHotAirBalloons", name = "Hot air balloon modifier", position = 51, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a hot air balloon")
    default int costHotAirBalloons() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costMagicCarpets", name = "Magic carpet modifier", position = 52, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a magic carpet")
    default int costMagicCarpets() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costMagicMushtrees", name = "Magic mushtree modifier", position = 53, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a magic mushtree")
    default int costMagicMushtrees() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costMinecarts", name = "Minecart modifier", position = 54, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a minecart")
    default int costMinecarts() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costMountainGuides", name = "Mountain guide modifier", position = 54, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a mountain guide")
    default int costMountainGuides() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costQuetzals", name = "Quetzal modifier", position = 55, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a quetzal")
    default int costQuetzals() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costQuetzalWhistle", name = "Quetzal whistle modifier", position = 56, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a quetzal whistle teleport")
    default int costQuetzalWhistle() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costSpiritTrees", name = "Spirit tree modifier", position = 57, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a spirit tree")
    default int costSpiritTrees() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costNonConsumableTeleportationItems", name = "Teleport item (reusable) modifier", position = 58, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a reusable teleport item")
    default int costNonConsumableTeleportationItems() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costConsumableTeleportationItems", name = "Teleport item (consumable) modifier", position = 59, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a consumable teleport item")
    default int costConsumableTeleportationItems() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costTeleportationBoxes", name = "Jewellery box modifier", position = 60, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a jewellery box")
    default int costTeleportationBoxes() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costTeleportationLevers", name = "Teleport lever modifier", position = 61, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a teleport lever")
    default int costTeleportationLevers() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costTeleportationPortals", name = "Teleport portal modifier", position = 62, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a teleport portal")
    default int costTeleportationPortals() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costTeleportationSpells", name = "Teleport spell modifier", position = 63, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a teleport spell")
    default int costTeleportationSpells() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costTeleportationMinigames", name = "Minigame teleport modifier", position = 64, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a minigame teleport")
    default int costTeleportationMinigames() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costWildernessObelisks", name = "Wilderness obelisk modifier", position = 65, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a wilderness obelisk")
    default int costWildernessObelisks() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costSeasonalTransports", name = "Seasonal transport modifier", position = 66, section = sectionThresholds,
        description = "Run-tile cost added when a route uses a Leagues transport")
    default int costSeasonalTransports() {
        return 0;
    }

    @Range(min = -10000, max = 10000)
    @ConfigItem(keyName = "costBankPickup", name = "Bank pickup modifier", position = 67, section = sectionThresholds,
        description = "Run-tile cost added when a route detours through a bank to withdraw a teleport item (+ Bank mode)")
    default int costBankPickup() {
        // Non-zero unlike the travel methods: banking mid-route is a real interruption, so a small
        // friction cost avoids trivial detours that save only a tile or two.
        return 15;
    }

    @ConfigSection(name = "Display", position = 67,
        description = "The path on the world map, minimap and game tiles")
    String sectionDisplay = "sectionDisplay";

    @ConfigItem(keyName = "drawMap", name = "Draw path on world map", position = 68, section = sectionDisplay,
        description = "Draw the path on the world map")
    default boolean drawMap() {
        return true;
    }

    @ConfigItem(keyName = "drawMinimap", name = "Draw path on minimap", position = 69, section = sectionDisplay,
        description = "Draw the path on the minimap")
    default boolean drawMinimap() {
        return true;
    }

    @ConfigItem(keyName = "drawTiles", name = "Draw path on tiles", position = 70, section = sectionDisplay,
        description = "Draw the path on the game tiles")
    default boolean drawTiles() {
        return true;
    }

    @ConfigItem(keyName = "highlightRouteItem", name = "Highlight the next item", position = 71, section = sectionDisplay,
        description = "Outline the item the route uses next in the inventory, equipment and bank; a worn-only item in the bag says Equip")
    default boolean highlightRouteItem() {
        return true;
    }

    @ConfigItem(keyName = "showTeleportPulse", name = "Teleport pulse", position = 72, section = sectionDisplay,
        description = "Pulse the tile where the path says to use a teleport")
    default boolean showTeleportPulse() {
        return true;
    }

    @ConfigItem(keyName = "showPathPulse", name = "Path pulse", position = 73, section = sectionDisplay,
        description = "Animate a pulse flowing along the path on the game tiles")
    default boolean showPathPulse() {
        return true;
    }

    @ConfigItem(keyName = "showPathArrows", name = "Path arrows", position = 74, section = sectionDisplay,
        description = "Draw arrow heads along the path on the game tiles")
    default boolean showPathArrows() {
        return true;
    }

    @ConfigItem(keyName = "showDirections", name = "Directions overlay", position = 75, section = sectionDisplay,
        description = "Show a movable step-by-step directions panel for the route on the map")
    default boolean showDirections() {
        return true;
    }

    @Range(min = 1, max = 30)
    @ConfigItem(keyName = "directionsSteps", name = "Steps shown", position = 75, section = sectionDisplay,
        description = "Lines the directions overlay lists before folding the rest into a count; a long route need not take the screen")
    default int directionsSteps() {
        return 14;
    }

    @ConfigItem(keyName = "overrideOverlayTransparency", name = "Override RuneLite transparency", position = 76, section = sectionDisplay,
        description = "Use the transparency below for the directions overlay instead of RuneLite's overlay background")
    default boolean overrideOverlayTransparency() {
        return false;
    }

    @Range(min = 0, max = 100)
    @Units(Units.PERCENT)
    @ConfigItem(keyName = "overlayTransparency", name = "Overlay transparency", position = 77, section = sectionDisplay,
        description = "Overlay background transparency with the override on; 100% invisible, 0% solid, RuneLite's look is about 40%")
    default int overlayTransparency() {
        return 100;
    }

    @ConfigItem(keyName = "overlayFontSize", name = "Overlay text size", position = 78, section = sectionDisplay,
        description = "Directions overlay text size; Small and Normal are the native faces, Extra large pixel-doubles them, Large is a softer 1.5x")
    default OverlayFontSize overlayFontSize() {
        return OverlayFontSize.NORMAL;
    }

    @ConfigItem(keyName = "arrivalAutoDismiss", name = "Auto-dismiss arrival", position = 79, section = sectionDisplay,
        description = "Hide the Arrived panel after a delay; off, it stays until clicked")
    default boolean arrivalAutoDismiss() {
        return false;
    }

    @Range(min = 1, max = 300)
    @Units(Units.SECONDS)
    @ConfigItem(keyName = "arrivalDismissSeconds", name = "Arrival dismiss delay", position = 80, section = sectionDisplay,
        description = "Seconds the Arrived panel lingers before it hides itself")
    default int arrivalDismissSeconds() {
        return 10;
    }

    @ConfigSection(name = "Colours", position = 72,
        description = "Path, text and overlay colours")
    String sectionColours = "sectionColours";

    @Alpha
    @ConfigItem(keyName = "colourPath", name = "Path", position = 73, section = sectionColours,
        description = "Path tiles on the world map, minimap and game scene")
    default Color colourPath() {
        return new Color(0, 255, 255);
    }

    @Alpha
    @ConfigItem(keyName = "colourPathSailing", name = "Sailing legs", position = 73, section = sectionColours,
        description = "Sailed legs on the world map; amber, the buoy colour, reads over both the ocean and the walking path")
    default Color colourPathSailing() {
        return new Color(255, 160, 0);
    }

    @Alpha
    @ConfigItem(keyName = "colourPathBlocked", name = "Blocked by door", position = 73, section = sectionColours,
        description = "The path beyond a door not yet seen open")
    default Color colourPathBlocked() {
        return new Color(224, 62, 62);
    }

    @Alpha
    @ConfigItem(keyName = "colourPathCalculating", name = "Calculating", position = 74, section = sectionColours,
        description = "Path tiles while a calculation runs, and unused targets when there are several")
    default Color colourPathCalculating() {
        return new Color(0, 0, 255);
    }

    @Alpha
    @ConfigItem(keyName = "colourPathUnreachable", name = "Unreachable", position = 75, section = sectionColours,
        description = "Path tiles when the target is still out of reach after the calculation")
    default Color colourPathUnreachable() {
        return new Color(200, 40, 240);
    }

    @Alpha
    @ConfigItem(keyName = "colourText", name = "Text", position = 78, section = sectionColours,
        description = "Tile counter and fairy ring code text")
    default Color colourText() {
        return Color.WHITE;
    }

    @ConfigItem(keyName = "colourTeleportPulse", name = "Teleport pulse", position = 79, section = sectionColours,
        description = "The teleport pulse; defaults to the path colour")
    default Color colourTeleportPulse() {
        return new Color(0, 255, 255);
    }

    @ConfigItem(keyName = "colourOverlayAccent", name = "Overlay accent", position = 80, section = sectionColours,
        description = "Directions overlay accent: the active step, header pin, divider and ETA badge")
    default Color colourOverlayAccent() {
        return new Color(0x4C, 0x8B, 0xF5);
    }

    @ConfigItem(keyName = "colourRouteItem", name = "Next item", position = 81, section = sectionColours,
        description = "Outline of the item the route uses next, in the inventory, equipment and bank")
    default Color colourRouteItem() {
        return new Color(0xF2, 0xC1, 0x4E);
    }

    @ConfigSection(name = "Hotkeys", position = 80,
        description = "Keyboard shortcuts")
    String sectionHotkeys = "sectionHotkeys";

    @ConfigItem(keyName = "clearPathHotkey", name = "Clear current path", position = 81, section = sectionHotkeys,
        description = "Clear the current path")
    default Keybind clearPathHotkey() {
        return Keybind.NOT_SET;
    }

    @ConfigItem(keyName = "focusSearchHotkey", name = "Focus search box", position = 82, section = sectionHotkeys,
        description = "Open the GPS panel and focus the destination search")
    default Keybind focusSearchHotkey() {
        return Keybind.NOT_SET;
    }

    @ConfigItem(keyName = "nearestBankHotkey", name = "Go to nearest bank", position = 83, section = sectionHotkeys,
        description = "The panel's Bank button: route to the nearest bank, then resume the replaced route")
    default Keybind nearestBankHotkey() {
        return Keybind.NOT_SET;
    }

    @ConfigItem(keyName = "nearestBankAndBackHotkey", name = "Go to nearest bank and back", position = 84, section = sectionHotkeys,
        description = "The panel's Bank (and back) button: the nearest bank and back, then resume the replaced route")
    default Keybind nearestBankAndBackHotkey() {
        return Keybind.NOT_SET;
    }

    @ConfigSection(name = "Debug Options", position = 85, closedByDefault = true,
        description = "Diagnostics")
    String sectionDebug = "sectionDebug";

    @ConfigItem(keyName = "drawRecalculationRanges", name = "Draw off-route ranges", position = 87, section = sectionDebug,
        description = "Draw the off-route warning (amber) and recalculate (red) boundaries around the player")
    default boolean drawRecalculationRanges() {
        return false;
    }

    @ConfigItem(keyName = "postTransports", name = "Post transports", position = 88, section = sectionDebug,
        description = "Post the current path's transports as a PluginMessage event")
    default boolean postTransports() {
        return false;
    }

    @ConfigItem(keyName = "unreachableText", name = "", hidden = true,
        description = "Text on the player tile when the destination is unreachable")
    default String unreachableText() {
        return "Destination could not be reached";
    }

}
