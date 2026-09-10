package gps;

import net.runelite.api.GameState;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

/**
 * The GPS sidebar button (plan step L20, out of the plugin class): mounted only in-game, since
 * it does nothing useful on the login screen. LOADING, HOPPING and CONNECTION_LOST leave it as
 * it is, so world hops do not flicker it.
 */
final class SidebarButton
{
	private final ClientToolbar toolbar;
	private final NavigationButton button;
	private boolean shown;

	SidebarButton(ClientToolbar toolbar, NavigationButton button)
	{
		this.toolbar = toolbar;
		this.button = button;
	}

	/** Every game-state change: shown when logged in, hidden on the login screens. */
	void onGameState(GameState state)
	{
		switch (state)
		{
			case LOGGED_IN:
				show(true);
				break;
			case LOGIN_SCREEN:
			case LOGIN_SCREEN_AUTHENTICATOR:
			case STARTING:
				show(false);
				break;
			default:
				break;
		}
	}

	void show(boolean show)
	{
		if (show == shown)
		{
			return;
		}
		shown = show;
		if (show)
		{
			toolbar.addNavigation(button);
		}
		else
		{
			toolbar.removeNavigation(button);
		}
	}

	/** Opens the panel (the focus-search hotkey). */
	void open()
	{
		toolbar.openPanel(button);
	}

	/** Plugin shutdown: off the toolbar whatever the state. */
	void remove()
	{
		toolbar.removeNavigation(button);
		shown = false;
	}
}
