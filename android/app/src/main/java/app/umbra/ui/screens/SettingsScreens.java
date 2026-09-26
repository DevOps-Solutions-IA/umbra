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

    public record SettingsState(SettingsSection section, String alias, String identityId, FeatureAvailability features,
                                boolean offlineEdition, boolean networkPaused, boolean relayRegistered, String relayAddress,
                                String expiryLabel, int expiryIndex, String version) {}
    public interface SettingsActions {
        void back(); void createInvitation(); void importInvitation(); void revokeInvitations();
        void lockNow(); void destroyIdentity(); void expiry(int index);
        void register(String address, String invitation); void syncNow(); void unregister(); void bluetoothOnly(boolean enabled);
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
        }
        return Screen.of(top, body, null);
    }

    private static void profile(Ui ui, SettingsState s, SettingsActions a, LinearLayout body) {
        LinearLayout head = ui.column(); head.setGravity(Gravity.CENTER_HORIZONTAL);
        head.addView(ui.avatar(s.alias(), false, 88));
        TextView name = ui.heading(UmbraType.TITLE, s.alias()); name.setGravity(Gravity.CENTER); head.addView(name, ui.margins(Ui.match(), 12, 4));
        LinearLayout chipRow = ui.row(); chipRow.setGravity(Gravity.CENTER); chipRow.addView(ui.connectionChip(s.offlineEdition(), s.networkPaused()));
        head.addView(chipRow, Ui.match());
        body.addView(head);
        LinearLayout id = ui.card();
        id.addView(ui.text(UmbraType.SECURITY_LABEL, "Identidad pública de este dispositivo"));
        TextView code = ui.code(Fingerprints.lines(s.identityId(), 4)); code.setPadding(0, ui.dp(8), 0, ui.dp(8)); id.addView(code);
        id.addView(ui.text(UmbraType.CAPTION, "Es pública: sirve para que tus contactos te identifiquen. No es una contraseña. UMBRA nunca muestra claves privadas."));
        body.addView(id);
        LinearLayout qr = ui.card();
        qr.addView(ui.text(UmbraType.LABEL, "QR de invitación"));
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
        vault.addView(ui.text(UmbraType.BODY, "Protegida por el bloqueo de pantalla de Android (Keystore)."), ui.margins(Ui.match(), 6, 2));
        vault.addView(ui.text(UmbraType.CAPTION, "Se bloquea al salir de la app y 4 minutos después de desbloquearla. Sin copias automáticas ni telemetría propia."));
        body.addView(vault);
        body.addView(ui.button(Ui.ButtonKind.PRIMARY, "Bloquear ahora", Glyph.LOCK, a::lockNow));
        body.addView(ui.sectionHeader("Próximas protecciones"));
        LinearLayout password = ui.card();
        password.addView(ui.text(UmbraType.LABEL, "Contraseña personal de la bóveda"));
        password.addView(ui.text(UmbraType.CAPTION, "Añadirá una contraseña propia además del bloqueo de Android. No está activa y no se simula."));
        LinearLayout field = ui.passwordField("Contraseña personal", e -> { e.setEnabled(false); });
        field.setAlpha(0.5f); password.addView(field);
        password.addView(ui.pendingChip());
        body.addView(password);
        body.addView(EntryScreens.emergencyLock(ui, s.features(), () -> {}));
        body.addView(EntryScreens.privateStartup(ui, PrivateStartupState.UNAVAILABLE));
        body.addView(ui.sectionHeader("Zona de riesgo"));
        body.addView(ui.button(Ui.ButtonKind.DESTRUCTIVE, "Destruir identidad y datos locales", Glyph.TRASH, a::destroyIdentity));
        body.addView(ui.text(UmbraType.CAPTION, "Irreversible. No borra copias exportadas ni garantiza el borrado físico de la memoria.", UmbraColors.WARNING));
    }

    private static void notifications(Ui ui, SettingsState s, LinearLayout body) {
        body.addView(ui.banner(Tone.NEUTRAL, Glyph.BELL_OFF, "Sin notificaciones", "UMBRA no recibe mensajes con la app bloqueada, así que no muestra notificaciones con contenido.", null, null));
        body.addView(ui.switchRow("Mostrar solo «Nuevo mensaje»", "Aviso genérico sin remitente ni contenido.", false, "UI preparada · Backend pendiente", null));
    }

    private static void network(Ui ui, SettingsState s, SettingsActions a, LinearLayout body) {
        if (s.offlineEdition()) {
            body.addView(ui.banner(Tone.OFFLINE, Glyph.BLUETOOTH, "Edición offline", "Esta versión se compila sin permiso de internet. Solo usa Bluetooth con teléfonos cercanos y no puede sincronizar con un servidor.", null, null));
            return;
        }
        LinearLayout state = ui.card();
        state.addView(ui.text(UmbraType.SECURITY_LABEL, "Servidor privado"));
        state.addView(ui.text(UmbraType.BODY, s.relayRegistered() ? "Buzón registrado" : "No configurado"), ui.margins(Ui.match(), 6, 2));
        state.addView(ui.text(UmbraType.CAPTION, s.relayRegistered() ? "El servidor solo transporta contenido cifrado y ve metadatos de entrega." : "Bluetooth sigue disponible sin servidor."));
        body.addView(state);
        body.addView(ui.switchRow("Solo Bluetooth", "Pausa el uso de internet.", s.networkPaused(), null, a::bluetoothOnly));
        EditText address = ui.field("https://chat.tudominio.com"); address.setSingleLine(true);
        address.setText(s.relayAddress() == null ? "" : s.relayAddress());
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        body.addView(ui.labeledField("Dirección del servidor", address));
        EditText[] invite = new EditText[1];
        body.addView(ui.text(UmbraType.CAPTION, "Invitación privada del administrador"), ui.margins(Ui.match(), 8, 0));
        body.addView(ui.passwordField("Invitación", e -> invite[0] = e));
        body.addView(ui.button(Ui.ButtonKind.PRIMARY, "Registrar buzón", Glyph.CLOUD, () -> {
            String inv = invite[0].getText().toString(); invite[0].setText(""); a.register(address.getText().toString().trim(), inv.trim());
        }));
        body.addView(ui.button(Ui.ButtonKind.SECONDARY, "Sincronizar ahora", Glyph.RETRY, a::syncNow));
        body.addView(ui.button(Ui.ButtonKind.DESTRUCTIVE, "Eliminar buzón del servidor", Glyph.TRASH, a::unregister));
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
        v.addView(ui.heading(UmbraType.HEADING, "UMBRA " + s.version()));
        v.addView(ui.text(UmbraType.CAPTION, "Versión de desarrollo. Pendiente de auditoría independiente. No usar todavía para secretos reales.", UmbraColors.WARNING));
        body.addView(v);
        body.addView(ui.sectionHeader("Estado real de las funciones"));
        for (Feature f : Feature.values()) {
            FeatureAvailability.Status st = s.features().status(f);
            Tone tone = st == FeatureAvailability.Status.AVAILABLE ? Tone.SUCCESS : st == FeatureAvailability.Status.NOT_IN_FLAVOR ? Tone.OFFLINE : Tone.NEUTRAL;
            Glyph g = st == FeatureAvailability.Status.AVAILABLE ? Glyph.CHECK : st == FeatureAvailability.Status.NOT_IN_FLAVOR ? Glyph.CLOUD_OFF : Glyph.TIMER;
            LinearLayout r = ui.column(); r.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8));
            r.addView(ui.text(UmbraType.LABEL, f.title));
            r.addView(ui.chip(tone, g, FeatureAvailability.label(st)));
            r.setContentDescription(f.title + ": " + FeatureAvailability.label(st));
            body.addView(r); body.addView(ui.divider());
        }
    }
}
