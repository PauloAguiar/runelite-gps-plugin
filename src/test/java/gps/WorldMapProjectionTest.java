package gps;

import java.awt.Rectangle;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Plan step L13: the world-map projection, out of the plugin class. A world point projects to
 * the map widget's pixels from the map's zoom, centre and bounds; a pixel projects back to the
 * world point (the shift-click "Set GPS Target" on the world map); no map widget means no
 * projection (the sentinel the overlays test for).
 */
@RunWith(MockitoJUnitRunner.class)
public class WorldMapProjectionTest
{
	@Mock
	Client client;
	@Mock
	WorldMap worldMap;
	@Mock
	Widget mapWidget;

	@Test
	public void projectsThereAndBack()
	{
		when(client.getWorldMap()).thenReturn(worldMap);
		when(worldMap.getWorldMapZoom()).thenReturn(4f);
		when(worldMap.getWorldMapPosition()).thenReturn(new Point(3200, 3200));
		when(client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER)).thenReturn(mapWidget);
		when(mapWidget.getBounds()).thenReturn(new Rectangle(100, 50, 800, 600));
		WorldMapProjection projection = new WorldMapProjection(client);

		int point = WorldPointUtil.packWorldPoint(3210, 3210, 0);
		assertEquals(542, projection.toGraphicsX(point));
		assertEquals(308, projection.toGraphicsY(point));
		assertEquals("a pixel maps back to the tile it shows", point, projection.worldPointAt(542, 308));
	}

	@Test
	public void noMapWidgetMeansNoProjection()
	{
		when(client.getWorldMap()).thenReturn(worldMap);
		lenient().when(worldMap.getWorldMapZoom()).thenReturn(4f);
		lenient().when(worldMap.getWorldMapPosition()).thenReturn(new Point(3200, 3200));
		when(client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER)).thenReturn(null);
		WorldMapProjection projection = new WorldMapProjection(client);

		int point = WorldPointUtil.packWorldPoint(3210, 3210, 0);
		assertEquals(WorldMapProjection.NONE, projection.toGraphicsX(point));
		assertEquals(WorldMapProjection.NONE, projection.toGraphicsY(point));
		assertEquals(WorldPointUtil.UNDEFINED, projection.worldPointAt(542, 308));
	}
}
