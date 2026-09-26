package app.umbra.ui.model;

/** Settings are split in sections; no single screen carries every control. */
public enum SettingsSection {
    PROFILE("Perfil", "Alias e identidad pública", Glyph.PERSON),
    PRIVACY("Privacidad", "Pantalla, recientes, notificaciones", Glyph.EYE_OFF),
    SECURITY("Seguridad", "Bloqueo, bóveda e identidad", Glyph.LOCK),
    DEVICES("Dispositivos", "Este teléfono y dispositivos vinculados", Glyph.DEVICES),
    NOTIFICATIONS("Notificaciones", "Qué se muestra fuera de UMBRA", Glyph.BELL_OFF),
    NETWORK("Red", "Servidor privado y modo de conexión", Glyph.CLOUD),
    STORAGE("Almacenamiento", "Caducidad y datos locales", Glyph.TIMER),
    ABOUT("Acerca de UMBRA", "Versión y estado de las funciones", Glyph.INFO);

    public final String title, subtitle; public final Glyph glyph;
    SettingsSection(String title, String subtitle, Glyph glyph) { this.title = title; this.subtitle = subtitle; this.glyph = glyph; }
}
