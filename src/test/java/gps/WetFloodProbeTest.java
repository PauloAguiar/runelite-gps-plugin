package gps;

import org.junit.Test;

public class WetFloodProbeTest
{
	@Test
	public void probe()
	{
		int pin = WorldPointUtil.packWorldPoint(2699, 3103, 0);
		System.out.println("sailable: " + SailingSea.isSailable(pin));
		int[] d = SailingSea.seaDistances(pin);
		java.util.List<int[]> moorings = new java.util.ArrayList<>();
		try (java.util.Scanner s = new java.util.Scanner(
			SailingSea.class.getResourceAsStream("/sailing-moorings.tsv"), "UTF-8"))
		{
			while (s.hasNextLine())
			{
				String[] f = s.nextLine().split("	");
				if (f.length >= 5 && !f[0].startsWith("#") && !"type".equals(f[0]))
				{
					moorings.add(new int[]{Integer.parseInt(f[3]), Integer.parseInt(f[4])});
				}
			}
		}
		for (int i = 0; i < d.length; i++)
		{
			if (d[i] != Integer.MAX_VALUE)
			{
				System.out.println("settled: water " + moorings.get(i)[0] + ","
					+ moorings.get(i)[1] + " dist " + d[i] / 100 + " tiles");
			}
		}
	}
}
