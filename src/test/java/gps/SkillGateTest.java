package gps;

import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import java.lang.reflect.Proxy;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The planner reads boosted skill levels when the config refreshes and nothing refreshed it on
 * a stat change, so a route planned at Agility 80 kept looping around the Fremennik Slayer
 * Dungeon's strange floors (81 Agility) after a boost (capture 2026-10-20). A stat change now
 * asks the config whether the new level crosses a level some transport requires; only then is
 * a refresh and a recompute worth it.
 */
public class SkillGateTest
{
	@Test
	public void aBoostAcrossARequiredLevelIsAGateCrossing()
	{
		PathfinderConfig config = config(80);
		assertTrue("80 to 82 crosses the strange floors' 81", config.levelGateCrossed(Skill.AGILITY, 82));
		assertTrue("exactly reaching 81 counts", config.levelGateCrossed(Skill.AGILITY, 81));
		assertFalse("no change", config.levelGateCrossed(Skill.AGILITY, 80));
		assertFalse("no transport asks for Hitpoints", config(80).levelGateCrossed(Skill.HITPOINTS, 70));
		assertTrue("draining back below 81 counts too", config(85).levelGateCrossed(Skill.AGILITY, 80));
	}

	private static PathfinderConfig config(int agility)
	{
		final Thread clientThread = Thread.currentThread();
		ItemContainer inventory = Mockito.mock(ItemContainer.class);
		Mockito.when(inventory.getItems()).thenReturn(new Item[0]);
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
						return agility;
					case "getItemContainer":
						return (int) args[0] == InventoryID.INV ? inventory : null;
					default:
						return HybridPageFillTest.defaultValue(method.getReturnType());
				}
			});
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class, invocation ->
		{
			Class<?> type = invocation.getMethod().getReturnType();
			if (type == boolean.class)
			{
				return !"avoidWilderness".equals(invocation.getMethod().getName());
			}
			if (type == int.class)
			{
				return "calculationCutoff".equals(invocation.getMethod().getName()) ? 120 : 0;
			}
			if (type == TeleportationItem.class)
			{
				return TeleportationItem.INVENTORY;
			}
			return HybridPageFillTest.defaultValue(type);
		});
		PathfinderConfig owned = new TestPathfinderConfig(client, cfg, QuestState.FINISHED, false, true);
		owned.refresh();
		return owned;
	}
}
