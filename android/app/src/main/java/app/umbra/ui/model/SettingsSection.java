package app.umbra.ui.model;

/** Settings are split in sections; no single screen carries every control. */
public enum SettingsSection {
    PROFILE("Perfil", null, Glyph.PERSON),
    PRIVACY("Privacidad", null, Glyph.EYE_OFF),
    SECURITY("Seguridad", null, Glyph.LOCK),
    DEVICES("Dispositivos", null, Glyph.DEVICES),
    ADMISSION("Admisión", null, Glyph.DEVICE_AUTHORIZED),
    NOTIFICATIONS("Notificaciones", null, Glyph.BELL_OFF),
    NETWORK("Red", null, Glyph.CLOUD),
    STORAGE("Datos", null, Glyph.TIMER),
    ABOUT("Acerca de", null, Glyph.INFO);

    public final String title, subtitle; public final Glyph glyph;
    SettingsSection(String title, String subtitle, Glyph glyph) { this.title = title; this.subtitle = subtitle; this.glyph = glyph; }
}
