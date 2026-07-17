package gps;

import net.runelite.api.Item;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The cross-session bank snapshot (Travel options → Bank) is persisted as {@code id:quantity}
 * pairs in RSProfile-scoped config. These tests pin the format: a round trip preserves every
 * real item, slots that carry no information (empty slots, withdrawn placeholders) are dropped,
 * and malformed stored data decodes to null (treated as "nothing saved") instead of throwing.
 */
public class BankSnapshotPersistenceTest
{
	@Test
	public void roundTripPreservesItemsAndQuantities()
	{
		Item[] items = {
			new Item(995, 1234567), // coins
			new Item(2434, 12),     // prayer potion(4)
			new Item(19675, 1),     // arclight
		};

		Item[] decoded = ShortestPathPlugin.decodeBankSnapshot(ShortestPathPlugin.encodeBankSnapshot(items));

		assertArrayEquals(items, decoded);
	}

	@Test
	public void emptySlotsAndPlaceholdersAreDropped()
	{
		Item[] items = {
			new Item(-1, 0),     // empty slot
			new Item(995, 100),
			new Item(2434, 0),   // placeholder: item withdrawn, slot kept
			new Item(-1, 5),     // nonsense id
		};

		Item[] decoded = ShortestPathPlugin.decodeBankSnapshot(ShortestPathPlugin.encodeBankSnapshot(items));

		assertEquals(1, decoded.length);
		assertEquals(new Item(995, 100), decoded[0]);
	}

	@Test
	public void nothingWorthSavingEncodesToNull()
	{
		assertNull(ShortestPathPlugin.encodeBankSnapshot(null));
		assertNull(ShortestPathPlugin.encodeBankSnapshot(new Item[0]));
		assertNull(ShortestPathPlugin.encodeBankSnapshot(new Item[]{new Item(-1, 0), new Item(4151, 0)}));
	}

	@Test
	public void missingOrMalformedStoredDataDecodesToNull()
	{
		assertNull(ShortestPathPlugin.decodeBankSnapshot(null));
		assertNull(ShortestPathPlugin.decodeBankSnapshot(""));
		assertNull(ShortestPathPlugin.decodeBankSnapshot("garbage"));
		assertNull(ShortestPathPlugin.decodeBankSnapshot("995:100,2434")); // pair missing quantity
		assertNull(ShortestPathPlugin.decodeBankSnapshot("995:abc"));      // non-numeric quantity
		assertNull(ShortestPathPlugin.decodeBankSnapshot(":5"));           // pair missing id
	}
}
