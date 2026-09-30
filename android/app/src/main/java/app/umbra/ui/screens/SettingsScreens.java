package app.umbra.ui.screens;

import android.view.Gravity;
import android.widget.*;
import app.umbra.ui.design.UmbraColors;
import app.umbra.ui.design.UmbraType;
import app.umbra.ui.design.Ui;
import app.umbra.ui.model.*;

/**
 * Settings sections. A control is interactive only when a real implementation exists; everything
 * else is shown with its current real behavior and the "Próximamente" marker. Explanations live in ⓘ sheets.
 */
public final class SettingsScreens {
    private SettingsScreens() {}

    /**
     * @param connectivity       domain connectivity presentation (consent, not reachability)
     * @param passwordConfigured domain {@code isPasswordConfigured()}, never a UI preference
     * @param autoLockLabel      process-local auto-lock chosen at unlock; not persisted
     */
    public record SettingsState(SettingsSection section, String alias, String identityId, FeatureAvailability features,
                                boolean offlineEdition, ConnectivityPresentation connectivity, boolean relayRegistered, String relayAddress,
                                String expiryLabel, int expiryIndex, String version, boolean passwordConfigured, String autoLockLabel,
                                AdmissionPresentation admission) {}
    public interface SettingsActions {
        void back(); void createInvitation(); void importInvitation(); void revokeInvitations();
        void lockNow(); void destroyIdentity(); void expiry(int index);
        void register(String address, String invitation); void syncNow(); void unregister();
        void connect(); void disconnect(); void nearby();
        void changePassword(); void enrollPassword(); void admission();
        void devices();
        /** Coordinated emergency lock (engine.emergencyLock()); no password. */
        void emergency();
    }

    public static Screen section(Ui ui, SettingsState s, SettingsActions a) {
        LinearLayout top = ui.column();
        Help help = switch (s.section()) {
            case SECURITY -> Help.ACCESS; case NETWORK -> Help.NETWORK; case STORAGE -> Help.STORAGE;
            case PROFILE -> Help.INVITATION; case ABOUT -> Help.DEVELOPMENT; default -> null;
        };
        top.addView(help == null ? ui.topBar(a::back, ui.titleBlock(s.section().title, null))
            : ui.topBar(a::back, ui.titleBlock(s.section().title, null), ui.helpButton(help)));
        LinearLayout body = ui.column();
        switch (s.section()) {
            case PROFILE -> profile(ui, s, a, body);
            case PRIVACY -> privacy(ui, s, body);
            case SECURITY -> security(ui, s, a, body);
            case NOTIFICATIONS -> notifications(ui, s, body);
            case NETWORK -> network(ui, s, a, body);
            case STORAGE -> storage(ui, s, a, body);
            case ABOUT -> about(ui, s, body);
            case DEVICES -> { body.addView(ui.button(Ui.ButtonKind.PRIMARY, "Abrir", Glyph.DEVICES, a::devices)); }
            case ADMISSION -> { body.addView(ui.button(Ui.ButtonKind.PRIMARY, "Abrir", Glyph.DEVICE_AUTHORIZED, a::admission)); }
        }
        return Screen.of(top, body, null);
    }

    private static LinearLayout fact(Ui ui, String label, String value) {
        LinearLayout r = ui.row(); r.setPadding(0, ui.dp(6), 0, ui.dp(6));
        r.addView(ui.text(UmbraType.CAPTION, label), Ui.weight());
        r.addView(ui.text(UmbraType.LABEL, value));
        r.setContentDescription(label + ": " + value);
        return r;
    }

    private static void profile(Ui ui, SettingsState s, SettingsActions a, LinearLayout body) {
        LinearLayout head = ui.column(); head.setGravity(Gravity.CENTER_HORIZONTAL);
        head.addView(ui.avatar(s.alias(), false, 88));
        TextView name = ui.heading(UmbraType.TITLE, s.alias()); name.setGravity(Gravity.CENTER); head.addView(name, ui.margins(Ui.match(), 12, 4));
        LinearLayout chipRow = ui.row(); chipRow.setGravity(Gravity.CENTER); chipRow.addView(ui.connectionChip(s.connectivity()));
        head.addView(chipRow, Ui.match());
        body.addView(head);
        LinearLayout id = ui.card();
        id.addView(ui.text(UmbraType.SECURITY_LABEL, "Identidad pública"));
        TextView code = ui.code(Fingerprints.lines(s.identityId(), 4)); code.setPadding(0, ui.dp(8), 0, ui.dp(4)); id.addView(code);
        body.addView(id);
        body.addView(ui.sectionHeader("Contactos"));
        body.addView(ui.button(Ui.ButtonKind.PRIMARY, "Crear invitación", Glyph.PERSON_ADD, a::createInvitation));
        body.addView(ui.button(Ui.ButtonKind.SECONDARY, "Importar archivo", Glyph.FILE, a::importInvitation));
        body.addView(ui.button(Ui.ButtonKind.DESTRUCTIVE, "Revocar invitaciones", Glyph.BLOCK, a::revokeInvitations));
        LinearLayout qr = ui.row(); qr.addView(ui.text(UmbraType.CAPTION, "Invitación por QR"), Ui.weight()); qr.addView(ui.pendingChip());
        body.addView(qr, ui.margins(Ui.match(), 6, 0));
        body.addView(ui.listRow(ui.iconTile(Glyph.DEVICES, Tone.NEUTRAL), "Dispositivos", null, ui.chevron(), a::devices));
    }

