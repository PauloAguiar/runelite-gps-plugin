package gps;

import com.google.gson.Gson;
import gps.transport.TransportType;
import java.util.List;
import java.util.Set;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan step L15: the persisted user choices, out of the plugin class. The routes mode reloads
 * by name, the three legacy 3-mode names map onto the Owned/All split, and anything else means
 * "keep the default"; the exclusion set reloads from JSON, dropping entries with no type and the
 * seasonal methods a prior version seeded there (and rewrites the cleaned set once, only when
 * something was dropped); the search history and favourites round-trip through their codec.
 */
@RunWith(MockitoJUnitRunner.class)
public class ChoiceStoreTest
{
	private static final String GROUP = "gps";

	@Mock
	ConfigManager configManager;

	private ChoiceStore store()
	{
		return new ChoiceStore(() -> configManager, Gson::new, GROUP);
	}

	@Test
	public void routesModeReloadsByNameOrLegacyName()
	{
		assertEquals(AlternativeRoutesMode.OWNED_WITH_BANK, ChoiceStore.decodeRoutesMode("OWNED_WITH_BANK"));
		assertEquals(AlternativeRoutesMode.OWNED_INVENTORY, ChoiceStore.decodeRoutesMode("AVAILABLE"));
		assertEquals(AlternativeRoutesMode.OWNED_WITH_BANK, ChoiceStore.decodeRoutesMode("AVAILABLE_WITH_BANK"));
		assertEquals(AlternativeRoutesMode.ALL_EVERYTHING, ChoiceStore.decodeRoutesMode("ALL_TELEPORTS"));
		assertEquals(AlternativeRoutesMode.ALL_EVERYTHING, ChoiceStore.decodeRoutesMode("ALL_UNLOCKED"));
		assertNull(ChoiceStore.decodeRoutesMode(null));
		assertNull(ChoiceStore.decodeRoutesMode(""));
		assertNull(ChoiceStore.decodeRoutesMode("bogus"));

		when(configManager.getConfiguration(GROUP, ChoiceStore.CONFIG_KEY_MODE)).thenReturn("ALL_TELEPORTS");
		assertEquals(AlternativeRoutesMode.ALL_EVERYTHING, store().loadRoutesMode());
		store().saveRoutesMode(AlternativeRoutesMode.OWNED_WITH_BANK);
		verify(configManager).setConfiguration(GROUP, ChoiceStore.CONFIG_KEY_MODE, "OWNED_WITH_BANK");
	}

	@Test
	public void exclusionsReloadDroppingSeasonalAndTypelessEntriesOnce()
	{
		TeleportMethod glory = new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Amulet of glory: Edgeville", 1);
		TeleportMethod seasonal = new TeleportMethod(TransportType.SEASONAL_TRANSPORTS, "Leagues portal", 2);
		TeleportMethod typeless = new TeleportMethod(null, "Corrupt", 3);
		Gson gson = new Gson();
		when(configManager.getConfiguration(GROUP, ChoiceStore.CONFIG_KEY_EXCLUSIONS))
			.thenReturn(gson.toJson(List.of(glory, seasonal, typeless)));

		Set<TeleportMethod> loaded = store().loadExclusions();
		assertEquals(Set.of(glory), loaded);
		ArgumentCaptor<String> rewritten = ArgumentCaptor.forClass(String.class);
		verify(configManager).setConfiguration(eq(GROUP), eq(ChoiceStore.CONFIG_KEY_EXCLUSIONS), rewritten.capture());
		assertEquals("the migration rewrote the cleaned set", List.of(glory),
			List.of(gson.fromJson(rewritten.getValue(), TeleportMethod[].class)));
	}

	@Test
	public void cleanExclusionsAreNotRewritten()
	{
		TeleportMethod glory = new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Amulet of glory: Edgeville", 1);
		when(configManager.getConfiguration(GROUP, ChoiceStore.CONFIG_KEY_EXCLUSIONS))
			.thenReturn(new Gson().toJson(List.of(glory)));
		assertEquals(Set.of(glory), store().loadExclusions());
		verify(configManager, never()).setConfiguration(eq(GROUP), eq(ChoiceStore.CONFIG_KEY_EXCLUSIONS), org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	public void historyAndFavouritesRoundTrip()
	{
		Destinations.Entry bank = new Destinations.Entry("bank", "Varrock west bank", 12345);
		Destinations.Entry home = new Destinations.Entry("favorite", "Home", 67890);
		ChoiceStore store = store();
		store.saveSearchHistory(List.of(bank));
		store.saveFavorites(List.of(home, bank));
		ArgumentCaptor<String> history = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> favorites = ArgumentCaptor.forClass(String.class);
		verify(configManager).setConfiguration(eq(GROUP), eq(ChoiceStore.CONFIG_KEY_SEARCH_HISTORY), history.capture());
		verify(configManager).setConfiguration(eq(GROUP), eq(ChoiceStore.CONFIG_KEY_FAVORITES), favorites.capture());

		when(configManager.getConfiguration(GROUP, ChoiceStore.CONFIG_KEY_SEARCH_HISTORY)).thenReturn(history.getValue());
		when(configManager.getConfiguration(GROUP, ChoiceStore.CONFIG_KEY_FAVORITES)).thenReturn(favorites.getValue());
		assertSameEntries(List.of(bank), store.loadSearchHistory());
		assertSameEntries(List.of(home, bank), store.loadFavorites());
	}

	/** Entries carry no equals; compare what the codec carries. */
	private static void assertSameEntries(List<Destinations.Entry> expected, List<Destinations.Entry> actual)
	{
		assertEquals(expected.size(), actual.size());
		for (int i = 0; i < expected.size(); i++)
		{
			assertEquals(expected.get(i).category, actual.get(i).category);
			assertEquals(expected.get(i).name, actual.get(i).name);
			assertEquals(expected.get(i).packedPosition, actual.get(i).packedPosition);
		}
	}
}
