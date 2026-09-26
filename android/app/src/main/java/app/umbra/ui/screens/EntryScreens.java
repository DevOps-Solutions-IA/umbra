package app.umbra.ui.screens;

import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.umbra.ui.design.UmbraColors;
import app.umbra.ui.design.UmbraType;
import app.umbra.ui.design.Ui;
import app.umbra.ui.model.*;

/** Lock and onboarding. Only real protections are described; pending ones are labeled as such. */
public final class EntryScreens {
    private EntryScreens() {}

    /** @param deviceSecure false when Android has no PIN/password/biometric (vault cannot be created). */
    public record LockState(boolean deviceSecure, boolean offlineEdition, String problem) {}
    public interface LockActions { void unlock(); void openSecuritySettings(); }

    public static Screen lock(Ui ui, LockState s, LockActions a) {
        LinearLayout body = ui.column(); body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(ui.dp(8), ui.dp(56), ui.dp(8), ui.dp(24));
        LinearLayout mark = ui.iconTile(Glyph.LOCK, Tone.ACCENT);
        mark.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)));
        mark.setBackground(ui.shape(UmbraColors.ACCENT_PRIMARY_CONTAINER, 24));
        body.addView(mark);
        TextView brand = ui.heading(UmbraType.DISPLAY, "UMBRA"); brand.setLetterSpacing(0.18f); brand.setGravity(Gravity.CENTER);
        body.addView(brand, ui.margins(Ui.match(), 20, 0));
        TextView tagline = ui.text(UmbraType.BODY_SECONDARY, "Mensajería privada entre personas que ya se conocen."); tagline.setGravity(Gravity.CENTER);
        body.addView(tagline, ui.margins(Ui.match(), 6, 28));
        LinearLayout card = ui.elevatedCard();
        LinearLayout head = ui.row(); head.addView(ui.iconView(Glyph.LOCK, UmbraColors.TEXT_PRIMARY, 20));
        TextView title = ui.heading(UmbraType.HEADING, "Bóveda bloqueada"); title.setPadding(ui.dp(10), 0, 0, 0); head.addView(title);
        card.addView(head);
        card.addView(ui.text(UmbraType.CAPTION, "Tu identidad y tus conversaciones se abren con el bloqueo de pantalla de este teléfono (Android Keystore)."), ui.margins(Ui.match(), 6, 0));
        if (s.offlineEdition()) card.addView(ui.chip(Tone.OFFLINE, Glyph.BLUETOOTH, "Modo offline · sin conexión a internet"));
        body.addView(card);
        if (!s.deviceSecure()) {
            body.addView(ui.banner(Tone.WARNING, Glyph.WARNING, "Falta un bloqueo de pantalla",
                "Activa un PIN, contraseña o biometría en Android antes de crear tu identidad.", null, null));
            body.addView(ui.button(Ui.ButtonKind.PRIMARY, "Configurar bloqueo de Android", Glyph.SETTINGS, a::openSecuritySettings));
        } else {
            if (s.problem() != null) body.addView(ui.banner(Tone.DANGER, Glyph.WARNING, "No se pudo desbloquear", s.problem(), null, null));
            body.addView(ui.button(Ui.ButtonKind.PRIMARY, "Desbloquear", Glyph.LOCK, a::unlock));
        }
        TextView foot = ui.text(UmbraType.CAPTION, "Sin número de teléfono · Sin correo · Sin agenda compartida"); foot.setGravity(Gravity.CENTER);
        body.addView(foot, ui.margins(Ui.match(), 18, 4));
        TextView dev = ui.text(UmbraType.CAPTION, "Versión de desarrollo. No auditada para uso sensible.", UmbraColors.WARNING); dev.setGravity(Gravity.CENTER);
        body.addView(dev);
        return Screen.of(null, body, null);
    }

    public interface OnboardingActions { void step(int next); void create(String alias); }

    /** Three short steps: what UMBRA is (no phone/email), verification, local identity. */
    public static Screen onboarding(Ui ui, int step, boolean offlineEdition, OnboardingActions a) {
        LinearLayout body = ui.column(); body.setPadding(ui.dp(4), ui.dp(24), ui.dp(4), ui.dp(16));
        TextView progress = ui.text(UmbraType.SECURITY_LABEL, "Paso " + (step + 1) + " de 3"); body.addView(progress);
        LinearLayout bottom = ui.column();
        switch (step) {
            case 0 -> {
                body.addView(ui.heading(UmbraType.DISPLAY, "Bienvenido a UMBRA"), ui.margins(Ui.match(), 10, 8));
                body.addView(ui.text(UmbraType.BODY_SECONDARY, "Mensajería privada para un grupo cerrado de personas autorizadas."));
                body.addView(point(ui, Glyph.PERSON, "Sin teléfono ni correo", "Tu identidad se crea en este dispositivo. No se usa tu número, tu correo ni tu agenda."));
                body.addView(point(ui, Glyph.SHIELD_CHECK, "Cifrado antes de salir", "El contenido se cifra en tu teléfono. El servidor privado solo transporta datos cifrados."));
                body.addView(point(ui, offlineEdition ? Glyph.BLUETOOTH : Glyph.CLOUD, offlineEdition ? "Edición offline" : "Conectado o cercano",
                    offlineEdition ? "Esta edición no usa internet: funciona por Bluetooth con teléfonos cercanos." : "Usa tu servidor privado o Bluetooth con teléfonos cercanos."));
                bottom.addView(ui.button(Ui.ButtonKind.PRIMARY, "Continuar", Glyph.CHEVRON, () -> a.step(1)));
            }
            case 1 -> {
                body.addView(ui.heading(UmbraType.DISPLAY, "Verifica a cada persona"), ui.margins(Ui.match(), 10, 8));
                body.addView(ui.text(UmbraType.BODY_SECONDARY, "Antes de conversar, comparen un código de seguridad en persona o por un canal en el que ya confíen."));
                LinearLayout states = ui.card();
                for (TrustLevel level : TrustLevel.values()) {
                    TrustPresentation p = TrustPresentation.of(level);
                    LinearLayout r = ui.row(); r.setPadding(0, ui.dp(6), 0, ui.dp(6));
                    r.addView(ui.trustBadge(p));
                    states.addView(r);
                    states.addView(ui.text(UmbraType.CAPTION, p.explanation()));
                }
                body.addView(states);
                bottom.addView(ui.button(Ui.ButtonKind.PRIMARY, "Entendido", Glyph.CHEVRON, () -> a.step(2)));
                bottom.addView(ui.button(Ui.ButtonKind.GHOST, "Atrás", Glyph.BACK, () -> a.step(0)));
            }
            default -> {
                body.addView(ui.heading(UmbraType.DISPLAY, "Crea tu identidad"), ui.margins(Ui.match(), 10, 8));
                body.addView(ui.text(UmbraType.BODY_SECONDARY, "Elige un alias. Solo lo verán los contactos que agregues."));
                EditText alias = ui.field("Tu alias privado");
                alias.setSingleLine(true);
                body.addView(ui.labeledField("Alias", alias));
                body.addView(ui.banner(Tone.WARNING, Glyph.WARNING, "Una identidad, este dispositivo",
                    "No hay llave maestra ni recuperación del historial. Perder el teléfono o invalidar su bloqueo puede dejar los datos inaccesibles.", null, null));
                LinearLayout pending = ui.card();
                pending.addView(ui.text(UmbraType.LABEL, "Admisión privada y contraseña personal"));
                pending.addView(ui.text(UmbraType.CAPTION, "Se integrarán aquí cuando el motor las implemente. Hoy no protegen nada y no se simulan."));
                pending.addView(ui.pendingChip());
                body.addView(pending);
                bottom.addView(ui.button(Ui.ButtonKind.PRIMARY, "Crear identidad protegida", Glyph.SHIELD_CHECK, () -> a.create(alias.getText().toString().trim())));
                bottom.addView(ui.button(Ui.ButtonKind.GHOST, "Atrás", Glyph.BACK, () -> a.step(1)));
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

    /** Future emergency lock control. Rendered disabled until the engine provides the domain action. */
    public static LinearLayout emergencyLock(Ui ui, FeatureAvailability features, Runnable trigger) {
        LinearLayout box = ui.column();
        android.widget.Button b = ui.button(Ui.ButtonKind.DESTRUCTIVE, "BLOQUEAR UMBRA", Glyph.LOCK, trigger);
        if (!features.available(Feature.EMERGENCY_LOCK)) {
            ui.disabled(b, "el bloqueo de emergencia aún no existe en el motor");
            box.addView(b); box.addView(ui.pendingChip());
            box.addView(ui.text(UmbraType.CAPTION, "Cuando exista, disparará una única acción del motor y mostrará la confirmación del estado local."));
        } else box.addView(b);
        return box;
    }

    /** Future private-startup indicator; only renders a claim when the engine reports one. */
    public static LinearLayout privateStartup(Ui ui, PrivateStartupState state) {
        LinearLayout box = ui.card();
        box.addView(ui.text(UmbraType.LABEL, "Inicio privado"));
        if (state == PrivateStartupState.UNAVAILABLE) {
            box.addView(ui.text(UmbraType.CAPTION, "Mostrará «UMBRA bloqueada · Sin conexión» cuando el motor lo implemente. Hoy la red se usa solo tras desbloquear y según tu elección en Red."));
            box.addView(ui.pendingChip());
        } else {
            box.addView(ui.chip(Tone.OFFLINE, Glyph.CLOUD_OFF, state == PrivateStartupState.LOCKED_NO_NETWORK ? "UMBRA bloqueada · Sin conexión" : "Desbloqueada"));
        }
        return box;
    }

}
