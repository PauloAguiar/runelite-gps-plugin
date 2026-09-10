package gps;

import java.awt.Color;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Plan step L21: the plugin-message config overrides, out of the plugin class. An applied
 * override answers instead of the config value, by type; an unknown key is rejected; a value of
 * the wrong type falls through to the config; clearing restores every config value; and the
 * route-affecting test names the keys the engine reads.
 */
public class ConfigOverridesTest
{
	@Before
	@After
	public void clean()
	{
		ConfigOverrides.clear();
	}

	@Test
	public void appliedOverridesAnswerByType()
	{
		ConfigOverrides.apply(Map.of(
			"avoidWilderness", true,
			"costBankPickup", 40,
			"useTeleportationItems", "Inventory",
			"pohJewelleryBoxTier", "Ornate",
			"colourPath", Color.RED,
			"bogus", 7));
		assertTrue(ConfigOverrides.override("avoidWilderness", false));
		assertEquals(40, ConfigOverrides.override("costBankPickup", 5));
		assertEquals(TeleportationItem.INVENTORY, ConfigOverrides.override("useTeleportationItems", TeleportationItem.NONE));
		assertEquals(JewelleryBoxTier.ORNATE, ConfigOverrides.override("pohJewelleryBoxTier", JewelleryBoxTier.NONE));
		assertEquals(Color.RED, ConfigOverrides.override("colourPath", Color.BLUE));
		assertEquals("an unknown key is rejected", 3, ConfigOverrides.override("bogus", 3));
		assertEquals("a key the message did not set keeps the config value", 9, ConfigOverrides.override("costWalking", 9));
	}

	@Test
	public void wrongTypesFallThroughAndClearRestores()
	{
		ConfigOverrides.apply(Map.of("avoidWilderness", "yes", "costBankPickup", true));
		assertFalse(ConfigOverrides.override("avoidWilderness", false));
		assertEquals(5, ConfigOverrides.override("costBankPickup", 5));
		ConfigOverrides.apply(Map.of("avoidWilderness", true));
		assertTrue(ConfigOverrides.override("avoidWilderness", false));
		ConfigOverrides.clear();
		assertFalse(ConfigOverrides.override("avoidWilderness", false));
	}

	@Test
	public void routeAffectingKeysAndKnownKeys()
	{
		assertTrue(ConfigOverrides.affectsRouting("pohMountGlory"));
		assertTrue(ConfigOverrides.affectsRouting("useFairyRings"));
		assertTrue(ConfigOverrides.affectsRouting("costBankPickup"));
		assertFalse(ConfigOverrides.affectsRouting("drawMap"));
		assertFalse(ConfigOverrides.affectsRouting(null));
		assertTrue(ConfigOverrides.knownKeys().contains("avoidWilderness"));
		assertFalse(ConfigOverrides.knownKeys().contains("bogus"));
	}
}
