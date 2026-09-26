package app.umbra.ui.model;

import java.util.ArrayList;
import java.util.List;

/** Primary destinations of the bottom navigation. */
public enum HomeTab {
    CHATS("Chats", Glyph.CHAT), CALLS("Llamadas", Glyph.CALL), NEARBY("Cerca", Glyph.BLUETOOTH), SETTINGS("Ajustes", Glyph.SETTINGS);

    public final String label; public final Glyph glyph;
    HomeTab(String label, Glyph glyph) { this.label = label; this.glyph = glyph; }

    /** Offline builds never show the Calls tab (no Internet media exists in that APK). */
    public static List<HomeTab> visible(FeatureAvailability features) {
        List<HomeTab> tabs = new ArrayList<>();
        tabs.add(CHATS);
        if (features.visible(Feature.VOICE_CALLS)) tabs.add(CALLS);
        tabs.add(NEARBY);
        tabs.add(SETTINGS);
        return tabs;
    }
}
