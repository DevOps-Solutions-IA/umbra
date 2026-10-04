package app.umbra.ui.model;

import java.util.ArrayList;
import java.util.List;

/** Primary destinations of the bottom navigation. */
public enum HomeTab {
    CHATS("Chats", Glyph.CHAT), CALLS("Llamadas", Glyph.CALL), NEARBY("Cerca", Glyph.BLUETOOTH), SETTINGS("Ajustes", Glyph.SETTINGS);

    public final String label; public final Glyph glyph;
    HomeTab(String label, Glyph glyph) { this.label = label; this.glyph = glyph; }

    /**
     * Chat-first hierarchy. Offline builds never show Calls (no Internet media) and keep Nearby as a tab because it
     * is their transport; connected builds reach Nearby from Ajustes → Conectividad and from "Agregar contacto".
     */
    public static List<HomeTab> visible(FeatureAvailability features) {
        List<HomeTab> tabs = new ArrayList<>();
        tabs.add(CHATS);
        if (features.visible(Feature.VOICE_CALLS)) tabs.add(CALLS);
        if (!features.connected()) tabs.add(NEARBY);
        tabs.add(SETTINGS);
        return tabs;
    }
}
