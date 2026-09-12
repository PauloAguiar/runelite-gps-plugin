package gps;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hidden type-toggle keys with no panel control: a stored value from an older build is
 * unreachable and can silently disable a whole network (the field report was
 * useCharterShips=false with no checkbox anywhere). Startup clears such keys so the on-by-default
 * decision holds. RuneLite writes each item's default into the store itself when the key is
 * absent, so a stored default is the normal state and must be left alone (review of 2026-09-12:
 * the routine used to clear and log that write at every start). Panel-backed toggles are never
 * on the list, and every listed toggle is a hidden item defaulting to on.
 */
@RunWith(MockitoJUnitRunner.class)
public class HiddenToggleMigrationTest
{
	@Mock
	ConfigManager configManager;

	@Test
	public void onlyAValueDifferingFromTheDefaultIsCleared()
	{
		when(configManager.getConfiguration(anyString(), anyString())).thenReturn(null);
		when(configManager.getConfiguration("gps", "useCharterShips")).thenReturn("false");
		when(configManager.getConfiguration("gps", "useBoats")).thenReturn("true");

		List<String> cleared = HiddenToggleMigration.clearStranded(configManager, "gps");

		assertEquals(List.of("useCharterShips"), cleared);
		verify(configManager).unsetConfiguration("gps", "useCharterShips");
		verify(configManager, never()).unsetConfiguration("gps", "useBoats");
		verify(configManager, times(1)).unsetConfiguration(anyString(), anyString());
	}

	@Test
	public void everyListedToggleIsAHiddenItemDefaultingToOn() throws Throwable
	{
		ShortestPathConfig proxy = (ShortestPathConfig) Proxy.newProxyInstance(
			ShortestPathConfig.class.getClassLoader(), new Class<?>[]{ShortestPathConfig.class},
			(p, m, args) ->
			{
				throw new UnsupportedOperationException(m.getName());
			});
		for (String key : HiddenToggleMigration.HIDDEN_TYPE_TOGGLES)
		{
			Method method = ShortestPathConfig.class.getMethod(key);
			ConfigItem item = method.getAnnotation(ConfigItem.class);
			assertNotNull(key + " is a config item", item);
			assertEquals(key + " keys its own name", key, item.keyName());
			assertTrue(key + " is hidden (no panel control)", item.hidden());
			assertTrue(key + " is a default method", method.isDefault());
			Object value = MethodHandles.privateLookupIn(ShortestPathConfig.class, MethodHandles.lookup())
				.unreflectSpecial(method, ShortestPathConfig.class).bindTo(proxy).invokeWithArguments();
			assertEquals(key + " defaults to on, the value the migration keeps",
				HiddenToggleMigration.DEFAULT, String.valueOf(value));
		}
	}

	@Test
	public void theListNeverContainsAPanelBackedToggle()
	{
		// Clearing one of those would undo a choice the user CAN see and make.
		Set<String> panelBacked = Set.of("useSailing", "useHotAirBalloons", "usePoh", "usePohFairyRing",
			"usePohSpiritTree", "usePohObelisk", "usePohMountedItems", "useTeleportationPortalsPoh",
			"useSpiritTrees", "useSeasonalTransports", "useTeleportationItems");
		for (String key : HiddenToggleMigration.HIDDEN_TYPE_TOGGLES)
		{
			assertFalse(key + " is panel-backed and must not be cleared", panelBacked.contains(key));
		}
	}
}
