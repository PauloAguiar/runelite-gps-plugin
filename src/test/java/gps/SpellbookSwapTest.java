package gps;

import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathStep;
import gps.pathfinder.Pathfinder;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.SplitFlagMap;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.Transport;
import java.lang.reflect.Proxy;
import java.util.List;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #14: on the Lunar spellbook with the runes for Spellbook Swap, every teleport of the
 * other books is a method, at the swap's price (its runes, 96 Magic, Dream Mentor, five more
 * ticks). The variants are generated rows of teleportation_spells.tsv (derive_spellbook_swap.py)
 * under the same display name, so "Varrock Teleport" is one catalog method with two ways to cast.
 */
public class SpellbookSwapTest
{
	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int VARROCK = WorldPointUtil.packWorldPoint(3213, 3424, 0);
	private static final int STANDARD = 0;
	private static final int LUNAR = 2;

	private static final Item[] VARROCK_RUNES = {new Item(ItemID.AIRRUNE, 3), new Item(ItemID.FIRERUNE, 1), new Item(ItemID.LAWRUNE, 2)};
	private static final Item[] WITH_SWAP = {new Item(ItemID.AIRRUNE, 3), new Item(ItemID.FIRERUNE, 1), new Item(ItemID.LAWRUNE, 2),
		new Item(ItemID.ASTRALRUNE, 3), new Item(ItemID.COSMICRUNE, 2)};

	@Test
	public void onLunarsTheSwapMakesVarrockTeleportAMethod()
	{
		Transport cast = firstTeleport(LUNAR, WITH_SWAP);
		assertNotNull("the teleport is used", cast);
		assertEquals("Varrock Teleport", cast.getDisplayInfo());
		assertEquals("the swap variant, not the standard row", "Spellbook Swap first: 3 astral, 2 cosmic, 1 law", cast.getNote());
		assertEquals(9, cast.getDuration());
	}

	@Test
	public void withoutTheSwapsRunesOrOffLunarsTheOtherRowApplies()
	{
		// On Lunars without astral and cosmic runes the spell is not a method (a free minigame teleport may still be).
		Transport other = firstTeleport(LUNAR, VARROCK_RUNES);
		assertTrue(other == null ? "walked" : other.getDisplayInfo(), other == null || !"Varrock Teleport".equals(other.getDisplayInfo()));
		Transport plain = firstTeleport(STANDARD, VARROCK_RUNES);
		assertNotNull(plain);
		assertNull("on the standard book the spell is cast as itself", plain.getNote());
		assertEquals(4, plain.getDuration());
	}

	/** The transport of the route's first teleport edge from Lumbridge to Varrock, or null when it walks. */
	private static Transport firstTeleport(int spellbook, Item[] carried)
	{
		final Thread clientThread = Thread.currentThread();
		ItemContainer inventory = Mockito.mock(ItemContainer.class);
		Mockito.when(inventory.getItems()).thenReturn(carried);
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
						return (int) args[0] == VarbitID.SPELLBOOK ? spellbook : 0;
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
		// Varbits apply: the spellbook gates every spell row; quests all done, so Dream Mentor is.
		PathfinderConfig owned = new TestPathfinderConfig(client, config, QuestState.FINISHED, false, true);
		owned.refresh();
		CollisionMap map = new CollisionMap(SplitFlagMap.fromResources());
		Pathfinder pathfinder = new Pathfinder(owned, LUMBRIDGE, Destinations.walkableTargets(map, VARROCK));
		pathfinder.run();
		List<PathStep> path = pathfinder.getPath();
		for (int i = 1; i < path.size(); i++)
		{
			for (Transport transport : EdgeTransports.forEdge(owned, path.get(i - 1), path.get(i)))
			{
				if (transport.getType() != null && transport.getType().isTeleport())
				{
					return transport;
				}
			}
		}
		return null;
	}
}
