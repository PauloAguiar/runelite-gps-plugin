package gps;

import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JPanel;
import net.runelite.client.config.Keybind;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Keybinds for the panel's "nearest bank" and "nearest bank and back" quick buttons (a player
 * suggestion), unset by default; a bound key runs the same action as its button.
 */
public class NearestBankHotkeysTest
{
	/**
	 * A key press as the toolkit delivers it: Keybind matches on the EXTENDED key code, which the
	 * native layer fills in and a hand-built event leaves at zero, so it is mirrored from the code.
	 */
	private static KeyEvent press(int keyCode, int modifiers)
	{
		return new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 0L, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED)
		{
			@Override
			public int getExtendedKeyCode()
			{
				return getKeyCode();
			}
		};
	}

	@Test
	public void bothDefaultToNotSet()
	{
		TestShortestPathConfig config = new TestShortestPathConfig();
		assertEquals(Keybind.NOT_SET, config.nearestBankHotkey());
		assertEquals(Keybind.NOT_SET, config.nearestBankAndBackHotkey());
	}

	@Test
	public void eachHotkeyFiresOnItsOwnBindingOnly()
	{
		AtomicInteger oneWay = new AtomicInteger();
		AtomicInteger andBack = new AtomicInteger();
		NearestBankHotkeys hotkeys = new NearestBankHotkeys(
			() -> new Keybind(KeyEvent.VK_B, KeyEvent.CTRL_DOWN_MASK), oneWay::incrementAndGet,
			() -> new Keybind(KeyEvent.VK_B, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK), andBack::incrementAndGet);

		hotkeys.bank().keyPressed(press(KeyEvent.VK_B, KeyEvent.CTRL_DOWN_MASK));
		hotkeys.bank().keyPressed(press(KeyEvent.VK_B, 0));
		hotkeys.bankAndBack().keyPressed(press(KeyEvent.VK_B, KeyEvent.CTRL_DOWN_MASK));
		hotkeys.bankAndBack().keyPressed(press(KeyEvent.VK_B, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK));
		assertEquals(1, oneWay.get());
		assertEquals("only the shifted press matches", 1, andBack.get());
	}

	@Test
	public void unboundKeysNeverFire()
	{
		AtomicInteger fired = new AtomicInteger();
		TestShortestPathConfig config = new TestShortestPathConfig();
		NearestBankHotkeys hotkeys = new NearestBankHotkeys(
			config::nearestBankHotkey, fired::incrementAndGet, config::nearestBankAndBackHotkey, fired::incrementAndGet);

		for (int key : new int[]{KeyEvent.VK_B, KeyEvent.VK_ENTER, KeyEvent.VK_UNDEFINED})
		{
			hotkeys.bank().keyPressed(press(key, 0));
			hotkeys.bankAndBack().keyPressed(press(key, KeyEvent.CTRL_DOWN_MASK));
		}
		assertEquals(0, fired.get());
	}
}
