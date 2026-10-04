package app.umbra.ui.model;

/** Settings are split in sections; no single screen carries every control. */
public enum SettingsSection {
    PROFILE("Perfil", null, Glyph.PERSON),
    SECURITY("Seguridad", null, Glyph.LOCK),
    PRIVACY("Privacidad", null, Glyph.EYE_OFF),
    DEVICES("Dispositivos", null, Glyph.DEVICES),
    NETWORK("Conectividad", null, Glyph.CLOUD),
    STORAGE("Datos y contenido", null, Glyph.TIMER),
    ADMISSION("Acceso privado", null, Glyph.DEVICE_AUTHORIZED),
    ABOUT("Acerca de UMBRA", null, Glyph.INFO);

    public final String title, subtitle; public final Glyph glyph;
    SettingsSection(String title, String subtitle, Glyph glyph) { this.title = title; this.subtitle = subtitle; this.glyph = glyph; }
}
