package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.SplitFlagMap;
import org.junit.Assume;
import org.junit.Test;

/**
 * Capture 20260830-172137: Quest Helper targets 3252-3253 x 3179-3180 (river Lum mouth, west of
 * Al Kharid) never resolve - every search exhausts with closest approach 3258,3176. Prints the
 * shipped collision around the box to name the disconnection. -Dgps.lumMouth=true.
 */
public class LumMouthCollisionProbeTest
{
	@Test
	public void probe()
	{
		Assume.assumeTrue(Boolean.getBoolean("gps.lumMouth"));
		CollisionMap map = new CollisionMap(SplitFlagMap.fromResources());
		StringBuilder sb = new StringBuilder("\n     ");
		for (int x = 3240; x <= 3266; x++)
		{
			sb.append(x % 10);
		}
		for (int y = 3195; y >= 3168; y--)
		{
			sb.append('\n').append(y).append(' ');
			for (int x = 3240; x <= 3266; x++)
			{
				boolean target = (x == 3252 || x == 3253) && (y == 3179 || y == 3180);
				boolean closest = x == 3258 && y == 3176;
				char c = map.isBlocked(x, y, 0) ? '#' : '.';
				if (target)
				{
					c = map.isBlocked(x, y, 0) ? 'X' : 'T';
				}
				else if (closest)
				{
					c = 'C';
				}
				sb.append(c);
			}
		}
		System.out.println("LUMMOUTH" + sb);
		// Directional walls around the box tell apart fences from full blocks.
		for (int y = 3182; y >= 3177; y--)
		{
			for (int x = 3250; x <= 3256; x++)
			{
				System.out.println("LUMMOUTH @" + x + "," + y
					+ " blocked=" + map.isBlocked(x, y, 0)
					+ " n=" + map.n(x, y, 0) + " s=" + map.s(x, y, 0)
					+ " e=" + map.e(x, y, 0) + " w=" + map.w(x, y, 0));
			}
		}
	}
}
