package gps;

import gps.pathfinder.PathStep;
import gps.transport.Transport;
import gps.transport.TransportType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import net.runelite.api.GameObject;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;

/**
 * What GPS knows about the player-owned house (plan step L18, out of the plugin class): the
 * model area route tiles live in, the template regions a live house scene is assembled from,
 * the exit-transport label for a route that ends inside, and the two adapters the furniture
 * detection reads the client through.
 */
public final class PlayerOwnedHouse
{
	// The POH model area (the transport data's y 5696 band) route tiles use. MIN_X is 1856 to
	// exclude the Daddy's Home miniquest area.
	private static final int MIN_X = 1856;
	private static final int MAX_X = 2047;
	private static final int MIN_Y = 5696;
	private static final int MAX_Y = 5767;
	// The map regions LIVE house instances are assembled from (rx 29-32, ry 110-111), distinct
	// from the model area above. Confirmed three ways (2026-07-17): a real house's chunk-dump
	// log, a cache scan (PohTemplateScanTest in shortest-path-tooling; ~13 copies of every room
	// hotspot, one per house STYLE), and the same region set hardcoded by other POH-aware
	// plugins. Every style and house location resolves to these regions. Checking the wrong band
	// here is why presence detection failed repeatedly.
	private static final Set<Integer> TEMPLATE_REGIONS =
		Set.of(7534, 7535, 7790, 7791, 8046, 8047, 8302, 8303);

	private PlayerOwnedHouse()
	{
	}

	/** Whether a world coordinate lies in the house model area. */
	public static boolean isInside(int x, int y)
	{
		return x >= MIN_X && x <= MAX_X && y >= MIN_Y && y <= MAX_Y;
	}

	/**
	 * Whether the world view is a player-owned house: an instance whose loaded map regions
	 * (which for instances are the TEMPLATE regions the scene is assembled from) include a house
	 * template region.
	 */
	public static boolean isHouseScene(WorldView worldView)
	{
		if (worldView == null || !worldView.isInstance())
		{
			return false;
		}
		int[] regions = worldView.getMapRegions();
		if (regions != null)
		{
			for (int region : regions)
			{
				if (TEMPLATE_REGIONS.contains(region))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * For a destination inside the house, the transport the path takes out of it (the first
	 * inside-to-outside edge after {@code currentIndex}), labelled by what it is: a mounted
	 * jewellery piece, the nexus, a fairy ring, a spirit tree, the obelisk. Null when the
	 * destination is outside, the path leaves the house before a transport, or none is found.
	 */
	public static String exitInfo(int destination, List<PathStep> path, int currentIndex,
		BiFunction<PathStep, PathStep, Set<Transport>> transportsForEdge)
	{
		if (path == null || currentIndex < 0)
		{
			return null;
		}
		if (!isInside(WorldPointUtil.unpackWorldX(destination), WorldPointUtil.unpackWorldY(destination)))
		{
			return null;
		}
		for (int i = currentIndex + 1; i < path.size() - 1; i++)
		{
			int stepLocation = path.get(i).getPackedPosition();
			int nextLocation = path.get(i + 1).getPackedPosition();
			boolean stepInside = isInside(WorldPointUtil.unpackWorldX(stepLocation), WorldPointUtil.unpackWorldY(stepLocation));
			boolean nextInside = isInside(WorldPointUtil.unpackWorldX(nextLocation), WorldPointUtil.unpackWorldY(nextLocation));
			if (stepInside && !nextInside)
			{
				for (Transport transport : transportsForEdge.apply(path.get(i), path.get(i + 1)))
				{
					String exitInfo = transport.getDisplayInfo();
					return exitInfo == null || exitInfo.isEmpty() ? null : exitLabel(transport, exitInfo);
				}
				return null;
			}
			if (!stepInside)
			{
				return null; // left the house without a transport
			}
		}
		return null;
	}

	private static String exitLabel(Transport transport, String exitInfo)
	{
		TransportType exitType = transport.getType();
		if (TransportType.TELEPORTATION_BOX.equals(exitType))
		{
			String objInfo = transport.getObjectInfo();
			if (objInfo != null && objInfo.contains("Amulet of Glory"))
			{
				return "Mounted Glory: " + exitInfo;
			}
			if (objInfo != null && objInfo.contains("Mythical cape"))
			{
				return "Mythical Cape: " + exitInfo;
			}
			if (objInfo != null && objInfo.contains("Xeric's Talisman"))
			{
				return "Xeric's Talisman: " + exitInfo;
			}
			if (objInfo != null && objInfo.contains("Digsite"))
			{
				return "Digsite Pendant: " + exitInfo;
			}
			return "Jewelry Box: " + exitInfo;
		}
		if (TransportType.TELEPORTATION_PORTAL_POH.equals(exitType))
		{
			return "Nexus: " + exitInfo;
		}
		if (TransportType.FAIRY_RING.equals(exitType))
		{
			return "Fairy Ring " + exitInfo;
		}
		if (TransportType.SPIRIT_TREE.equals(exitType))
		{
			return "Spirit Tree: " + exitInfo;
		}
		if (TransportType.WILDERNESS_OBELISK.equals(exitType))
		{
			return "Obelisk: " + exitInfo;
		}
		return exitInfo;
	}

	/** What the furniture detection reads from a loaded scene. */
	static PohDetectionService.Scene scene(WorldView worldView)
	{
		return new PohDetectionService.Scene()
		{
			@Override
			public boolean isHouse()
			{
				return isHouseScene(worldView);
			}

			@Override
			public boolean isInstance()
			{
				return worldView != null && worldView.isInstance();
			}

			@Override
			public String describeChunks()
			{
				return WorldPointUtil.describeInstanceChunks(worldView);
			}

			@Override
			public Set<Integer> objectIds()
			{
				return sceneObjectIds(worldView);
			}
		};
	}

	/** Every game object id in the loaded scene: the tile walk behind the house scan. */
	private static Set<Integer> sceneObjectIds(WorldView worldView)
	{
		Set<Integer> ids = new HashSet<>();
		Tile[][][] tiles = worldView.getScene().getTiles();
		for (Tile[][] plane : tiles)
		{
			if (plane == null)
			{
				continue;
			}
			for (Tile[] column : plane)
			{
				if (column == null)
				{
					continue;
				}
				for (Tile tile : column)
				{
					if (tile == null || tile.getGameObjects() == null)
					{
						continue;
					}
					for (GameObject object : tile.getGameObjects())
					{
						if (object != null)
						{
							ids.add(object.getId());
						}
					}
				}
			}
		}
		return ids;
	}

	/** The house declarations a scan may raise: read from the config, written through {@code raise}. */
	static PohDetectionService.Declarations declarations(ShortestPathConfig config, BiConsumer<String, Object> raise)
	{
		return new PohDetectionService.Declarations()
		{
			@Override
			public boolean fairyRing()
			{
				return config.usePohFairyRing();
			}

			@Override
			public boolean spiritTree()
			{
				return config.usePohSpiritTree();
			}

			@Override
			public boolean obelisk()
			{
				return config.usePohObelisk();
			}

			@Override
			public JewelleryBoxTier jewelleryBoxTier()
			{
				return config.pohJewelleryBoxTier();
			}

			@Override
			public void raise(String key, Object value)
			{
				raise.accept(key, value);
			}
		};
	}
}
