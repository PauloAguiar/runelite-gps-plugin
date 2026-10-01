package gps;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Panel series P2: the configuration sections' header chips, out of the panel class. The
 * seconds chip reads in the route cards' sign convention (a preference of +15 s ranks as if
 * 15 s cheaper, shown as a green minus); the balloon and spirit-tree chips summarise the
 * section state in a word. The sections' controls are built from the config items they bind
 * (label = the item's name, tooltip = its description), so the keys they name must exist.
 */
public class ConfigSectionsViewTest
{
	@Test
	public void theBiasChipUsesTheCardsSignConvention()
	{
		assertEquals("neutral", ConfigSectionsView.biasChip(0));
		assertEquals("−15s", ConfigSectionsView.biasChip(15));
		assertEquals("+15s", ConfigSectionsView.biasChip(-15));
		assertEquals(ColorScheme.LIGHT_GRAY_COLOR, ConfigSectionsView.biasColor(0));
		assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, ConfigSectionsView.biasColor(15));
		assertEquals(ColorScheme.PROGRESS_INPROGRESS_COLOR, ConfigSectionsView.biasColor(-15));
	}

	@Test
	public void theBalloonChipSaysOffOnOrLowLogs()
	{
		assertEquals("off", ConfigSectionsView.balloonState(false, true));
		assertEquals("on", ConfigSectionsView.balloonState(true, false));
		assertEquals("low logs", ConfigSectionsView.balloonState(true, true));
	}

	@Test
	public void theSpiritTreeChipSaysAllOnNoneOrTheCount()
	{
		assertEquals("all", ConfigSectionsView.spiritTreeState(false, true, 3));
		assertEquals("on", ConfigSectionsView.spiritTreeState(true, false, 0));
		assertEquals("none", ConfigSectionsView.spiritTreeState(true, true, 0));
		assertEquals("2 planted", ConfigSectionsView.spiritTreeState(true, true, 2));
	}

	@Test
	public void everyKeyTheSectionsBindIsAHiddenConfigItemWithLabelAndTooltip() throws Exception
	{
		// The sections name their items by key string, so a typo would only surface when a player
		// expands the section: scan the source for every bound key and resolve each one.
		String source = new String(Files.readAllBytes(Paths.get("src/main/java/gps/ConfigSectionsView.java")),
			StandardCharsets.UTF_8);
		Matcher keys = Pattern.compile("(?:toggle|spinner|item)\\(\"(\\w+)\"").matcher(source);
		Set<String> bound = new TreeSet<>();
		while (keys.find())
		{
			bound.add(keys.group(1));
		}
		assertTrue("the sections bind " + bound.size() + " keys; expected the house, bank, balloon, sailing and tree items",
			bound.size() >= 20);
		for (String key : bound)
		{
			ConfigItem item = ConfigSectionsView.item(key);
			assertEquals(key, item.keyName());
			assertTrue(key + " shows in the panel, so it stays hidden from RuneLite's config panel", item.hidden());
			assertFalse(key + " needs a name: it is the checkbox label", item.name().isEmpty());
			assertFalse(key + " needs a description: it is the tooltip", item.description().isEmpty());
		}
	}

	@Test
	public void theConfigNamesAreThePanelLabels()
	{
		assertEquals("Use my house for routes", ConfigSectionsView.item("usePoh").name());
		assertEquals("Warn below", ConfigSectionsView.item("balloonLogWarningThreshold").name());
		assertEquals("Keep sailing while at the helm", ConfigSectionsView.item("sailingKeepSailing").name());
	}

	@Test
	public void aMisspeltKeyFailsLoudly()
	{
		try
		{
			ConfigSectionsView.item("usePOH");
			fail("an unknown key must not build a silent control");
		}
		catch (IllegalArgumentException expected)
		{
			assertTrue(expected.getMessage().contains("usePOH"));
		}
	}
}
