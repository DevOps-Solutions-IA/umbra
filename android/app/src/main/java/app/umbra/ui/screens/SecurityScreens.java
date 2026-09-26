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
        if (s.callsVisible()) actions.addView(ui.callControl(Glyph.CALL, "Llamar", s.trust().allowsCalls() ? null : "Requiere verificación", false, false, s.trust().allowsCalls(), a::call));
        for (int i = 0; i < actions.getChildCount(); i++) { LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) actions.getChildAt(i).getLayoutParams(); if (p == null) p = Ui.wrap(); p.setMarginStart(ui.dp(14)); p.setMarginEnd(ui.dp(14)); actions.getChildAt(i).setLayoutParams(p); }
        body.addView(actions, ui.margins(Ui.match(), 4, 8));

        LinearLayout trust = ui.card();
        trust.addView(ui.text(UmbraType.SECURITY_LABEL, "Estado de confianza"));
        trust.addView(ui.heading(UmbraType.HEADING, s.trust().headline()), ui.margins(Ui.match(), 6, 2));
        trust.addView(ui.text(UmbraType.CAPTION, s.trust().explanation()));
        if (s.trust().level() != TrustLevel.BLOCKED)
            trust.addView(ui.button(s.trust().level() == TrustLevel.VERIFIED ? Ui.ButtonKind.SECONDARY : Ui.ButtonKind.PRIMARY, s.trust().primaryAction(), Glyph.SHIELD_CHECK, a::verify));
        body.addView(trust);

        LinearLayout identity = ui.card();
        identity.addView(ui.text(UmbraType.SECURITY_LABEL, "Identidad"));
        identity.addView(ui.text(UmbraType.CAPTION, "Identificador público"), ui.margins(Ui.match(), 6, 0));
        identity.addView(ui.code(Fingerprints.shortId(s.peerId())));
        identity.addView(ui.listRow(ui.iconTile(Glyph.QR, Tone.NEUTRAL), "Código de seguridad", "Compáralo con esta persona", ui.chevron(), a::verify));
        body.addView(identity);

        LinearLayout devices = ui.card();
        devices.addView(ui.text(UmbraType.SECURITY_LABEL, "Dispositivos"));
        if (s.approvedDevices() < 0) {
            devices.addView(ui.text(UmbraType.BODY, "Lista de dispositivos no aprobada o caducada"), ui.margins(Ui.match(), 6, 2));
            devices.addView(ui.text(UmbraType.CAPTION, "Hasta aprobarla, no se envían ubicación ni llamadas a este contacto."));
        } else {
            devices.addView(ui.text(UmbraType.BODY, s.approvedDevices() + (s.approvedDevices() == 1 ? " dispositivo en su lista firmada" : " dispositivos en su lista firmada")), ui.margins(Ui.match(), 6, 2));
            devices.addView(ui.text(UmbraType.CAPTION, "Si cambia la lista firmada, UMBRA te pedirá revisarla."));
        }
        body.addView(devices);

        LinearLayout media = ui.card();
        media.addView(ui.text(UmbraType.SECURITY_LABEL, "Multimedia compartida"));
        media.addView(ui.text(UmbraType.BODY, s.sharedFiles() == 0 ? "Sin archivos en esta conversación" : s.sharedFiles() + (s.sharedFiles() == 1 ? " archivo" : " archivos") + " en esta conversación"), ui.margins(Ui.match(), 6, 0));
        body.addView(media);

        body.addView(ui.sectionHeader("Acciones"));
        body.addView(ui.button(Ui.ButtonKind.DESTRUCTIVE, s.trust().level() == TrustLevel.BLOCKED ? "Desbloquear contacto" : "Bloquear contacto", Glyph.BLOCK,
            () -> a.block(s.trust().level() != TrustLevel.BLOCKED)));
        body.addView(ui.button(Ui.ButtonKind.DESTRUCTIVE, "Vaciar conversación local", Glyph.TRASH, a::clear));
        Button remove = ui.button(Ui.ButtonKind.DESTRUCTIVE, "Eliminar contacto", Glyph.TRASH, null);
        ui.disabled(remove, "el motor todavía no permite eliminar contactos");
        body.addView(remove); body.addView(ui.pendingChip());
        return Screen.of(top, body, null);
    }

    public enum Method { CODE, QR, MANUAL }
    public record VerifyState(String alias, TrustPresentation trust, String safetyCode, Bitmap qr, Method method,
                              boolean showTechnical, String ownShortId, String peerShortId, FeatureAvailability features) {}
    public interface VerifyActions { void back(); void method(Method m); void compare(String code); void technical(boolean show); }

    /** Verification without cryptographic knowledge: states in words, code in blocks, advanced details on request. */
    public static Screen verify(Ui ui, VerifyState s, VerifyActions a) {
        LinearLayout top = ui.column();
        top.addView(ui.topBar(a::back, ui.titleBlock("Verificar contacto", ui.text(UmbraType.CAPTION, s.alias()))));
        LinearLayout body = ui.column();
        TrustLevel level = s.trust().level();
        body.addView(ui.banner(s.trust().tone(), s.trust().glyph(), s.trust().label().toUpperCase(java.util.Locale.ROOT), s.trust().explanation(), null, null));
        if (level == TrustLevel.IDENTITY_CHANGED) {
            LinearLayout next = ui.card();
            next.addView(ui.text(UmbraType.LABEL, "Aceptar la nueva identidad"));
            next.addView(ui.text(UmbraType.CAPTION, "Necesitas la nueva invitación de esta persona y comparar su nuevo código. Mientras tanto, los envíos siguen bloqueados."));
            next.addView(ui.pendingChip());
            body.addView(next);
        }
        body.addView(ui.segmented(new String[]{"Código", "QR", "Comparar"}, s.method().ordinal(), null, i -> a.method(Method.values()[i])));
        LinearLayout panel = ui.card();
        switch (s.method()) {
            case CODE -> {
                panel.addView(ui.text(UmbraType.LABEL, "Código de seguridad"));
                panel.addView(ui.text(UmbraType.CAPTION, "Léanlo en voz alta en persona o por un canal en el que ya confíen. Debe coincidir completo en ambos teléfonos."));
                TextView code = ui.code(Fingerprints.lines(s.safetyCode(), 4));
                code.setBackground(ui.shape(UmbraColors.BACKGROUND_SECONDARY, 12)); code.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
                code.setGravity(Gravity.CENTER);
                panel.addView(code, ui.margins(Ui.match(), 10, 4));
                panel.addView(ui.text(UmbraType.CAPTION, "El código no se copia al portapapeles."));
            }
            case QR -> {
                LinearLayout qrHead = ui.row(); qrHead.addView(ui.logo(20, UmbraColors.ACCENT_SECONDARY));
                TextView qrTitle = ui.text(UmbraType.LABEL, "Mismo código en QR"); qrTitle.setPadding(ui.dp(8), 0, 0, 0); qrHead.addView(qrTitle);
                panel.addView(qrHead);
                if (s.qr() != null) {
                    ImageView qr = new ImageView(ui.context()); qr.setImageBitmap(s.qr()); qr.setAdjustViewBounds(true);
                    qr.setContentDescription("Código QR del código de seguridad con " + s.alias());
                    qr.setBackgroundColor(0xFFFFFFFF); qr.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
                    LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(ui.dp(220), ui.dp(220)); qp.gravity = Gravity.CENTER_HORIZONTAL; qp.topMargin = ui.dp(10);
                    panel.addView(qr, qp);
                } else panel.addView(ui.text(UmbraType.CAPTION, "No se pudo generar el QR. Usa el código completo."));
                panel.addView(ui.text(UmbraType.CAPTION, "Escanear el QR del otro teléfono con la cámara todavía no está disponible; compara el código completo."), ui.margins(Ui.match(), 8, 0));
                panel.addView(ui.pendingChip());
            }
            case MANUAL -> {
                panel.addView(ui.text(UmbraType.LABEL, "Comparación manual"));
                panel.addView(ui.text(UmbraType.CAPTION, "Escribe el código completo que ve la otra persona. UMBRA lo compara con el tuyo; solo si coincide se marca como verificado."));
                if (level == TrustLevel.BLOCKED) panel.addView(ui.text(UmbraType.CAPTION, "Desbloquea el contacto para verificarlo.", UmbraColors.WARNING_FG));
                else {
                    EditText entered = ui.field("Código de la otra persona");
                    entered.setSingleLine(false); entered.setMaxLines(4);
                    entered.setTypeface(UmbraType.MONOSPACE.typeface());
                    entered.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                    panel.addView(ui.labeledField("Código completo", entered));
                    panel.addView(ui.button(Ui.ButtonKind.PRIMARY, level == TrustLevel.VERIFIED ? "Comparar de nuevo" : "Comparar y verificar", Glyph.SHIELD_CHECK,
                        () -> a.compare(entered.getText().toString())));
                }
            }
        }
        body.addView(panel);
        body.addView(ui.sectionHeader("Qué significa cada estado"));
        for (TrustLevel l : TrustLevel.values()) {
            TrustPresentation p = TrustPresentation.of(l);
            LinearLayout r = ui.row(); r.setGravity(Gravity.TOP); r.setPadding(0, ui.dp(6), 0, ui.dp(6));
            r.addView(ui.iconView(p.glyph(), Ui.toneColor(p.tone()), 20));
            LinearLayout t = ui.column(); t.setPadding(ui.dp(10), 0, 0, 0);
            t.addView(ui.text(UmbraType.LABEL, p.label().toUpperCase(java.util.Locale.ROOT), Ui.toneColor(p.tone())));
            t.addView(ui.text(UmbraType.CAPTION, p.explanation()));
            r.addView(t, Ui.weight()); body.addView(r);
        }
        Button tech = ui.button(Ui.ButtonKind.GHOST, s.showTechnical() ? "Ocultar detalles técnicos" : "Detalles técnicos", Glyph.INFO, () -> a.technical(!s.showTechnical()));
        tech.setStateDescription(s.showTechnical() ? "Visibles" : "Ocultos");
        body.addView(tech);
        if (s.showTechnical()) {
            LinearLayout t = ui.card();
            t.addView(ui.text(UmbraType.CAPTION, "Tu identificador público: " + s.ownShortId()));
            t.addView(ui.text(UmbraType.CAPTION, "Identificador público del contacto: " + s.peerShortId()));
            t.addView(ui.text(UmbraType.CAPTION, "El código de seguridad se deriva de ambas identidades públicas. Verificarlo fija la identidad del contacto; un cambio posterior bloquea operaciones sensibles hasta repetir la comparación."));
            t.addView(ui.text(UmbraType.CAPTION, "Un dispositivo verificado no es una persona verificada: compara siempre fuera de banda."));
            body.addView(t);
        }
        body.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        return Screen.of(top, body, null);
    }
}
