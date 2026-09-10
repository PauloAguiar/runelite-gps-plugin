package gps;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.WorldView;

/**
 * Passive sea-obstacle learning (plan step L19, out of the plugin class): every ten ticks,
 * harvest scene tiles that the shipped ocean calls sailable but live collision blocks (moored
 * vessels, harbour clutter). The offline map plans; the client corrects itself as scenes
 * reveal the truth.
 */
final class SeaObstacleLearner
{
	static final int SCAN_PERIOD_TICKS = 10;
	// Scene border padding reads 0xFFFFFF (everything blocked); the first field harvest showed
	// the border bands dwarfing the actual galleon, so a 3-tile edge margin is skipped too.
	private static final int EDGE_MARGIN = 3;
	// The player's own hull is a WorldEntity projecting live-blocked collision onto sailable
	// water: without this exclusion every scan learned the boat's footprint as a permanent
	// obstacle, a breadcrumb trail along everywhere the player sailed (field capture 232906:
	// the direct channel home was sealed by the player's own wake, forcing a disembark and
	// re-embark detour through Cairn Isle).
	private static final int OWN_BOAT_RADIUS = 10;

	private final Client client;
	private final IntSupplier playerLocation;
	private int cooldown;

	SeaObstacleLearner(Client client, IntSupplier playerLocation)
	{
		this.client = client;
		this.playerLocation = playerLocation;
	}

	/** Client thread, once per game tick. */
	void onTick()
	{
		if (--cooldown <= 0)
		{
			cooldown = SCAN_PERIOD_TICKS;
			scan();
		}
	}

	/** A real obstacle carries BLOCK_MOVEMENT_OBJECT; the border padding and margin are skipped. */
	private void scan()
	{
		WorldView view = client.getTopLevelWorldView();
		if (view == null || view.getCollisionMaps() == null || view.getPlane() != 0)
		{
			return;
		}
		CollisionData collision = view.getCollisionMaps()[0];
		if (collision == null)
		{
			return;
		}
		int[][] flags = collision.getFlags();
		int baseX = view.getBaseX();
		int baseY = view.getBaseY();
		int playerAt = playerLocation.getAsInt();
		List<Integer> found = null;
		for (int sx = EDGE_MARGIN; sx < flags.length - EDGE_MARGIN; sx++)
		{
			for (int sy = EDGE_MARGIN; sy < flags[sx].length - EDGE_MARGIN; sy++)
			{
				int tileFlags = flags[sx][sy];
				if (tileFlags == 0xFFFFFF || (tileFlags & CollisionDataFlag.BLOCK_MOVEMENT_OBJECT) == 0)
				{
					continue;
				}
				int x = baseX + sx;
				int y = baseY + sy;
				if (playerAt != WorldPointUtil.UNDEFINED
					&& Math.max(Math.abs(WorldPointUtil.unpackWorldX(playerAt) - x),
						Math.abs(WorldPointUtil.unpackWorldY(playerAt) - y)) <= OWN_BOAT_RADIUS)
				{
					continue;
				}
				int packed = WorldPointUtil.packWorldPoint(x, y, 0);
				if (SailingSea.isSailable(packed) && !SailingSea.obstacleAt(x, y))
				{
					if (found == null)
					{
						found = new ArrayList<>();
					}
					found.add(packed);
				}
			}
		}
		if (found != null)
		{
			SailingSea.learnObstacles(found);
		}
	}
}
