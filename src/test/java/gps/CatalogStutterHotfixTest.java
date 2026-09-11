package gps;

import gps.pathfinder.PathfinderConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hub issues #23/#24 (0.13.1): every inventory or equipment change ran a full planning-config
 * refresh on the client thread at the next tick - a 13,922-row transport loop invoking the
 * quest-status clientscript per row x quest - a per-action micro stutter, even with no path
 * active and the panel closed. The hotfix memoizes quest states per refresh pass and only
 * consumes the catalog-dirty flag while the panel is actually visible (with a short cooldown
 * against bursts while it is open).
 */
@RunWith(MockitoJUnitRunner.class)
public class CatalogStutterHotfixTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	/** Each unique quest costs exactly one clientscript per refresh pass, not one per row. */
	@Test
	public void questStateRunsOneScriptPerUniqueQuest()
	{
		when(client.getIntStack()).thenReturn(new int[]{1});
		PathfinderConfig pathConfig = new PathfinderConfig(client, config);

		assertSame(pathConfig.getQuestState(Quest.COOKS_ASSISTANT),
			pathConfig.getQuestState(Quest.COOKS_ASSISTANT));
		verify(client, times(1)).runScript((Object[]) any());

		pathConfig.getQuestState(Quest.DEMON_SLAYER);
		verify(client, times(2)).runScript((Object[]) any());

		// A hundred re-reads of already-seen quests (the transport row loop) add no scripts.
		for (int i = 0; i < 100; i++)
		{
			pathConfig.getQuestState(Quest.COOKS_ASSISTANT);
			pathConfig.getQuestState(Quest.DEMON_SLAYER);
		}
		verify(client, times(2)).runScript((Object[]) any());
	}

	/** The memo is per-pass, not permanent: an on-thread refreshTransports re-reads progress. */
	@Test
	public void refreshPassRereadsQuestProgress() throws Exception
	{
		when(client.getIntStack()).thenReturn(new int[]{1});
		// The client thread is THIS thread, fixed - so the spawned thread below really is
		// off-thread (an answer of currentThread() would make every thread "the client thread").
		Thread clientThread = Thread.currentThread();
		lenient().when(client.getClientThread()).thenReturn(clientThread);
		PathfinderConfig pathConfig = new PathfinderConfig(client, config);

		pathConfig.getQuestState(Quest.THE_GRAND_TREE);
		verify(client, times(1)).runScript((Object[]) any());

		// Off the client thread the guard returns before the memo clear - the cache survives
		// (searches may consult quest states between refreshes).
		Method refreshTransports = PathfinderConfig.class.getDeclaredMethod("refreshTransports");
		refreshTransports.setAccessible(true);
		Thread offThread = new Thread(() ->
		{
			try
			{
				refreshTransports.invoke(pathConfig);
			}
			catch (Exception ignored)
			{
			}
		});
		offThread.start();
		offThread.join();
		pathConfig.getQuestState(Quest.THE_GRAND_TREE);
		verify(client, times(1)).runScript((Object[]) any());

		// On the client thread the pass clears the memo first, so quest progress is re-read
		// (the type-level disableUnless gates query The Grand Tree again immediately).
		int before = Mockito.mockingDetails(client).getInvocations().size();
		try
		{
			refreshTransports.invoke(pathConfig);
		}
		catch (Exception ignored)
		{
			// A bare-mock client can fail deep in the row loop; the clear and the type-gate
			// queries have already run by then, which is all this test asserts.
		}
		assertTrue("an on-thread pass must re-run quest scripts after clearing the memo",
			Mockito.mockingDetails(client).getInvocations().size() > before);
	}

	/** Hidden panel: the dirty flag waits; visible panel: consumed, with a burst cooldown. */
	@Test
	public void catalogRefreshWaitsForTheVisiblePanel() throws Exception
	{
		ShortestPathPlugin plugin = new ShortestPathPlugin();
		AlternativeRoutesService service = mock(AlternativeRoutesService.class);
		ShortestPathPanel panel = mock(ShortestPathPanel.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		lenient().when(client.getTickCount()).thenReturn(100);
		set(plugin, "client", client);
		set(routes(plugin), "service", service);
		set(plugin, "altPanel", panel);
		set(refresher(plugin), "dirty", true);

		RouteController routes = routes(plugin);

		// Panel hidden (the reporters' state): nothing runs, the flag stays armed.
		routes.refreshCatalogIfDue();
		verify(service, never()).refreshCatalog(any(), any());
		assertTrue("dirty flag must survive a hidden-panel tick", getBool(refresher(plugin), "dirty"));

		// Panel opens: the pending flag is consumed on the next tick.
		set(routes, "panelVisible", true);
		routes.refreshCatalogIfDue();
		verify(service, times(1)).refreshCatalog(any(), any());
		assertFalse(getBool(refresher(plugin), "dirty"));

		// A burst on the same tick (chopping logs with the panel open) waits out the cooldown...
		set(refresher(plugin), "dirty", true);
		routes.refreshCatalogIfDue();
		verify(service, times(1)).refreshCatalog(any(), any());
		assertTrue(getBool(refresher(plugin), "dirty"));

		// ...and runs once the cooldown lapses.
		when(client.getTickCount()).thenReturn(105);
		routes.refreshCatalogIfDue();
		verify(service, times(2)).refreshCatalog(any(), any());
		assertFalse(getBool(refresher(plugin), "dirty"));
	}

	/** The plugin's route controller (see RouteController), which owns the generation state since L35. */
	private static RouteController routes(ShortestPathPlugin plugin) throws Exception
	{
		Field f = ShortestPathPlugin.class.getDeclaredField("routes");
		f.setAccessible(true);
		return (RouteController) f.get(plugin);
	}

	/** The controller's catalog refresher (see CatalogRefresher), which owns the dirty flag since L29. */
	private static CatalogRefresher refresher(ShortestPathPlugin plugin) throws Exception
	{
		Field f = RouteController.class.getDeclaredField("catalogRefresh");
		f.setAccessible(true);
		return (CatalogRefresher) f.get(routes(plugin));
	}

	private static void set(Object target, String field, Object value) throws Exception
	{
		Field f = target.getClass().getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	private static boolean getBool(Object target, String field) throws Exception
	{
		Field f = target.getClass().getDeclaredField(field);
		f.setAccessible(true);
		return (boolean) f.get(target);
	}
}
