package gps;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.RoundRectangle2D;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Frames the spell the displayed route casts next in the open spellbook (see {@link SpellWidgets}),
 * in the "Next item" colour; nothing when the next step is not a spell or its spellbook is not the
 * one shown.
 */
final class SpellbookOverlay extends Overlay {
    private final Client client;
    private final ShortestPathPlugin plugin;

    SpellbookOverlay(Client client, ShortestPathPlugin plugin) {
        this.client = client;
        this.plugin = plugin;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(PRIORITY_LOW);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        RouteItemCue cue = plugin.nextStepItems();
        if (cue == null || cue.spell == null || !plugin.display().highlightRouteItem)
            return null;
        int component = SpellWidgets.componentFor(cue.spell);
        Widget spell = component < 0 ? null : client.getWidget(component);
        if (spell == null || spell.isHidden())
            return null;
        Rectangle bounds = spell.getBounds();
        graphics.setColor(plugin.display().colourRouteItem);
        graphics.setStroke(new BasicStroke(2));
        graphics.draw(new RoundRectangle2D.Double(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1, 6, 6));
        return null;
    }
}
