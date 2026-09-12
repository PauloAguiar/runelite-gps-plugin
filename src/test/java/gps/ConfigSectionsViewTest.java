package gps;

import net.runelite.client.ui.ColorScheme;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Panel series P2: the configuration sections' header chips, out of the panel class. The
 * seconds chip reads in the route cards' sign convention (a preference of +15 s ranks as if
 * 15 s cheaper, shown as a green minus); the balloon and spirit-tree chips summarise the
 * section state in a word.
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
}
