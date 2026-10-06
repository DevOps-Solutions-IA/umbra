package app.umbra.ui.screens;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import app.umbra.ui.design.UmbraColors;
import app.umbra.ui.design.UmbraType;
import app.umbra.ui.design.Ui;
import app.umbra.ui.model.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Home: bottom navigation plus the Chats, Calls, Nearby and Settings destinations. */
public final class HomeScreens {
    private HomeScreens() {}

    public interface TabActions { void select(HomeTab tab); }

    public static View bottomNav(Ui ui, FeatureAvailability features, HomeTab current, TabActions a) {
        List<HomeTab> tabs = HomeTab.visible(features);
        List<Ui.NavItem> items = new ArrayList<>();
        for (HomeTab t : tabs) items.add(new Ui.NavItem(t.label, t.glyph));
        return ui.bottomNav(items, Math.max(0, tabs.indexOf(current)), index -> a.select(tabs.get(index)));
    }

    // ------------------------------------------------------------------ Chats
    public enum Filter { ALL, PEOPLE, GROUPS }
    public record IncomingCall(String callId, String alias) {}
    public record ChatsState(List<ConversationItem> conversations, boolean loading, Filter filter, FeatureAvailability features,
                             boolean offlineEdition, ConnectivityPresentation connectivity, AdmissionPresentation admission,
                             String transportNotice, IncomingCall incoming) {}
    public interface ChatsActions {
        void open(ConversationItem item); void newMessage(); void newGroup(); void addContact();
        void filter(Filter filter); void openIncoming(String callId); void networkDetails(); void admission();
        /** Coordinated emergency lock: one explicit tap, no password. */
        void emergency();
    }

