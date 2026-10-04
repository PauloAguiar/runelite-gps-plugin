package gps;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/**
 * Marks the items the displayed route needs with a {@link RouteItemMark} in the "Next item" colour
 * (Quest Helper's look, a ring thicker so both show on an item both plugins want): in the inventory
 * and the equipment tab the next step's item (not a worn fairy-ring staff: worn, it is done), in an
 * open bank every slot the route's bank step still has to supply. A worn-only item sitting in the bag says "Equip": the step cannot be taken until it
 * is worn.
 */
final class RouteItemOverlay extends WidgetItemOverlay {
    private final ShortestPathPlugin plugin;
    private final Map<Long, RouteItemMark> marks = new HashMap<>();
    private Color marksColour;

    RouteItemOverlay(ShortestPathPlugin plugin) {
        this.plugin = plugin;
        showOnInventory();
        showOnEquipment();
        showOnBank();
    }

    @Override
    public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem item) {
        if (!plugin.display().highlightRouteItem)
            return;
        int group = WidgetUtil.componentToInterface(item.getWidget().getId());
        if (group == InterfaceID.BANKMAIN || group == InterfaceID.SHARED_BANK) {
            if (!plugin.bankPickupIds().contains(itemId))
                return;
        }
        else {
            RouteItemCue cue = plugin.nextStepItems();
            if (cue == null || !cue.itemIds.contains(itemId) || (cue.wearToUse && group == InterfaceID.WORNITEMS))
                return;
        }
        Rectangle bounds = item.getCanvasBounds();
        Color colour = plugin.display().colourRouteItem;
        RouteItemMark mark = mark(itemId, item.getQuantity(), colour);
        graphics.drawImage(mark.outline, bounds.x - 1, bounds.y - 1, null);
        graphics.drawImage(mark.fill, bounds.x, bounds.y, null);
        if ((group == InterfaceID.INVENTORY || group == InterfaceID.BANKSIDE) && plugin.wornOnly(itemId)) {
            graphics.setFont(FontManager.getRunescapeSmallFont());
            int x = bounds.x + 1;
            int y = bounds.y + bounds.height - 2;
            graphics.setColor(Color.BLACK);
            graphics.drawString("Equip", x + 1, y + 1);
            graphics.setColor(colour);
            graphics.drawString("Equip", x, y);
        }
    }

    /** The mark for an item, built on first sight and dropped with the colour that made it. */
    private RouteItemMark mark(int itemId, int quantity, Color colour) {
        if (!colour.equals(marksColour) || marks.size() > 64) {
            marks.clear();
            marksColour = colour;
        }
        return marks.computeIfAbsent(((long) itemId << 32) | quantity,
            k -> RouteItemMark.of(plugin.getItemManager(), itemId, quantity, colour));
    }
}
