package app.umbra.ui.screens;

import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.umbra.ui.design.UmbraColors;
import app.umbra.ui.design.UmbraType;
import app.umbra.ui.design.Ui;
import app.umbra.ui.model.*;

/**
 * Private device admission. The screen presents the state reported by the domain and offers only the
 * operations the domain implements. Admission is per device and is shown apart from contact
 * verification and contact invitations. Provisioning is offline (files): nothing is sent automatically.
 */
public final class AdmissionScreens {
    private AdmissionScreens() {}

    public record RequestInfo(String deviceFingerprint, String identityFingerprint, String realm, String expires, boolean expired) {}
    /**
     * @param renewalAvailable the domain returned this phone's current public credential (ADMITTED), so a
     *                         renewal request can carry it; otherwise a new request is generated
     */
    public record AdmissionState(AdmissionPresentation presentation, String realm, String authority, String identity,
                                 RequestInfo request, String credentialExpires, boolean offlineEdition, boolean busy,
                                 boolean renewalAvailable) {}
    public interface AdmissionActions { void back(); void importFile(); void createRequest(); void exportRequest(); void cancelRequest(); void admin(); }

    /** Label + public value (fingerprints, identifiers, times). Never private material. */
    private static void fingerprint(Ui ui, LinearLayout box, String label, String value) {
        if (value == null) return;
        LinearLayout r = ui.row(); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, ui.dp(6), 0, ui.dp(6));
        r.addView(ui.text(UmbraType.CAPTION, label), Ui.weight());
        TextView code = ui.code(value); code.setContentDescription(label + ". " + code.getContentDescription()); r.addView(code);
        box.addView(r);
    }
    private static void fact(Ui ui, LinearLayout box, String label, String value) {
        LinearLayout r = ui.row(); r.setPadding(0, ui.dp(6), 0, ui.dp(6));
        r.addView(ui.text(UmbraType.CAPTION, label), Ui.weight()); r.addView(ui.text(UmbraType.LABEL, value));
        r.setContentDescription(label + ": " + value); box.addView(r);
    }

    public static Screen status(Ui ui, AdmissionState s, AdmissionActions a) {
        AdmissionPresentation p = s.presentation();
        LinearLayout top = ui.column();
        top.addView(ui.topBar(a::back, ui.titleBlock("Admisión", ui.chip(p.tone(), p.glyph(), p.title())), ui.helpButton(Help.ADMISSION)));
        LinearLayout body = ui.column();
        LinearLayout card = ui.elevatedCard();
        if (!p.body().isEmpty()) {
            TextView line = ui.text(UmbraType.LABEL, p.body(), p.tone() == Tone.NEUTRAL ? UmbraColors.TEXT_PRIMARY : Ui.toneColor(p.tone()));
            line.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
            card.addView(line, ui.margins(Ui.match(), 0, 4));
        }
        RequestInfo r = s.request();
        if (r != null) {
            fingerprint(ui, card, "Huella", r.deviceFingerprint());
            fingerprint(ui, card, "Identidad", r.identityFingerprint());
            fact(ui, card, r.expired() ? "Venció" : "Vence", r.expires());
        } else fingerprint(ui, card, "Identidad", s.identity());
        if (s.realm() != null) { fingerprint(ui, card, "Entorno", s.realm()); fingerprint(ui, card, "Autoridad", s.authority()); }
        if (s.credentialExpires() != null) fact(ui, card, "Vigente hasta", s.credentialExpires());
        body.addView(card);

        if (r != null && p.canExportRequest() && !r.expired()) body.addView(ui.button(Ui.ButtonKind.PRIMARY, "Exportar solicitud", Glyph.FILE, a::exportRequest));
        if (p.canCreateRequest()) {
            String label = s.renewalAvailable() ? "Solicitar renovación" : "Generar solicitud";
            Button create = ui.button(r == null ? Ui.ButtonKind.PRIMARY : Ui.ButtonKind.SECONDARY, label, Glyph.DEVICE_PENDING, a::createRequest);
            if (s.busy()) ui.disabled(create, "operación en curso");
            body.addView(create);
        }
        if (p.canImportRealm() || p.canImportDecision() || p.canImportRevocation()) {
            String label = p.canImportRealm() ? "Importar entorno" : p.canImportDecision() ? "Importar respuesta" : "Importar revocación";
            Button importer = ui.button(p.canImportRealm() || p.canImportDecision() ? Ui.ButtonKind.PRIMARY : Ui.ButtonKind.SECONDARY, label, Glyph.FILE, a::importFile);
            if (s.busy()) ui.disabled(importer, "operación en curso");
            body.addView(importer);
        }
        if (r != null) {
            // Local abandonment of this exact request (AdmissionService.cancelPendingRequest); not a remote recall.
            Button cancel = ui.button(Ui.ButtonKind.GHOST, "Cancelar solicitud", Glyph.CLOSE, a::cancelRequest);
            if (s.busy()) ui.disabled(cancel, "operación en curso");
            body.addView(cancel);
        }
        body.addView(ui.sectionHeader("Administración"));
        body.addView(ui.listRow(ui.iconTile(Glyph.SHIELD, Tone.NEUTRAL), "Administración", "Solo autoridad", ui.chevron(), a::admin));
        return Screen.of(top, body, null);
    }

    // ------------------------------------------------------------------ administrator
    /** Authority-only public metadata of one issued credential (AdmissionService.issuedCredentials). */
    public record IssuedRow(String device, String issued, String expires, boolean revoked, boolean expired) {}
    /** @param authority snapshot of {@code isAdmissionAuthority()}; presentation only, the domain re-checks */
    public record AdminState(boolean realmConfigured, String realm, String authority, boolean busy, boolean isAuthority,
                             java.util.List<IssuedRow> issued) {}
    public interface AdminActions { void back(); void createRealm(); void exportRealm(); void reviewRequest(); void revokeCredential(); }

    public static Screen admin(Ui ui, AdminState s, AdminActions a) {
        LinearLayout top = ui.column();
        top.addView(ui.topBar(a::back, ui.titleBlock("Administración", ui.chip(Tone.NEUTRAL, Glyph.SHIELD, "Solo autoridad")), ui.helpButton(Help.ADMIN)));
        LinearLayout body = ui.column();
        if (!s.realmConfigured()) {
            body.addView(ui.banner(Tone.WARNING, Glyph.WARNING, "Sin recuperación ni rotación", null, null, null));
            Button create = ui.button(Ui.ButtonKind.DESTRUCTIVE, "Crear entorno", Glyph.SHIELD_CHECK, a::createRealm);
            if (s.busy()) ui.disabled(create, "operación en curso");
            body.addView(create);
            return Screen.of(top, body, null);
        }
        LinearLayout realm = ui.card();
        fingerprint(ui, realm, "Entorno", s.realm());
        fingerprint(ui, realm, "Autoridad", s.authority());
        body.addView(realm);
        if (!s.isAuthority()) {
            // Members may re-share the public realm file; decisions belong to the authority phone only.
            body.addView(ui.banner(Tone.NEUTRAL, Glyph.SHIELD, "Este teléfono no es la autoridad", null, null, null));
            body.addView(ui.button(Ui.ButtonKind.SECONDARY, "Exportar entorno", Glyph.FILE, a::exportRealm));
            return Screen.of(top, body, null);
        }
        Button review = ui.button(Ui.ButtonKind.PRIMARY, "Revisar solicitud", Glyph.DEVICE_PENDING, a::reviewRequest);
        Button revoke = ui.button(Ui.ButtonKind.DESTRUCTIVE, "Revocar credencial", Glyph.DEVICE_REVOKED, a::revokeCredential);
        if (s.busy()) { ui.disabled(review, "operación en curso"); ui.disabled(revoke, "operación en curso"); }
        body.addView(review);
        body.addView(ui.button(Ui.ButtonKind.SECONDARY, "Exportar entorno", Glyph.FILE, a::exportRealm));
        body.addView(revoke);
        body.addView(ui.sectionHeader("Credenciales emitidas"));
        if (s.issued().isEmpty()) body.addView(ui.text(UmbraType.CAPTION, "Ninguna."));
        for (IssuedRow row : s.issued()) {
            Tone tone = row.revoked() ? Tone.BLOCKED : row.expired() ? Tone.WARNING : Tone.SUCCESS;
            String state = row.revoked() ? "Revocada" : row.expired() ? "Vencida" : "Vigente";
            LinearLayout item = ui.listRow(ui.iconTile(row.revoked() ? Glyph.DEVICE_REVOKED : Glyph.DEVICE_AUTHORIZED, tone), row.device(),
                "Emitida " + row.issued() + " · vence " + row.expires(), ui.chip(tone, row.revoked() ? Glyph.BLOCK : Glyph.CHECK, state), null);
            item.setContentDescription("Credencial de " + row.device() + ". " + state + ". Vence " + row.expires());
            body.addView(item);
        }
        return Screen.of(top, body, null);
    }

    public static final long[] TTL_SECONDS = {86_400, 604_800};
    public static final String[] TTL_LABELS = {"24 h", "7 d"};
    public record ReviewInfo(String deviceFingerprint, String identityFingerprint, String realm, String expires, boolean renewal) {}
    public interface ReviewActions { void approve(long ttlSeconds); void reject(); void cancel(); }

    /** Everything the authority must compare before deciding. No alias exists in a request; nothing here is an identity claim. */
    public static LinearLayout reviewSheet(Ui ui, ReviewInfo r, ReviewActions a) {
        LinearLayout box = ui.column();
        box.addView(ui.heading(UmbraType.TITLE, r.renewal() ? "Revisar renovación" : "Revisar solicitud"));
        box.addView(ui.chip(Tone.NEUTRAL, Glyph.INFO, "Compara las huellas con la persona"), ui.margins(Ui.wrap(), 6, 6));
        fingerprint(ui, box, "Huella", r.deviceFingerprint());
        fingerprint(ui, box, "Identidad", r.identityFingerprint());
        fingerprint(ui, box, "Entorno", r.realm());
        fact(ui, box, "Vence", r.expires());
        if (r.renewal()) box.addView(ui.text(UmbraType.CAPTION, "Revoca la credencial actual."));
        for (int i = 0; i < TTL_SECONDS.length; i++) {
            long seconds = TTL_SECONDS[i];
            box.addView(ui.button(i == 0 ? Ui.ButtonKind.PRIMARY : Ui.ButtonKind.SECONDARY, "Aprobar " + TTL_LABELS[i], Glyph.CHECK, () -> a.approve(seconds)));
        }
        box.addView(ui.button(Ui.ButtonKind.DESTRUCTIVE, "Rechazar", Glyph.BLOCK, a::reject));
        box.addView(ui.button(Ui.ButtonKind.GHOST, "Cancelar", Glyph.CLOSE, a::cancel));
        return box;
    }

    public static final String[] REVOCATION_REASONS = {"owner_request", "device_lost", "policy"};
    public static final String[] REVOCATION_LABELS = {"A pedido", "Perdido", "Política"};
    public record RevokeInfo(String identityFingerprint, String expires) {}
    public interface RevokeActions { void revoke(String reason); void cancel(); }

    public static LinearLayout revokeSheet(Ui ui, RevokeInfo r, RevokeActions a) {
        LinearLayout box = ui.column();
        LinearLayout head = ui.row(); head.addView(ui.heading(UmbraType.TITLE, "Revocar credencial"), Ui.weight()); head.addView(ui.helpButton(Help.REVOCATION));
        box.addView(head);
        fingerprint(ui, box, "Identidad", r.identityFingerprint());
        fact(ui, box, "Vigente hasta", r.expires());
        box.addView(ui.chip(Tone.WARNING, Glyph.WARNING, "No borra datos ya entregados"), ui.margins(Ui.wrap(), 6, 6));
        box.addView(ui.text(UmbraType.SECURITY_LABEL, "Motivo"));
        for (int i = 0; i < REVOCATION_REASONS.length; i++) {
            String reason = REVOCATION_REASONS[i];
            box.addView(ui.button(Ui.ButtonKind.DESTRUCTIVE, "Revocar · " + REVOCATION_LABELS[i], Glyph.DEVICE_REVOKED, () -> a.revoke(reason)));
        }
        box.addView(ui.button(Ui.ButtonKind.GHOST, "Cancelar", Glyph.CLOSE, a::cancel));
        return box;
    }
}
