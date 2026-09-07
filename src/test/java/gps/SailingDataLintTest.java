package gps;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Review 2026-09-06: two Wyrmscraig gangplank rows shared one landing tile, so the land-keyed
 * port matrix overwrote a mooring index (its rows were lost), carried self-pairs and duplicate
 * keys, and the generated sailing.tsv held a self-loop row. The loaders now tolerate all of it;
 * this lint keeps the shipped data clean so the tolerance is never load-bearing.
 */
public class SailingDataLintTest
{
	private static List<String[]> rows(String resource, String headerFirstCell) throws IOException
	{
		List<String[]> out = new ArrayList<>();
		try (InputStream in = SailingDataLintTest.class.getResourceAsStream(resource);
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = reader.readLine()) != null)
			{
				if (line.isEmpty() || line.startsWith("#") || line.startsWith(headerFirstCell))
				{
					continue;
				}
				out.add(line.split("\t"));
			}
		}
		return out;
	}

	@Test
	public void mooringLandTilesAreUnique() throws IOException
	{
		Set<String> seen = new HashSet<>();
		List<String> duplicates = new ArrayList<>();
		for (String[] row : rows("/sailing-moorings.tsv", "type"))
		{
			if (!seen.add(row[1] + "," + row[2]))
			{
				duplicates.add(row[6] + " @" + row[1] + "," + row[2]);
			}
		}
		assertTrue("moorings sharing a land tile collapse in the port matrix: " + duplicates, duplicates.isEmpty());
	}

	@Test
	public void portMatrixHasNoSelfPairsOrDuplicateKeys() throws IOException
	{
		Set<String> seen = new HashSet<>();
		int selfPairs = 0;
		int duplicates = 0;
		for (String[] row : rows("/sailing-sea-matrix.tsv", "fromLandX"))
		{
			String key = String.join(",", row[0], row[1], row[2], row[3]);
			if (row[0].equals(row[2]) && row[1].equals(row[3]))
			{
				selfPairs++;
			}
			else if (!seen.add(key))
			{
				duplicates++;
			}
		}
		assertTrue("self-pairs: " + selfPairs + ", duplicate keys: " + duplicates, selfPairs == 0 && duplicates == 0);
	}

	@Test
	public void sailingRowsNeverLoop() throws IOException
	{
		int loops = 0;
		for (String[] row : rows("/transports/sailing.tsv", "# Origin"))
		{
			if (row.length > 1 && row[0].trim().equals(row[1].trim()))
			{
				loops++;
			}
		}
		assertTrue("self-loop sailing rows: " + loops, loops == 0);
	}
}
