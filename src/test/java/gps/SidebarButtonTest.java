package gps;

import net.runelite.api.GameState;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Plan step L20: the sidebar button, out of the plugin class. Mounted once when logged in,
 * removed once on the login screens, untouched by loading and hopping states.
 */
@RunWith(MockitoJUnitRunner.class)
public class SidebarButtonTest
{
	@Mock
	ClientToolbar toolbar;
	// A real button: the class is final, and its builder needs no client.
	private final NavigationButton button = NavigationButton.builder().tooltip("GPS").priority(70).build();

	@Test
	public void mountedInGameOnlyAndOnlyOnce()
	{
		SidebarButton sidebar = new SidebarButton(toolbar, button);
		sidebar.onGameState(GameState.LOGGING_IN);
		verify(toolbar, never()).addNavigation(button);
		sidebar.onGameState(GameState.LOGGED_IN);
		sidebar.onGameState(GameState.LOADING);
		sidebar.onGameState(GameState.HOPPING);
		sidebar.onGameState(GameState.LOGGED_IN);
		verify(toolbar, times(1)).addNavigation(button);
		verify(toolbar, never()).removeNavigation(button);

		sidebar.onGameState(GameState.LOGIN_SCREEN);
		sidebar.onGameState(GameState.STARTING);
		verify(toolbar, times(1)).removeNavigation(button);

		sidebar.onGameState(GameState.LOGGED_IN);
		sidebar.remove();
		verify(toolbar, times(2)).addNavigation(button);
		verify(toolbar, times(2)).removeNavigation(button);
	}
}
