package gps;

import java.util.Scanner;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/** Every shipped sailing leg must produce a drawable sea track (else the overlay dashes). */
public class SeaTrackCoverageTest
{
	@Test
	public void everyPortPairHasATrack()
	{
		// Full sweep incl. full-grid legs takes minutes: audit tool, not a suite tax.
		org.junit.Assume.assumeTrue(Boolean.getBoolean("gps.trackCoverage"));
		int total = 0;
		java.util.List<String> missing = new java.util.ArrayList<>();
		try (Scanner scanner = new Scanner(
			SailingSea.class.getResourceAsStream("/transports/sailing.tsv"), "UTF-8"))
		{
			while (scanner.hasNextLine())
			{
				String line = scanner.nextLine();
				if (line.startsWith("#") || line.isBlank())
				{
					continue;
				}
				String[] f = line.split("	");
				String[] o = f[0].split(" ");
				String[] d = f[1].split(" ");
				int origin = WorldPointUtil.packWorldPoint(
					Integer.parseInt(o[0]), Integer.parseInt(o[1]), 0);
				int destination = WorldPointUtil.packWorldPoint(
					Integer.parseInt(d[0]), Integer.parseInt(d[1]), 0);
				total++;
				if (SailingSea.seaPathBlocking(origin, destination) == null)
				{
					if (missing.size() < 12)
					{
						missing.add(f[0] + " -> " + f[1] + " (" + f[7] + ")");
					}
				}
			}
		}
		assertTrue("checked rows", total > 2000);
		assertTrue(missing.size() + " of " + total + " sailing legs have NO drawable track:\n  "
			+ String.join("\n  ", missing), missing.isEmpty());
	}
}
