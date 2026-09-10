package gps;

import com.google.gson.Gson;
import java.util.List;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Plan step L27: the search memory, out of the plugin class. A favourite with the same label
 * replaces the old one, the list is capped, removal matches label and position, a selection
 * goes to the front of the history, and every change persists.
 */
@RunWith(MockitoJUnitRunner.class)
public class SearchMemoryTest
{
	@Mock
	ConfigManager configManager;

	private SearchMemory memory()
	{
		return new SearchMemory(new ChoiceStore(() -> configManager, Gson::new, "gps"));
	}

	@Test
	public void favouritesReplaceByLabelAndRemoveByLabelAndPosition()
	{
		SearchMemory memory = memory();
		memory.addFavorite("Home", 100);
		memory.addFavorite("Bank", 200);
		memory.addFavorite("Home", 300);
		assertEquals(List.of("Bank", "Home"), names(memory.favorites()));
		assertEquals(300, memory.favorites().get(1).packedPosition);

		memory.removeFavorite(new Destinations.Entry("favorite", "Home", 100));
		assertEquals("a different position is a different favourite", 2, memory.favorites().size());
		memory.removeFavorite(new Destinations.Entry("favorite", "Home", 300));
		assertEquals(List.of("Bank"), names(memory.favorites()));
		verify(configManager, times(5)).setConfiguration(eq("gps"), eq(ChoiceStore.CONFIG_KEY_FAVORITES), anyString());
	}

	@Test
	public void theFavouriteListIsCapped()
	{
		SearchMemory memory = memory();
		for (int i = 0; i < ChoiceStore.FAVORITES_LIMIT + 5; i++)
		{
			memory.addFavorite("Spot " + i, i);
		}
		assertEquals(ChoiceStore.FAVORITES_LIMIT, memory.favorites().size());
		assertTrue("the extras were not added", names(memory.favorites()).contains("Spot 0"));
	}

	@Test
	public void selectionsGoToTheFrontOfTheHistory()
	{
		SearchMemory memory = memory();
		memory.recordSelection(new Destinations.Entry("bank", "Varrock west bank", 1));
		memory.recordSelection(new Destinations.Entry("city", "Falador", 2));
		memory.recordSelection(new Destinations.Entry("bank", "Varrock west bank", 1));
		assertEquals(List.of("Varrock west bank", "Falador"), names(memory.history()));
		verify(configManager, times(3)).setConfiguration(eq("gps"), eq(ChoiceStore.CONFIG_KEY_SEARCH_HISTORY), anyString());
	}

	private static List<String> names(List<Destinations.Entry> entries)
	{
		List<String> names = new java.util.ArrayList<>();
		for (Destinations.Entry entry : entries)
		{
			names.add(entry.name);
		}
		return names;
	}
}
