package gps;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The item families live in item-variations.tsv and rune-sources.tsv (0.14.0 moved them out of
 * code). Issue #22: every elemental staff the wiki lists as an unlimited source of a rune must be
 * in that rune's staff family — the mystic element staves (1401-1407) and the "pretty" lava /
 * steam variants were missing, so a player wielding a Mystic air staff was told they had no
 * air runes.
 */
public class ItemVariationsTest
{
	private static ItemVariations family(String name)
	{
		ItemVariations family = ItemVariations.fromName(name);
		assertNotNull("no item family named " + name, family);
		return family;
	}

	private static void assertFamily(ItemVariations family, int... ids)
	{
		for (int id : ids)
		{
			assertTrue(family + " must include item " + id,
				Arrays.stream(family.getIds()).anyMatch(i -> i == id));
		}
	}

	@Test
	public void mysticElementStavesAreRuneSources()
	{
		assertFamily(family("STAFF_OF_AIR"), ItemID.STAFF_OF_AIR, ItemID.MYSTIC_AIR_STAFF,
			ItemID.AIR_BATTLESTAFF, ItemID.MYSTIC_MIST_BATTLESTAFF, ItemID.MYSTIC_DUST_BATTLESTAFF);
		assertFamily(family("STAFF_OF_EARTH"), ItemID.STAFF_OF_EARTH, ItemID.MYSTIC_EARTH_STAFF,
			ItemID.EARTH_BATTLESTAFF, ItemID.MYSTIC_MUD_STAFF, ItemID.MYSTIC_LAVA_STAFF,
			// The "(or)" cosmetic variants are the same staves — a pretty lava staff counted as
			// fire but not earth (field report 2026-08-23), so the Civitas spell demanded runes.
			ItemID.LAVA_BATTLESTAFF_PRETTY, ItemID.MYSTIC_LAVA_STAFF_PRETTY);
		assertFamily(family("STAFF_OF_FIRE"), ItemID.STAFF_OF_FIRE, ItemID.MYSTIC_FIRE_STAFF,
			ItemID.FIRE_BATTLESTAFF, ItemID.MYSTIC_LAVA_STAFF, ItemID.MYSTIC_LAVA_STAFF_PRETTY,
			ItemID.LAVA_BATTLESTAFF_PRETTY, ItemID.STEAM_BATTLESTAFF_PRETTY,
			ItemID.MYSTIC_STEAM_BATTLESTAFF, ItemID.MYSTIC_STEAM_BATTLESTAFF_PRETTY);
		assertFamily(family("STAFF_OF_WATER"), ItemID.STAFF_OF_WATER, ItemID.MYSTIC_WATER_STAFF,
			ItemID.WATER_BATTLESTAFF, ItemID.MYSTIC_MUD_STAFF, ItemID.MYSTIC_STEAM_BATTLESTAFF,
			ItemID.STEAM_BATTLESTAFF_PRETTY, ItemID.MYSTIC_STEAM_BATTLESTAFF_PRETTY);
	}

	@Test
	public void runeStaffSubstitutionCoversEveryElement()
	{
		// The substitution table must resolve for each elemental rune, else a staff never counts.
		for (String rune : new String[]{"AIR_RUNE", "EARTH_RUNE", "FIRE_RUNE", "WATER_RUNE"})
		{
			int[] staves = ItemVariations.staves(family(rune));
			assertTrue("no staff family for " + rune, staves != null && staves.length > 0);
		}
	}

	@Test
	public void theTableNamesEveryIdByItsGamevalConstant() throws Exception
	{
		// The gameval name column exists for reading; it must agree with the id beside it, or
		// the row lies to whoever edits it next.
		List<String[]> rows = rows("/item-variations.tsv");
		assertTrue("the table lost most of its rows: " + rows.size(), rows.size() > 200);
		for (String[] row : rows)
		{
			assertEquals("a row is Family, Item id, Gameval name: " + String.join("|", row), 3, row.length);
			int named = ItemID.class.getField(row[2]).getInt(null);
			assertEquals(row[0] + ": " + row[2] + " is item " + named + " in ItemID, the row says " + row[1],
				named, Integer.parseInt(row[1]));
			assertFamily(family(row[0]), named);
		}
	}

	@Test
	public void runeSourcesNameLoadedFamilies() throws Exception
	{
		List<String[]> rows = rows("/rune-sources.tsv");
		assertTrue("the rune sources are gone", rows.size() >= 11);
		for (String[] row : rows)
		{
			ItemVariations rune = family(row[0]);
			if (row.length > 1 && !row[1].isEmpty())
			{
				assertSame(row[0] + " staves", family(row[1]).getIds(), ItemVariations.staves(rune));
			}
			if (row.length > 2 && !row[2].isEmpty())
			{
				assertSame(row[0] + " offhands", family(row[2]).getIds(), ItemVariations.offhands(rune));
			}
		}
		assertNull("an item that is not a rune has no staff", ItemVariations.staves(family("AXE")));
		assertNull("only the elemental runes have tomes", ItemVariations.offhands(family("AIR_RUNE")));
		assertNull(ItemVariations.staves(null));
		assertNull(ItemVariations.offhands(null));
	}

	@Test
	public void dramenStaffIsTheOneFamilyCodeNamesDirectly()
	{
		assertSame(family("DRAMEN_STAFF"), ItemVariations.DRAMEN_STAFF);
		assertFamily(ItemVariations.DRAMEN_STAFF, ItemID.DRAMEN_STAFF, ItemID.LUNAR_MOONCLAN_LIMINAL_STAFF);
		assertEquals("DRAMEN_STAFF", ItemVariations.DRAMEN_STAFF.toString());
		assertNull("an unknown name is null so the parser can try a raw item id", ItemVariations.fromName("NO_SUCH_ITEM"));
	}

	/** The data rows of a TSV resource: comments and the header skipped, fields tab-split. */
	private static List<String[]> rows(String resource)
	{
		List<String[]> rows = new ArrayList<>();
		try (InputStream in = ItemVariationsTest.class.getResourceAsStream(resource))
		{
			assertNotNull(resource + " is missing from the resources", in);
			Scanner scanner = new Scanner(in, "UTF-8");
			boolean header = true;
			while (scanner.hasNextLine())
			{
				String line = scanner.nextLine();
				if (line.isEmpty() || line.startsWith("#"))
				{
					continue;
				}
				if (header)
				{
					header = false;
					continue;
				}
				rows.add(line.split("\t"));
			}
		}
		catch (IOException e)
		{
			throw new AssertionError(e);
		}
		return rows;
	}
}
