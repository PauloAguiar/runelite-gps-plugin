package gps;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hidden type-toggle keys with no panel control: a stored value (old build / pre-fork upstream
 * config) is unreachable and silently disables a whole network — the field report was
 * useCharterShips=false with no checkbox anywhere, and charters never appeared. Startup clears
 * such keys so the on-by-default decision holds; panel-backed toggles must never be touched.
 */
@RunWith(MockitoJUnitRunner.class)
public class StrandedToggleMigrationTest
{
	@Mock
	ConfigManager configManager;

	@Test
	public void strandedValuesAreClearedAndPanelBackedTogglesUntouched() throws Exception
	{
		ShortestPathPlugin plugin = new ShortestPathPlugin();
		Field f = ShortestPathPlugin.class.getDeclaredField("configManager");
		f.setAccessible(true);
		f.set(plugin, configManager);
		// Only the charter key has a stored (stranded) value in this scenario.
		when(configManager.getConfiguration(anyString(), anyString())).thenReturn(null);
		when(configManager.getConfiguration("gps", "useCharterShips")).thenReturn("false");

		Method migrate = ShortestPathPlugin.class.getDeclaredMethod("clearUnsurfacedTypeToggles");
		migrate.setAccessible(true);
		migrate.invoke(plugin);

		verify(configManager).unsetConfiguration("gps", "useCharterShips");
		verify(configManager, never()).unsetConfiguration(eq("gps"), eq("useBoats"));

		// The list itself must never contain a toggle the panel writes: clearing one of those
		// would undo a choice the user CAN see and make.
		Set<String> panelBacked = Set.of("useSailing", "useHotAirBalloons", "usePoh", "usePohFairyRing",
			"usePohSpiritTree", "usePohObelisk", "usePohMountedItems", "useTeleportationPortalsPoh",
			"useSpiritTrees", "useSeasonalTransports", "useTeleportationItems");
		for (String key : ShortestPathPlugin.UNSURFACED_TYPE_TOGGLES)
		{
			assertFalse(key + " is panel-backed and must not be cleared", panelBacked.contains(key));
		}
	}
}
