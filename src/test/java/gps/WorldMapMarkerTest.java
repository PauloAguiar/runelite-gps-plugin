package gps;

import java.util.Set;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Plan step L33: the world-map pin, out of the plugin class. A single target is pinned, a
 * multi-target set is not, unless the one-shot override names the tile the set was expanded
 * from, and that override is consumed by the placement it applies to.
 */
@RunWith(MockitoJUnitRunner.class)
public class WorldMapMarkerTest
{
	private static final int BOOTH = WorldPointUtil.packWorldPoint(3185, 3436, 0);
	private static final int WEST = WorldPointUtil.packWorldPoint(3184, 3436, 0);
	private static final int EAST = WorldPointUtil.packWorldPoint(3186, 3436, 0);

	@Mock
	WorldMapPointManager manager;

	@Test
	public void aSingleTargetIsPinnedAndAMultiTargetSetIsNot()
	{
		WorldMapMarker marker = new WorldMapMarker(() -> manager);
		marker.place(Set.of(BOOTH));
		assertEquals(BOOTH, marker.pinnedTile());
		verify(manager, times(1)).add(any());

		marker.place(Set.of(WEST, EAST));
		assertEquals("a category of targets has no single place to pin", WorldPointUtil.UNDEFINED, marker.pinnedTile());
		verify(manager, times(1)).add(any());
		verify(manager, times(2)).removeIf(any());
	}

	@Test
	public void theOverridePinsTheExpandedTileOnceThenTheRuleReturns()
	{
		WorldMapMarker marker = new WorldMapMarker(() -> manager);
		marker.pinNextAt(BOOTH);
		marker.place(Set.of(WEST, EAST));
		assertEquals("the searched booth, not its walkable surround", BOOTH, marker.pinnedTile());

		marker.place(Set.of(WEST, EAST));
		assertEquals("consumed: the next multi-target set pins nothing", WorldPointUtil.UNDEFINED, marker.pinnedTile());
	}

	@Test
	public void clearRemovesThePin()
	{
		WorldMapMarker marker = new WorldMapMarker(() -> manager);
		marker.clear();
		verify(manager, never()).add(any());
		marker.place(Set.of(BOOTH));
		marker.clear();
		assertEquals(WorldPointUtil.UNDEFINED, marker.pinnedTile());
	}
}
