package gps;

import java.awt.Color;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The panel's bank quick buttons say when a click adds a stop (a player suggestion): with a route
 * under way, the route resumes after the bank, so the icon carries a "+" badge and the tooltip
 * says so; otherwise the buttons read as before.
 */
public class BankStopButtonTest
{
	private static Destinations.NearestOption option(String id)
	{
		return Destinations.NEAREST_OPTIONS.stream().filter(o -> o.id.equals(id)).findFirst().orElseThrow();
	}

	@Test
	public void theTooltipSaysWhenAClickAddsAStop()
	{
		assertEquals("Nearest bank", BankDetour.buttonTooltip(option("bank"), false));
		assertEquals("Nearest bank (and back)", BankDetour.buttonTooltip(option("bank_round_trip"), false));
		String stop = BankDetour.buttonTooltip(option("bank"), true);
		assertTrue(stop, stop.contains("Add a stop") && stop.contains("resumes after the bank"));
		String stopAndBack = BankDetour.buttonTooltip(option("bank_round_trip"), true);
		assertTrue(stopAndBack, stopAndBack.contains("Add a stop") && stopAndBack.contains("and back"));
	}

	@Test
	public void theIconCarriesAPlusBadgeWhenAClickAddsAStop()
	{
		for (String id : new String[]{"bank", "bank_round_trip"})
		{
			ImageIcon plain = RouteIcons.bankButtonIcon(id, false);
			ImageIcon stop = RouteIcons.bankButtonIcon(id, true);
			assertSame("without a stop the button keeps its icon", RouteIcons.destinationIcon(id), plain);
			assertNotSame(plain, stop);
			assertEquals(plain.getIconWidth(), stop.getIconWidth());
			assertEquals(plain.getIconHeight(), stop.getIconHeight());
			// The badge sits in the top-right corner: green there, off the plus's arms.
			Color badge = new Color(((BufferedImage) stop.getImage()).getRGB(10, 2), true);
			assertTrue(id + " badge pixel " + badge, badge.getAlpha() > 200
				&& badge.getGreen() > badge.getRed() && badge.getGreen() > badge.getBlue());
		}
	}
}
