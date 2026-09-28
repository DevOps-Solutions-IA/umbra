package app.umbra.ui.model;

/**
 * What the entry flow must ask for after Android system authentication, derived only from the
 * domain-reported vault state and protection metadata. A step is never an authorization: the
 * domain re-checks the gate, password and lease on every operation.
 */
public enum AccessStep {
    /** No local database yet: create the personal password before any identity is stored. */
    CREATE_PASSWORD(Tone.ACCENT, Glyph.PASSWORD, "Nueva contraseña", "Sin recuperación si la olvidas."),
    /** Existing vault protected only by the device key (before v1). Stays legacy until explicit enrollment. */
    LEGACY_ENROLLMENT(Tone.WARNING, Glyph.VAULT_LOCKED, "Añadir contraseña", "Hoy solo usa el bloqueo de Android."),
    /** Password configured and vault locked: Android authentication alone does not open it. */
    PASSWORD_UNLOCK(Tone.NEUTRAL, Glyph.VAULT_LOCKED, "Contraseña", ""),
    /** Vault open in this process; the Activity may build the Engine. */
    OPEN(Tone.SUCCESS, Glyph.VAULT_UNLOCKED, "Bóveda abierta", ""),
    /** Local protection metadata is damaged. Never reset automatically. */
    CORRUPT(Tone.DANGER, Glyph.WARNING, "Bóveda dañada", "No se borró ni se reinició nada."),
    /** Android key missing or invalidated. Never regenerated automatically. */
    KEY_UNAVAILABLE(Tone.DANGER, Glyph.WARNING, "Clave no disponible", "No se borró nada ni se generó otra clave.");

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
    public static final String LOCKED_AFTER_CREATE = "Contraseña creada. Bóveda bloqueada.";
    public static final String LOCKED_AFTER_CHANGE = "Contraseña cambiada. Bóveda bloqueada.";
    /** Generic by design: the domain does not reveal whether the password or the tag failed. */
    public static final String UNLOCK_FAILED = "No se pudo abrir. Revisa la contraseña.";
    /** The password was accepted but the stored records could not be read; the vault was locked again. */
    public static final String RECORDS_UNREADABLE = "Registros ilegibles. Bóveda bloqueada; no se borró nada.";
}
