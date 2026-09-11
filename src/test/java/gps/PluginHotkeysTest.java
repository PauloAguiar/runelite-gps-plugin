package gps;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JPanel;
import net.runelite.client.config.Keybind;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Plan step L32: the input listeners, out of the plugin class. Each hotkey fires on its own
 * binding only (modifiers included), and the arrival click is consumed only when it dismissed
 * the panel, so an ordinary click still reaches the game.
 */
public class PluginHotkeysTest
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

	private static MouseEvent click()
	{
		return new MouseEvent(new JPanel(), MouseEvent.MOUSE_PRESSED, 0L, 0, 5, 5, 1, false);
	}

	@Test
	public void eachHotkeyFiresOnItsOwnBindingOnly()
	{
		AtomicInteger cleared = new AtomicInteger();
		AtomicInteger focused = new AtomicInteger();
		PluginHotkeys hotkeys = new PluginHotkeys(
			() -> new Keybind(KeyEvent.VK_F, 0), cleared::incrementAndGet,
			() -> new Keybind(KeyEvent.VK_G, KeyEvent.CTRL_DOWN_MASK), focused::incrementAndGet,
			point -> false);

		hotkeys.clearPath().keyPressed(press(KeyEvent.VK_F, 0));
		hotkeys.clearPath().keyPressed(press(KeyEvent.VK_G, 0));
		hotkeys.focusSearch().keyPressed(press(KeyEvent.VK_G, 0));
		hotkeys.focusSearch().keyPressed(press(KeyEvent.VK_G, KeyEvent.CTRL_DOWN_MASK));
		assertEquals(1, cleared.get());
		assertEquals("only the modified press matches", 1, focused.get());
	}

	@Test
	public void theArrivalClickIsConsumedOnlyWhenItDismissedThePanel()
	{
		PluginHotkeys dismissing = new PluginHotkeys(() -> Keybind.NOT_SET, () -> { }, () -> Keybind.NOT_SET, () -> { },
			point -> point.x == 5);
		MouseEvent hit = click();
		dismissing.dismissArrival().mousePressed(hit);
		assertTrue(hit.isConsumed());

		PluginHotkeys idle = new PluginHotkeys(() -> Keybind.NOT_SET, () -> { }, () -> Keybind.NOT_SET, () -> { },
			point -> false);
		MouseEvent miss = click();
		idle.dismissArrival().mousePressed(miss);
		assertFalse("an ordinary click still reaches the game", miss.isConsumed());
	}
}
