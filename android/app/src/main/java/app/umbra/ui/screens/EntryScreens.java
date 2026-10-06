package app.umbra.ui.screens;

import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.umbra.ui.design.UmbraColors;
import app.umbra.ui.design.UmbraType;
import app.umbra.ui.design.Ui;
import app.umbra.ui.model.*;

/** Lock and onboarding. Only real protections are described; pending ones are labeled as such. */
public final class EntryScreens {
    private EntryScreens() {}

    /**
     * @param deviceSecure false when Android has no PIN/password/biometric (vault cannot be created)
     * @param notice       neutral result of the last operation (e.g. password created, vault locked again)
     */
    /**
     * @param reason         why it locked, from the access coordinator's lock cause (null: nothing useful to say)
     * @param authenticating an Android authentication prompt is in progress (the button shows that phase)
     */
    public record LockState(boolean deviceSecure, boolean offlineEdition, String problem, String notice, String reason, boolean authenticating) {
        public LockState(boolean deviceSecure, boolean offlineEdition, String problem, String notice) { this(deviceSecure, offlineEdition, problem, notice, null, false); }
    }
    public interface LockActions { void unlock(); void openSecuritySettings(); }

    public static Screen lock(Ui ui, LockState s, LockActions a) {
        LinearLayout body = ui.column(); body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(ui.dp(8), ui.dp(96), ui.dp(8), ui.dp(16));
        body.addView(ui.logo(72, UmbraColors.ACCENT_MUTED));
        TextView brand = ui.heading(UmbraType.DISPLAY, "UMBRA"); brand.setLetterSpacing(0.22f); brand.setGravity(Gravity.CENTER);
        body.addView(brand, ui.margins(Ui.match(), 20, 8));
        // One quiet status line; the cause is said once, only when it helps.
        TextView status = ui.text(UmbraType.BODY_SECONDARY, s.reason() != null ? s.reason() : "UMBRA bloqueado");
        status.setGravity(Gravity.CENTER); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(status, Ui.match());
        if (s.notice() != null) body.addView(ui.banner(Tone.NEUTRAL, Glyph.INFO, s.notice(), null, null, null), ui.margins(Ui.match(), 16, 0));
        if (s.problem() != null) body.addView(ui.banner(Tone.DANGER, Glyph.WARNING, s.problem(), null, null, null), ui.margins(Ui.match(), 16, 0));
        LinearLayout bottom = ui.column();
        if (!s.deviceSecure()) {
            bottom.addView(ui.banner(Tone.WARNING, Glyph.WARNING, "Falta bloqueo de pantalla", "Activa PIN, contraseña o biometría en Android.", null, null));
            bottom.addView(ui.button(Ui.ButtonKind.PRIMARY, "Configurar bloqueo", Glyph.SETTINGS, a::openSecuritySettings));
        } else {
            Button unlock = ui.button(Ui.ButtonKind.PRIMARY, "Desbloquear", Glyph.UNLOCK, a::unlock);
            if (s.authenticating()) ui.busy(unlock, "Esperando a Android…");
            bottom.addView(unlock);
        }
        TextView dev = ui.text(UmbraType.CAPTION, "VERSIÓN DE DESARROLLO", UmbraColors.TEXT_TERTIARY); dev.setGravity(Gravity.CENTER); dev.setLetterSpacing(0.08f);
        bottom.addView(dev, ui.margins(Ui.match(), 10, 0));
        return Screen.of(null, body, bottom);
    }

    public interface OnboardingActions {
        void step(int next); void create(String alias);
        /** After the identity exists: configure the private connection (connected builds), add a contact, or finish. */
        default void privateConnection() {} default void addContact() {} default void finish() {}
    }

