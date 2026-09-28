package app.umbra.ui.screens;

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
    public record AdmissionState(AdmissionPresentation presentation, String realm, String authority, String identity,
                                 RequestInfo request, String credentialExpires, boolean offlineEdition, boolean busy) {}
    public interface AdmissionActions { void back(); void importFile(); void createRequest(); void exportRequest(); void admin(); }

    private static void fingerprint(Ui ui, LinearLayout box, String label, String value) {
        if (value == null) return;
        box.addView(ui.text(UmbraType.CAPTION, label), ui.margins(Ui.match(), 8, 0));
        TextView code = ui.code(value); code.setContentDescription(label + ": " + value); box.addView(code);
    }

    public static Screen status(Ui ui, AdmissionState s, AdmissionActions a) {
        AdmissionPresentation p = s.presentation();
        LinearLayout top = ui.column();
        top.addView(ui.topBar(a::back, ui.titleBlock("Admisión del dispositivo", ui.chip(p.tone(), p.glyph(), p.title()))));
        LinearLayout body = ui.column();
        LinearLayout state = ui.elevatedCard();
        LinearLayout head = ui.row(); head.addView(ui.iconTile(p.glyph(), p.tone()));
        TextView title = ui.heading(UmbraType.HEADING, p.title()); title.setPadding(ui.dp(12), 0, 0, 0);
        title.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE); head.addView(title, Ui.weight());
        state.addView(head);
        state.addView(ui.text(UmbraType.BODY_SECONDARY, p.body()), ui.margins(Ui.match(), 8, 0));
        if (s.credentialExpires() != null) state.addView(ui.text(UmbraType.CAPTION, "Credencial vigente hasta " + s.credentialExpires() + ". No se renueva sola."), ui.margins(Ui.match(), 6, 0));
        body.addView(state);

        if (s.request() != null) {
            RequestInfo r = s.request();
            LinearLayout req = ui.card();
            req.addView(ui.text(UmbraType.SECURITY_LABEL, r.expired() ? "Solicitud vencida" : "Solicitud generada para compartir"));
            req.addView(ui.text(UmbraType.CAPTION, r.expired() ? "Venció " + r.expires() + "." : "Vence " + r.expires() + " (dura diez minutos)."), ui.margins(Ui.match(), 4, 0));
            fingerprint(ui, req, "Huella de admisión de este dispositivo", r.deviceFingerprint());
            fingerprint(ui, req, "Identidad de este dispositivo", r.identityFingerprint());
            fingerprint(ui, req, "Entorno", r.realm());
            req.addView(ui.text(UmbraType.CAPTION, "Generada no significa recibida: UMBRA no sabe si el administrador la tiene hasta que importes su respuesta firmada. "
                + "El administrador debe comparar estas huellas contigo por un canal confiable."), ui.margins(Ui.match(), 8, 0));
            body.addView(req);
            if (p.canExportRequest() && !r.expired()) body.addView(ui.button(Ui.ButtonKind.PRIMARY, "Exportar solicitud (archivo)", Glyph.FILE, a::exportRequest));
            LinearLayout qr = ui.row(); qr.addView(ui.iconView(Glyph.QR, UmbraColors.TEXT_TERTIARY, 20));
            TextView qrText = ui.text(UmbraType.CAPTION, "QR: no hay lector en esta versión, así que la solicitud se comparte como archivo."); qrText.setPadding(ui.dp(8), 0, 0, 0);
            qr.addView(qrText, Ui.weight()); body.addView(qr, ui.margins(Ui.match(), 4, 0));
        }

        if (p.canCreateRequest()) {
            String label = switch (p.status()) {
                case ADMITTED -> "Generar solicitud de renovación";
                case EXPIRED -> s.request() != null ? "Generar una solicitud nueva" : "Generar solicitud de renovación";
                default -> "Generar solicitud de admisión";
            };
            Button create = ui.button(s.request() == null ? Ui.ButtonKind.PRIMARY : Ui.ButtonKind.SECONDARY, label, Glyph.DEVICE_PENDING, a::createRequest);
            if (s.busy()) ui.disabled(create, "operación en curso");
            body.addView(create);
        }
        boolean canImport = p.canImportRealm() || p.canImportDecision() || p.canImportRevocation();
        if (canImport) {
            String label = p.canImportRealm() ? "Importar configuración del entorno" : p.canImportDecision() ? "Importar respuesta del administrador" : "Importar revocación firmada";
            Button importer = ui.button(p.canImportRealm() || p.canImportDecision() ? Ui.ButtonKind.PRIMARY : Ui.ButtonKind.SECONDARY, label, Glyph.FILE, a::importFile);
            if (s.busy()) ui.disabled(importer, "operación en curso");
            body.addView(importer);
            if (p.canImportRealm()) body.addView(ui.text(UmbraType.CAPTION, "Importar la configuración deja el entorno configurado. No admite este dispositivo ni conecta nada."), ui.margins(Ui.match(), 4, 0));
        }

        if (s.realm() != null) {
            LinearLayout realm = ui.card();
            realm.addView(ui.text(UmbraType.SECURITY_LABEL, "Entorno UMBRA configurado"));
            fingerprint(ui, realm, "Identificador del entorno", s.realm());
            fingerprint(ui, realm, "Huella de la autoridad", s.authority());
            realm.addView(ui.text(UmbraType.CAPTION, "Si llega una configuración con otra autoridad, se rechaza y esta se conserva."), ui.margins(Ui.match(), 6, 0));
            body.addView(realm);
        }
        LinearLayout device = ui.card();
        device.addView(ui.text(UmbraType.SECURITY_LABEL, "Por dispositivo"));
        device.addView(ui.text(UmbraType.CAPTION, "Cada teléfono tiene su propia admisión. Vincular otro dispositivo a tu identidad no lo admite, y estar admitido no verifica a ningún contacto."), ui.margins(Ui.match(), 4, 0));
        fingerprint(ui, device, "Identidad de este dispositivo", s.identity());
        body.addView(device);
        body.addView(ui.banner(Tone.NEUTRAL, Glyph.INFO, "Revocación sin conexión", AdmissionPresentation.OFFLINE_REVOCATION_NOTE, null, null));
        if (s.offlineEdition()) body.addView(ui.text(UmbraType.CAPTION, "Edición offline: la admisión se gestiona solo con archivos, sin internet.", UmbraColors.TEXT_SECONDARY), ui.margins(Ui.match(), 4, 0));
        body.addView(ui.sectionHeader("Administración"));
        body.addView(ui.listRow(ui.iconTile(Glyph.SHIELD, Tone.NEUTRAL), "Herramientas del administrador",
            "Solo funcionan en el teléfono que creó el entorno", ui.chevron(), a::admin));
        return Screen.of(top, body, null);
    }

    // ------------------------------------------------------------------ administrator
    public record AdminState(boolean realmConfigured, String realm, String authority, boolean busy) {}
    public interface AdminActions { void back(); void createRealm(); void exportRealm(); void reviewRequest(); void revokeCredential(); }

    public static Screen admin(Ui ui, AdminState s, AdminActions a) {
        LinearLayout top = ui.column();
        top.addView(ui.topBar(a::back, ui.titleBlock("Administración del entorno", null)));
        LinearLayout body = ui.column();
        body.addView(ui.banner(Tone.WARNING, Glyph.SHIELD, "Solo para la autoridad del entorno",
            "Estas acciones solo funcionan en el teléfono que creó el entorno. Esta versión no puede mostrar si este teléfono es la autoridad: el motor lo comprueba en cada operación y la rechaza si no lo es.", null, null));
        if (!s.realmConfigured()) {
            body.addView(ui.sectionHeader("Crear un entorno"));
            body.addView(ui.text(UmbraType.CAPTION, "Este teléfono pasará a ser la única autoridad del entorno. No existe recuperación ni rotación de la autoridad: si pierdes este teléfono o su bóveda, nadie podrá admitir más dispositivos."));
            Button create = ui.button(Ui.ButtonKind.DESTRUCTIVE, "Crear entorno y ser su autoridad", Glyph.SHIELD_CHECK, a::createRealm);
            if (s.busy()) ui.disabled(create, "operación en curso");
            body.addView(create);
            return Screen.of(top, body, null);
        }
        LinearLayout realm = ui.card();
        realm.addView(ui.text(UmbraType.SECURITY_LABEL, "Entorno"));
        fingerprint(ui, realm, "Identificador del entorno", s.realm());
        fingerprint(ui, realm, "Huella de la autoridad", s.authority());
        body.addView(realm);
        body.addView(ui.button(Ui.ButtonKind.SECONDARY, "Exportar configuración pública", Glyph.FILE, a::exportRealm));
        body.addView(ui.text(UmbraType.CAPTION, "Entrégala por un canal confiable. Contiene solo datos públicos; no es una invitación ni admite a nadie."), ui.margins(Ui.match(), 4, 0));
        body.addView(ui.sectionHeader("Solicitudes"));
        Button review = ui.button(Ui.ButtonKind.PRIMARY, "Revisar solicitud (archivo)", Glyph.DEVICE_PENDING, a::reviewRequest);
        Button revoke = ui.button(Ui.ButtonKind.DESTRUCTIVE, "Revocar una credencial (archivo)", Glyph.DEVICE_REVOKED, a::revokeCredential);
        if (s.busy()) { ui.disabled(review, "operación en curso"); ui.disabled(revoke, "operación en curso"); }
        body.addView(review);
        body.addView(ui.text(UmbraType.CAPTION, "Una solicitud de renovación trae también la credencial actual: aprobarla emite una nueva y revoca la anterior en la misma operación."), ui.margins(Ui.match(), 4, 0));
        body.addView(revoke);
        body.addView(ui.text(UmbraType.CAPTION, AdmissionPresentation.OFFLINE_REVOCATION_NOTE), ui.margins(Ui.match(), 4, 0));
        return Screen.of(top, body, null);
    }

    public static final long[] TTL_SECONDS = {86_400, 604_800};
    public static final String[] TTL_LABELS = {"24 horas", "7 días"};
    public record ReviewInfo(String deviceFingerprint, String identityFingerprint, String realm, String expires, boolean renewal) {}
    public interface ReviewActions { void approve(long ttlSeconds); void reject(); void cancel(); }

    /** Everything the authority must compare before deciding. No alias exists in a request; nothing here is an identity claim. */
    public static LinearLayout reviewSheet(Ui ui, ReviewInfo r, ReviewActions a) {
        LinearLayout box = ui.column();
        box.addView(ui.heading(UmbraType.TITLE, r.renewal() ? "Revisar renovación" : "Revisar solicitud"));
        box.addView(ui.text(UmbraType.CAPTION, "Compara estas huellas con la persona por un canal confiable antes de decidir. El motor ya comprobó la firma, el entorno y la vigencia; decidir sigue siendo tuyo."), ui.margins(Ui.match(), 4, 4));
        fingerprint(ui, box, "Huella de admisión del dispositivo", r.deviceFingerprint());
        fingerprint(ui, box, "Identidad del dispositivo", r.identityFingerprint());
        fingerprint(ui, box, "Entorno", r.realm());
        box.addView(ui.text(UmbraType.CAPTION, "La solicitud vence " + r.expires() + "."), ui.margins(Ui.match(), 6, 0));
        if (r.renewal()) box.addView(ui.text(UmbraType.CAPTION, "Aprobar emite una credencial nueva y revoca la actual de ese dispositivo."), ui.margins(Ui.match(), 4, 0));
        box.addView(ui.text(UmbraType.SECURITY_LABEL, "Vigencia de la credencial"), ui.margins(Ui.match(), 10, 0));
        box.addView(ui.text(UmbraType.CAPTION, "Máximo siete días; no se renueva sola."));
        for (int i = 0; i < TTL_SECONDS.length; i++) {
            long seconds = TTL_SECONDS[i];
            box.addView(ui.button(i == 0 ? Ui.ButtonKind.PRIMARY : Ui.ButtonKind.SECONDARY,
                (r.renewal() ? "Aprobar renovación · " : "Aprobar · ") + TTL_LABELS[i], Glyph.CHECK, () -> a.approve(seconds)));
        }
        box.addView(ui.button(Ui.ButtonKind.DESTRUCTIVE, "Rechazar", Glyph.BLOCK, a::reject));
        box.addView(ui.button(Ui.ButtonKind.GHOST, "Cancelar", Glyph.CLOSE, a::cancel));
        return box;
    }

    public static final String[] REVOCATION_REASONS = {"owner_request", "device_lost", "policy"};
    public static final String[] REVOCATION_LABELS = {"Lo pidió su dueño", "Dispositivo perdido", "Política del entorno"};
    public record RevokeInfo(String identityFingerprint, String expires) {}
    public interface RevokeActions { void revoke(String reason); void cancel(); }

    public static LinearLayout revokeSheet(Ui ui, RevokeInfo r, RevokeActions a) {
        LinearLayout box = ui.column();
        box.addView(ui.heading(UmbraType.TITLE, "Revocar credencial"));
        fingerprint(ui, box, "Identidad del dispositivo", r.identityFingerprint());
        box.addView(ui.text(UmbraType.CAPTION, "Credencial vigente hasta " + r.expires() + "."), ui.margins(Ui.match(), 6, 0));
        box.addView(ui.text(UmbraType.CAPTION, "Ese dispositivo no podrá abrir nuevas sesiones cuando cada teléfono conozca la revocación. "
            + "No borra datos que ya recibió y los teléfonos sin conexión solo la conocen al importar el archivo firmado."), ui.margins(Ui.match(), 6, 6));
        box.addView(ui.text(UmbraType.SECURITY_LABEL, "Motivo"));
        for (int i = 0; i < REVOCATION_REASONS.length; i++) {
            String reason = REVOCATION_REASONS[i];
            box.addView(ui.button(Ui.ButtonKind.DESTRUCTIVE, "Revocar · " + REVOCATION_LABELS[i], Glyph.DEVICE_REVOKED, () -> a.revoke(reason)));
        }
        box.addView(ui.button(Ui.ButtonKind.GHOST, "Cancelar", Glyph.CLOSE, a::cancel));
        return box;
    }
}
