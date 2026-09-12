package gps;

import gps.transport.TransportType;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Panel series P3: the method catalog's rules, out of the panel class. Teleport items group by
 * charge model and every other method by its category; a banked item is usable only in the
 * Inventory + bank mode; the funnel filter keeps excluded methods or one unavailability kind,
 * and the text filter matches the category or the label, case-insensitively.
 */
public class MethodCatalogViewTest
{
	private static final TeleportMethod TABLET = new TeleportMethod(TransportType.TELEPORTATION_ITEM,
		"Varrock tablet", WorldPointUtil.packWorldPoint(3212, 3424, 0));
	private static final TeleportMethod RING = new TeleportMethod(TransportType.FAIRY_RING,
		"BKR", WorldPointUtil.packWorldPoint(3469, 3431, 0));

	@Test
	public void teleportItemsGroupByChargeModelAndTheRestByCategory()
	{
		assertEquals(RING.category(), MethodCatalogView.groupKey(RING));
		String key = MethodCatalogView.groupKey(TABLET);
		assertEquals(TABLET.isConsumable() ? "Items (charged)" : "Items (permanent)", key);
	}

	@Test
	public void aBankedItemIsUsableOnlyInTheBankMode()
	{
		assertTrue(MethodCatalogView.usable(null, AlternativeRoutesMode.OWNED_INVENTORY));
		assertTrue(MethodCatalogView.usable(MethodAvailability.IN_BANK, AlternativeRoutesMode.OWNED_WITH_BANK));
		assertFalse(MethodCatalogView.usable(MethodAvailability.IN_BANK, AlternativeRoutesMode.OWNED_INVENTORY));
		assertFalse(MethodCatalogView.usable(MethodAvailability.MISSING_LEVEL, AlternativeRoutesMode.OWNED_WITH_BANK));
	}

	@Test
	public void theFunnelAndTextFiltersNarrowTheRows()
	{
		Set<TeleportMethod> excluded = Set.of(RING);
		Map<TeleportMethod, MethodAvailability> unavailable = Map.of(TABLET, MethodAvailability.IN_BANK);

		assertTrue(MethodCatalogView.matches(TABLET, MethodCatalogView.Filter.ALL, "", excluded, unavailable));
		assertTrue("the disabled filter keeps excluded methods",
			MethodCatalogView.matches(RING, MethodCatalogView.Filter.DISABLED, "", excluded, unavailable));
		assertFalse(MethodCatalogView.matches(TABLET, MethodCatalogView.Filter.DISABLED, "", excluded, unavailable));
		assertTrue("an availability filter keeps that kind",
			MethodCatalogView.matches(TABLET, MethodCatalogView.Filter.IN_BANK, "", excluded, unavailable));
		assertFalse(MethodCatalogView.matches(RING, MethodCatalogView.Filter.IN_BANK, "", excluded, unavailable));

		assertTrue("the text matches the label, case-insensitively",
			MethodCatalogView.matches(TABLET, MethodCatalogView.Filter.ALL, "VARROCK", excluded, unavailable));
		assertTrue("or the category",
			MethodCatalogView.matches(RING, MethodCatalogView.Filter.ALL, RING.category().toLowerCase(), excluded, unavailable));
		assertFalse(MethodCatalogView.matches(RING, MethodCatalogView.Filter.ALL, "varrock", excluded, unavailable));
	}

	@Test
	public void everyUnavailabilityKindHasAReason()
	{
		for (MethodAvailability status : MethodAvailability.values())
		{
			assertFalse(status.name(), PanelWidgets.statusReason(status).isEmpty());
		}
	}
}
