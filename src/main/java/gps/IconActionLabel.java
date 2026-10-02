package gps;

import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * A clickable icon control, modelled on the tile-packs panel action labels: shows a base icon, swaps
 * to a hover icon while the cursor is over it, runs an action on left-click, and carries a tooltip.
 */
class IconActionLabel extends JLabel {
    // The at-rest icon, swappable so a container (e.g. a route row) can reveal the control on its
    // own hover; the direct-hover icon still wins while the cursor is over the control itself.
    private ImageIcon restIcon;
    private boolean hovered;

    IconActionLabel(ImageIcon icon, ImageIcon hoverIcon, String tooltip, Runnable onClick) {
        this.restIcon = icon;
        setIcon(icon);
        setToolTipText(tooltip);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e))
                    onClick.run();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                hovered = true;
                setIcon(hoverIcon);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = false;
                setIcon(restIcon);
            }
        });
    }

    /** Sets the at-rest icon; applied immediately unless the cursor is directly over this control. */
    void setRestIcon(ImageIcon icon) {
        restIcon = icon;
        if (!hovered)
            setIcon(icon);
    }
}
