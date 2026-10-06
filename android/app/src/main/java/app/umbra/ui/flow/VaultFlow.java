package app.umbra.ui.flow;

import app.umbra.core.AccessGate;
import app.umbra.crypto.Engine;
import app.umbra.data.Vault;
import app.umbra.ui.model.AccessStep;
import app.umbra.ui.model.PasswordPolicy;
import java.util.function.LongSupplier;

/**
 * Worker-thread orchestration of the real {@link Vault} password API for the entry flow. No View access,
 * no persistence and no decision of its own: every call is the documented domain operation, and the
 * caller-owned password buffers are erased in {@code finally}, including on failure.
 */
public final class VaultFlow {
    private VaultFlow() {}

    /** Domain-reported step. A locked gate propagates; unreadable protection metadata is CORRUPT (never reset). */
    public static AccessStep step(Vault vault) {
        try { return AccessStep.from(vault.getVaultState().name(), vault.isPasswordConfigured()); }
        catch (AccessGate.LockedException locked) { throw locked; }
        catch (RuntimeException damaged) { return AccessStep.CORRUPT; }
    }

    /** New install or explicit legacy enrollment. The domain finishes locked (gate included). */
    public static void createPassword(Vault vault, byte[] password) throws Exception {
        try { vault.createPassword(password); } finally { PasswordPolicy.erase(password); }
    }

    /**
     * The password opened the vault but its records could not be used afterwards. The vault has already been
     * locked again (no data key is left held without a session); nothing was deleted or reset.
     */
    public static final class RecordsUnreadable extends Exception {
        public RecordsUnreadable(Throwable cause) { super("Vault records unreadable after unlock", cause); }
    }

    /**
     * Applies the process-local auto-lock choice while still locked (domain rule), unlocks with the
     * password and builds the Engine. Connectivity starts at UNLOCKED_OFFLINE: this never connects.
     */
    public static Engine unlock(Vault vault, byte[] password, long autoLockMillis, LongSupplier elapsed) throws Exception {
        try {
            vault.setAutoLockPolicy(autoLockMillis);
            vault.unlock(password);
        } finally { PasswordPolicy.erase(password); }
        try { return open(vault, elapsed); }
        catch (AccessGate.LockedException locked) { throw locked; }
        catch (Exception unreadable) { vault.lock(); throw new RecordsUnreadable(unreadable); }
    }

    /** Legacy (not enrolled) vault: Android authentication alone opens it, as before v1. */
    public static Engine openLegacy(Vault vault, LongSupplier elapsed) throws Exception { return open(vault, elapsed); }

    /**
     * Builds the Engine after the canonical {@code vault.access().unlock(...)} reported OPENED. Connectivity starts
     * at UNLOCKED_OFFLINE (never connects). Unreadable records lock the vault again and never delete anything.
     */
    public static Engine opened(Vault vault, LongSupplier elapsed) throws Exception {
        try { return open(vault, elapsed); }
        catch (AccessGate.LockedException locked) { throw locked; }
        catch (Exception unreadable) { vault.lock(); throw new RecordsUnreadable(unreadable); }
    }

    private static Engine open(Vault vault, LongSupplier elapsed) throws Exception {
        Engine engine = new Engine(vault, elapsed);
        engine.connectivity().vaultUnlocked();
        return engine;
    }

    /** Requires the open vault; the domain verifies the current password and finishes locked. */
    public static void changePassword(Vault vault, byte[] current, byte[] replacement) throws Exception {
        try { vault.changePassword(current, replacement); }
        finally { PasswordPolicy.erase(current); PasswordPolicy.erase(replacement); }
    }
}
