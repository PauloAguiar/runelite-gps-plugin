package gps;

import java.awt.event.KeyEvent;
import java.util.function.Supplier;
import net.runelite.client.config.Keybind;
import net.runelite.client.input.KeyListener;

/**
 * The keyboard twins of the panel's "Bank" and "Bank (and back)" quick buttons (a player
 * suggestion). Each runs the same action as its button, so the destination a trip replaces is
 * resumed at the bank too. Unset by default (see ShortestPathConfig); the bindings are read at
 * key time, so a changed binding applies at once.
 */
final class NearestBankHotkeys
{
	private final KeyListener bank;
	private final KeyListener bankAndBack;

	NearestBankHotkeys(Supplier<Keybind> bankKey, Runnable onBank,
		Supplier<Keybind> bankAndBackKey, Runnable onBankAndBack)
	{
		bank = hotkey(bankKey, onBank);
		bankAndBack = hotkey(bankAndBackKey, onBankAndBack);
	}

	/** Routes to the nearest bank. */
	KeyListener bank()
	{
		return bank;
	}

	/** Routes to the nearest bank and back. */
	KeyListener bankAndBack()
	{
		return bankAndBack;
	}

	private static KeyListener hotkey(Supplier<Keybind> key, Runnable action)
	{
		return new KeyListener()
		{
			@Override
			public void keyTyped(KeyEvent e)
			{
			}

			@Override
			public void keyPressed(KeyEvent e)
			{
				Keybind bound = key.get();
				if (bound != null && bound.matches(e))
				{
					action.run();
				}
			}

			@Override
			public void keyReleased(KeyEvent e)
			{
			}
		};
	}
}
