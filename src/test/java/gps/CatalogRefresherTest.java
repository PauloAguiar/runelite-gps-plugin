package gps;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Plan step L29: the catalog-refresh cadence, out of the plugin class. An inventory change marks
 * the catalog dirty only when the routing-relevant item fingerprint changed (issues #23/#24:
 * logs, ore and loot must not schedule a refresh); a dirty catalog waits while the panel is
 * hidden, a generation is in flight or the player is logged out; a claimed refresh arms a
 * short cooldown so bursts coalesce, and the flag is kept until a refresh is actually claimed,
 * so no change is lost, only delayed.
 */
public class CatalogRefresherTest
{
	@Test
	public void nothingToDoUntilSomethingChanged()
	{
		CatalogRefresher refresher = new CatalogRefresher();
		assertFalse(refresher.claim(10, true, false, true));
	}

	@Test
	public void onlyAChangedItemFingerprintDirtiesTheCatalog()
	{
		CatalogRefresher refresher = new CatalogRefresher();
		assertTrue("the first fingerprint is a change (nothing was known)", refresher.noteItems(42L));
		assertTrue(refresher.claim(10, true, false, true));
		assertFalse("same routing-relevant items: not dirty", refresher.noteItems(42L));
		assertFalse(refresher.claim(20, true, false, true));
		assertTrue(refresher.noteItems(43L));
		assertTrue(refresher.claim(30, true, false, true));
	}

	@Test
	public void aDirtyCatalogWaitsForThePanelALullAndALogin()
	{
		CatalogRefresher refresher = new CatalogRefresher();
		refresher.markDirty();
		assertFalse("panel hidden: the flag waits", refresher.claim(10, false, false, true));
		assertFalse("generation in flight: it re-snapshots anyway", refresher.claim(11, true, true, true));
		assertFalse("logged out", refresher.claim(12, true, false, false));
		assertTrue("still dirty: claimed once the panel is open in-game", refresher.claim(13, true, false, true));
		assertFalse("claimed: clean again", refresher.claim(14, true, false, true));
	}

	@Test
	public void burstsCoalesceThroughTheCooldown()
	{
		CatalogRefresher refresher = new CatalogRefresher();
		refresher.markDirty();
		assertTrue(refresher.claim(100, true, false, true));
		refresher.markDirty();
		assertFalse("within the cooldown the flag stays set", refresher.claim(102, true, false, true));
		assertFalse(refresher.claim(100 + CatalogRefresher.COOLDOWN_TICKS - 1, true, false, true));
		assertTrue("the cooldown elapsed: the kept flag is claimed", refresher.claim(100 + CatalogRefresher.COOLDOWN_TICKS, true, false, true));
	}
}
