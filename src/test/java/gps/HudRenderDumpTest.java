package gps;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import javax.imageio.ImageIO;
import net.runelite.api.Client;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Scratch harness: renders the directions HUD's "Finding the best route" panel headlessly to a
 * PNG (GPS_HUD_DUMP=path) so its layout can be inspected without a client. Not a regression test.
 */
public class HudRenderDumpTest
{
	@Test
	public void dumpFindingPanel() throws Exception
	{
		if (System.getenv("GPS_HUD_DUMP") == null)
		{
			return;
		}
		ShortestPathPlugin plugin = Mockito.mock(ShortestPathPlugin.class, Mockito.withSettings().lenient());
		Mockito.when(plugin.isFindingRoute()).thenReturn(true);
		Mockito.when(plugin.getDisplayedRoute()).thenReturn(null);
		Mockito.when(plugin.getTargetSource()).thenReturn(System.getenv("GPS_HUD_SOURCE"));
		set(plugin, "showDirections", true);
		set(plugin, "colourOverlayAccent", new Color(0x3C, 0x8C, 0xE6));
		set(plugin, "overlayFontSize", OverlayFontSize.NORMAL);
		set(plugin, "overrideOverlayTransparency", false);

		RouteDirectionsOverlay overlay = new RouteDirectionsOverlay(Mockito.mock(Client.class), plugin);
		for (String source : new String[]{"Slayer gear advisor", null})
		{
			Mockito.when(plugin.getTargetSource()).thenReturn(source);
			BufferedImage image = new BufferedImage(420, 160, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = image.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			// A game-like backdrop so the translucent panel reads as it does in the client.
			g.setColor(new Color(0x4A, 0x6B, 0x3A));
			g.fillRect(0, 0, image.getWidth(), image.getHeight());
			g.setFont(net.runelite.client.ui.FontManager.getRunescapeFont());
			g.translate(10, 10);
			// The panel component sizes itself from the previous frame: render twice, keep the second.
			overlay.render(g);
			g.setColor(new Color(0x4A, 0x6B, 0x3A));
			g.fillRect(-10, -10, image.getWidth(), image.getHeight());
			Dimension size = overlay.render(g);
			g.dispose();
			File out = new File(System.getenv("GPS_HUD_DUMP") + (source == null ? "-plain.png" : "-source.png"));
			ImageIO.write(image, "png", out);
			System.out.println("wrote " + out.getAbsolutePath() + " panel=" + size
				+ " children=" + overlay.getPanelComponent().getChildren().size());
		}
	}

	private static void set(Object target, String field, Object value) throws Exception
	{
		Field f = ShortestPathPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}
}
