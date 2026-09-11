package gps;

import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.runelite.client.config.Keybind;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;

/**
 * The plugin's input listeners (plan step L32, out of the plugin class): the clear-path hotkey,
 * the focus-search hotkey (opens the GPS side panel and focuses its destination box, so a place
 * can be searched without opening the panel by hand), and the click that dismisses the overlay's
 * lingering "Arrived!" panel. The bindings are read on every press, so a config change applies
 * at once.
 */
final class PluginHotkeys
{
	private final KeyListener clearPath;
	private final KeyListener focusSearch;
	private final MouseAdapter dismissArrival;

	PluginHotkeys(Supplier<Keybind> clearPathKey, Runnable onClearPath,
		Supplier<Keybind> focusSearchKey, Runnable onFocusSearch, Predicate<Point> dismissArrivalAt)
	{
		this.clearPath = hotkey(clearPathKey, onClearPath);
		this.focusSearch = hotkey(focusSearchKey, onFocusSearch);
		this.dismissArrival = new MouseAdapter()
		{
			@Override
			public MouseEvent mousePressed(MouseEvent event)
			{
				if (dismissArrivalAt.test(event.getPoint()))
				{
					event.consume();
				}
				return event;
			}
		};
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
				if (key.get().matches(e))
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

	void register(KeyManager keys, MouseManager mouse)
	{
		keys.registerKeyListener(clearPath);
		keys.registerKeyListener(focusSearch);
		mouse.registerMouseListener(dismissArrival);
	}

	void unregister(KeyManager keys, MouseManager mouse)
	{
		keys.unregisterKeyListener(clearPath);
		keys.unregisterKeyListener(focusSearch);
		mouse.unregisterMouseListener(dismissArrival);
	}

	KeyListener clearPath()
	{
		return clearPath;
	}

	KeyListener focusSearch()
	{
		return focusSearch;
	}

	MouseAdapter dismissArrival()
	{
		return dismissArrival;
	}
}
