package gps;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The search index's bank pins (destinations.tsv, category bank) and the engine's bank tiles
 * (destinations/game_features/bank.tsv) describe the same banks, so a surface bank pin must stand
 * near an engine tile. The cache dump behind the index once accepted any object named "Bank
 * table", which put a "West Ardougne Bank" in the Mourner HQ (a decorative table, object 591,
 * field report 2026-10-03): "nearest bank" then walked players to a table in a city with no bank.
 */
public class BankDestinationSanityTest
{
	/** Banks the engine deliberately leaves out but the index keeps: the Group Ironman bank. */
	private static final Set<String> ENGINE_EXCLUDES = Set.of("Lumbridge Swamp Bank");
	private static final int NEAR = 12;

	@Test
	public void everySurfaceBankPinStandsAtAnEngineBank() throws IOException
	{
		List<int[]> engine = new ArrayList<>();
		for (String line : lines("/destinations/game_features/bank.tsv"))
		{
			String[] c = line.split("\t")[0].trim().split(" ");
			engine.add(new int[]{Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])});
		}
		assertTrue("the engine's bank list is gone", engine.size() > 200);

		List<String> strays = new ArrayList<>();
		for (String line : lines("/destinations.tsv"))
		{
			String[] f = line.split("\t");
			if (!"bank".equals(f[0]) || f[1].startsWith("category"))
			{
				continue;
			}
			int x = Integer.parseInt(f[2]);
			int y = Integer.parseInt(f[3]);
			int plane = Integer.parseInt(f[4]);
			// Only the surface: upper floors and the instance and underground bands have their own
			// rules (template scenery is remapped, dungeon banks sit far from any surface tile).
			if (plane != 0 || y >= 4000 || ENGINE_EXCLUDES.contains(f[1]))
			{
				continue;
			}
			int nearest = Integer.MAX_VALUE;
			for (int[] e : engine)
			{
				if (e[2] == 0)
				{
					nearest = Math.min(nearest, Math.max(Math.abs(e[0] - x), Math.abs(e[1] - y)));
				}
			}
			if (nearest > NEAR)
			{
				strays.add(f[1] + " at " + x + "," + y + " (" + nearest + " tiles from the nearest engine bank)");
			}
		}
		assertTrue("bank pins with no engine bank nearby, a decorative Bank table in the cache dump: " + strays,
			strays.isEmpty());
	}

	private static List<String> lines(String resource) throws IOException
	{
		List<String> rows = new ArrayList<>();
		try (InputStream in = BankDestinationSanityTest.class.getResourceAsStream(resource))
		{
			assertNotNull(resource + " is missing", in);
			Scanner scanner = new Scanner(in, "UTF-8");
			while (scanner.hasNextLine())
			{
				String line = scanner.nextLine();
				if (!line.isEmpty() && !line.startsWith("#") && Character.isLetterOrDigit(line.charAt(0)))
				{
					rows.add(line);
				}
			}
		}
		return rows;
	}
}
