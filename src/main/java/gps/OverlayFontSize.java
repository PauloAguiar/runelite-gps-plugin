package gps;

/**
 * Text-size presets for the directions overlay. The RuneScape fonts are pixel-art TTFs designed for
 * size 16, which stay sharpest at their native size or an exact pixel-doubling — so Small shifts the
 * three-tier hierarchy down onto the native small face (16), Normal keeps the native faces (16), and
 * Extra large pixel-doubles everything to 32. Large sits between at 24 (1.5x): a genuine middle step,
 * a touch softer than the exact-multiple sizes since the pixel grid no longer lands on whole screen
 * pixels, but the most-requested in-between.
 */
public enum OverlayFontSize {
    SMALL("Small"),
    NORMAL("Normal"),
    LARGE("Large"),
    EXTRA_LARGE("Extra large");

    private final String label;

    OverlayFontSize(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
