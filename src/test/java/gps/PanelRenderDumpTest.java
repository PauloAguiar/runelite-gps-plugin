package gps;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Scratch harness: renders the side panel headlessly to a PNG so layout changes (label wrapping,
 * new sections, the warning toggle row) can be inspected without a client. Not a regression test.
 */
public class PanelRenderDumpTest
{
	@Test
	public void dump() throws Exception
	{
		if (System.getenv("GPS_PANEL_DUMP") == null)
		{
			return;
		}
		ShortestPathConfig config = Mockito.mock(ShortestPathConfig.class, Mockito.withSettings().lenient());
		Mockito.when(config.rememberBank()).thenReturn(true);
		Mockito.when(config.usePoh()).thenReturn(true);
		Mockito.when(config.pohSmartDetect()).thenReturn(true);
		Mockito.when(config.useSpiritTrees()).thenReturn(true);
		Mockito.when(config.spiritTreeSmartMode()).thenReturn(true);
		Mockito.when(config.useHotAirBalloons()).thenReturn(true);
		Mockito.when(config.balloonSmartMode()).thenReturn(true);
		Mockito.when(config.balloonStorageSynced()).thenReturn(false);
		Mockito.when(config.hideWarningBanners()).thenReturn(Boolean.parseBoolean(System.getenv("GPS_PANEL_HIDDEN")));
		Mockito.when(config.pohJewelleryBoxTier()).thenReturn(JewelleryBoxTier.NONE);

		ShortestPathPlugin plugin = Mockito.mock(ShortestPathPlugin.class, Mockito.withSettings().lenient());
		Mockito.when(plugin.getGpsConfig()).thenReturn(config);
		Mockito.when(plugin.getRoutesMode()).thenReturn(AlternativeRoutesMode.OWNED_WITH_BANK);
		Mockito.when(plugin.getBalloonLowLogTypes()).thenReturn(List.of());
		Mockito.when(plugin.getDetectedPohFurniture()).thenReturn(List.of());
		Mockito.when(plugin.getAvailablePlantedSpiritTrees()).thenReturn(List.of());
		Mockito.when(plugin.getFavoriteDestinations()).thenReturn(List.of());

		ShortestPathPanel panel = new ShortestPathPanel(plugin);
		for (String field : new String[]{"travelSectionExpanded", "bankSectionExpanded", "pohSectionExpanded"})
		{
			Field f = ShortestPathPanel.class.getDeclaredField(field);
			f.setAccessible(true);
			f.setBoolean(panel, true);
		}
		panel.refreshConfigSections();
		panel.displayRoutes(List.of(), List.of(), Map.of(), Set.of(), false, false);
		// Show the inline favourite editor so its layout is part of the dump.
		java.lang.reflect.Method toggle = ShortestPathPanel.class.getDeclaredMethod("toggleFavoriteEditor");
		toggle.setAccessible(true);
		toggle.invoke(panel);

		JPanel root = panel;
		root.setSize(242, 1400);
		layoutTree(root);
		BufferedImage image = new BufferedImage(242, 1400, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		root.paint(g);
		g.dispose();
		File out = new File(System.getenv("GPS_PANEL_DUMP"));
		ImageIO.write(image, "png", out);
		System.out.println("wrote " + out.getAbsolutePath());
	}

	private static void layoutTree(java.awt.Component c)
	{
		c.doLayout();
		if (c instanceof java.awt.Container)
		{
			for (java.awt.Component child : ((java.awt.Container) c).getComponents())
			{
				layoutTree(child);
			}
		}
	}
}
