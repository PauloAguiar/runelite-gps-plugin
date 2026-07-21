package gps.dev;

import gps.WorldPointUtil;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Loader for the transport files' Meta column (dev audit only — the plugin runtime ignores it).
 * Compact vocabulary, absence = trusted: {@code ~field} means the value is machine-estimated
 * (cache geometry, family-calibrated ticks) and {@code ?field} means it awaits confirmation
 * (e.g. an unverified varbit gate). The audit shows these in-game so field sessions can confirm
 * them; once confirmed, the tag is removed from the row and the entry disappears here.
 */
final class MetaEdges
{
	/** One tagged row: where to go, what to do, and which tags are outstanding. */
	static final class Entry
	{
		final int origin;      // UNDEFINED for origin-less teleports (confirmable only by use)
		final int destination; // UNDEFINED when the row has no destination column value
		final String menu;
		final String duration;
		final String tags;
		final String file;

		Entry(int origin, int destination, String menu, String duration, String tags, String file)
		{
			this.origin = origin;
			this.destination = destination;
			this.menu = menu;
			this.duration = duration;
			this.tags = tags;
			this.file = file;
		}

		String describe()
		{
			String where = origin != WorldPointUtil.UNDEFINED
				? WorldPointUtil.unpackWorldX(origin) + "," + WorldPointUtil.unpackWorldY(origin)
				+ "," + WorldPointUtil.unpackWorldPlane(origin)
				: "(no origin)";
			return (menu.isEmpty() ? file : menu) + " @" + where + " [" + tags + "]";
		}
	}

	private MetaEdges()
	{
	}

	private static final String[] FILES = {
		"transports.tsv", "agility_shortcuts.tsv", "boats.tsv", "canoes.tsv", "charter_ships.tsv",
		"fairy_rings.tsv", "gnome_gliders.tsv", "hot_air_balloons.tsv", "magic_carpets.tsv",
		"magic_mushtrees.tsv", "minecarts.tsv", "mountain_guides.tsv", "quetzal_whistle.tsv",
		"quetzals.tsv", "ships.tsv", "spirit_trees.tsv", "teleportation_boxes.tsv",
		"teleportation_items.tsv", "teleportation_levers.tsv", "teleportation_minigames.tsv",
		"teleportation_portals.tsv", "teleportation_portals_poh.tsv", "teleportation_spells.tsv",
		"wilderness_obelisks.tsv",
	};

	/** Every row across the transport files whose Meta column is non-empty. */
	static List<Entry> load()
	{
		List<Entry> entries = new ArrayList<>();
		for (String file : FILES)
		{
			try (InputStream in = MetaEdges.class.getResourceAsStream("/transports/" + file))
			{
				if (in == null)
				{
					continue;
				}
				try (Scanner scanner = new Scanner(in, "UTF-8"))
				{
					String[] headers = null;
					int metaIndex = -1;
					while (scanner.hasNextLine())
					{
						String line = scanner.nextLine();
						if (headers == null)
						{
							headers = (line.startsWith("# ") ? line.substring(2) : line).split("\t");
							for (int i = 0; i < headers.length; i++)
							{
								if ("Meta".equals(headers[i].trim()))
								{
									metaIndex = i;
								}
							}
							if (metaIndex < 0)
							{
								break; // no Meta column in this file
							}
							continue;
						}
						if (line.startsWith("#") || line.isBlank())
						{
							continue;
						}
						String[] fields = line.split("\t", -1);
						if (metaIndex >= fields.length || fields[metaIndex].trim().isEmpty())
						{
							continue;
						}
						entries.add(new Entry(
							parseTile(column(headers, fields, "Origin")),
							parseTile(column(headers, fields, "Destination")),
							column(headers, fields, "menuOption menuTarget objectID").trim(),
							column(headers, fields, "Duration").trim(),
							fields[metaIndex].trim(),
							file));
					}
				}
			}
			catch (Exception e)
			{
				// dev tooling: skip unreadable files rather than break the client
			}
		}
		return entries;
	}

	private static String column(String[] headers, String[] fields, String name)
	{
		for (int i = 0; i < headers.length && i < fields.length; i++)
		{
			if (name.equals(headers[i].trim()))
			{
				return fields[i];
			}
		}
		return "";
	}

	static int parseTile(String text)
	{
		String[] parts = text.trim().split(" ");
		if (parts.length < 3)
		{
			return WorldPointUtil.UNDEFINED;
		}
		try
		{
			return WorldPointUtil.packWorldPoint(Integer.parseInt(parts[0]),
				Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
		}
		catch (NumberFormatException e)
		{
			return WorldPointUtil.UNDEFINED;
		}
	}
}
