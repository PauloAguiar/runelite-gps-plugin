package gps;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;

/**
 * Outlines the item the displayed route uses next, in the inventory, the equipment tab and the
 * bank (the pickup): the sprite's own outline plus a light fill in the "Next item" colour, the
 * way Quest Helper marks its items, so the two read alike. A worn-only item sitting in the bag
 * says "Equip": the step cannot be taken until it is worn.
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
        Color colour = plugin.display().colourRouteItem;
        BufferedImage outline = plugin.getItemManager().getItemOutline(itemId, item.getQuantity(), colour);
        graphics.drawImage(outline, bounds.x, bounds.y, null);
        Image fill = ImageUtil.fillImage(plugin.getItemManager().getImage(itemId, item.getQuantity(), false),
            ColorUtil.colorWithAlpha(colour, 65));
        graphics.drawImage(fill, bounds.x, bounds.y, null);
        if (WidgetUtil.componentToInterface(item.getWidget().getId()) == InterfaceID.INVENTORY && plugin.wornOnly(itemId)) {
            graphics.setFont(FontManager.getRunescapeSmallFont());
            int x = bounds.x + 1;
            int y = bounds.y + bounds.height - 2;
            graphics.setColor(Color.BLACK);
            graphics.drawString("Equip", x + 1, y + 1);
            graphics.setColor(colour);
            graphics.drawString("Equip", x, y);
        }
    }
}
