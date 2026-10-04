package gps;

import gps.transport.Transport;
import gps.transport.TransportLoader;
import gps.transport.TransportType;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import net.runelite.api.gameval.InterfaceID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The spellbook highlight needs a component for every teleport spell the data can route by, and
 * the packed ids in spell-widgets.tsv must be the API's: a row drifting from its named constant
 * would outline the wrong spell.
 */
public class SpellWidgetsTest
{
	@Test
	public void everySpellInTheTransportDataHasAComponent()
	{
		Map<Integer, Set<Transport>> transports = TransportLoader.loadAllFromResources();
		List<String> missing = new ArrayList<>();
		int spells = 0;
		for (Set<Transport> set : transports.values())
		{
			for (Transport transport : set)
			{
				if (!TransportType.TELEPORTATION_SPELL.equals(transport.getType()))
				{
					continue;
				}
				spells++;
				if (SpellWidgets.componentFor(transport.getDisplayInfo()) < 0 && !missing.contains(transport.getDisplayInfo()))
				{
					missing.add(transport.getDisplayInfo());
				}
			}
		}
		assertTrue("spell transports loaded", spells > 50);
		assertEquals("spells without a spellbook component", List.of(), missing);
	}

	@Test
	public void qualifiedNamesFindTheirSpell()
	{
		assertEquals(InterfaceID.MagicSpellbook.VARROCK_TELEPORT, SpellWidgets.componentFor("Varrock Teleport: GE"));
		assertEquals(InterfaceID.MagicSpellbook.TELEPORT_ME_TO_BOAT, SpellWidgets.componentFor("Teleport to Boat — Port Sarim"));
		assertEquals(InterfaceID.MagicSpellbook.TELEPORT_TO_YOUR_HOUSE, SpellWidgets.componentFor("Teleport to House (Outside)"));
		assertEquals(InterfaceID.MagicSpellbook.TELEPORT_HOME_STANDARD, SpellWidgets.componentFor("Lumbridge Home Teleport"));
		assertEquals(-1, SpellWidgets.componentFor("Not a spell"));
		assertEquals(-1, SpellWidgets.componentFor(null));
	}

	@Test
	public void everyRowNamesItsConstantCorrectly() throws Exception
	{
		int rows = 0;
		try (InputStream in = SpellWidgetsTest.class.getResourceAsStream("/spell-widgets.tsv");
			Scanner scanner = new Scanner(in, "UTF-8"))
		{
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
				String[] row = line.split("\t");
				Field constant = InterfaceID.MagicSpellbook.class.getField(row[2]);
				assertEquals(row[0] + " names " + row[2], constant.getInt(null), Integer.parseInt(row[1]));
				rows++;
			}
		}
		assertTrue(rows >= 40);
	}
}
