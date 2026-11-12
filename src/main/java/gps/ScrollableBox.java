package gps;

import java.awt.*;
import java.awt.*;
import javax.swing.*;

/**
 * A panel that always lays out at the scroll viewport's width. A plain JPanel inside a JScrollPane
 * keeps its preferred width even with the horizontal scrollbar disabled, so any row slightly wider
 * than the viewport pushes the whole content under the vertical scrollbar and gets clipped.
 */
final class ScrollableBox extends JPanel implements Scrollable {
    ScrollableBox(LayoutManager layout) { super(layout); }

    @Override public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 16;
    }

    @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return Math.max(visibleRect.height - 16, 16);
    }

    @Override public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
