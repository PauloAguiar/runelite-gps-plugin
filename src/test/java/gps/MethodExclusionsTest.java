package gps;

import com.google.gson.Gson;
import gps.transport.TransportType;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Plan step L26: the excluded methods, out of the plugin class. Every real change persists once
 * and notifies once; a no-op change (excluding an excluded method, clearing an empty set) does
 * neither; the route list is stale after a change until a generation marks the set as used.
 */
@RunWith(MockitoJUnitRunner.class)
public class MethodExclusionsTest
{
	@Mock
	ConfigManager configManager;

	private final AtomicInteger changes = new AtomicInteger();
	private final TeleportMethod tab = new TeleportMethod(TransportType.TELEPORTATION_ITEM, "Varrock tablet", 1);
	private final TeleportMethod fairy = new TeleportMethod(TransportType.FAIRY_RING, "BKR", 2);

	private MethodExclusions exclusions()
	{
		return new MethodExclusions(new ChoiceStore(() -> configManager, Gson::new, "gps"), changes::incrementAndGet);
	}

	private void verifySaves(int count)
	{
		verify(configManager, times(count)).setConfiguration(eq("gps"), eq(ChoiceStore.CONFIG_KEY_EXCLUSIONS), anyString());
	}

	@Test
	public void changesPersistAndNotifyOnceEach()
	{
		MethodExclusions exclusions = exclusions();
		exclusions.exclude(tab);
		exclusions.exclude(tab);
		assertTrue(exclusions.contains(tab));
		assertEquals(1, changes.get());
		verifySaves(1);

		exclusions.excludeAll(List.of(tab, fairy));
		assertEquals(Set.of(tab, fairy), exclusions.copy());
		assertEquals(2, changes.get());

		exclusions.includeAll(List.of(fairy));
		exclusions.include(fairy);
		assertEquals(Set.of(tab), exclusions.copy());
		assertEquals(3, changes.get());

		exclusions.clear();
		exclusions.clear();
		assertTrue(exclusions.isEmpty());
		assertEquals(4, changes.get());
		verifySaves(4);
	}

	@Test
	public void theListIsStaleAfterAChangeUntilAGenerationUsesTheSet()
	{
		MethodExclusions exclusions = exclusions();
		assertFalse("nothing excluded, nothing generated: fresh", exclusions.isStale());
		exclusions.exclude(tab);
		assertTrue(exclusions.isStale());
		exclusions.markGenerated();
		assertFalse(exclusions.isStale());
		exclusions.include(tab);
		assertTrue(exclusions.isStale());
	}
}
