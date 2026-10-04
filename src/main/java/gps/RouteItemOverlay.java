package gps;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.RoundRectangle2D;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/**
 * Outlines the item the displayed route uses next, in the inventory, the equipment tab and the
 * bank (the pickup), in the directions overlay's accent so it reads as part of the route. A
 * worn-only item sitting in the bag says "Equip": the step cannot be taken until it is worn.
 */
final class RouteItemOverlay extends WidgetItemOverlay {
    private final ShortestPathPlugin plugin;

    RouteItemOverlay(ShortestPathPlugin plugin) {
        this.plugin = plugin;
        showOnInventory();
        showOnEquipment();
        showOnBank();
    }

    @Override
    public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem item) {
        RouteItemCue cue = plugin.nextStepItems();
        if (cue == null || !cue.itemIds.contains(itemId) || !plugin.display().highlightRouteItem)
            return;
        Rectangle bounds = item.getCanvasBounds();
        Color accent = plugin.display().colourOverlayAccent;
        graphics.setColor(accent);
        graphics.setStroke(new BasicStroke(2));
        graphics.draw(new RoundRectangle2D.Double(bounds.x - 1, bounds.y - 1, bounds.width + 2, bounds.height + 2, 6, 6));
        if (WidgetUtil.componentToInterface(item.getWidget().getId()) == InterfaceID.INVENTORY && plugin.wornOnly(itemId)) {
            graphics.setFont(FontManager.getRunescapeSmallFont());
            int x = bounds.x + 1;
            int y = bounds.y + bounds.height - 2;
            graphics.setColor(Color.BLACK);
            graphics.drawString("Equip", x + 1, y + 1);
            graphics.setColor(accent);
            graphics.drawString("Equip", x, y);
        }
    }
}
