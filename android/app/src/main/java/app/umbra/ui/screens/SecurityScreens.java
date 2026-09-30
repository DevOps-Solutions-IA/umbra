package app.umbra.ui.screens;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import app.umbra.ui.design.UmbraColors;
import app.umbra.ui.design.UmbraType;
import app.umbra.ui.design.Ui;
import app.umbra.ui.model.*;

/** Contact information and identity verification. Private keys are never part of any state here. */
public final class SecurityScreens {
    private SecurityScreens() {}

    /**
     * @param approvedDevices devices in the contact's approved roster, or -1 when no roster was approved
     * @param sharedFiles     files currently in the local conversation
     */
    public record ContactState(String peerId, String alias, TrustPresentation trust, int approvedDevices, int sharedFiles,
                               boolean callsVisible, FeatureAvailability features) {}
    public interface ContactActions {
        void back(); void verify(); void block(boolean block); void clear(); void call(); void message();
    }

    public static Screen contact(Ui ui, ContactState s, ContactActions a) {
        LinearLayout top = ui.column();
        top.addView(ui.topBar(a::back, ui.titleBlock("Contacto", null)));
        LinearLayout body = ui.column();
        LinearLayout head = ui.column(); head.setGravity(Gravity.CENTER_HORIZONTAL); head.setPadding(0, ui.dp(8), 0, ui.dp(8));
        head.addView(ui.avatar(s.alias(), false, 88));
        TextView name = ui.heading(UmbraType.TITLE, s.alias()); name.setGravity(Gravity.CENTER); head.addView(name, ui.margins(Ui.match(), 12, 6));
        LinearLayout badgeRow = ui.row(); badgeRow.setGravity(Gravity.CENTER); badgeRow.addView(ui.trustBadge(s.trust())); head.addView(badgeRow, Ui.match());
        body.addView(head);

        LinearLayout actions = ui.row(); actions.setGravity(Gravity.CENTER);
        actions.addView(ui.callControl(Glyph.CHAT, "Mensaje", null, false, false, true, a::message));
        if (s.callsVisible()) actions.addView(ui.callControl(Glyph.CALL, "Llamar", s.trust().allowsCalls() ? null : "Sin verificar", false, false, s.trust().allowsCalls(), a::call));
        for (int i = 0; i < actions.getChildCount(); i++) { LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) actions.getChildAt(i).getLayoutParams(); if (p == null) p = Ui.wrap(); p.setMarginStart(ui.dp(14)); p.setMarginEnd(ui.dp(14)); actions.getChildAt(i).setLayoutParams(p); }
        body.addView(actions, ui.margins(Ui.match(), 4, 8));