    private static void privacy(Ui ui, SettingsState s, LinearLayout body) {
        body.addView(ui.switchRow("Capturas bloqueadas", null, true, null, null));
        body.addView(ui.switchRow("Recientes ocultos", null, true, null, null));
        body.addView(ui.switchRow("Teclado sin aprendizaje", null, true, null, null));
        body.addView(ui.switchRow("Contenido en notificaciones", null, false, "Próximamente", null));
        body.addView(ui.switchRow("Abrir enlaces", null, false, "Próximamente", null));
        body.addView(ui.switchRow("Limpiar metadatos de fotos", null, false, "Próximamente", null));
        body.addView(ui.switchRow("Portapapeles protegido", null, false, "Próximamente", null));
    }

    private static void security(Ui ui, SettingsState s, SettingsActions a, LinearLayout body) {
        LinearLayout vault = ui.card();
        vault.addView(ui.text(UmbraType.SECURITY_LABEL, "Bóveda"));
        if (s.passwordConfigured()) {
            vault.addView(ui.chip(Tone.SUCCESS, Glyph.PASSWORD, "Contraseña activa"));
            vault.addView(fact(ui, "Autobloqueo", s.autoLockLabel()));
        } else {
            vault.addView(ui.chip(Tone.WARNING, Glyph.VAULT_LOCKED, "Sin contraseña"));
            vault.addView(ui.text(UmbraType.CAPTION, "Solo usa el bloqueo de Android."));
        }
        body.addView(vault);
        if (s.passwordConfigured()) body.addView(ui.button(Ui.ButtonKind.SECONDARY, "Cambiar contraseña", Glyph.CHANGE_PASSWORD, a::changePassword));
        else body.addView(ui.button(Ui.ButtonKind.PRIMARY, "Añadir contraseña", Glyph.PASSWORD, a::enrollPassword));
        body.addView(ui.button(Ui.ButtonKind.PRIMARY, "Bloquear ahora", Glyph.LOCK, a::lockNow));
        LinearLayout status = ui.card();
        status.addView(fact(ui, "Red", s.connectivity().chip()));
        status.addView(fact(ui, "Admisión", s.admission().title()));
        body.addView(status);
        body.addView(ui.listRow(ui.iconTile(s.admission().glyph(), s.admission().tone()), "Admisión", null, ui.chevron(), a::admission));
        body.addView(ui.sectionHeader("Emergencia"));
        body.addView(EntryScreens.emergencyLock(ui, s.features(), a::emergency));
        body.addView(ui.sectionHeader("Zona de riesgo"));
        body.addView(ui.button(Ui.ButtonKind.DESTRUCTIVE, "Destruir identidad", Glyph.TRASH, a::destroyIdentity));
        body.addView(ui.text(UmbraType.CAPTION, "Irreversible.", UmbraColors.WARNING_FG));
    }

    private static void notifications(Ui ui, SettingsState s, LinearLayout body) {
        body.addView(ui.banner(Tone.NEUTRAL, Glyph.BELL_OFF, "Sin notificaciones", null, null, null));
        body.addView(ui.switchRow("Solo «Nuevo mensaje»", null, false, "Próximamente", null));
    }

