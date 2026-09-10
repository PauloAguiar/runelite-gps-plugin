package gps;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan step L7: the boat banner, out of the plugin class. Owned boats become {name, port, hull}
 * rows from the sailing varbits and the game's own name-part tables, a varbit burst rebuilds the
 * banner once on the next tick, the rows persist as "name|port|hull" and restore (older
 * snapshots without a hull included), and logging out forgets everything.
 */
@RunWith(MockitoJUnitRunner.class)
public class BoatBannerServiceTest
{
	@Mock
	Client client;
	@Mock
	ConfigManager configManager;

	private static final String GROUP = "gps";

	@Test
	public void ownedBoatsBecomeRowsAndPersist()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		lenient().when(client.getVarbitValue(anyInt())).thenReturn(0);
		// Boat 1: owned, moored at port 1, named "Salty Old Tub" via indexes 2/3/4, a sloop.
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_OWNED)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_PORT)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_NAME_1)).thenReturn(2);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_NAME_2)).thenReturn(3);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_NAME_3)).thenReturn(4);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_TYPE)).thenReturn(2);
		when(client.getDBTableField(eq(DBTableID.SailingBoatNameOptions.Row.SAILING_BOAT_NAME_PREFIX_OPTIONS),
			eq(DBTableID.SailingBoatNameOptions.COL_OPTION), eq(0))).thenReturn(new Object[]{"", "Salty", "Salty"});
		when(client.getDBTableField(eq(DBTableID.SailingBoatNameOptions.Row.SAILING_BOAT_NAME_DESCRIPTOR_OPTIONS),
			eq(DBTableID.SailingBoatNameOptions.COL_OPTION), eq(0))).thenReturn(new Object[]{"", "x", "Old", "Old"});
		when(client.getDBTableField(eq(DBTableID.SailingBoatNameOptions.Row.SAILING_BOAT_NAME_NOUN_OPTIONS),
			eq(DBTableID.SailingBoatNameOptions.COL_OPTION), eq(0))).thenReturn(new Object[]{"", "x", "y", "Tub", "Tub"});
		AtomicInteger notified = new AtomicInteger();

		BoatBannerService service = new BoatBannerService(client, configManager, GROUP, notified::incrementAndGet);
		assertNull("nothing collected yet", service.banner());
		service.markDirty();
		service.onTick();

		List<String[]> rows = service.banner();
		assertEquals("boats 2 to 5 are neither owned nor named", 1, rows.size());
		assertEquals("Salty Old Tub", rows.get(0)[0]);
		assertEquals(SailingPorts.portName(1), rows.get(0)[1]);
		assertEquals("Sloop", rows.get(0)[2]);
		assertTrue(service.isLive());
		assertEquals(1, notified.get());
		verify(configManager).setRSProfileConfiguration(GROUP, BoatBannerService.CONFIG_KEY_BOAT_PORTS,
			"Salty Old Tub|" + SailingPorts.portName(1) + "|Sloop");

		// A second tick without a new varbit change rebuilds nothing.
		service.onTick();
		assertEquals(1, notified.get());
	}

	@Test
	public void aNamedButUnownedFlagBoatStillCounts()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		lenient().when(client.getVarbitValue(anyInt())).thenReturn(0);
		// Port Sarim is port id 0 and the owned flag can lag: a set descriptor proves the boat.
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_2_NAME_2)).thenReturn(1);
		// A hull tier this build does not know (0 is the raft).
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_2_TYPE)).thenReturn(7);
		BoatBannerService service = new BoatBannerService(client, configManager, GROUP, null);
		service.refresh();
		assertEquals(1, service.banner().size());
		assertEquals("no readable name: the slot label", "Boat 2", service.banner().get(0)[0]);
		assertEquals("unknown hull shows no type", "", service.banner().get(0)[2]);
	}

	@Test
	public void restoreReadsTheSnapshotOnceAndLogoutForgetsIt()
	{
		when(configManager.getRSProfileConfiguration(GROUP, BoatBannerService.CONFIG_KEY_BOAT_PORTS))
			.thenReturn("Salty Old Tub|Port Sarim|Skiff;Old Snapshot|Catherby;|Bad;");
		BoatBannerService service = new BoatBannerService(client, configManager, GROUP, null);
		service.restore();
		List<String[]> rows = service.banner();
		assertEquals("rows without a name are dropped", 2, rows.size());
		assertEquals("Skiff", rows.get(0)[2]);
		assertEquals("an older snapshot has no hull", "", rows.get(1)[2]);
		assertFalse("a restored banner is not live", service.isLive());

		service.restore();
		verify(configManager).getRSProfileConfiguration(GROUP, BoatBannerService.CONFIG_KEY_BOAT_PORTS);

		service.reset();
		assertNull(service.banner());
		assertFalse(service.isLive());
	}

	@Test
	public void notLoggedInMeansNoRebuildAndNoWrite()
	{
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		BoatBannerService service = new BoatBannerService(client, configManager, GROUP, null);
		service.markDirty();
		service.onTick();
		assertNull(service.banner());
		verify(configManager, never()).setRSProfileConfiguration(eq(GROUP), eq(BoatBannerService.CONFIG_KEY_BOAT_PORTS), (String) org.mockito.ArgumentMatchers.any());
	}

	@Test
	public void trackedVarbitsAndTheWireFormat()
	{
		BoatBannerService service = new BoatBannerService(client, configManager, GROUP, null);
		assertTrue(service.tracks(VarbitID.SAILING_BOAT_3_PORT));
		assertFalse(service.tracks(VarbitID.SAILING_BOARDED_BOAT));
		assertEquals("A|B|Raft;C|D|", BoatBannerService.encode(List.of(new String[]{"A", "B", "Raft"}, new String[]{"C", "D", ""})));
		assertEquals(2, BoatBannerService.decode("A|B|Raft;C|D").size());
	}
}
