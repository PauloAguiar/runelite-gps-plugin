package gps;

import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The balloon storage crates have no varbits — the chat messages are the only source of truth for
 * the stored counts (same approach and message set as the dedicated tictac7x-balloon plugin). Each
 * game message form must resolve to the right config key and count.
 */
public class BalloonLogStorageTest
{
	@Test
	public void checkMessageSyncsEveryLogType()
	{
		Map<String, Integer> updates = BalloonLogStorage.parse(
			"This crate currently contains 12 logs, 3 oak logs, 0 willow logs, 5 yew logs and 2 magic logs.");
		assertEquals(Integer.valueOf(12), updates.get("balloonStoredLogs"));
		assertEquals(Integer.valueOf(3), updates.get("balloonStoredOakLogs"));
		assertEquals(Integer.valueOf(0), updates.get("balloonStoredWillowLogs"));
		assertEquals(Integer.valueOf(5), updates.get("balloonStoredYewLogs"));
		assertEquals(Integer.valueOf(2), updates.get("balloonStoredMagicLogs"));
		assertEquals(5, updates.size());
	}

	@Test
	public void storingLogsReportsTheNewTotal()
	{
		assertEquals(Map.of("balloonStoredWillowLogs", 7),
			BalloonLogStorage.parse("You put the Willow logs in the crate. You now have 7 stored."));
		assertEquals(Map.of("balloonStoredLogs", 4),
			BalloonLogStorage.parse("You put the Logs in the crate. You now have 4 stored."));
	}

	@Test
	public void flyingReportsTheRemainingCount()
	{
		assertEquals(Map.of("balloonStoredMagicLogs", 3),
			BalloonLogStorage.parse("You have 3 sets of Magic logs left in storage."));
		assertEquals(Map.of("balloonStoredOakLogs", 1),
			BalloonLogStorage.parse("You have one set of Oak logs left in storage."));
		assertEquals(Map.of("balloonStoredYewLogs", 0),
			BalloonLogStorage.parse("You used the last of your Yew logs."));
	}

	@Test
	public void aRefusedTripMeansTheStorageIsEmptyForThatType()
	{
		assertEquals(Map.of("balloonStoredWillowLogs", 0),
			BalloonLogStorage.parse("You need 1 willow logs to make this trip."));
	}

	@Test
	public void unrelatedAndUnknownTypeMessagesAreIgnored()
	{
		assertTrue(BalloonLogStorage.parse("You board the balloon and fly to Varrock.").isEmpty());
		assertTrue(BalloonLogStorage.parse(
			"You put the Redwood logs in the crate. You now have 2 stored.").isEmpty());
	}

	@Test
	public void lowTypesWarnsOnlyForUnlockedRoutesBelowTheThreshold()
	{
		int[] stored = {0, 5, 0, 2, 0};
		boolean[] unlocked = {true, true, true, true, false};
		// normal (0) and willow (0) are unlocked and below 1; oak (5) and yew (2) are covered;
		// magic (0) is low but the route is locked, so it is noise rather than a warning.
		assertEquals(java.util.List.of("normal", "willow"),
			BalloonLogStorage.lowTypes(stored, unlocked, 1));
		// Threshold 3 pulls yew (2) in; threshold 0 disables warnings entirely.
		assertEquals(java.util.List.of("normal", "willow", "yew"),
			BalloonLogStorage.lowTypes(stored, unlocked, 3));
		assertTrue(BalloonLogStorage.lowTypes(stored, unlocked, 0).isEmpty());
	}

	/** Plan step L34: the chat tracking itself, out of the plugin class. */
	@Test
	public void chatLinesUpdateTheCountsOnlyOnTheGameChannelsWithSmartModeOn()
	{
		ShortestPathConfig config = mock(ShortestPathConfig.class);
		ConfigManager configManager = mock(ConfigManager.class);
		when(config.balloonSmartMode()).thenReturn(true);
		String stored = "You put the Willow logs in the crate. You now have 7 stored.";

		BalloonLogStorage.track(ChatMessageType.PUBLICCHAT, stored, config, configManager, "gps");
		verifyNoInteractions(configManager);

		BalloonLogStorage.track(ChatMessageType.SPAM, stored, config, configManager, "gps");
		verify(configManager).setConfiguration("gps", "balloonStoredWillowLogs", 7);
		verify(configManager).setConfiguration("gps", "balloonStorageSynced", true);

		when(config.balloonStorageSynced()).thenReturn(true);
		BalloonLogStorage.track(ChatMessageType.MESBOX, "You used the last of your Yew logs.", config, configManager, "gps");
		verify(configManager).setConfiguration("gps", "balloonStoredYewLogs", 0);
		verify(configManager, times(1)).setConfiguration("gps", "balloonStorageSynced", true);

		when(config.balloonSmartMode()).thenReturn(false);
		BalloonLogStorage.track(ChatMessageType.SPAM, stored, config, configManager, "gps");
		verifyNoMoreInteractions(configManager);
	}
}
