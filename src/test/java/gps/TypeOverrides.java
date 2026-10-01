package gps;

import gps.transport.TransportType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Narrows the transport world for a test. The toggle-less transport types (shortcuts, boats,
 * fairy rings, spells, ...) are always on in production; a test that exercises one of them in
 * isolation switches the others off through their override keys, the way a mocked config used
 * to leave every unstubbed toggle false. Static state: call {@link #clear()} in an @After.
 */
public final class TypeOverrides
{
	private TypeOverrides()
	{
	}

	/** Only these toggle-less types stay on; the rest are overridden off. Types with a config toggle are untouched. */
	public static void only(TransportType... enabled)
	{
		Set<String> keep = new HashSet<>();
		for (TransportType type : enabled)
		{
			keep.add(type.getEnabledKey());
		}
		Map<String, Object> off = new HashMap<>();
		for (TransportType type : TransportType.values())
		{
			String key = type.getEnabledKey();
			if (key != null && !type.hasEnabledGetter() && !keep.contains(key))
			{
				off.put(key, false);
			}
		}
		ConfigOverrides.apply(off);
	}

	/** Switches the named toggle-less types off, leaving the rest on. */
	public static void off(TransportType... disabled)
	{
		Map<String, Object> off = new HashMap<>();
		for (TransportType type : disabled)
		{
			off.put(type.getEnabledKey(), false);
		}
		ConfigOverrides.apply(off);
	}

	public static void clear()
	{
		ConfigOverrides.clear();
	}
}
