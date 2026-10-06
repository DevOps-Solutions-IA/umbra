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
        body.addView(l, ui.margins(Ui.match(), 12, 0));
        body.addView(field);
        return holder[0];
    }
    private static LinearLayout header(Ui ui, AccessStep step, Help help) {
        LinearLayout body = ui.column(); body.setPadding(ui.dp(8), ui.dp(40), ui.dp(8), ui.dp(8));
        LinearLayout brand = ui.row(); brand.addView(ui.logo(40, UmbraColors.ACCENT_MUTED)); brand.addView(new android.widget.Space(ui.context()), Ui.weight());
        if (help != null) brand.addView(ui.helpButton(help));
        body.addView(brand);
        body.addView(ui.heading(UmbraType.TITLE, step.title), ui.margins(Ui.match(), 16, 4));
        if (!step.body.isEmpty()) body.addView(ui.text(UmbraType.BODY_SECONDARY, step.body));
        return body;
    }
    /** Errors inline; the running phase is shown on the submit button itself (busy, double taps ignored). */
    private static void busy(Ui ui, LinearLayout body, boolean busy, String problem) {
        if (problem != null && !busy) body.addView(ui.banner(Tone.DANGER, Glyph.WARNING, problem, null, null, null), ui.margins(Ui.match(), 10, 0));
    }

    public static Screen create(Ui ui, CreateState s, CreateActions a) {
        LinearLayout body = header(ui, s.legacy() ? AccessStep.LEGACY_ENROLLMENT : AccessStep.CREATE_PASSWORD, Help.ACCESS);
        EditText password = secret(ui, body, "Contraseña", "Mínimo " + PasswordPolicy.MIN_BYTES + " caracteres");
        EditText confirmation = secret(ui, body, "Repetir", "Repetir contraseña");
        if (s.legacy()) body.addView(ui.banner(Tone.WARNING, Glyph.WARNING, "Sin recuperación si la olvidas", null, null, null), ui.margins(Ui.match(), 12, 0));
        busy(ui, body, s.busy(), s.problem());
        LinearLayout bottom = ui.column();
        Button submit = ui.button(Ui.ButtonKind.PRIMARY, s.legacy() ? "Inscribir" : "Crear", Glyph.PASSWORD, () -> a.submit(password, confirmation));
        if (s.busy()) ui.busy(submit, "Creando protección…");
        bottom.addView(submit);
        if (s.legacy()) {
            Button later = ui.button(Ui.ButtonKind.GHOST, "Ahora no", Glyph.CLOSE, a::later);
            if (s.busy()) ui.disabled(later, "operación en curso");
            bottom.addView(later);
        }
        bottom.addView(ui.button(Ui.ButtonKind.GHOST, "Bloquear", Glyph.LOCK, a::lockNow));
        return Screen.of(null, body, bottom);
    }

    public static Screen unlock(Ui ui, UnlockState s, UnlockActions a) {
        LinearLayout body = header(ui, AccessStep.PASSWORD_UNLOCK, Help.ACCESS);
        EditText password = secret(ui, body, "Contraseña", "Contraseña personal");
        LinearLayout lockHead = ui.row(); lockHead.setGravity(android.view.Gravity.CENTER_VERTICAL);
        lockHead.addView(ui.text(UmbraType.SECURITY_LABEL, "Autobloqueo"), Ui.weight()); lockHead.addView(ui.helpButton(Help.AUTO_LOCK));
        body.addView(lockHead, ui.margins(Ui.match(), 12, 0));
        body.addView(ui.segmented(PasswordPolicy.AUTO_LOCK_LABELS, s.autoLockIndex(), null, a::autoLock));
        busy(ui, body, s.busy(), s.problem());
        LinearLayout bottom = ui.column();
        if (!s.offlineEdition()) { // unlocking never connects: said once, quietly
            TextView quiet = ui.text(UmbraType.CAPTION, "Abrir no conecta a la red."); quiet.setGravity(android.view.Gravity.CENTER);
            bottom.addView(quiet, Ui.match());
        }
        Button submit = ui.button(Ui.ButtonKind.PRIMARY, "Abrir", Glyph.UNLOCK, () -> a.submit(password));
        if (s.busy()) ui.busy(submit, "Abriendo…");
        bottom.addView(submit);
        bottom.addView(ui.button(Ui.ButtonKind.GHOST, "Bloquear", Glyph.LOCK, a::lockNow));
        return Screen.of(null, body, bottom);
    }

    public static Screen change(Ui ui, ChangeState s, ChangeActions a) {
        LinearLayout top = ui.column();
        top.addView(ui.topBar(a::back, ui.titleBlock("Cambiar contraseña", null), ui.helpButton(Help.ACCESS)));
        LinearLayout body = ui.column();
        EditText current = secret(ui, body, "Actual", "Contraseña actual");
        EditText next = secret(ui, body, "Nueva", "Mínimo " + PasswordPolicy.MIN_BYTES + " caracteres");
        EditText confirmation = secret(ui, body, "Repetir", "Repetir contraseña nueva");
        busy(ui, body, s.busy(), s.problem());
        Button submit = ui.button(Ui.ButtonKind.PRIMARY, "Cambiar", Glyph.CHANGE_PASSWORD, () -> a.submit(current, next, confirmation));
        if (s.busy()) ui.busy(submit, "Cambiando contraseña…");
        LinearLayout bottom = ui.column(); bottom.addView(submit);
        return Screen.of(top, body, bottom);
    }

    /** CORRUPT / KEY_UNAVAILABLE: explanation only. No reset, reinitialization or recovery is offered. */
    public static Screen failure(Ui ui, AccessStep step, Runnable lockNow) {
        LinearLayout body = header(ui, step, null);
        body.setGravity(Gravity.START);
        LinearLayout bottom = ui.column();
        bottom.addView(ui.button(Ui.ButtonKind.PRIMARY, "Bloquear", Glyph.LOCK, lockNow));
        return Screen.of(null, body, bottom);
    }
}
