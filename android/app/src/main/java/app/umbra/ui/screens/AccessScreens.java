package app.umbra.ui.screens;

import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.umbra.ui.design.UmbraColors;
import app.umbra.ui.design.UmbraType;
import app.umbra.ui.design.Ui;
import app.umbra.ui.model.*;

/**
 * Personal password screens shown after Android authentication: create/enroll, unlock, change and the
 * terminal failure states. Screens hand the EditText to the host; the host reads it on the main thread,
 * converts it to caller-owned UTF-8 bytes, clears the field and performs the domain call on its worker.
 * Nothing here stores, logs or restores a password.
 */
public final class AccessScreens {
    private AccessScreens() {}

    public record CreateState(boolean legacy, boolean busy, String problem) {}
    public interface CreateActions { void submit(EditText password, EditText confirmation); void later(); void lockNow(); }

    public record UnlockState(boolean busy, String problem, int autoLockIndex, boolean offlineEdition) {}
    public interface UnlockActions { void submit(EditText password); void autoLock(int index); void lockNow(); }

    public record ChangeState(boolean busy, String problem) {}
    public interface ChangeActions { void back(); void submit(EditText current, EditText replacement, EditText confirmation); }

    /** Secret input with a visible label bound for TalkBack; the label never contains the value. */
    private static EditText secret(Ui ui, LinearLayout body, String label, String hint) {
        EditText[] holder = new EditText[1];
        LinearLayout field = ui.passwordField(hint, e -> holder[0] = e);
        TextView l = ui.text(UmbraType.CAPTION, label);
        if (holder[0].getId() == View.NO_ID) holder[0].setId(View.generateViewId());
        l.setLabelFor(holder[0].getId());
        body.addView(l, ui.margins(Ui.match(), 10, 0));
        body.addView(field);
        return holder[0];
    }
    private static LinearLayout header(Ui ui, AccessStep step) {
        LinearLayout body = ui.column(); body.setPadding(ui.dp(4), ui.dp(32), ui.dp(4), ui.dp(8));
        body.addView(ui.logo(40, UmbraColors.ACCENT_MUTED), ui.margins(new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)), 0, 12));
        LinearLayout head = ui.row(); head.addView(ui.iconView(step.glyph, UmbraColors.TEXT_PRIMARY, 22));
        TextView title = ui.heading(UmbraType.TITLE, step.title); title.setPadding(ui.dp(10), 0, 0, 0); head.addView(title, Ui.weight());
        body.addView(head);
        body.addView(ui.text(UmbraType.BODY_SECONDARY, step.body), ui.margins(Ui.match(), 6, 4));
        return body;
    }
    private static void busy(Ui ui, LinearLayout body, boolean busy, String problem) {
        if (problem != null) body.addView(ui.banner(Tone.DANGER, Glyph.WARNING, "No se completó", problem, null, null));
        if (busy) {
            TextView t = ui.text(UmbraType.CAPTION, "Comprobando en este teléfono… puede tardar unos segundos.");
            t.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            body.addView(t, ui.margins(Ui.match(), 8, 0));
        }
    }

    public static Screen create(Ui ui, CreateState s, CreateActions a) {
        LinearLayout body = header(ui, s.legacy() ? AccessStep.LEGACY_ENROLLMENT : AccessStep.CREATE_PASSWORD);
        EditText password = secret(ui, body, "Contraseña personal", "Al menos " + PasswordPolicy.MIN_BYTES + " caracteres");
        EditText confirmation = secret(ui, body, "Repite la contraseña", "Repite la contraseña");
        body.addView(ui.text(UmbraType.CAPTION, "Se usa tal como la escribes: no se recortan espacios ni se normaliza. "
            + "Mínimo " + PasswordPolicy.MIN_BYTES + " y máximo " + PasswordPolicy.MAX_BYTES + " bytes en UTF-8. El largo no garantiza que sea fuerte."), ui.margins(Ui.match(), 8, 0));
        body.addView(ui.banner(Tone.WARNING, Glyph.WARNING, "Sin recuperación",
            "UMBRA no guarda una copia ni una pista. Si la olvidas, los datos de este teléfono quedan inaccesibles.", null, null));
        busy(ui, body, s.busy(), s.problem());
        LinearLayout bottom = ui.column();
        Button submit = ui.button(Ui.ButtonKind.PRIMARY, s.legacy() ? "Inscribir con contraseña" : "Crear contraseña", Glyph.PASSWORD, () -> a.submit(password, confirmation));
        if (s.busy()) ui.disabled(submit, "operación en curso");
        bottom.addView(submit);
        bottom.addView(ui.text(UmbraType.CAPTION, "Al terminar, la bóveda queda bloqueada y tendrás que desbloquearla de nuevo."), ui.margins(Ui.match(), 4, 0));
        if (s.legacy()) {
            Button later = ui.button(Ui.ButtonKind.GHOST, "Ahora no (seguir solo con el bloqueo de Android)", Glyph.CLOSE, a::later);
            if (s.busy()) ui.disabled(later, "operación en curso");
            bottom.addView(later);
        }
        bottom.addView(ui.button(Ui.ButtonKind.GHOST, "Bloquear", Glyph.LOCK, a::lockNow));
        return Screen.of(null, body, bottom);
    }

    public static Screen unlock(Ui ui, UnlockState s, UnlockActions a) {
        LinearLayout body = header(ui, AccessStep.PASSWORD_UNLOCK);
        EditText password = secret(ui, body, "Contraseña personal", "Contraseña personal");
        body.addView(ui.text(UmbraType.SECURITY_LABEL, "Autobloqueo de esta sesión"), ui.margins(Ui.match(), 14, 0));
        body.addView(ui.segmented(PasswordPolicy.AUTO_LOCK_LABELS, s.autoLockIndex(), null, a::autoLock));
        body.addView(ui.text(UmbraType.CAPTION, "Se aplica solo mientras UMBRA siga abierta; no se guarda. El máximo es 4 minutos y salir de la app siempre bloquea."));
        body.addView(ui.chip(Tone.NEUTRAL, Glyph.NETWORK_OFF, s.offlineEdition() ? "Modo offline · sin conexión" : "Desbloquear no conecta"));
        busy(ui, body, s.busy(), s.problem());
        LinearLayout bottom = ui.column();
        Button submit = ui.button(Ui.ButtonKind.PRIMARY, "Abrir bóveda", Glyph.UNLOCK, () -> a.submit(password));
        if (s.busy()) ui.disabled(submit, "operación en curso");
        bottom.addView(submit);
        bottom.addView(ui.button(Ui.ButtonKind.GHOST, "Bloquear", Glyph.LOCK, a::lockNow));
        return Screen.of(null, body, bottom);
    }

    public static Screen change(Ui ui, ChangeState s, ChangeActions a) {
        LinearLayout top = ui.column();
        top.addView(ui.topBar(a::back, ui.titleBlock("Cambiar contraseña", null)));
        LinearLayout body = ui.column();
        EditText current = secret(ui, body, "Contraseña actual", "Contraseña actual");
        EditText next = secret(ui, body, "Contraseña nueva", "Al menos " + PasswordPolicy.MIN_BYTES + " caracteres");
        EditText confirmation = secret(ui, body, "Repite la contraseña nueva", "Repite la contraseña nueva");
        body.addView(ui.text(UmbraType.CAPTION, "Tu identidad, tus conversaciones y su cifrado no cambian. Al terminar, la bóveda queda bloqueada."), ui.margins(Ui.match(), 8, 0));
        body.addView(ui.text(UmbraType.CAPTION, "Una copia antigua completa del almacenamiento seguiría abriéndose con la contraseña anterior.", UmbraColors.WARNING_FG), ui.margins(Ui.match(), 4, 0));
        busy(ui, body, s.busy(), s.problem());
        Button submit = ui.button(Ui.ButtonKind.PRIMARY, "Cambiar y bloquear", Glyph.CHANGE_PASSWORD, () -> a.submit(current, next, confirmation));
        if (s.busy()) ui.disabled(submit, "operación en curso");
        LinearLayout bottom = ui.column(); bottom.addView(submit);
        return Screen.of(top, body, bottom);
    }

    /** CORRUPT / KEY_UNAVAILABLE: explanation only. No reset, reinitialization or recovery is offered. */
    public static Screen failure(Ui ui, AccessStep step, Runnable lockNow) {
        LinearLayout body = header(ui, step);
        body.setGravity(Gravity.START);
        body.addView(ui.banner(Tone.DANGER, Glyph.WARNING, "Sin acciones automáticas",
            "UMBRA no borra, no reinicia la identidad y no crea claves nuevas para ocultar este problema.", null, null));
        LinearLayout bottom = ui.column();
        bottom.addView(ui.button(Ui.ButtonKind.PRIMARY, "Bloquear", Glyph.LOCK, lockNow));
        return Screen.of(null, body, bottom);
    }
}