        if (s.trust().level() != TrustLevel.BLOCKED)
            body.addView(ui.button(s.trust().level() == TrustLevel.VERIFIED ? Ui.ButtonKind.SECONDARY : Ui.ButtonKind.PRIMARY, s.trust().primaryAction(), Glyph.SHIELD_CHECK, a::verify));
        LinearLayout info = ui.card();
        info.addView(fact(ui, "ID", Fingerprints.shortId(s.peerId())));
        info.addView(fact(ui, "Dispositivos", s.approvedDevices() < 0 ? "Sin lista aprobada" : String.valueOf(s.approvedDevices())));
        info.addView(fact(ui, "Archivos", String.valueOf(s.sharedFiles())));
        body.addView(info);
        body.addView(ui.sectionHeader("Acciones"));
        body.addView(ui.button(Ui.ButtonKind.DESTRUCTIVE, s.trust().level() == TrustLevel.BLOCKED ? "Desbloquear" : "Bloquear", Glyph.BLOCK,
            () -> a.block(s.trust().level() != TrustLevel.BLOCKED)));
        body.addView(ui.button(Ui.ButtonKind.DESTRUCTIVE, "Vaciar chat", Glyph.TRASH, a::clear));
        Button remove = ui.button(Ui.ButtonKind.DESTRUCTIVE, "Eliminar contacto", Glyph.TRASH, null);
        ui.disabled(remove, "próximamente");
        body.addView(remove);
        return Screen.of(top, body, null);
    }

    public enum Method { CODE, QR, MANUAL }
    public record VerifyState(String alias, TrustPresentation trust, String safetyCode, Bitmap qr, Method method,
                              boolean showTechnical, String ownShortId, String peerShortId, FeatureAvailability features) {}
    public interface VerifyActions { void back(); void method(Method m); void compare(String code); void technical(boolean show); }

    /** Label/value row for compact facts (public identifiers and counts only). */
    private static LinearLayout fact(Ui ui, String label, String value) {
        LinearLayout r = ui.row(); r.setPadding(0, ui.dp(6), 0, ui.dp(6));
        r.addView(ui.text(UmbraType.CAPTION, label), Ui.weight());
        r.addView(ui.text(UmbraType.LABEL, value));
        r.setContentDescription(label + ": " + value);
        return r;
    }

    /** Verification: state chip, method, code and one action. Explanations live in the ⓘ sheet. */
    public static Screen verify(Ui ui, VerifyState s, VerifyActions a) {
        TrustLevel level = s.trust().level();
        LinearLayout top = ui.column();
        top.addView(ui.topBar(a::back, ui.titleBlock("Verificar · " + s.alias(), ui.chip(s.trust().tone(), s.trust().glyph(), s.trust().label())), ui.helpButton(Help.VERIFY)));
        LinearLayout body = ui.column();
        if (level == TrustLevel.IDENTITY_CHANGED) {
            body.addView(ui.banner(s.trust().tone(), s.trust().glyph(), "Nueva identidad", "Requiere su nueva invitación.", null, null));
            body.addView(ui.pendingChip());
        }
        body.addView(ui.segmented(new String[]{"Código", "QR", "Comparar"}, s.method().ordinal(), null, i -> a.method(Method.values()[i])));
        switch (s.method()) {
            case CODE -> {
                TextView code = ui.code(Fingerprints.lines(s.safetyCode(), 4));
                code.setBackground(ui.outlined(UmbraColors.BACKGROUND_SECONDARY, UmbraColors.OUTLINE, 14)); code.setPadding(ui.dp(14), ui.dp(16), ui.dp(14), ui.dp(16));
                code.setGravity(Gravity.CENTER);
                body.addView(code, ui.margins(Ui.match(), 10, 6));
                body.addView(ui.text(UmbraType.CAPTION, "Compárenlo completo, en persona."));
            }
            case QR -> {
                if (s.qr() != null) {
                    ImageView qr = new ImageView(ui.context()); qr.setImageBitmap(s.qr()); qr.setAdjustViewBounds(true);
                    qr.setContentDescription("QR del código de seguridad con " + s.alias());
                    qr.setBackgroundColor(0xFFFFFFFF); qr.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
                    LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(ui.dp(220), ui.dp(220)); qp.gravity = Gravity.CENTER_HORIZONTAL; qp.topMargin = ui.dp(10);
                    body.addView(qr, qp);
                } else body.addView(ui.text(UmbraType.CAPTION, "QR no disponible. Usa el código."));
                LinearLayout scan = ui.row(); scan.addView(ui.text(UmbraType.CAPTION, "Escanear"), Ui.weight()); scan.addView(ui.pendingChip());
                body.addView(scan, ui.margins(Ui.match(), 8, 0));
            }
            case MANUAL -> {
                if (level == TrustLevel.BLOCKED) body.addView(ui.text(UmbraType.CAPTION, "Desbloquea para verificar.", UmbraColors.WARNING_FG));
                else {
                    EditText entered = ui.field("Código de " + s.alias());
                    entered.setSingleLine(false); entered.setMaxLines(4);
                    entered.setTypeface(UmbraType.MONOSPACE.typeface());
                    entered.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                    body.addView(ui.labeledField("Código completo", entered));
                    body.addView(ui.button(Ui.ButtonKind.PRIMARY, level == TrustLevel.VERIFIED ? "Comparar de nuevo" : "Verificar", Glyph.SHIELD_CHECK,
                        () -> a.compare(entered.getText().toString())));
                }
            }
        }
        Button tech = ui.button(Ui.ButtonKind.GHOST, s.showTechnical() ? "Ocultar detalles" : "Detalles", Glyph.INFO, () -> a.technical(!s.showTechnical()));
        tech.setStateDescription(s.showTechnical() ? "Visibles" : "Ocultos");
        body.addView(tech);
        if (s.showTechnical()) {
            LinearLayout t = ui.card();
            t.addView(fact(ui, "Tu ID", s.ownShortId()));
            t.addView(fact(ui, "ID de " + s.alias(), s.peerShortId()));
            body.addView(t);
        }
        body.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        return Screen.of(top, body, null);
    }
}
