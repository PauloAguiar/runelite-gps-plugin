package gps;

import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

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
}
