package gps;

import gps.pathfinder.PathfinderConfig;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan step L16: the bank knowledge and its cross-session snapshot, out of the plugin class.
 * The first sight of the live bank is reported once; a save is staged with the profile key of
 * the moment and written once (bank close, logout, shutdown), an empty bank dropping the stored
 * snapshot instead; a restore fills in only when nothing was seen live and the setting is on;
 * logout forgets; turning the setting off drops the stored snapshot and, when this session's
 * knowledge came from it, the knowledge too.
 */
@RunWith(MockitoJUnitRunner.class)
public class BankSnapshotServiceTest
{
	private static final String GROUP = "gps";
	private static final String PROFILE = "rsprofile.abc";

	@Mock
	ConfigManager configManager;
	@Mock
	PathfinderConfig pathfinderConfig;
	@Mock
	ItemContainer bank;

	private boolean rememberBank = true;

	private BankSnapshotService service()
	{
		return new BankSnapshotService(configManager, GROUP, () -> rememberBank, pathfinderConfig);
	}

	private static final Item[] ITEMS = {new Item(995, 100), new Item(2434, 3)};

	@Test
	public void theFirstSightIsReportedOnceAndTheStagedSaveWritesOnce()
	{
		when(bank.getItems()).thenReturn(ITEMS);
		when(configManager.getRSProfileKey()).thenReturn(PROFILE);
		when(pathfinderConfig.getBankSnapshot()).thenReturn(ITEMS);
		BankSnapshotService service = service();

		assertTrue(service.bankOpened(bank));
		assertTrue(service.isKnown());
		assertFalse(service.isRestored());
		verify(pathfinderConfig).setBankSnapshot(ITEMS);
		assertFalse("a deposit is not a first sight", service.bankOpened(bank));

		service.persist();
		verify(configManager).setConfiguration(GROUP, PROFILE, BankSnapshotService.CONFIG_KEY_BANK_SNAPSHOT, "995:100,2434:3");
		service.persist();
		verify(configManager).setConfiguration(eq(GROUP), eq(PROFILE), eq(BankSnapshotService.CONFIG_KEY_BANK_SNAPSHOT), anyString());
	}

	@Test
	public void anEmptyBankDropsTheStoredSnapshot()
	{
		when(bank.getItems()).thenReturn(new Item[]{new Item(-1, 0)});
		when(configManager.getRSProfileKey()).thenReturn(PROFILE);
		when(pathfinderConfig.getBankSnapshot()).thenReturn(new Item[]{new Item(-1, 0)});
		BankSnapshotService service = service();
		service.bankOpened(bank);
		service.persist();
		verify(configManager).unsetConfiguration(GROUP, PROFILE, BankSnapshotService.CONFIG_KEY_BANK_SNAPSHOT);
	}

	@Test
	public void nothingIsStagedWhenTheSettingIsOff()
	{
		rememberBank = false;
		when(bank.getItems()).thenReturn(ITEMS);
		BankSnapshotService service = service();
		service.bankOpened(bank);
		service.persist();
		verify(configManager, never()).setConfiguration(anyString(), anyString(), anyString(), any());
		verify(configManager, never()).getRSProfileKey();
	}

	@Test
	public void restoreFillsInOnlyBeforeTheLiveBankAndTheLiveBankSupersedesIt()
	{
		when(configManager.getRSProfileConfiguration(GROUP, BankSnapshotService.CONFIG_KEY_BANK_SNAPSHOT)).thenReturn("995:100");
		BankSnapshotService service = service();
		service.restore();
		assertTrue(service.isKnown());
		assertTrue(service.isRestored());
		verify(pathfinderConfig).setBankSnapshot(new Item[]{new Item(995, 100)});

		when(bank.getItems()).thenReturn(ITEMS);
		when(configManager.getRSProfileKey()).thenReturn(PROFILE);
		assertFalse("restored knowledge counts as known: not a first sight", service.bankOpened(bank));
		assertFalse(service.isRestored());

		service.restore();
		verify(configManager).getRSProfileConfiguration(GROUP, BankSnapshotService.CONFIG_KEY_BANK_SNAPSHOT);
	}

	@Test
	public void restoreIsSkippedWhenTheSettingIsOff()
	{
		rememberBank = false;
		BankSnapshotService service = service();
		service.restore();
		assertFalse(service.isKnown());
		verify(configManager, never()).getRSProfileConfiguration(anyString(), anyString());
	}

	@Test
	public void logoutPersistsThenForgets()
	{
		when(bank.getItems()).thenReturn(ITEMS);
		when(configManager.getRSProfileKey()).thenReturn(PROFILE);
		when(pathfinderConfig.getBankSnapshot()).thenReturn(ITEMS);
		BankSnapshotService service = service();
		service.bankOpened(bank);
		service.forget();
		verify(configManager).setConfiguration(GROUP, PROFILE, BankSnapshotService.CONFIG_KEY_BANK_SNAPSHOT, "995:100,2434:3");
		verify(pathfinderConfig).clearBank();
		assertFalse(service.isKnown());
		assertFalse(service.isRestored());
	}

	@Test
	public void turningTheSettingOffDropsTheStoredSnapshotAndRestoredKnowledge()
	{
		when(configManager.getRSProfileConfiguration(GROUP, BankSnapshotService.CONFIG_KEY_BANK_SNAPSHOT)).thenReturn("995:100");
		BankSnapshotService service = service();
		service.restore();
		assertTrue("the knowledge came from the snapshot: dropped with it", service.forgetStored());
		verify(configManager).unsetRSProfileConfiguration(GROUP, BankSnapshotService.CONFIG_KEY_BANK_SNAPSHOT);
		verify(pathfinderConfig).clearBank();
		assertFalse(service.isKnown());

		when(bank.getItems()).thenReturn(ITEMS);
		when(configManager.getRSProfileKey()).thenReturn(PROFILE);
		service.bankOpened(bank);
		assertFalse("live knowledge stays", service.forgetStored());
		assertTrue(service.isKnown());
	}

	@Test
	public void turningTheSettingOnSavesALiveBankAtOnce()
	{
		when(bank.getItems()).thenReturn(ITEMS);
		when(configManager.getRSProfileKey()).thenReturn(PROFILE);
		when(pathfinderConfig.getBankSnapshot()).thenReturn(ITEMS);
		rememberBank = false;
		BankSnapshotService service = service();
		service.bankOpened(bank);
		rememberBank = true;
		service.rememberNow(true);
		verify(configManager).setConfiguration(GROUP, PROFILE, BankSnapshotService.CONFIG_KEY_BANK_SNAPSHOT, "995:100,2434:3");
	}
}
