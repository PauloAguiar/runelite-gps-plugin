package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathStep;
import gps.pathfinder.Pathfinder;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.SplitFlagMap;
import gps.pathfinder.TestPathfinderConfig;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.QuestState;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The Camulet's "Enakhra's Temple" teleport lands on the temple's top floor, 3106,9315 plane 2
 * (capture gps-capture-20261003-173436: the player stood there right after using it). The row
 * had the landing on plane 0, so a player who had just teleported was told to teleport again,
 * since the engine took the ground-floor tile as a cheaper start than the ladders down.
 */
public class EnakhraTempleTest
{
	private static final int LANDING = WorldPointUtil.packWorldPoint(3106, 9315, 2);
	private static final int QUARRY = WorldPointUtil.packWorldPoint(3309, 2962, 0);
	private static final int FAR = WorldPointUtil.packWorldPoint(3222, 3218, 0);

	@Test
	public void aPlayerWhoJustLandedIsNotToldToTeleportAgain()
	{
		List<PathStep> path = pathWithCamulet(LANDING, QUARRY, 4);
		assertNotNull("the quarry is reachable from the landing", path);
		for (int i = 1; i < path.size(); i++)
		{
			int from = path.get(i - 1).getPackedPosition();
			int to = path.get(i).getPackedPosition();
			boolean jump = WorldPointUtil.unpackWorldPlane(from) != WorldPointUtil.unpackWorldPlane(to)
				|| WorldPointUtil.distanceBetween2D(from, to) > 1;
			assertFalse("the route must leave the landing tile on foot, not by the teleport that brought it there",
				jump && from == LANDING);
		}
	}

	/**
	 * Varbit 1574 holds the Camulet's charges left, 7 once Lazim's unlimited upgrade is bought (wiki
	 * RuneScape:Varbit/1574; the reporting player's captures read 1 right after a teleport and 0
	 * later). An empty Camulet was still offered: both rows now need a charge.
	 */
	@Test
	public void theCamuletNeedsAChargeLeft()
	{
		assertTrue("four charges: the teleport is the way in", teleportsToTheTemple(pathWithCamulet(FAR, LANDING, 4)));
		assertTrue("one charge is enough", teleportsToTheTemple(pathWithCamulet(FAR, LANDING, 1)));
		assertTrue("7 is Lazim's unlimited upgrade", teleportsToTheTemple(pathWithCamulet(FAR, LANDING, 7)));
		assertFalse("empty: the route must not count on it", teleportsToTheTemple(pathWithCamulet(FAR, LANDING, 0)));
	}

	private static boolean teleportsToTheTemple(List<PathStep> path)
	{
		if (path == null)
		{
			return false;
		}
		for (int i = 1; i < path.size(); i++)
		{
			if (path.get(i).getPackedPosition() == LANDING
				&& WorldPointUtil.distanceBetween2D(path.get(i - 1).getPackedPosition(), LANDING) > 50)
			{
				return true;
			}
		}
		return false;
	}

	/** The owned-mode path with a Camulet holding {@code charges} in the inventory, or null when the target is unreachable. */
	private static List<PathStep> pathWithCamulet(int start, int target, int charges)
	{
		final Thread clientThread = Thread.currentThread();
		ItemContainer inventory = Mockito.mock(ItemContainer.class);
		Mockito.when(inventory.getItems()).thenReturn(new Item[]{new Item(ItemID.CAMULET, 1)});
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getGameState":
						return GameState.LOGGED_IN;
					case "getClientThread":
						return clientThread;
					case "getBoostedSkillLevel":
						return 99;
					case "getItemContainer":
						return (int) args[0] == InventoryID.INV ? inventory : null;
					case "getVarbitValue":
						return (int) args[0] == VarbitID.ENAKH_CAMULET_CHARGE ? charges : 0;
					default:
						return HybridPageFillTest.defaultValue(method.getReturnType());
				}
			});
		ShortestPathConfig config = Mockito.mock(ShortestPathConfig.class, invocation ->
		{
			String name = invocation.getMethod().getName();
			Class<?> type = invocation.getMethod().getReturnType();
			if (type == boolean.class)
			{
				return !"avoidWilderness".equals(name);
			}
			if (type == int.class)
			{
				return "calculationCutoff".equals(name) ? 120 : 0;
			}
			if (type == TeleportationItem.class)
			{
				return TeleportationItem.INVENTORY;
			}
			return HybridPageFillTest.defaultValue(type);
		});
		// Varbits apply (all zero here): the Camulet's quarry option is locked until Enakhra's Lament's
		// post-quest unlock (varbit 4485), as it was for the reporting player.
		PathfinderConfig owned = new TestPathfinderConfig(client, config, QuestState.FINISHED, false, true);
		owned.refresh();
		CollisionMap map = new CollisionMap(SplitFlagMap.fromResources());
		Set<Integer> ring = Destinations.walkableTargets(map, target);
		Pathfinder pathfinder = new Pathfinder(owned, start, ring);
		pathfinder.run();
		return pathfinder.getResult().isReached() ? pathfinder.getPath() : null;
	}
}
