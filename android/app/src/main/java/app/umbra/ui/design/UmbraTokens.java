package app.umbra.ui.design;

/**
 * Single source of spacing, radii, component heights, opacity and motion for the View-based UI.
 * Values are dp/ms; {@code res/values/dimens.xml} and {@code res/values/integers.xml} mirror them for
 * resources (UiDesignTokensTest keeps both identical). Screens and components use these names instead
 * of magic numbers so the whole product keeps one rhythm.
 */
public final class UmbraTokens {
    private UmbraTokens() {}

    /** 4-based spacing scale. */
    public static final int SPACE_4 = 4, SPACE_8 = 8, SPACE_12 = 12, SPACE_16 = 16, SPACE_24 = 24, SPACE_32 = 32,
        SPACE_40 = 40, SPACE_48 = 48;
    /** Screen edge gutter and vertical rhythm between blocks. */
    public static final int GUTTER = SPACE_16, BLOCK_GAP = SPACE_8, SECTION_GAP = SPACE_24;

    /** Corner radii. */
    public static final int RADIUS_SMALL = 8, RADIUS_CONTROL = 14, RADIUS_CARD = 18, RADIUS_SHEET = 24, RADIUS_PILL = 100;

    /** Component heights. Every interactive target is at least TOUCH_MIN. */
    public static final int TOUCH_MIN = 48, BUTTON_HEIGHT = 52, FIELD_HEIGHT = 52, ROW_HEIGHT = 64, APP_BAR_HEIGHT = 56,
        NAV_HEIGHT = 56, AVATAR = 48, ICON = 24, ICON_SMALL = 20, ICON_TINY = 16, SPINNER = 18;

    /** Hairline for dividers/borders. */
    public static final float HAIRLINE = 0.75f, BORDER = 1f;

    /** State opacity. */
    public static final float OPACITY_DISABLED = 0.5f, OPACITY_PRESSED_OVERLAY = 0.2f, OPACITY_BUSY_LABEL = 0.85f;

    /** Motion (ms). Short, never blocking: security work is never delayed for an animation. */
    public static final int MOTION_FAST = 120, MOTION_STANDARD = 200, MOTION_EMPHASIS = 280, MOTION_SPINNER_TURN = 900,
        MOTION_SKELETON_PULSE = 900;

    /** Polling/backoff for foreground-only pairing progression (ms). */
    public static final int PAIRING_POLL_FIRST = 1500, PAIRING_POLL_MAX = 8000;
}