    /** Three short steps: what UMBRA is, verification, local identity. */
    /**
     * Messaging setup, not PKI: 1) UMBRA protected (the password was just created), 2) local identity (alias),
     * 3) ready: configure the private connection (connected) or add a contact. Identity creation never admits,
     * connects or verifies anything.
     */
    public static Screen onboarding(Ui ui, int step, boolean offlineEdition, OnboardingActions a) {
        LinearLayout body = ui.column(); body.setPadding(ui.dp(4), ui.dp(24), ui.dp(4), ui.dp(16));
        body.addView(ui.text(UmbraType.SECURITY_LABEL, "Paso " + (Math.min(step, 2) + 1) + " de 3"));
        LinearLayout bottom = ui.column();
        switch (step) {
            case 0 -> {
                body.addView(ui.logo(48, UmbraColors.ACCENT_MUTED), ui.margins(new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)), 16, 0));
                body.addView(ui.heading(UmbraType.DISPLAY, "UMBRA protegido"), ui.margins(Ui.match(), 10, 8));
                body.addView(point(ui, Glyph.PERSON, "Sin teléfono ni correo", "Identidad creada en este dispositivo."));
                body.addView(point(ui, Glyph.SHIELD_CHECK, "Cifrado en el teléfono", "El servidor solo transporta datos cifrados."));
                body.addView(point(ui, offlineEdition ? Glyph.OFFLINE_BLUETOOTH : Glyph.CLOUD, offlineEdition ? "Sin internet" : "Conexión privada o cercana",
                    offlineEdition ? "Solo Bluetooth con teléfonos cercanos." : "Tu servidor privado o Bluetooth cercano."));
                bottom.addView(ui.button(Ui.ButtonKind.PRIMARY, "Continuar", Glyph.CHEVRON, () -> a.step(1)));
            }
            case 1 -> {
                body.addView(ui.heading(UmbraType.DISPLAY, "Tu identidad"), ui.margins(Ui.match(), 10, 8));
                EditText alias = ui.field("Alias privado");
                alias.setSingleLine(true);
                body.addView(ui.labeledField("Alias", alias));
                body.addView(ui.banner(Tone.WARNING, Glyph.WARNING, "Sin recuperación", "Si pierdes el teléfono, pierdes los datos.", null, null));
                body.addView(ui.chip(Tone.NEUTRAL, offlineEdition ? Glyph.PERSON_ADD : Glyph.DEVICE_PENDING,
                    offlineEdition ? "Después: contactos" : "Después: conexión privada"));
                bottom.addView(ui.button(Ui.ButtonKind.PRIMARY, "Crear identidad", Glyph.SHIELD_CHECK, () -> a.create(alias.getText().toString().trim())));
                bottom.addView(ui.button(Ui.ButtonKind.GHOST, "Atrás", Glyph.BACK, () -> a.step(0)));
            }
            default -> {
                LinearLayout tile = ui.iconTile(Glyph.CHECK, Tone.SUCCESS); tile.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)));
                body.addView(tile, ui.margins(new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)), 16, 0));
                body.addView(ui.heading(UmbraType.DISPLAY, "Listo"), ui.margins(Ui.match(), 10, 8));
                body.addView(ui.text(UmbraType.BODY_SECONDARY, "Los contactos quedan sin verificar hasta comparar el código."));
                if (!offlineEdition) {
                    bottom.addView(ui.button(Ui.ButtonKind.PRIMARY, "Configurar conexión", Glyph.CLOUD, a::privateConnection));
                    bottom.addView(ui.button(Ui.ButtonKind.SECONDARY, "Agregar contacto", Glyph.PERSON_ADD, a::addContact));
                } else bottom.addView(ui.button(Ui.ButtonKind.PRIMARY, "Agregar contacto", Glyph.PERSON_ADD, a::addContact));
                bottom.addView(ui.button(Ui.ButtonKind.GHOST, "Ir a chats", Glyph.CHAT, a::finish));
            }
        }
        return Screen.of(null, body, bottom);
    }
    private static LinearLayout point(Ui ui, Glyph glyph, String title, String body) {
        LinearLayout r = ui.row(); r.setGravity(Gravity.TOP); r.setPadding(0, ui.dp(16), 0, 0);
        r.addView(ui.iconTile(glyph, Tone.ACCENT));
        LinearLayout t = ui.column(); t.setPadding(ui.dp(14), 0, 0, 0);
        t.addView(ui.text(UmbraType.HEADING, title)); t.addView(ui.text(UmbraType.CAPTION, body));
        r.addView(t, Ui.weight());
        return r;
    }

    /**
     * Emergency lock control: one explicit action, no password. The trigger must hide sensitive content
     * first and then request the domain closure (engine.emergencyLock()); it never waits for a server.
     */
    public static LinearLayout emergencyLock(Ui ui, FeatureAvailability features, Runnable trigger) {
        LinearLayout box = ui.column();
        android.widget.Button b = ui.button(Ui.ButtonKind.DESTRUCTIVE, "Bloqueo de emergencia", Glyph.EMERGENCY_LOCK, trigger);
        if (!features.available(Feature.EMERGENCY_LOCK)) {
            ui.disabled(b, "próximamente");
            box.addView(b); box.addView(ui.pendingChip());
        } else box.addView(b);
        return box;
    }

    /**
     * Closure progress reported by the domain coordinator. Content is already hidden when this is shown.
     * "Desbloquear" is offered only after CLOSED; INCOMPLETE keeps access denied.
     */
    public static Screen emergencyStatus(Ui ui, EmergencyPresentation p, Runnable unlock) {
        LinearLayout body = ui.column(); body.setGravity(Gravity.CENTER_HORIZONTAL); body.setPadding(ui.dp(8), ui.dp(56), ui.dp(8), ui.dp(8));
        body.addView(ui.logo(56, UmbraColors.ACCENT_MUTED));
        TextView title = ui.heading(UmbraType.DISPLAY, p.title()); title.setGravity(Gravity.CENTER);
        title.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        body.addView(title, ui.margins(Ui.match(), 20, 6));
        LinearLayout chipRow = ui.row(); chipRow.setGravity(Gravity.CENTER);
        chipRow.addView(ui.chip(p.tone(), p.glyph(), "Emergencia"));
        body.addView(chipRow, Ui.match());
        if (!p.body().isEmpty()) { TextView line = ui.text(UmbraType.BODY_SECONDARY, p.body()); line.setGravity(Gravity.CENTER); body.addView(line, ui.margins(Ui.match(), 10, 0)); }
        LinearLayout card = ui.card();
        for (EmergencyPresentation.Line line : p.lines()) {
            LinearLayout r = ui.row(); r.setPadding(0, ui.dp(6), 0, ui.dp(6));
            r.addView(ui.text(UmbraType.CAPTION, line.subsystem()), Ui.weight());
            r.addView(ui.chip(line.tone(), line.tone() == Tone.SUCCESS ? Glyph.CHECK : line.tone() == Tone.DANGER ? Glyph.WARNING : Glyph.TIMER, line.outcome()));
            r.setContentDescription(line.subsystem() + ": " + line.outcome());
            card.addView(r);
        }
        if (!p.lines().isEmpty()) body.addView(card, ui.margins(Ui.match(), 16, 0));
        LinearLayout bottom = ui.column();
        LinearLayout help = ui.row(); help.setGravity(Gravity.CENTER); help.addView(ui.helpButton(Help.EMERGENCY)); bottom.addView(help, Ui.match());
        if (p.allowsNewAuthentication()) bottom.addView(ui.button(Ui.ButtonKind.PRIMARY, "Desbloquear", Glyph.UNLOCK, unlock));
        return Screen.of(null, body, bottom);
    }
}
