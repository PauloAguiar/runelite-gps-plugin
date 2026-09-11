package gps;

import java.awt.image.BufferedImage;
import java.util.Set;
import java.util.function.Supplier;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import net.runelite.client.util.ImageUtil;

/**
 * The destination pin on the world map (plan step L33, out of the plugin class). A single target
 * gets a pin on its tile; a multi-target set (an amenity category, "nearest bank") gets none,
 * unless a one-shot override names the tile the set was expanded from (a searched bank booth
 * and its walkable surround still pins the booth). Clicking the pin jumps the map to it.
 */
final class WorldMapMarker
{
	private static final BufferedImage IMAGE = ImageUtil.loadImageResource(ShortestPathPlugin.class, "/marker.png");

	// A supplier: the manager is injected into the plugin after field initialisation.
	private final Supplier<WorldMapPointManager> manager;
	private WorldMapPoint marker;
	// The one-shot override for the next placement; UNDEFINED = the default rule.
	private int pinNext = WorldPointUtil.UNDEFINED;

	WorldMapMarker(Supplier<WorldMapPointManager> manager)
	{
		this.manager = manager;
	}

	/** The next placement pins {@code tile} whatever the target set (consumed by that placement). */
	void pinNextAt(int tile)
	{
		pinNext = tile;
	}

	/** Replaces the pin for a new target set: the override, else the single target, else none. */
	void place(Set<Integer> targets)
	{
		clear();
		int tile = pinNext != WorldPointUtil.UNDEFINED ? pinNext
			: (targets.size() == 1 ? targets.iterator().next() : WorldPointUtil.UNDEFINED);
		pinNext = WorldPointUtil.UNDEFINED;
		if (tile == WorldPointUtil.UNDEFINED)
		{
			return;
		}
		marker = new WorldMapPoint(WorldPointUtil.unpackWorldPoint(tile), IMAGE);
		marker.setName("Target");
		marker.setTarget(marker.getWorldPoint());
		marker.setJumpOnClick(true);
		manager.get().add(marker);
	}

	/** Removes the pin, if any. */
	void clear()
	{
		manager.get().removeIf(x -> x == marker);
		marker = null;
	}

	/** The pinned tile, or UNDEFINED when there is no pin. */
	int pinnedTile()
	{
		return marker == null ? WorldPointUtil.UNDEFINED : WorldPointUtil.packWorldPoint(marker.getWorldPoint());
	}
}
