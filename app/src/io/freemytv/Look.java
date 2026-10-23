package io.freemytv;

/**
 * Your own look: the color that marks the focused item, and how the glass reads. "Chalet": light oak, white fur and
 * warm lamp light (the room the TV is in), with black text and clear Liquid Glass. The original design is ACCENT
 * 0xFFF5F5F7, RING 0, CLEAR 0xFFFFFFFF, SMOKE 0xFF111113, the gray gradients below and every factor 1.
 */
final class Look {
    private Look() {}

    /** Fill of the focused button, tab, drawer item and settings row (light colors: the text on it stays dark). */
    static final int ACCENT = 0xFFFBF7F1;
    /** A thin ring of this color around the focused app on the shelf; 0: none. */
    static final int RING = 0xFFB8875C;
    /** The glass's own color: by day (warm white) and at night (warm charcoal). */
    static final int CLEAR = 0xFFFFF8EF, SMOKE = 0xFF1A1613;
    /** Background without a photo: top and bottom of the gradient, by day and at night. */
    static final int DAY_TOP = 0xFFF6F1EA, DAY_BOTTOM = 0xFFE4D7C8, NIGHT_TOP = 0xFF1E1A17, NIGHT_BOTTOM = 0xFF0C0A09;
    /**
     * The glass's tint: below 1 clearer (more of the photo shows through), above 1 frostier. GLASS for the controls
     * (shelf, buttons: Liquid Glass), PANEL for the drawer's cards, which hold small text (as Apple does: clear glass
     * over the content, frosted where there is text to read).
     */
    static final float GLASS = 0.3f, PANEL = 0.75f;
    /**
     * By day the glass shows the photo behind it whitened (the frosted look); MILK scales how much: below 1 the photo's
     * own colors come through.
     */
    static final float MILK = 0.5f;
    /** How solid the focused button, tab and drawer item are: 1 opaque, lower lets the glass show through. */
    static final float FOCUS = 0.7f;
    /** Your photos (Photos) also as the home screen's background; false: only the screensaver shows them. */
    static final boolean PHOTO_HOME = false;
    /** Liquid Glass (Android 13+): how much the rim bends the image behind it, and its highlight. */
    static final float REFRACT = 1.8f, SHINE = 1.6f;

    static float tint(float alpha) {
        return Math.min(1, alpha * GLASS);
    }

    static float panel(float alpha) {
        return Math.min(1, alpha * PANEL);
    }
}
