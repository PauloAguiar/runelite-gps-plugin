package gps;

import java.util.List;
import net.runelite.api.Client;

/**
 * The varbits the side panel shows (plan step L25, out of the plugin class), cached on the
 * client thread each game tick because the panel reads them on the Swing thread: the house
 * location and the balloon route unlocks.
 */
final class PanelVarbits
{
	// Varbit 2187: 0 = no house, 1-9 = the owned location.
	private static final int HOUSE_LOCATION = 2187;
	private static final String[] HOUSE_LOCATIONS = {
		null, "Rimmington", "Taverley", "Pollnivneach", "Rellekka", "Brimhaven",
		"Yanille", "Prifddinas", "Hosidius", "Aldarin"};
	// ZEP_MULTI_* in {Entrana, Taverley, Castle Wars, Grand Tree, Crafting Guild, Varrock} order.
	private static final int[] BALLOON_UNLOCKS = {2867, 2868, 2869, 2870, 2871, 2872};

	private volatile int houseLocationId;
	private volatile int[] balloonUnlockVarbits = new int[BALLOON_UNLOCKS.length];

	/** Client thread, once per game tick. */
	void onTick(Client client)
	{
		houseLocationId = client.getVarbitValue(HOUSE_LOCATION);
		int[] unlocks = new int[BALLOON_UNLOCKS.length];
		for (int i = 0; i < unlocks.length; i++)
		{
			unlocks[i] = client.getVarbitValue(BALLOON_UNLOCKS[i]);
		}
		balloonUnlockVarbits = unlocks;
	}

	/** The owned house's location name, or null without a house. */
	String houseLocationName()
	{
		return houseLocationName(houseLocationId);
	}

	static String houseLocationName(int id)
	{
		return (id > 0 && id < HOUSE_LOCATIONS.length) ? HOUSE_LOCATIONS[id] : null;
	}

	/**
	 * The balloon log types that warrant a low-storage warning: routes the player has unlocked
	 * whose stored count sits below the configured threshold. Empty when smart mode is off, the
	 * storage was never synced, or nothing is low.
	 */
	List<String> balloonLowLogTypes(ShortestPathConfig config)
	{
		if (!config.useHotAirBalloons() || !config.balloonSmartMode() || !config.balloonStorageSynced())
		{
			return List.of();
		}
		return BalloonLogStorage.lowTypes(balloonStoredCounts(config), balloonRoutesUnlocked(balloonUnlockVarbits),
			config.balloonLogWarningThreshold());
	}

	/**
	 * Which balloon log types have an unlocked route, in {@link BalloonLogStorage#TYPE_NAMES}
	 * order: normal logs (Entrana or Taverley) unlock at quest completion (2), the rest on the
	 * first flight (1).
	 */
	static boolean[] balloonRoutesUnlocked(int[] unlocks)
	{
		return new boolean[]{
			unlocks[0] >= 2 || unlocks[1] >= 2, unlocks[4] >= 1, unlocks[5] >= 1, unlocks[2] >= 1, unlocks[3] >= 1};
	}

	/** The chat-parsed stored log counts, in {@link BalloonLogStorage#TYPE_NAMES} order. */
	static int[] balloonStoredCounts(ShortestPathConfig config)
	{
		return new int[]{config.balloonStoredLogs(), config.balloonStoredOakLogs(),
			config.balloonStoredWillowLogs(), config.balloonStoredYewLogs(), config.balloonStoredMagicLogs()};
	}
}
