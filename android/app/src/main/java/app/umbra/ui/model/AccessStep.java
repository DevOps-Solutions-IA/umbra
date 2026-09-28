package app.umbra.ui.model;

/**
 * What the entry flow must ask for after Android system authentication, derived only from the
 * domain-reported vault state and protection metadata. A step is never an authorization: the
 * domain re-checks the gate, password and lease on every operation.
 */
public enum AccessStep {
    /** No local database yet: create the personal password before any identity is stored. */
    CREATE_PASSWORD(Tone.ACCENT, Glyph.PASSWORD, "Crea tu contraseña personal",
        "Protege la bóveda de este teléfono además del bloqueo de Android. No hay recuperación: si la olvidas, los datos quedan inaccesibles."),
    /** Existing vault protected only by the device key (before v1). Stays legacy until explicit enrollment. */
    LEGACY_ENROLLMENT(Tone.WARNING, Glyph.VAULT_LOCKED, "Añade una contraseña personal",
        "Esta bóveda solo depende del bloqueo de Android. Puedes inscribirla ahora; se volverá a cifrar localmente y terminará bloqueada."),
    /** Password configured and vault locked: Android authentication alone does not open it. */
    PASSWORD_UNLOCK(Tone.NEUTRAL, Glyph.VAULT_LOCKED, "Contraseña personal",
        "Android ya confirmó que eres tú en este teléfono. Falta tu contraseña personal para abrir la bóveda."),
    /** Vault open in this process; the Activity may build the Engine. */
    OPEN(Tone.SUCCESS, Glyph.VAULT_UNLOCKED, "Bóveda abierta", "La bóveda está abierta en esta sesión."),
    /** Local protection metadata is damaged. Never reset automatically. */
    CORRUPT(Tone.DANGER, Glyph.WARNING, "La bóveda está dañada",
        "UMBRA no pudo leer la protección local. No se borró ni se reinició nada. No existe recuperación automática."),
    /** Android key missing or invalidated. Never regenerated automatically. */
    KEY_UNAVAILABLE(Tone.DANGER, Glyph.WARNING, "Clave del dispositivo no disponible",
        "Android ya no entrega la clave de esta bóveda (por ejemplo, tras cambiar el bloqueo de pantalla). No se generó otra clave ni se borraron datos.");

    public final Tone tone; public final Glyph glyph; public final String title, body;
    AccessStep(Tone tone, Glyph glyph, String title, String body) { this.tone = tone; this.glyph = glyph; this.title = title; this.body = body; }

    /**
     * @param vaultState         {@code Vault.State} name as reported by the domain
     * @param passwordConfigured {@code Vault.isPasswordConfigured()}
     */
    public static AccessStep from(String vaultState, boolean passwordConfigured) {
        if ("CORRUPT".equals(vaultState)) return CORRUPT;
        if ("KEY_UNAVAILABLE".equals(vaultState)) return KEY_UNAVAILABLE;
        if ("UNINITIALIZED".equals(vaultState)) return CREATE_PASSWORD;
        if (!passwordConfigured) return LEGACY_ENROLLMENT;
        if ("UNLOCKED".equals(vaultState)) return OPEN;
        // LOCKED, UNLOCKING, LOCKING and unknown values fail closed to the password prompt.
        return PASSWORD_UNLOCK;
    }

    public boolean terminalFailure() { return this == CORRUPT || this == KEY_UNAVAILABLE; }

    /** Shown after create/change: both operations end with the vault locked by the domain. */
    public static final String LOCKED_AFTER_CREATE = "Contraseña creada. La bóveda quedó bloqueada: desbloquea con Android y tu contraseña.";
    public static final String LOCKED_AFTER_CHANGE = "Contraseña cambiada. La bóveda quedó bloqueada: desbloquea con Android y la nueva contraseña.";
    /** Generic by design: the domain does not reveal whether the password or the tag failed. */
    public static final String UNLOCK_FAILED = "No se pudo abrir la bóveda. Revisa la contraseña e inténtalo de nuevo.";
    /** The password was accepted but the stored records could not be read; the vault was locked again. */
    public static final String RECORDS_UNREADABLE = "La contraseña abrió la bóveda, pero sus registros no se pudieron leer. Se volvió a bloquear; no se borró ni se reinició nada.";
}
