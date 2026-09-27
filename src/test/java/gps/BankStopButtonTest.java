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
 * The panel's Bank quick button says when a click adds a stop (a player suggestion): with a route
 * under way, the route resumes after the bank, so the icon carries a "+" badge and the tooltip
 * says so; otherwise it reads as before. Bank (and back) keeps its look.
 */
public class BankStopButtonTest
{
	@Test
	public void theTooltipSaysWhenAClickAddsAStop()
	{
		assertEquals("Nearest bank", BankDetour.bankButtonTooltip(false));
		String stop = BankDetour.bankButtonTooltip(true);
		assertTrue(stop, stop.contains("Add a stop") && stop.contains("resumes after the bank"));
	}

	@Test
	public void theIconCarriesAPlusBadgeWhenAClickAddsAStop()
	{
		ImageIcon plain = RouteIcons.bankButtonIcon(false);
		ImageIcon stop = RouteIcons.bankButtonIcon(true);
		assertSame("without a stop the button keeps its icon", RouteIcons.destinationIcon("bank"), plain);
		assertNotSame(plain, stop);
		assertEquals(plain.getIconWidth(), stop.getIconWidth());
		assertEquals(plain.getIconHeight(), stop.getIconHeight());
		// The badge sits in the top-right corner: green there, off the plus's arms.
		Color badge = new Color(((BufferedImage) stop.getImage()).getRGB(10, 2), true);
		assertTrue("badge pixel " + badge, badge.getAlpha() > 200
			&& badge.getGreen() > badge.getRed() && badge.getGreen() > badge.getBlue());
	}
}