    /**
     * Chats first: real conversations, one primary action ("Nueva conversación"). Status appears only when it
     * changes what the person can do (incoming call, access pending, server unreachable); nothing permanent.
     */
    public static Screen chats(Ui ui, ChatsState s, ChatsActions a, View nav) {
        LinearLayout top = ui.column();
        View status = s.offlineEdition() ? null : ui.connectionChip(s.connectivity());
        top.addView(ui.topBar(null, ui.titleBlock("Chats", status),
            s.features().available(Feature.EMERGENCY_LOCK) ? ui.iconButton(Glyph.EMERGENCY_LOCK, "Bloqueo de emergencia", a::emergency) : null,
            ui.iconButton(Glyph.PERSON_ADD, "Agregar contacto", a::addContact)));
        if (s.incoming() != null)
            top.addView(ui.banner(Tone.ACCENT, Glyph.CALL, "Llamada de " + s.incoming().alias(), null,
                "Ver", () -> a.openIncoming(s.incoming().callId())));
        // Local chats stay available; admission only gates the realm's network and Nearby operations.
        if (s.admission() != null && !s.admission().admitted())
            top.addView(ui.banner(s.admission().tone(), s.admission().glyph(), s.admission().title(), null, "Configurar", a::admission));
        if (s.transportNotice() != null)
            top.addView(ui.banner(Tone.WARNING, Glyph.NETWORK_OFF, s.transportNotice(), null, "Detalles", a::networkDetails));

        LinearLayout bottom = ui.column();
        if (s.loading()) { bottom.addView(nav); return Screen.of(top, ui.skeleton(5), bottom); }
        // Groups have no end-to-end implementation yet (PRODUCT_GAP): only 1:1 conversations are listed.
        List<ConversationItem> shown = new ArrayList<>();
        for (ConversationItem c : s.conversations()) if (!c.group()) shown.add(c);
        if (shown.isEmpty()) {
            bottom.addView(nav);
            return Screen.of(top, ui.emptyState(Glyph.CHAT, "Sin conversaciones", "Agrega a alguien para empezar.", "Nueva conversación", a::newMessage), bottom);
        }
        LinearLayout fabRow = ui.row(); fabRow.setGravity(android.view.Gravity.END);
        fabRow.addView(ui.fab("Nueva conversación", Glyph.ADD, a::newMessage));
        bottom.addView(fabRow, Ui.match());
        bottom.addView(nav);
        List<ConversationItem> filtered = new ArrayList<>(shown);
        ListView list = Lists.of(ui, filtered, c -> conversationRow(ui, c, a), false);
        list.setContentDescription("Conversaciones");
        if (shown.size() > 6) { // search only when the list is long enough to need it
            EditText search = ui.field("Buscar");
            search.setCompoundDrawablesRelative(ui.icon(Glyph.SEARCH, UmbraColors.TEXT_TERTIARY, 20), null, null, null);
            search.setCompoundDrawablePadding(ui.dp(10)); search.setSingleLine(true);
            search.setContentDescription("Buscar conversaciones por alias");
            search.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence t, int st, int c, int af) {}
                public void onTextChanged(CharSequence t, int st, int b, int c) {
                    String q = t.toString().toLowerCase(Locale.ROOT); filtered.clear();
                    for (ConversationItem item : shown) if (item.title().toLowerCase(Locale.ROOT).contains(q)) filtered.add(item);
                    ((RowAdapter<?>) list.getAdapter()).notifyDataSetChanged();
                }
                public void afterTextChanged(Editable e) {}
            });
            top.addView(search);
        }
        return Screen.list(top, list, bottom);
    }

    public static View conversationRow(Ui ui, ConversationItem c, ChatsActions a) {
        LinearLayout trailing = ui.column(); trailing.setGravity(android.view.Gravity.END);
        if (c.timeLabel() != null && !c.timeLabel().isEmpty()) trailing.addView(ui.text(UmbraType.CAPTION, c.timeLabel()));
        if (c.unread() > 0) trailing.addView(ui.badge(c.unread()));
        if (c.muted()) trailing.addView(ui.iconView(Glyph.BELL_OFF, UmbraColors.TEXT_TERTIARY, 16));
        LinearLayout row = ui.listRow(ui.avatar(c.title(), c.group(), 48), c.title(), null, trailing, () -> a.open(c));
        LinearLayout texts = (LinearLayout) row.getChildAt(1);
        LinearLayout status = ui.row();
        if (!c.group()) {
            TrustPresentation p = TrustPresentation.of(c.trust());
            status.addView(ui.iconView(p.glyph(), Ui.toneColor(p.tone()), 14));
            TextView sub = ui.text(UmbraType.CAPTION, c.subtitle(), c.trust() == TrustLevel.VERIFIED ? UmbraColors.TEXT_SECONDARY : Ui.toneColor(p.tone()));
            sub.setPadding(ui.dp(6), 0, 0, 0); status.addView(sub, Ui.weight());
        } else {
            status.addView(ui.iconView(Glyph.GROUP, UmbraColors.TEXT_SECONDARY, 14));
            TextView sub = ui.text(UmbraType.CAPTION, c.subtitle()); sub.setPadding(ui.dp(6), 0, 0, 0); status.addView(sub, Ui.weight());
        }
        texts.addView(status, ui.margins(Ui.match(), 3, 0));
        row.setContentDescription(c.accessibilityLabel());
        return row;
    }

    // ------------------------------------------------------------------ Calls
    public record CallRow(String callId, String alias, CallPresentation state, String time, boolean video) {}
    public interface CallsActions { void open(CallRow row); void startFromChats(); }

    public static Screen calls(Ui ui, List<CallRow> rows, boolean activeSession, CallsActions a, View nav) {
        LinearLayout top = ui.column();
        top.addView(ui.topBar(null, ui.titleBlock("Llamadas", ui.chip(Tone.NEUTRAL, Glyph.SHIELD, "Solo verificados")), ui.helpButton(Help.CALL)));
        if (rows.isEmpty()) {
            return Screen.of(top, ui.emptyState(Glyph.CALL, "Sin llamadas", null, "Ir a chats", a::startFromChats), nav);
        }
        ListView list = Lists.of(ui, rows, r -> {
            LinearLayout trailing = ui.column();
            trailing.addView(ui.chip(r.state().tone(), r.state().incoming() ? Glyph.CALL : r.state().live() ? Glyph.MIC : Glyph.INFO, r.state().phase()));
            View row = ui.listRow(ui.avatar(r.alias(), false, 44), r.alias(), r.time(), trailing, () -> a.open(r));
            row.setContentDescription(r.alias() + ". " + r.state().phase() + (r.time() == null ? "" : ". " + r.time()));
            return row;
        }, false);
        if (activeSession) top.addView(ui.banner(Tone.SUCCESS, Glyph.MIC, "Llamada en curso", null, null, null));
        return Screen.list(top, list, nav);
    }

    // ------------------------------------------------------------------ Nearby (Bluetooth)
    /**
     * @param nearbyActive domain {@code isNearbySessionAllowed()}; the radio controls stay disabled until the
     *                     person explicitly starts Nearby, which never happens on open, unlock or connect.
     */
    public record NearbyState(String transportStatus, boolean offlineEdition, ConnectivityPresentation connectivity,
                              boolean nearbyActive, boolean admitted) {}
    public interface NearbyActions {
        void startNearby(); void stopNearby(); void listen(); void makeVisible(); void connectVerified();
        void enrollNew(); void systemSettings(); void networkSettings();
    }

    /** @param back non-null when shown as a pushed screen (connected builds); null as the offline tab. */
    public static Screen nearby(Ui ui, NearbyState s, NearbyActions a, View nav) { return nearby(ui, s, a, nav, null); }
    public static Screen nearby(Ui ui, NearbyState s, NearbyActions a, View nav, Runnable back) {
        LinearLayout top = ui.column();
        top.addView(ui.topBar(back, ui.titleBlock("Conexión cercana", ui.chip(s.nearbyActive() ? Tone.OFFLINE : Tone.NEUTRAL,
            s.offlineEdition() ? Glyph.OFFLINE_BLUETOOTH : Glyph.BLUETOOTH, s.nearbyActive() ? "Cercanía activa" : "Cercanía detenida")), ui.helpButton(Help.NEARBY)));
        LinearLayout body = ui.column();
        if (s.nearbyActive()) {
            TextView st = ui.text(UmbraType.HEADING, s.transportStatus());
            st.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            body.addView(st, ui.margins(Ui.match(), 4, 8));
            body.addView(ui.button(Ui.ButtonKind.DESTRUCTIVE, "Detener cercanía", Glyph.STOP, a::stopNearby));
        } else {
            if (!s.admitted()) body.addView(ui.banner(Tone.WARNING, Glyph.DEVICE_PENDING, "Requiere acceso privado activo", null, null, null));
            Button start = ui.button(Ui.ButtonKind.PRIMARY, "Activar cercanía", Glyph.BLUETOOTH, a::startNearby);
            if (!s.admitted()) ui.disabled(start, "requiere acceso privado activo");
            body.addView(start);
        }
        body.addView(ui.sectionHeader("Contactos verificados"));
        Button listen = ui.button(Ui.ButtonKind.SECONDARY, "Esperar contacto", Glyph.BLUETOOTH, a::listen);
        Button connect = ui.button(Ui.ButtonKind.SECONDARY, "Conectar contacto", Glyph.CHEVRON, a::connectVerified);
        body.addView(listen); body.addView(connect);
        body.addView(ui.sectionHeader("Contacto nuevo"));
        Button enroll = ui.button(Ui.ButtonKind.SECONDARY, "Vincular", Glyph.PERSON_ADD, a::enrollNew);
        Button visible = ui.button(Ui.ButtonKind.SECONDARY, "Visible 120 s", Glyph.EYE, a::makeVisible);
        body.addView(enroll); body.addView(visible);
        if (!s.nearbyActive()) for (Button b : new Button[]{listen, connect, enroll, visible}) ui.disabled(b, "activa la conexión cercana");
        body.addView(ui.button(Ui.ButtonKind.GHOST, "Emparejar en Android", Glyph.SETTINGS, a::systemSettings));
        if (!s.offlineEdition()) {
            body.addView(ui.sectionHeader("Red"));
            body.addView(ui.listRow(ui.iconTile(s.connectivity().glyph(), s.connectivity().tone()), s.connectivity().title(), null, ui.chevron(), a::networkSettings));
        }
        return Screen.of(top, body, nav);
    }

    // ------------------------------------------------------------------ Settings root
    public interface SettingsActions { void open(SettingsSection section); void lockNow(); }

    /** Clean grouped list (no cards): account, security, devices, connectivity, data, advanced, about. */
    public static Screen settings(Ui ui, String alias, ConnectivityPresentation connectivity, SettingsActions a, View nav) {
        LinearLayout top = ui.column();
        top.addView(ui.topBar(null, ui.titleBlock("Ajustes", null)));
        LinearLayout body = ui.column();
        body.addView(ui.listRow(ui.avatar(alias, false, 52), alias, "Perfil e identidad", ui.chevron(), () -> a.open(SettingsSection.PROFILE)));
        body.addView(ui.sectionHeader("Privacidad y seguridad"));
        body.addView(ui.settingRow(SettingsSection.SECURITY.glyph, Tone.NEUTRAL, SettingsSection.SECURITY.title, null, () -> a.open(SettingsSection.SECURITY)));
        body.addView(ui.settingRow(SettingsSection.PRIVACY.glyph, Tone.NEUTRAL, SettingsSection.PRIVACY.title, null, () -> a.open(SettingsSection.PRIVACY)));
        body.addView(ui.settingRow(SettingsSection.DEVICES.glyph, Tone.NEUTRAL, SettingsSection.DEVICES.title, null, () -> a.open(SettingsSection.DEVICES)));
        body.addView(ui.sectionHeader("Conectividad y datos"));
        body.addView(ui.settingRow(connectivity.glyph(), connectivity.tone(), SettingsSection.NETWORK.title, connectivity.chip(), () -> a.open(SettingsSection.NETWORK)));
        body.addView(ui.settingRow(SettingsSection.STORAGE.glyph, Tone.NEUTRAL, SettingsSection.STORAGE.title, null, () -> a.open(SettingsSection.STORAGE)));
        body.addView(ui.sectionHeader("Configuración avanzada"));
        body.addView(ui.settingRow(SettingsSection.ADMISSION.glyph, Tone.NEUTRAL, SettingsSection.ADMISSION.title, null, () -> a.open(SettingsSection.ADMISSION)));
        body.addView(ui.settingRow(SettingsSection.ABOUT.glyph, Tone.NEUTRAL, SettingsSection.ABOUT.title, null, () -> a.open(SettingsSection.ABOUT)));
        body.addView(ui.button(Ui.ButtonKind.SECONDARY, "Bloquear ahora", Glyph.LOCK, a::lockNow), ui.margins(Ui.match(), 16, 8));
        return Screen.of(top, body, nav);
    }
}
