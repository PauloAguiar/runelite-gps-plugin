package shortestpath;

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
public interface ShortestPathConfig extends Config
{
	@ConfigSection(
		name = "Settings",
		description = "Options for the pathfinding",
		position = 0
	)
	String sectionSettings = "sectionSettings";

	@ConfigItem(
		keyName = "avoidWilderness",
		name = "Avoid wilderness",
		description = "Whether the wilderness should be avoided if possible<br>" +
			"(otherwise, will e.g. use wilderness lever from Edgeville to Ardougne)",
		position = 1,
		section = sectionSettings
	)
	default boolean avoidWilderness()
	{
		return true;
	}

	@ConfigItem(
		keyName = "defaultRouteCount",
		name = "Routes to find",
		description = "How many alternative routes to search for by default<br>" +
			"(the panel's \"Show more\" button can extend this per query)",
		position = 1,
		section = sectionSettings
	)
	@Range(min = 1, max = 25)
	default int defaultRouteCount()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "routeCountStep",
		name = "Show more increment",
		description = "How many extra routes the panel's \"Show more\" button fetches each time",
		position = 2,
		section = sectionSettings
	)
	@Range(min = 1, max = 25)
	default int routeCountStep()
	{
		return 5;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useAgilityShortcuts",
		name = "Use agility shortcuts",
		description = "Whether to include agility shortcuts in the path.<br>" +
			"You must also have the required agility level",
		position = 2,
		section = sectionSettings
	)
	default boolean useAgilityShortcuts()
	{
		return true;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useGrappleShortcuts",
		name = "Use grapple shortcuts",
		description = "Whether to include crossbow grapple agility shortcuts in the path.<br>" +
			"You must also have the required agility, ranged and strength levels",
		position = 3,
		section = sectionSettings
	)
	default boolean useGrappleShortcuts()
	{
		return false;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useBoats",
		name = "Use boats",
		description = "Whether to include small boats in the path<br>" +
			"(e.g. the boat to Fishing Platform)",
		position = 4,
		section = sectionSettings
	)
	default boolean useBoats()
	{
		return true;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useCanoes",
		name = "Use canoes",
		description = "Whether to include canoes in the path",
		position = 5,
		section = sectionSettings
	)
	default boolean useCanoes()
	{
		return false;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useCharterShips",
		name = "Use charter ships",
		description = "Whether to include charter ships in the path",
		position = 6,
		section = sectionSettings
	)
	default boolean useCharterShips()
	{
		return false;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useShips",
		name = "Use ships",
		description = "Whether to include passenger ships in the path<br>" +
			"(e.g. the customs ships to Karamja)",
		position = 7,
		section = sectionSettings
	)
	default boolean useShips()
	{
		return true;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useFairyRings",
		name = "Use fairy rings",
		description = "Whether to include fairy rings in the path.<br>" +
			"You must also have completed the required quests or miniquests",
		position = 8,
		section = sectionSettings
	)
	default boolean useFairyRings()
	{
		return true;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useGnomeGliders",
		name = "Use gnome gliders",
		description = "Whether to include gnome gliders in the path",
		position = 9,
		section = sectionSettings
	)
	default boolean useGnomeGliders()
	{
		return true;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useHotAirBalloons",
		name = "Use hot air balloons",
		description = "Whether to include hot air balloons in the path",
		position = 10,
		section = sectionSettings
	)
	default boolean useHotAirBalloons()
	{
		return false;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useMagicCarpets",
		name = "Use magic carpets",
		description = "Whether to include magic carpets in the path",
		position = 11,
		section = sectionSettings
	)
	default boolean useMagicCarpets()
	{
		return true;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useMagicMushtrees",
		name = "Use magic mushtrees",
		description = "Whether to include Fossil Island Magic Mushtrees in the path<br>" +
			"(e.g. the Mycelium transport network from Verdant Valley to Mushroom Meadow)",
		position = 12,
		section = sectionSettings
	)
	default boolean useMagicMushtrees()
	{
		return true;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useMinecarts",
		name = "Use minecarts",
		description = "Whether to include minecarts in the path<br>" +
			"(e.g. the Keldagrim and Lovakengj minecart networks)",
		position = 13,
		section = sectionSettings
	)
	default boolean useMinecarts()
	{
		return true;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useQuetzals",
		name = "Use quetzals",
		description = "Whether to include quetzals in the path",
		position = 14,
		section = sectionSettings
	)
	default boolean useQuetzals()
	{
		return true;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useSpiritTrees",
		name = "Use spirit trees",
		description = "Whether to include spirit trees in the path",
		position = 15,
		section = sectionSettings
	)
	default boolean useSpiritTrees()
	{
		return true;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useTeleportationItems",
		name = "Use teleportation items",
		description = "Whether to include teleportation items from the player's inventory and equipment.<br>" +
			"Options labelled (perm) only use permanent non-charge items.<br>" +
			"The All options do not check skill, quest or item requirements.",
		position = 16,
		section = sectionSettings
	)
	default TeleportationItem useTeleportationItems()
	{
		return TeleportationItem.INVENTORY_NON_CONSUMABLE;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useTeleportationLevers",
		name = "Use teleportation levers",
		description = "Whether to include teleportation levers in the path<br>" +
			"(e.g. the lever from Edgeville to Wilderness)",
		position = 17,
		section = sectionSettings
	)
	default boolean useTeleportationLevers()
	{
		return true;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useTeleportationPortals",
		name = "Use teleportation portals",
		description = "Whether to include teleportation portals in the path<br>" +
			"(e.g. the portal from Ferox Enclave to Castle Wars)",
		position = 18,
		section = sectionSettings
	)
	default boolean useTeleportationPortals()
	{
		return true;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useTeleportationSpells",
		name = "Use teleportation spells",
		description = "Whether to include teleportation spells in the path",
		position = 19,
		section = sectionSettings
	)
	default boolean useTeleportationSpells()
	{
		return true;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useTeleportationMinigames",
		name = "Use teleportation to minigames",
		description = "Whether to include teleportation to minigames/activities/grouping in the path<br>" +
			"(e.g. the Nightmare Zone minigame teleport). These teleports share a 20 minute cooldown.",
		position = 20,
		section = sectionSettings
	)
	default boolean useTeleportationMinigames()
	{
		return true;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useWildernessObelisks",
		name = "Use wilderness obelisks",
		description = "Whether to include wilderness obelisks in the path",
		position = 21,
		section = sectionSettings
	)
	default boolean useWildernessObelisks()
	{
		return true;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useSeasonalTransports",
		name = "Use seasonal transports",
		description = "Whether to include seasonal transports like League teleports in the path",
		position = 22,
		section = sectionSettings
	)
	default boolean useSeasonalTransports()
	{
		return false;
	}

	@ConfigItem(
		hidden = true,
		keyName = "includeBankPath",
		name = "Include path to bank",
		description = "Whether to include the path to the closest bank<br>" +
			"when suggesting teleports from the bank",
		position = 23,
		section = sectionSettings
	)
	default boolean includeBankPath()
	{
		return false;
	}

	@ConfigItem(
		hidden = true,
		keyName = "currencyThreshold",
		name = "Currency threshold",
		description = "The maximum amount of currency to use on a single transportation method." +
			"<br>The currencies affected by the threshold are coins, trading sticks, ecto-tokens and warrior guild tokens.",
		position = 24,
		section = sectionSettings
	)
	default int currencyThreshold()
	{
		return 100000;
	}

	@ConfigItem(
		keyName = "cancelInstead",
		name = "Cancel instead of recalculating",
		description = "Whether the path should be cancelled rather than recalculated " +
			"when the recalculate distance limit is exceeded",
		position = 25,
		section = sectionSettings
	)
	default boolean cancelInstead()
	{
		return false;
	}

	@Range(
		min = -1,
		max = 20000
	)
	@ConfigItem(
		keyName = "recalculateDistance",
		name = "Recalculate distance",
		description = "Distance from the path the player should be for it to be recalculated (-1 for never)",
		position = 26,
		section = sectionSettings
	)
	default int recalculateDistance()
	{
		return 10;
	}

	@Range(
		min = -1,
		max = 50
	)
	@ConfigItem(
		keyName = "finishDistance",
		name = "Finish distance",
		description = "Distance from the target tile at which the path should be ended (-1 for never).<br>" +
			"Measured along the calculated path, so standing near the goal across a wall doesn't count",
		position = 27,
		section = sectionSettings
	)
	default int reachedDistance()
	{
		return 5;
	}

	@Range(
		max = 20000
	)
	@ConfigItem(
		keyName = "unreachableTargetDistanceThreshold",
		name = "Unreachable target distance",
		description = "Distance from the target at which a finished path is considered not to reach the target." +
			"<br>Useful for determining if a path is potentially invalid.",
		position = 28,
		section = sectionSettings
	)
	default int unreachableTargetDistance()
	{
		return 2;
	}

	@ConfigItem(
		keyName = "showTileCounter",
		name = "Show tile counter",
		description = "Whether to display the number of tiles travelled, number of tiles remaining or disable counting",
		position = 29,
		section = sectionSettings
	)
	default TileCounter showTileCounter()
	{
		return TileCounter.DISABLED;
	}

	@ConfigItem(
		keyName = "tileCounterStep",
		name = "Tile counter step",
		description = "The number of tiles between the displayed tile counter numbers",
		position = 30,
		section = sectionSettings
	)
	default int tileCounterStep()
	{
		return 1;
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
		description = "The cutoff threshold in number of ticks (0.6 seconds) of no progress being<br>" +
			"made towards the path target before the calculation will be stopped",
		position = 31,
		section = sectionSettings
	)
	default int calculationCutoff()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "showTransportInfo",
		name = "Show transport info",
		description = "Whether to display transport destination hint info, e.g. which chat option and text to click",
		position = 32,
		section = sectionSettings
	)
	default boolean showTransportInfo()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showBankPickupInfo",
		name = "Show transport hint at pickup",
		description = "When standing at a bank on the path, also show the transport hint for the next step requiring an item pickup",
		position = 33,
		section = sectionSettings
	)
	default boolean showBankPickupInfo()
	{
		return false;
	}

	@ConfigItem(
		hidden = true,
		keyName = "usePoh",
		name = "Enable POH teleports",
		description = "Master toggle for all Player-Owned House (POH) teleports.<br>" +
			"When disabled, all POH transports are excluded regardless of individual settings below.",
		position = 35,
		section = sectionSettings
	)
	default boolean usePoh()
	{
		return false;
	}

	@ConfigItem(
		hidden = true,
		keyName = "usePohFairyRing",
		name = "POH fairy ring",
		description = "Whether to include the POH fairy ring in the path.<br>" +
			"Enable this if you have built a fairy ring in your house (85 Construction or boosted)",
		position = 36,
		section = sectionSettings
	)
	default boolean usePohFairyRing()
	{
		return false;
	}

	@ConfigItem(
		hidden = true,
		keyName = "usePohSpiritTree",
		name = "POH spirit tree",
		description = "Whether to include the POH spirit tree in the path.<br>" +
			"Enable this if you have built a spirit tree in your house (75 Construction, 83 Farming or boosted)",
		position = 37,
		section = sectionSettings
	)
	default boolean usePohSpiritTree()
	{
		return false;
	}

	@ConfigItem(
		hidden = true,
		keyName = "useTeleportationPortalsPoh",
		name = "POH portal nexus",
		description = "Whether to include POH teleportation portals/nexus in the path",
		position = 38,
		section = sectionSettings
	)
	default boolean useTeleportationPortalsPoh()
	{
		return false;
	}

	@ConfigItem(
		hidden = true,
		keyName = "pohJewelleryBoxTier",
		name = "POH jewellery box tier",
		description = "The tier of jewellery box built in your POH<br>" +
			"(Basic: 1-9, Fancy: A-J, Ornate: K-R). Set to None to disable jewellery box.",
		position = 39,
		section = sectionSettings
	)
	default JewelleryBoxTier pohJewelleryBoxTier()
	{
		return JewelleryBoxTier.ORNATE;
	}

	@ConfigItem(
		hidden = true,
		keyName = "usePohMountedItems",
		name = "POH mounted items",
		description = "Whether to include POH mounted items in the path<br>" +
			"(e.g. mounted glory, Xeric's talisman, digsite pendant, mythical cape)",
		position = 40,
		section = sectionSettings
	)
	default boolean usePohMountedItems()
	{
		return true;
	}

	@ConfigItem(
		hidden = true,
		keyName = "usePohObelisk",
		name = "POH wilderness obelisk",
		description = "Whether to include the POH wilderness obelisk in the path.<br>" +
			"Enable this if you have built an obelisk in your house (80 Construction or boosted)",
		position = 41,
		section = sectionSettings
	)
	default boolean usePohObelisk()
	{
		return false;
	}

	@ConfigSection(
		name = "Travel method weights",
		description = "Extra step weight added when a route uses each travel method.<br>" +
			"Higher = the path avoids it unless it saves more than that many tiles of walking<br>" +
			"(e.g. a fairy ring weight of 30 is only used when it saves over 30 tiles).",
		position = 42,
		closedByDefault = true
	)
	String sectionThresholds = "sectionThresholds";

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costAgilityShortcuts",
		name = "Agility shortcut weight",
		description = "Weight added to the route's cost when it uses an agility shortcut.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 43,
		section = sectionThresholds
	)
	default int costAgilityShortcuts()
	{
		return 0;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costGrappleShortcuts",
		name = "Grapple shortcut weight",
		description = "Weight added to the route's cost when it uses a grapple shortcut.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 44,
		section = sectionThresholds
	)
	default int costGrappleShortcuts()
	{
		return 0;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costBoats",
		name = "Boat weight",
		description = "Weight added to the route's cost when it uses a small boat.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 45,
		section = sectionThresholds
	)
	default int costBoats()
	{
		return 0;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costCanoes",
		name = "Canoe weight",
		description = "Weight added to the route's cost when it uses a canoe.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 46,
		section = sectionThresholds
	)
	default int costCanoes()
	{
		return 5;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costCharterShips",
		name = "Charter ship weight",
		description = "Weight added to the route's cost when it uses a charter ship.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 47,
		section = sectionThresholds
	)
	default int costCharterShips()
	{
		return 20;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costShips",
		name = "Ship weight",
		description = "Weight added to the route's cost when it uses a passenger ship.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 48,
		section = sectionThresholds
	)
	default int costShips()
	{
		return 10;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costFairyRings",
		name = "Fairy ring weight",
		description = "Weight added to the route's cost when it uses a fairy ring.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 49,
		section = sectionThresholds
	)
	default int costFairyRings()
	{
		return 30;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costGnomeGliders",
		name = "Gnome glider weight",
		description = "Weight added to the route's cost when it uses a gnome glider.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 50,
		section = sectionThresholds
	)
	default int costGnomeGliders()
	{
		return 25;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costHotAirBalloons",
		name = "Hot air balloon weight",
		description = "Weight added to the route's cost when it uses a hot air balloon.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 51,
		section = sectionThresholds
	)
	default int costHotAirBalloons()
	{
		return 30;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costMagicCarpets",
		name = "Magic carpet weight",
		description = "Weight added to the route's cost when it uses a magic carpet.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 52,
		section = sectionThresholds
	)
	default int costMagicCarpets()
	{
		return 20;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costMagicMushtrees",
		name = "Magic mushtree weight",
		description = "Weight added to the route's cost when it uses a magic mushtree.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 53,
		section = sectionThresholds
	)
	default int costMagicMushtrees()
	{
		return 20;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costMinecarts",
		name = "Minecart weight",
		description = "Weight added to the route's cost when it uses a minecart.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 54,
		section = sectionThresholds
	)
	default int costMinecarts()
	{
		return 15;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costQuetzals",
		name = "Quetzal weight",
		description = "Weight added to the route's cost when it uses a quetzal.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 55,
		section = sectionThresholds
	)
	default int costQuetzals()
	{
		return 20;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costQuetzalWhistle",
		name = "Quetzal whistle weight",
		description = "Weight added to the route's cost when it uses a quetzal whistle teleport.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 56,
		section = sectionThresholds
	)
	default int costQuetzalWhistle()
	{
		return 20;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costSpiritTrees",
		name = "Spirit tree weight",
		description = "Weight added to the route's cost when it uses a spirit tree.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 57,
		section = sectionThresholds
	)
	default int costSpiritTrees()
	{
		return 20;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costNonConsumableTeleportationItems",
		name = "Teleport item (reusable) weight",
		description = "Weight added to the route's cost when it uses a reusable (permanent) teleport item.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 58,
		section = sectionThresholds
	)
	default int costNonConsumableTeleportationItems()
	{
		return 20;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costConsumableTeleportationItems",
		name = "Teleport item (consumable) weight",
		description = "Weight added to the route's cost when it uses a consumable (one-use) teleport item.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 59,
		section = sectionThresholds
	)
	default int costConsumableTeleportationItems()
	{
		return 25;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costTeleportationBoxes",
		name = "Jewellery box weight",
		description = "Weight added to the route's cost when it uses a jewellery box.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 60,
		section = sectionThresholds
	)
	default int costTeleportationBoxes()
	{
		return 15;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costTeleportationLevers",
		name = "Teleport lever weight",
		description = "Weight added to the route's cost when it uses a teleport lever.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 61,
		section = sectionThresholds
	)
	default int costTeleportationLevers()
	{
		return 15;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costTeleportationPortals",
		name = "Teleport portal weight",
		description = "Weight added to the route's cost when it uses a teleport portal.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 62,
		section = sectionThresholds
	)
	default int costTeleportationPortals()
	{
		return 0;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costTeleportationSpells",
		name = "Teleport spell weight",
		description = "Weight added to the route's cost when it uses a teleport spell.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 63,
		section = sectionThresholds
	)
	default int costTeleportationSpells()
	{
		return 15;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costTeleportationMinigames",
		name = "Minigame teleport weight",
		description = "Weight added to the route's cost when it uses a minigame teleport.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 64,
		section = sectionThresholds
	)
	default int costTeleportationMinigames()
	{
		return 20;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costWildernessObelisks",
		name = "Wilderness obelisk weight",
		description = "Weight added to the route's cost when it uses a wilderness obelisk.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 65,
		section = sectionThresholds
	)
	default int costWildernessObelisks()
	{
		return 20;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costSeasonalTransports",
		name = "Seasonal transport weight",
		description = "Weight added to the route's cost when it uses a seasonal (Leagues) transport.<br>" +
			"In run-tiles (2 = 1 game tick, 0.6s): positive avoids it, negative favors it.",
		position = 66,
		section = sectionThresholds
	)
	default int costSeasonalTransports()
	{
		return 0;
	}

	@Range(
		min = -10000,
		max = 10000
	)
	@ConfigItem(
		keyName = "costBankPickup",
		name = "Bank pickup weight",
		description = "Weight added to the route's cost when it detours through a bank to withdraw<br>" +
			"a teleport item (Owned: inventory + bank mode). In run-tiles (2 = 1 game tick,<br>" +
			"0.6s): positive avoids banking, negative favors it.",
		position = 67,
		section = sectionThresholds
	)
	default int costBankPickup()
	{
		return 50;
	}

	@ConfigSection(
		name = "Display",
		description = "Options for displaying the path on the world map, minimap and scene tiles",
		position = 67
	)
	String sectionDisplay = "sectionDisplay";

	@ConfigItem(
		keyName = "drawMap",
		name = "Draw path on world map",
		description = "Whether the path should be drawn on the world map",
		position = 68,
		section = sectionDisplay
	)
	default boolean drawMap()
	{
		return true;
	}

	@ConfigItem(
		keyName = "drawMinimap",
		name = "Draw path on minimap",
		description = "Whether the path should be drawn on the minimap",
		position = 69,
		section = sectionDisplay
	)
	default boolean drawMinimap()
	{
		return true;
	}

	@ConfigItem(
		keyName = "drawTiles",
		name = "Draw path on tiles",
		description = "Whether the path should be drawn on the game tiles",
		position = 70,
		section = sectionDisplay
	)
	default boolean drawTiles()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showTeleportPulse",
		name = "Teleport pulse",
		description = "Animate a pulsing highlight on the tile when the path tells you to use a teleport",
		position = 72,
		section = sectionDisplay
	)
	default boolean showTeleportPulse()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showDirections",
		name = "Directions overlay",
		description = "Show a movable step-by-step directions panel for the route currently shown on the map<br>" +
			"(walking legs, teleports to use, bank withdrawals and climbs)",
		position = 75,
		section = sectionDisplay
	)
	default boolean showDirections()
	{
		return true;
	}

	@ConfigItem(
		keyName = "arrivalAutoDismiss",
		name = "Auto-dismiss arrival",
		description = "Automatically hide the \"Arrived!\" panel after a delay.<br>" +
			"When off it stays until clicked",
		position = 76,
		section = sectionDisplay
	)
	default boolean arrivalAutoDismiss()
	{
		return false;
	}

	@Range(min = 1, max = 300)
	@Units(Units.SECONDS)
	@ConfigItem(
		keyName = "arrivalDismissSeconds",
		name = "Arrival dismiss delay",
		description = "How long the \"Arrived!\" panel lingers before auto-dismissing (when enabled)",
		position = 77,
		section = sectionDisplay
	)
	default int arrivalDismissSeconds()
	{
		return 10;
	}

	@ConfigSection(
		name = "Colours",
		description = "Colours for the path map, minimap and scene tiles",
		position = 72
	)
	String sectionColours = "sectionColours";

	@Alpha
	@ConfigItem(
		keyName = "colourPath",
		name = "Path",
		description = "Colour of the path tiles on the world map, minimap and in the game scene",
		position = 73,
		section = sectionColours
	)
	default Color colourPath()
	{
		return new Color(0, 255, 255);
	}

	@Alpha
	@ConfigItem(
		keyName = "colourPathBlocked",
		name = "Blocked by door",
		description = "Colour of the path beyond a door that has not been seen open yet:<br>" +
			"the route assumes doors are passable, so this marks the part you can't walk yet",
		position = 73,
		section = sectionColours
	)
	default Color colourPathBlocked()
	{
		return new Color(224, 62, 62);
	}

	@Alpha
	@ConfigItem(
		keyName = "colourPathCalculating",
		name = "Calculating",
		description = "Colour of the path tiles while the pathfinding calculation is in progress," +
			"<br>and the colour of unused targets if there are more than a single target",
		position = 74,
		section = sectionColours
	)
	default Color colourPathCalculating()
	{
		return new Color(0, 0, 255);
	}

	@Alpha
	@ConfigItem(
		keyName = "colourPathUnreachable",
		name = "Unreachable",
		description = "Colour of the path tiles when pathfinding has finished but the target is still too far away",
		position = 75,
		section = sectionColours
	)
	default Color colourPathUnreachable()
	{
		return new Color(200, 40, 240);
	}

	@Alpha
	@ConfigItem(
		keyName = "colourTransports",
		name = "Transports",
		description = "Colour of the transport tiles",
		position = 76,
		section = sectionColours
	)
	default Color colourTransports()
	{
		return new Color(0, 255, 0, 128);
	}

	@Alpha
	@ConfigItem(
		keyName = "colourCollisionMap",
		name = "Collision map",
		description = "Colour of the collision map tiles",
		position = 77,
		section = sectionColours
	)
	default Color colourCollisionMap()
	{
		return new Color(0, 128, 255, 128);
	}

	@Alpha
	@ConfigItem(
		keyName = "colourText",
		name = "Text",
		description = "Colour of the text of the tile counter and fairy ring codes",
		position = 78,
		section = sectionColours
	)
	default Color colourText()
	{
		return Color.WHITE;
	}

	@ConfigItem(
		keyName = "colourTeleportPulse",
		name = "Teleport pulse",
		description = "Colour of the pulsing teleport highlight (defaults to the path colour)",
		position = 79,
		section = sectionColours
	)
	default Color colourTeleportPulse()
	{
		return new Color(0, 255, 255);
	}

	@ConfigSection(
		name = "Hotkeys",
		description = "Options for keyboard shortcuts",
		position = 80
	)
	String sectionHotkeys = "sectionHotkeys";

	@ConfigItem(
		keyName = "clearPathHotkey",
		name = "Clear current path",
		description = "Hotkey to clear the current path",
		position = 81,
		section = sectionHotkeys
	)
	default Keybind clearPathHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigSection(
		name = "Debug Options",
		description = "Various options for debugging",
		position = 82,
		closedByDefault = true
	)
	String sectionDebug = "sectionDebug";

	@ConfigItem(
		keyName = "drawTransports",
		name = "Draw transports",
		description = "Whether transports should be drawn",
		position = 83,
		section = sectionDebug
	)
	default boolean drawTransports()
	{
		return false;
	}

	@ConfigItem(
		keyName = "drawCollisionMap",
		name = "Draw collision map",
		description = "Whether the collision map should be drawn",
		position = 84,
		section = sectionDebug
	)
	default boolean drawCollisionMap()
	{
		return false;
	}

	@ConfigItem(
		keyName = "drawDebugPanel",
		name = "Show debug panel",
		description = "Toggles displaying the pathfinding debug stats panel",
		position = 85,
		section = sectionDebug
	)
	default boolean drawDebugPanel()
	{
		return false;
	}

	@ConfigItem(
		keyName = "postTransports",
		name = "Post transports",
		description = "Whether to post the transports used in the current path as a PluginMessage event",
		position = 86,
		section = sectionDebug
	)
	default boolean postTransports()
	{
		return false;
	}

	@ConfigItem(
		keyName = "unreachableText",
		name = "",
		description = "Text shown on the player tile when the destination cannot be reached",
		hidden = true
	)
	default String unreachableText()
	{
		return "Destination could not be reached";
	}

	@ConfigItem(
		keyName = "builtTeleportationBoxes",
		name = "",
		description = "ID=X Y Z;ID=X Y Z;ID=X Y Z",
		hidden = true
	)
	@SuppressWarnings("unused")
	default String builtTeleportationBoxes()
	{
		return "";
	}

	@ConfigItem(
		keyName = "builtTeleportationBoxes",
		name = "",
		description = "",
		hidden = true
	)
	@SuppressWarnings("unused")
	void setBuiltTeleportationBoxes(String content);

	@ConfigItem(
		keyName = "builtTeleportationPortalsPoh",
		name = "",
		description = "ID=X Y Z;ID=X Y Z;ID=X Y Z",
		hidden = true
	)
	@SuppressWarnings("unused")
	default String builtTeleportationPortalsPoh()
	{
		return "";
	}

	@ConfigItem(
		keyName = "builtTeleportationPortalsPoh",
		name = "",
		description = "",
		hidden = true
	)
	@SuppressWarnings("unused")
	void setBuiltTeleportationPortalsPoh(String content);

}
