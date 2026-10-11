package gps;

import net.runelite.api.ChatMessageType;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Any canoe station stores one axe for the player, shared by every station, and a stored axe
 * works without being withdrawn (wiki, Canoe Station). The game exposes the storage only in
 * chat, like the balloon log crates, so the store and withdraw lines flip the config flag the
 * canoe rows read. The exact lines are provisional until confirmed from the field: a line that
 * does not match leaves the flag alone, and the flag can be set by hand in the config.
 */
@RunWith(MockitoJUnitRunner.class)
public class CanoeAxeStorageTest
{
	@Mock
	ConfigManager configManager;

	@Test
	public void storingAndWithdrawingFlipTheFlag()
	{
		assertEquals(Boolean.TRUE, CanoeAxeStorage.parse("You store your Dragon axe at the canoe station."));
		assertEquals(Boolean.TRUE, CanoeAxeStorage.parse("You leave your rune axe with the canoe station."));
		assertEquals(Boolean.FALSE, CanoeAxeStorage.parse("You withdraw your Dragon axe from the canoe station."));
		assertEquals(Boolean.FALSE, CanoeAxeStorage.parse("You take your axe back from the canoe station."));
		assertNull("other chat leaves it alone", CanoeAxeStorage.parse("You put the Logs in the crate. You now have 4 stored."));
		assertNull(CanoeAxeStorage.parse("You store the axe in your bank."));
	}

	@Test
	public void onlyGameLinesWriteTheConfig()
	{
		CanoeAxeStorage.track(ChatMessageType.GAMEMESSAGE, "You store your Dragon axe at the canoe station.", configManager, "gps");
		verify(configManager).setConfiguration("gps", "canoeAxeStored", true);
		CanoeAxeStorage.track(ChatMessageType.PUBLICCHAT, "You withdraw your Dragon axe from the canoe station.", configManager, "gps");
		verify(configManager, never()).setConfiguration("gps", "canoeAxeStored", false);
	}
}
