package gps.pathfinder;

import gps.JewelleryBoxTier;
import gps.ShortestPathConfig;
import gps.TeleportMethod;
import gps.TeleportationItem;
import gps.transport.TransportType;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The house's mounted items are individual assumptions (they can't be scene-detected): each
 * mount has its own toggle under "Mounted items", and only the selected ones exist for routing
 * — in every mode, since what furniture exists is a structural fact, not a possession.
 */
@RunWith(MockitoJUnitRunner.class)
public class PohMountSelectionTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	@Before
	public void before()
	{
		when(config.calculationCutoff()).thenReturn(30);
		when(config.currencyThreshold()).thenReturn(10000000);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.NONE);
		when(config.usePoh()).thenReturn(true);
		when(config.usePohMountedItems()).thenReturn(true);
		// Below Ornate, so the mounted glory is not folded into the box.
		when(config.pohJewelleryBoxTier()).thenReturn(JewelleryBoxTier.BASIC);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(i -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
	}

	private Set<TeleportMethod> catalog(boolean glory, boolean xerics, boolean digsite, boolean mythical)
	{
		when(config.pohMountGlory()).thenReturn(glory);
		when(config.pohMountXerics()).thenReturn(xerics);
		when(config.pohMountDigsite()).thenReturn(digsite);
		when(config.pohMountMythical()).thenReturn(mythical);
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		return planning.getMethodCatalog();
	}

	private static boolean hasMount(Set<TeleportMethod> catalog, String mount)
	{
		for (TeleportMethod method : catalog)
		{
			if (method.getType() == TransportType.TELEPORTATION_BOX && method.label().startsWith(mount + ":"))
			{
				return true;
			}
		}
		return false;
	}

	@Test
	public void eachMountIsItsOwnSwitch()
	{
		Set<TeleportMethod> all = catalog(true, true, true, true);
		assertTrue("glory mount present", hasMount(all, "Amulet of glory"));
		assertTrue("Xeric's mount present", hasMount(all, "Xeric's talisman"));
		assertTrue("digsite mount present", hasMount(all, "Digsite pendant"));
		assertTrue("mythical cape mount present", hasMount(all, "Mythical cape"));

		Set<TeleportMethod> noXerics = catalog(true, false, true, true);
		assertFalse("Xeric's off: its rows must not exist for routing", hasMount(noXerics, "Xeric's talisman"));
		assertTrue("the other mounts stay", hasMount(noXerics, "Amulet of glory"));
		assertTrue(hasMount(noXerics, "Digsite pendant"));
	}

	@Test
	public void masterToggleStillRulesThemAll()
	{
		when(config.usePohMountedItems()).thenReturn(false);
		Set<TeleportMethod> none = catalog(true, true, true, true);
		assertFalse(hasMount(none, "Amulet of glory"));
		assertFalse(hasMount(none, "Xeric's talisman"));
	}
}
