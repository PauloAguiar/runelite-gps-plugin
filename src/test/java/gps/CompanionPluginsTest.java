package gps;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Plan step L20: the companion-plugin checks, out of the plugin class. The original Shortest
 * Path plugin enabled alongside is a conflict; Quest Helper enabled with its "Use Shortest Path
 * plugin" option off means quest steps never reach GPS; a disabled plugin counts for neither;
 * the change callback fires only when a verdict flips.
 */
@RunWith(MockitoJUnitRunner.class)
public class CompanionPluginsTest
{
	@PluginDescriptor(name = "Shortest Path")
	static class FakeShortestPath extends Plugin
	{
	}

	@PluginDescriptor(name = "Quest Helper")
	static class FakeQuestHelper extends Plugin
	{
	}

	@PluginDescriptor(name = "GPS")
	static class Self extends Plugin
	{
	}

	@Mock
	PluginManager pluginManager;
	@Mock
	ConfigManager configManager;

	private final Plugin self = new Self();
	private final AtomicInteger changes = new AtomicInteger();

	@Test
	public void anEnabledShortestPathIsAConflictAndTheCallbackFiresOnce()
	{
		Plugin shortestPath = new FakeShortestPath();
		when(pluginManager.getPlugins()).thenReturn(List.of(self, shortestPath));
		when(pluginManager.isPluginEnabled(shortestPath)).thenReturn(true);
		CompanionPlugins companions = new CompanionPlugins(pluginManager, configManager, self, changes::incrementAndGet);

		companions.refresh();
		assertTrue(companions.isShortestPathConflict());
		assertFalse(companions.isQuestHelperPathingOff());
		companions.refresh();
		assertEquals("unchanged verdicts do not fire the callback", 1, changes.get());

		when(pluginManager.isPluginEnabled(shortestPath)).thenReturn(false);
		companions.refresh();
		assertFalse("disabled: no conflict", companions.isShortestPathConflict());
		assertEquals(2, changes.get());
	}

	@Test
	public void questHelperWithoutItsOptionMeansPathingOff()
	{
		Plugin questHelper = new FakeQuestHelper();
		when(pluginManager.getPlugins()).thenReturn(List.of(questHelper, self));
		when(pluginManager.isPluginEnabled(questHelper)).thenReturn(true);
		when(configManager.getConfiguration("questhelper", "useShortestPath")).thenReturn("false");
		CompanionPlugins companions = new CompanionPlugins(pluginManager, configManager, self, changes::incrementAndGet);

		companions.refresh();
		assertTrue(companions.isQuestHelperPathingOff());
		assertFalse(companions.isShortestPathConflict());

		when(configManager.getConfiguration("questhelper", "useShortestPath")).thenReturn("true");
		companions.refresh();
		assertFalse("the option on: quest steps reach GPS", companions.isQuestHelperPathingOff());
		assertEquals(2, changes.get());
	}
}
