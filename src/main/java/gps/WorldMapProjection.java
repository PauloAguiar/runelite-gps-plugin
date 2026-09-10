package gps;

import java.awt.Rectangle;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;

/**
 * World map projection (plan step L13, out of the plugin class): world points to the map
 * widget's screen pixels, from the map's zoom, centre and bounds, and a screen pixel back to the
 * world point it shows (the shift-click "Set GPS Target" on the world map). Client thread and
 * render thread both read the live widget; no widget means no projection.
 */
final class WorldMapProjection
{
	/** The pixel result when the world map is not open. */
	static final int NONE = Integer.MIN_VALUE;

	private final Client client;

	WorldMapProjection(Client client)
	{
		this.client = client;
	}

	/** The world point under a screen pixel, or UNDEFINED when the map is not open. */
	int worldPointAt(int pixelX, int pixelY)
	{
		WorldMap worldMap = client.getWorldMap();
		float zoom = worldMap.getWorldMapZoom();
		int mapPoint = WorldPointUtil.packWorldPoint(worldMap.getWorldMapPosition().getX(), worldMap.getWorldMapPosition().getY(), 0);
		int middleX = toGraphicsX(mapPoint);
		int middleY = toGraphicsY(mapPoint);

		if (pixelX == NONE || pixelY == NONE || middleX == NONE || middleY == NONE)
		{
			return WorldPointUtil.UNDEFINED;
		}

		final int dx = (int) ((pixelX - middleX) / zoom);
		final int dy = (int) ((-(pixelY - middleY)) / zoom);

		return WorldPointUtil.dxdy(mapPoint, dx, dy);
	}

	/** The screen x of a world point on the map, or NONE when the map is not open. */
	int toGraphicsX(int packedWorldPoint)
	{
		WorldMap worldMap = client.getWorldMap();

		float pixelsPerTile = worldMap.getWorldMapZoom();

		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map != null)
		{
			Rectangle worldMapRect = map.getBounds();

			int widthInTiles = (int) Math.ceil(worldMapRect.getWidth() / pixelsPerTile);

			Point worldMapPosition = worldMap.getWorldMapPosition();

			int xTileOffset = WorldPointUtil.unpackWorldX(packedWorldPoint) + widthInTiles / 2 - worldMapPosition.getX();

			int xGraphDiff = ((int) (xTileOffset * pixelsPerTile));
			xGraphDiff += (int) (pixelsPerTile - Math.ceil(pixelsPerTile / 2));
			xGraphDiff += (int) worldMapRect.getX();

			return xGraphDiff;
		}
		return NONE;
	}

	/** The screen y of a world point on the map, or NONE when the map is not open. */
	int toGraphicsY(int packedWorldPoint)
	{
		WorldMap worldMap = client.getWorldMap();

		float pixelsPerTile = worldMap.getWorldMapZoom();

		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map != null)
		{
			Rectangle worldMapRect = map.getBounds();

			int heightInTiles = (int) Math.ceil(worldMapRect.getHeight() / pixelsPerTile);

			Point worldMapPosition = worldMap.getWorldMapPosition();

			int yTileMax = worldMapPosition.getY() - heightInTiles / 2;
			int yTileOffset = (yTileMax - WorldPointUtil.unpackWorldY(packedWorldPoint) - 1) * -1;

			int yGraphDiff = (int) (yTileOffset * pixelsPerTile);
			yGraphDiff -= (int) (pixelsPerTile - Math.ceil(pixelsPerTile / 2));
			yGraphDiff = worldMapRect.height - yGraphDiff;
			yGraphDiff += (int) worldMapRect.getY();

			return yGraphDiff;
		}
		return NONE;
	}
}
