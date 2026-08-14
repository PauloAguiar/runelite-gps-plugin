package gps;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * The game's boat-location registry: SAILING_BOAT_N_PORT varbit values joined to this
 * plugin's mooring tiles (shipped sailing-ports.tsv, ids adapted from the 'Dude, Where's
 * My Boat?' hub plugin). A port may span several moorings; the first is the primary
 * (teleport landing) berth. Special values: 254 = capsized, 255 = bottled — no mooring.
 */
public final class SailingPorts
{
	public static final int PORT_CAPSIZED = 254;
	public static final int PORT_BOTTLED = 255;

	private static volatile SailingPorts instance;

	private final Map<Integer, String> names = new HashMap<>();
	/** Packed mooring land tiles per port id; first entry is the primary berth. */
	private final Map<Integer, List<Integer>> moorings = new HashMap<>();

	private SailingPorts()
	{
		try (InputStream in = SailingPorts.class.getResourceAsStream("/sailing-ports.tsv");
			Scanner scanner = new Scanner(in, "UTF-8"))
		{
			while (scanner.hasNextLine())
			{
				String[] fields = scanner.nextLine().split("\t");
				if (fields.length < 4 || fields[0].startsWith("#") || "portId".equals(fields[0]))
				{
					continue;
				}
				int id = Integer.parseInt(fields[0]);
				names.putIfAbsent(id, fields[1].trim());
				moorings.computeIfAbsent(id, k -> new ArrayList<>()).add(WorldPointUtil.packWorldPoint(
					Integer.parseInt(fields[2]), Integer.parseInt(fields[3]), 0));
			}
		}
		catch (java.io.IOException | RuntimeException e)
		{
			// A missing registry only disables boat-location awareness, never routing.
		}
	}

	private static SailingPorts get()
	{
		SailingPorts loaded = instance;
		if (loaded == null)
		{
			synchronized (SailingPorts.class)
			{
				loaded = instance;
				if (loaded == null)
				{
					instance = loaded = new SailingPorts();
				}
			}
		}
		return loaded;
	}

	/** Packed mooring land tiles of the port, empty for capsized/bottled/unknown ids. */
	public static List<Integer> portMoorings(int portId)
	{
		return get().moorings.getOrDefault(portId, List.of());
	}

	/** Display name for a port id; capsized/bottled and unknown ids get honest fallbacks. */
	public static String portName(int portId)
	{
		if (portId == PORT_CAPSIZED)
		{
			return "capsized";
		}
		if (portId == PORT_BOTTLED)
		{
			return "in a bottle";
		}
		String name = get().names.get(portId);
		return name != null ? name : "port " + portId;
	}
}