    private static void network(Ui ui, SettingsState s, SettingsActions a, LinearLayout body) {
        ConnectivityPresentation c = s.connectivity();
        if (s.offlineEdition()) {
            body.addView(ui.banner(Tone.OFFLINE, Glyph.OFFLINE_BLUETOOTH, c.title(), c.body(), null, null));
            body.addView(ui.listRow(ui.iconTile(Glyph.BLUETOOTH, Tone.OFFLINE), "Cercanía", c.nearby(), ui.chevron(), a::nearby));
            return;
        }
        LinearLayout state = ui.row(); state.setGravity(Gravity.CENTER_VERTICAL);
        state.addView(ui.connectionChip(c));
        if (c.service() != null) { TextView svc = ui.text(UmbraType.CAPTION, c.service()); svc.setPadding(ui.dp(10), 0, 0, 0); state.addView(svc, Ui.weight()); }
        state.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(state, ui.margins(Ui.match(), 4, 4));
        if (!c.body().isEmpty()) body.addView(ui.text(UmbraType.CAPTION, c.body()));
        if (c.disconnectEnabled()) body.addView(ui.button(Ui.ButtonKind.DESTRUCTIVE, "Desconectar", Glyph.CLOUD_OFF, a::disconnect));
        else {
            Button connect = ui.button(Ui.ButtonKind.PRIMARY, "Conectar", Glyph.CLOUD, a::connect);
            if (!c.connectEnabled()) ui.disabled(connect, s.relayRegistered() ? "requiere admisión vigente" : "configura el servidor");
            body.addView(connect);
        }
        body.addView(ui.listRow(ui.iconTile(Glyph.BLUETOOTH, Tone.OFFLINE), "Cercanía", c.nearby(), ui.chevron(), a::nearby));
        body.addView(ui.sectionHeader("Servidor"));
        LinearLayout server = ui.card();
        server.addView(fact(ui, "Buzón", s.relayRegistered() ? "Registrado" : "Sin registrar"));
        if (s.relayAddress() != null && !s.relayAddress().isEmpty()) server.addView(fact(ui, "Dirección", s.relayAddress().replaceFirst("^https://", "")));
        body.addView(server);
        EditText address = ui.field("https://servidor"); address.setSingleLine(true);
        address.setText(s.relayAddress() == null ? "" : s.relayAddress());
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        body.addView(ui.labeledField("Dirección", address));
        EditText[] invite = new EditText[1];
        TextView inviteLabel = ui.text(UmbraType.CAPTION, "Invitación del administrador");
        LinearLayout inviteField = ui.passwordField("Invitación", e -> invite[0] = e);
        if (invite[0].getId() == android.view.View.NO_ID) invite[0].setId(android.view.View.generateViewId());
        inviteLabel.setLabelFor(invite[0].getId());
        body.addView(inviteLabel, ui.margins(Ui.match(), 8, 0));
        body.addView(inviteField);
        Button register = ui.button(Ui.ButtonKind.SECONDARY, "Registrar buzón", Glyph.CLOUD, () -> {
            String inv = invite[0].getText().toString(); invite[0].setText(""); a.register(address.getText().toString().trim(), inv.trim());
        });
        if (!s.admission().admitted()) ui.disabled(register, "requiere admisión vigente");
        body.addView(register);
        Button sync = ui.button(Ui.ButtonKind.SECONDARY, "Sincronizar", Glyph.RETRY, a::syncNow);
        if (!c.networkEnabled()) ui.disabled(sync, "red deshabilitada");
        body.addView(sync);
        Button unregister = ui.button(Ui.ButtonKind.DESTRUCTIVE, "Eliminar buzón", Glyph.TRASH, a::unregister);
        if (!c.networkEnabled()) ui.disabled(unregister, "conecta primero");
        body.addView(unregister);
    }

    private static void storage(Ui ui, SettingsState s, SettingsActions a, LinearLayout body) {
        body.addView(ui.text(UmbraType.SECURITY_LABEL, "Caducidad de mensajes"));
        body.addView(ui.segmented(new String[]{"1 hora", "24 horas", "7 días"}, s.expiryIndex(), null, a::expiry));
        LinearLayout local = ui.card();
        local.addView(fact(ui, "Datos", "Cifrados en este teléfono"));
        local.addView(fact(ui, "Archivos", "Hasta 256 KiB"));
        body.addView(local);
    }

    private static void about(Ui ui, SettingsState s, LinearLayout body) {
        LinearLayout v = ui.card();
        v.addView(ui.logo(40, UmbraColors.ACCENT_MUTED));
        v.addView(ui.heading(UmbraType.HEADING, "UMBRA"), ui.margins(Ui.match(), 10, 0));
        // The build version (with its flavor suffix, e.g. "-offline") is an identifier, not Spanish copy.
        v.addView(ui.identifier(UmbraType.CAPTION, s.version(), "Versión " + s.version()), ui.margins(Ui.match(), 2, 0));
        v.addView(ui.chip(Tone.WARNING, Glyph.WARNING, "Versión de desarrollo"));
        body.addView(v);
        body.addView(ui.sectionHeader("Funciones"));
        for (Feature f : Feature.values()) {
            FeatureAvailability.Status st = s.features().status(f);
            Tone tone = st == FeatureAvailability.Status.AVAILABLE ? Tone.SUCCESS : st == FeatureAvailability.Status.NOT_IN_FLAVOR ? Tone.OFFLINE : Tone.NEUTRAL;
            Glyph g = st == FeatureAvailability.Status.AVAILABLE ? Glyph.CHECK : st == FeatureAvailability.Status.NOT_IN_FLAVOR ? Glyph.NETWORK_OFF : Glyph.TIMER;
            LinearLayout r = ui.row(); r.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6));
            r.addView(ui.text(UmbraType.LABEL, f.title), Ui.weight());
            r.addView(ui.chip(tone, g, FeatureAvailability.label(st)));
            r.setContentDescription(f.title + ": " + FeatureAvailability.label(st));
            body.addView(r);
        }
    }
}
