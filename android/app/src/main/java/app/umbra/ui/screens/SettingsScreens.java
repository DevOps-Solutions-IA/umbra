package app.umbra.ui.screens;

import android.view.Gravity;
import android.widget.*;
import app.umbra.ui.design.UmbraColors;
import app.umbra.ui.design.UmbraType;
import app.umbra.ui.design.Ui;
import app.umbra.ui.model.*;

/**
 * Settings sections. A control is interactive only when a real implementation exists; everything
 * else is shown with its current real behavior and the "UI preparada · Backend pendiente" marker.
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
    }

    public static Screen section(Ui ui, SettingsState s, SettingsActions a) {
        LinearLayout top = ui.column();
        top.addView(ui.topBar(a::back, ui.titleBlock(s.section().title, null)));
        LinearLayout body = ui.column();
        switch (s.section()) {
            case PROFILE -> profile(ui, s, a, body);
            case PRIVACY -> privacy(ui, s, body);
            case SECURITY -> security(ui, s, a, body);
            case NOTIFICATIONS -> notifications(ui, s, body);
            case NETWORK -> network(ui, s, a, body);
            case STORAGE -> storage(ui, s, a, body);
            case ABOUT -> about(ui, s, body);
            case DEVICES -> { body.addView(ui.button(Ui.ButtonKind.PRIMARY, "Abrir dispositivos", Glyph.DEVICES, a::devices)); }
            case ADMISSION -> { body.addView(ui.button(Ui.ButtonKind.PRIMARY, "Abrir admisión", Glyph.DEVICE_AUTHORIZED, a::admission)); }
        }
        return Screen.of(top, body, null);
    }

    private static void profile(Ui ui, SettingsState s, SettingsActions a, LinearLayout body) {
        LinearLayout head = ui.column(); head.setGravity(Gravity.CENTER_HORIZONTAL);
        head.addView(ui.avatar(s.alias(), false, 88));
        TextView name = ui.heading(UmbraType.TITLE, s.alias()); name.setGravity(Gravity.CENTER); head.addView(name, ui.margins(Ui.match(), 12, 4));
        LinearLayout chipRow = ui.row(); chipRow.setGravity(Gravity.CENTER); chipRow.addView(ui.connectionChip(s.connectivity()));
        head.addView(chipRow, Ui.match());
        body.addView(head);
        LinearLayout id = ui.card();
        id.addView(ui.text(UmbraType.SECURITY_LABEL, "Identidad pública de este dispositivo"));
        TextView code = ui.code(Fingerprints.lines(s.identityId(), 4)); code.setPadding(0, ui.dp(8), 0, ui.dp(8)); id.addView(code);
        id.addView(ui.text(UmbraType.CAPTION, "Es pública: sirve para que tus contactos te identifiquen. No es una contraseña. UMBRA nunca muestra claves privadas."));
        body.addView(id);
        LinearLayout qr = ui.card();
        LinearLayout qrHead = ui.row(); qrHead.addView(ui.logo(24, UmbraColors.ACCENT_SECONDARY));
        TextView qrTitle = ui.text(UmbraType.LABEL, "QR de invitación"); qrTitle.setPadding(ui.dp(10), 0, 0, 0); qrHead.addView(qrTitle);
        qr.addView(qrHead);
        qr.addView(ui.text(UmbraType.CAPTION, "Hoy las invitaciones se intercambian como archivo de un solo uso. Mostrarlas como QR requiere escaneo con cámara."));
        qr.addView(ui.pendingChip());
        body.addView(qr);
        body.addView(ui.sectionHeader("Agregar contactos"));
        body.addView(ui.button(Ui.ButtonKind.PRIMARY, "Crear invitación de un uso (1 hora)", Glyph.PERSON_ADD, a::createInvitation));
        body.addView(ui.button(Ui.ButtonKind.SECONDARY, "Importar invitación, solicitud o confirmación", Glyph.FILE, a::importInvitation));
        body.addView(ui.button(Ui.ButtonKind.DESTRUCTIVE, "Revocar invitaciones sin usar", Glyph.BLOCK, a::revokeInvitations));
        body.addView(ui.text(UmbraType.CAPTION, "Se intercambian tres archivos: invitación, solicitud y confirmación. Después comparen el código de seguridad. Los archivos exportados quedan fuera de la bóveda."), ui.margins(Ui.match(), 6, 0));
        body.addView(ui.listRow(ui.iconTile(Glyph.DEVICES, Tone.NEUTRAL), "Dispositivos", "Este teléfono y dispositivos vinculados", ui.chevron(), a::devices));
    }

    private static void privacy(Ui ui, SettingsState s, LinearLayout body) {
        body.addView(ui.text(UmbraType.CAPTION, "Protecciones activas en esta versión y controles que llegarán con su implementación."));
        body.addView(ui.switchRow("Capturas de pantalla", "Bloqueadas en todas las pantallas y diálogos de UMBRA.", true, null, null));
        body.addView(ui.switchRow("Vista en aplicaciones recientes", "Oculta: Android no guarda una miniatura con contenido.", true, null, null));
        body.addView(ui.switchRow("Teclado sin aprendizaje", "Se pide al teclado no aprender de lo que escribes en UMBRA.", true, null, null));
        body.addView(ui.switchRow("Contenido de notificaciones", "Esta versión no muestra notificaciones.", false, "UI preparada · Backend pendiente", null));
        body.addView(ui.switchRow("Enlaces externos", "Los mensajes no abren enlaces automáticamente.", false, "UI preparada · Backend pendiente", null));
        body.addView(ui.switchRow("Metadatos de imágenes", "Enviar fotos sigue desactivado hasta poder limpiar EXIF.", false, "UI preparada · Backend pendiente", null));
        body.addView(ui.switchRow("Portapapeles protegido", "Los códigos de seguridad no se copian.", false, "UI preparada · Backend pendiente", null));
    }

    private static void security(Ui ui, SettingsState s, SettingsActions a, LinearLayout body) {
        LinearLayout vault = ui.card();
        vault.addView(ui.text(UmbraType.SECURITY_LABEL, "Bóveda"));
        if (s.passwordConfigured()) {
            vault.addView(ui.chip(Tone.SUCCESS, Glyph.PASSWORD, "Contraseña personal activa"));
            vault.addView(ui.text(UmbraType.CAPTION, "Se abre con el bloqueo de Android y tu contraseña personal. Se bloquea al salir de la app y, como máximo, 4 minutos después de desbloquearla."), ui.margins(Ui.match(), 6, 0));
            vault.addView(ui.text(UmbraType.CAPTION, "Autobloqueo de esta sesión: " + s.autoLockLabel() + ". Se elige al desbloquear y no se guarda: al reiniciar UMBRA vuelve a 4 min."), ui.margins(Ui.match(), 4, 0));
        } else {
            vault.addView(ui.chip(Tone.WARNING, Glyph.VAULT_LOCKED, "Sin contraseña personal (bóveda anterior)"));
            vault.addView(ui.text(UmbraType.CAPTION, "Hoy solo depende del bloqueo de pantalla de Android. Añadir la contraseña vuelve a cifrar la bóveda en este teléfono y la deja bloqueada."), ui.margins(Ui.match(), 6, 0));
        }
        body.addView(vault);
        if (s.passwordConfigured()) body.addView(ui.button(Ui.ButtonKind.SECONDARY, "Cambiar contraseña personal", Glyph.CHANGE_PASSWORD, a::changePassword));
        else body.addView(ui.button(Ui.ButtonKind.PRIMARY, "Añadir contraseña personal", Glyph.PASSWORD, a::enrollPassword));
        body.addView(ui.text(UmbraType.CAPTION, "No existe recuperación: si olvidas la contraseña, los datos de este teléfono quedan inaccesibles."), ui.margins(Ui.match(), 4, 0));
        body.addView(ui.button(Ui.ButtonKind.PRIMARY, "Bloquear ahora", Glyph.LOCK, a::lockNow));
        body.addView(ui.text(UmbraType.CAPTION, "Bloquear cierra la bóveda y las conexiones de esta sesión. No es el bloqueo de emergencia."), ui.margins(Ui.match(), 4, 0));
        LinearLayout startup = ui.card();
        startup.addView(ui.text(UmbraType.SECURITY_LABEL, "Inicio privado"));
        startup.addView(ui.chip(s.connectivity().tone(), s.connectivity().glyph(), s.connectivity().chip()));
        startup.addView(ui.text(UmbraType.CAPTION, "Al abrir o desbloquear, UMBRA no conecta ni activa Bluetooth. La red y Nearby se habilitan solo cuando tú lo pides y se pierden al bloquear o reiniciar."), ui.margins(Ui.match(), 6, 0));
        body.addView(startup);
        body.addView(ui.listRow(ui.iconTile(s.admission().glyph(), s.admission().tone()), "Admisión de este dispositivo", s.admission().title(), ui.chevron(), a::admission));
        body.addView(ui.sectionHeader("Próximas protecciones"));
        body.addView(EntryScreens.emergencyLock(ui, s.features(), () -> {}));
        body.addView(ui.sectionHeader("Zona de riesgo"));
        body.addView(ui.button(Ui.ButtonKind.DESTRUCTIVE, "Destruir identidad y datos locales", Glyph.TRASH, a::destroyIdentity));
        body.addView(ui.text(UmbraType.CAPTION, "Irreversible. No borra copias exportadas ni garantiza el borrado físico de la memoria.", UmbraColors.WARNING_FG));
    }

    private static void notifications(Ui ui, SettingsState s, LinearLayout body) {
        body.addView(ui.banner(Tone.NEUTRAL, Glyph.BELL_OFF, "Sin notificaciones", "UMBRA no recibe mensajes con la app bloqueada, así que no muestra notificaciones con contenido.", null, null));
        body.addView(ui.switchRow("Mostrar solo «Nuevo mensaje»", "Aviso genérico sin remitente ni contenido.", false, "UI preparada · Backend pendiente", null));
    }

    private static void network(Ui ui, SettingsState s, SettingsActions a, LinearLayout body) {
        ConnectivityPresentation c = s.connectivity();
        if (s.offlineEdition()) {
            body.addView(ui.banner(Tone.OFFLINE, Glyph.OFFLINE_BLUETOOTH, c.title(), c.body(), null, null));
            body.addView(ui.listRow(ui.iconTile(Glyph.BLUETOOTH, Tone.OFFLINE), "Nearby", c.nearby(), ui.chevron(), a::nearby));
            return;
        }
        LinearLayout state = ui.card();
        state.addView(ui.text(UmbraType.SECURITY_LABEL, "Red"));
        TextView title = ui.text(UmbraType.HEADING, c.title()); title.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        state.addView(title, ui.margins(Ui.match(), 6, 2));
        state.addView(ui.text(UmbraType.CAPTION, c.body()));
        if (c.service() != null) state.addView(ui.text(UmbraType.CAPTION, c.service()), ui.margins(Ui.match(), 4, 0));
        body.addView(state);
        if (c.disconnectEnabled()) body.addView(ui.button(Ui.ButtonKind.DESTRUCTIVE, "Desconectar", Glyph.CLOUD_OFF, a::disconnect));
        else {
            Button connect = ui.button(Ui.ButtonKind.PRIMARY, "Conectar", Glyph.CLOUD, a::connect);
            if (!c.connectEnabled()) ui.disabled(connect, s.relayRegistered() ? "requiere admisión vigente y bóveda abierta" : "configura primero el servidor privado");
            body.addView(connect);
        }
        body.addView(ui.text(UmbraType.CAPTION, "Desconectar no bloquea la bóveda. Si la red se pierde o cambia, UMBRA no reconecta sola."), ui.margins(Ui.match(), 4, 0));
        body.addView(ui.listRow(ui.iconTile(Glyph.BLUETOOTH, Tone.OFFLINE), "Nearby", c.nearby() + " · consentimiento separado", ui.chevron(), a::nearby));
        body.addView(ui.sectionHeader("Servidor privado"));
        LinearLayout server = ui.card();
        server.addView(ui.text(UmbraType.BODY, s.relayRegistered() ? "Buzón registrado" : "No configurado"));
        server.addView(ui.text(UmbraType.CAPTION, s.relayRegistered() ? "El servidor solo transporta contenido cifrado y ve metadatos de entrega." : "Bluetooth sigue disponible sin servidor."), ui.margins(Ui.match(), 2, 0));
        body.addView(server);
        EditText address = ui.field("https://chat.tudominio.com"); address.setSingleLine(true);
        address.setText(s.relayAddress() == null ? "" : s.relayAddress());
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        body.addView(ui.labeledField("Dirección del servidor", address));
        EditText[] invite = new EditText[1];
        body.addView(ui.text(UmbraType.CAPTION, "Invitación privada del administrador"), ui.margins(Ui.match(), 8, 0));
        body.addView(ui.passwordField("Invitación", e -> invite[0] = e));
        Button register = ui.button(Ui.ButtonKind.SECONDARY, "Conectar y registrar buzón", Glyph.CLOUD, () -> {
            String inv = invite[0].getText().toString(); invite[0].setText(""); a.register(address.getText().toString().trim(), inv.trim());
        });
        if (!s.admission().admitted()) ui.disabled(register, "requiere admisión vigente");
        body.addView(register);
        body.addView(ui.text(UmbraType.CAPTION, "Registrar habilita la red para esa dirección con tu confirmación y después registra el buzón."), ui.margins(Ui.match(), 4, 0));
        Button sync = ui.button(Ui.ButtonKind.SECONDARY, "Sincronizar ahora", Glyph.RETRY, a::syncNow);
        if (!c.networkEnabled()) ui.disabled(sync, "la red está deshabilitada");
        body.addView(sync);
        Button unregister = ui.button(Ui.ButtonKind.DESTRUCTIVE, "Eliminar buzón del servidor", Glyph.TRASH, a::unregister);
        if (!c.networkEnabled()) ui.disabled(unregister, "conecta primero");
        body.addView(unregister);
    }

    private static void storage(Ui ui, SettingsState s, SettingsActions a, LinearLayout body) {
        body.addView(ui.text(UmbraType.SECURITY_LABEL, "Caducidad de mensajes nuevos"));
        body.addView(ui.segmented(new String[]{"1 hora", "24 horas", "7 días"}, s.expiryIndex(), null, a::expiry));
        body.addView(ui.text(UmbraType.CAPTION, "Cuenta desde el envío. No impide que el destinatario copie el contenido."));
        LinearLayout local = ui.card();
        local.addView(ui.text(UmbraType.SECURITY_LABEL, "Datos locales"));
        local.addView(ui.text(UmbraType.CAPTION, "Todo se guarda cifrado en la bóveda de este teléfono. Excluido de copias de seguridad y transferencias. Archivos de hasta 256 KiB."), ui.margins(Ui.match(), 6, 0));
        body.addView(local);
    }

    private static void about(Ui ui, SettingsState s, LinearLayout body) {
        LinearLayout v = ui.card();
        v.addView(ui.logo(40, UmbraColors.ACCENT_MUTED));
        v.addView(ui.heading(UmbraType.HEADING, "UMBRA " + s.version()), ui.margins(Ui.match(), 10, 0));
        v.addView(ui.text(UmbraType.CAPTION, "Versión de desarrollo. Pendiente de auditoría independiente. No usar todavía para secretos reales.", UmbraColors.WARNING_FG));
        body.addView(v);
        body.addView(ui.sectionHeader("Estado real de las funciones"));
        for (Feature f : Feature.values()) {
            FeatureAvailability.Status st = s.features().status(f);
            Tone tone = st == FeatureAvailability.Status.AVAILABLE ? Tone.SUCCESS : st == FeatureAvailability.Status.NOT_IN_FLAVOR ? Tone.OFFLINE : Tone.NEUTRAL;
            Glyph g = st == FeatureAvailability.Status.AVAILABLE ? Glyph.CHECK : st == FeatureAvailability.Status.NOT_IN_FLAVOR ? Glyph.NETWORK_OFF : Glyph.TIMER;
            LinearLayout r = ui.column(); r.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8));
            r.addView(ui.text(UmbraType.LABEL, f.title));
            r.addView(ui.chip(tone, g, FeatureAvailability.label(st)));
            r.setContentDescription(f.title + ": " + FeatureAvailability.label(st));
            body.addView(r); body.addView(ui.divider());
        }
    }
}
