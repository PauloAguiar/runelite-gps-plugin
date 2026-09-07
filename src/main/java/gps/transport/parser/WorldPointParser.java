package gps.transport.parser;

import gps.WorldPointUtil;
import gps.transport.Transport;

/**
 * Parses world point coordinates from TSV field values.
 *
 * <p>
 * Format: {@code x y plane} (space-separated)
 * </p>
 * <p>
 * Empty values are treated as location permutations (for fairy rings, etc.)
 * </p>
 */
public class WorldPointParser implements FieldParser<Integer>
{
	private static final String DELIM_SPACE = " ";

	@Override
	public Integer parse(String value)
	{
		if (value == null || value.isEmpty())
		{
			return Transport.LOCATION_PERMUTATION;
		}
		String trimmed = value.trim();
		if (trimmed.isEmpty())
		{
			return Transport.LOCATION_PERMUTATION;
		}
		String[] parts = trimmed.split(DELIM_SPACE);
		if (parts.length != 3)
		{
			// A padded or malformed cell used to become a silent location permutation: a row
			// with a leading space lost its destination and dropped out of the graph (the
			// Mage of Zamorak Abyss teleport). Record it so the data lint fails instead.
			ParseErrors.record("coordinate", value);
			return Transport.LOCATION_PERMUTATION;
		}
		return WorldPointUtil.packWorldPoint(
			Integer.parseInt(parts[0]),
			Integer.parseInt(parts[1]),
			Integer.parseInt(parts[2]));
	}
}
