package gps;

import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Plan step L17: the spirit-tree travel menu parse, out of the plugin class. Rows read as
 * "<col=735a28>N</col>: Name" (old menu) or "<col=ffffff>N</col>: Name" (new menu); a greyed
 * row ("<col=5f5f5f>" before the name) is a tree the player cannot use; the first row is always
 * Tree Gnome Village, and a menu whose first row is not 39 characters is not the travel menu.
 */
public class SpiritTreeSyncTest
{
	@Test
	public void oldMenuRowsYieldTheUsableTrees()
	{
		String[] rows = {
			"<col=735a28>1</col>: Tree Gnome Village",
			"<col=735a28>2</col>: Gnome Stronghold",
			"<col=735a28>3</col>: <col=5f5f5f>Battlefield of Khazard",
			"<col=735a28>4</col>: Grand Exchange",
			"<col=735a28>5</col>: <col=5f5f5f>Feldip Hills",
			"Cancel",
		};
		assertEquals(Set.of("Tree Gnome Village", "Gnome Stronghold", "Grand Exchange"),
			SpiritTreeSync.parseLabels(rows, false));
	}

	@Test
	public void newMenuRowsUseTheWhiteNumberColour()
	{
		String[] rows = {
			"<col=ffffff>1</col>: Tree Gnome Village",
			"<col=ffffff>2</col>: <col=5f5f5f>Gnome Stronghold",
			"<col=ffffff>3</col>: Port Sarim",
		};
		assertEquals(Set.of("Tree Gnome Village", "Port Sarim"), SpiritTreeSync.parseLabels(rows, true));
		assertEquals("the old pattern reads nothing from the new menu", Set.of(), SpiritTreeSync.parseLabels(rows, false));
	}

	@Test
	public void anotherMenuIsNotParsed()
	{
		assertNull(SpiritTreeSync.parseLabels(new String[]{"<col=735a28>1</col>: Lumbridge"}, false));
		assertNull(SpiritTreeSync.parseLabels(new String[0], false));
		assertNull(SpiritTreeSync.parseLabels(null, false));
		assertNull(SpiritTreeSync.parseLabels(new String[]{null}, false));
	}
}
